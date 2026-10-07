package com.text2sql.agent.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结果集规范化。执行准确率的正确性几乎全部压在这个类上。
 *
 * <p>规则与 Python 侧 {@code scripts/eval_lib.py} 保持逐条一致。**两份实现必须同步改**，
 * 否则会出现「Python 校验评估集说没问题，Java 评估器却判失败」这种最难查的 bug。
 * 之所以不共用一份实现，是因为 Python 侧是离线标注工具、Java 侧是运行时组件，
 * 强行共用会把离线依赖拖进运行时。
 *
 * <p>四条规则，每条都对应一个真实的假失败来源：
 *
 * <ol>
 *   <li><b>浮点精度</b>：{@code 15843553.24} 与 {@code 15843553.240000001} 是同一个答案。
 *       统一四舍五入到 4 位小数。注意不能用「科学计数法」输出，
 *       否则 610 会变成 6.1E+2，同一个数字两种写法又成了假失败。</li>
 *   <li><b>NULL</b>：JDBC 给的是 Java null，psql 给的是空串，两者含义不同。
 *       统一成字面量 {@code NULL}，避免「真 NULL」和「空字符串」混淆。</li>
 *   <li><b>时间写法</b>：{@code 2018-01-01} 和 {@code 2018-01-01 00:00:00}
 *       指向同一个时间点，只是 PostgreSQL 的 {@code DATE} 和 {@code TIMESTAMP}
 *       展示不同。它们可以归一；{@code 2018-01}、{@code 2018-Q1} 这类有损字符串
 *       不展开，因为那需要额外约定粒度。</li>
 *   <li><b>行顺序</b>：只有 {@code ORDER BY} 与 {@code LIMIT} 同时出现时，
 *       顺序才承载语义（「销量最高的 10 个」顺序错了就是错的）。
 *       单纯的 ORDER BY 只是展示用，不应影响判定。</li>
 * </ol>
 *
 * <p>第三条是评测鲁棒性中最容易被忽略的关键细节。不加它，
 * 「每个州有多少订单（按州名排序）」会因为模型少写一个展示性的 ORDER BY 被误判为错误，
 * 产生不必要的比对误杀。
 */
public final class ResultNormalizer {

    private static final int SCALE = 4;
    private static final String NULL_TOKEN = "NULL";

    private ResultNormalizer() {
    }

    /** 判断结果顺序是否承载语义。 */
    public static boolean orderMatters(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        return upper.contains("ORDER BY") && upper.contains("LIMIT");
    }

    public static List<List<String>> normalize(QueryResult result, boolean ordered) {
        List<List<String>> rows = new ArrayList<>(result.rows().size());
        for (List<String> row : result.rows()) {
            List<String> normalized = new ArrayList<>(row.size());
            for (String cell : row) {
                normalized.add(normalizeValue(cell));
            }
            rows.add(normalized);
        }
        if (!ordered) {
            rows.sort(ResultNormalizer::compareRows);
        }
        return rows;
    }

    /**
     * 规范化单个单元格。
     *
     * <p>数字走浮点精度规则，完整日期/时间走 {@link #normalizeTemporal} 的规范形式，
     * 其余文本原样保留。只有日期和当天零点 timestamp 会归一到同一值。
     */
    public static String normalizeValue(String value) {
        if (value == null) {
            return NULL_TOKEN;
        }
        String text = value.strip();
        if (text.isEmpty()) {
            // 空串保留原样。注意不要把它当成 NULL——psql 的 NULL 已经在
            // Python 侧用哨兵标记过，Java 侧 JDBC 给的是 null。
            return text;
        }
        try {
            BigDecimal decimal = new BigDecimal(text);
            BigDecimal scaled = decimal.setScale(SCALE, RoundingMode.HALF_UP);
            String plain = scaled.toPlainString();
            if (plain.contains(".")) {
                plain = plain.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return plain.isEmpty() ? "0" : plain;
        } catch (NumberFormatException e) {
            return normalizeTemporal(text);
        }
    }

    /**
     * 时间归一化：把 DATE 和当天零点 timestamp 映射到同一个规范串。
     *
     * <p><b>为什么需要它</b>
     *
     * <p>评估集中的时间分组使用 {@code DATE_TRUNC}，周起点使用 {@code DATE}；
     * 模型可能返回 timestamp。两者只差展示类型，不需要猜月份或季度的首日。
     * {@code 2018-01} 和 {@code 2018-Q1} 保持文本原样，避免有损表示被投影成某个
     * 人为约定的日期。
     *
     * <p><b>已知边界</b>
     *
     * <p>非零小数秒和带时区后缀的值不做时间换算，原样返回；
     * 非时间格式的文本也原样返回。
     */
    private static String normalizeTemporal(String text) {
        Matcher m = DATE_PATTERN.matcher(text);
        if (!m.matches()) {
            return text;
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
        int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
        String fraction = m.group(7);
        if (hour > 23 || minute > 59 || second > 59
                || (fraction != null && !fraction.substring(1).chars().allMatch(c -> c == '0'))) {
            return text;
        }
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return text;
        }
        return canonical(year, month, day, hour, minute, second);
    }

    private static String canonical(int year, int month, int day, int hour, int minute, int second) {
        return "%04d-%02d-%02d %02d:%02d:%02d".formatted(year, month, day, hour, minute, second);
    }

    /** 时间格式：完整日期、日期+时间，以及可选的小数秒。 */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?)?"
                    + "(\\.\\d+)?");

    private static int compareRows(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int cmp = a.get(i).compareTo(b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    /**
     * 比对两个结果集是否等价。
     *
     * <p>返回的是布尔值而不是相似度：执行准确率是「对/错」二值指标，
     * 部分匹配会让数字变得不可解释。要衡量「差多少」，应该用结果集
     * 的行级 F1，那是另一个指标（阶段 2 可以加）。
     */
    public static boolean equivalent(List<List<String>> expected, List<List<String>> actual) {
        return expected.equals(actual);
    }
}
