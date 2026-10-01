package com.text2sql.agent.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 结果集规范化。执行准确率的正确性几乎全部压在这个类上。
 *
 * <p>规则与 Python 侧 {@code scripts/eval_lib.py} 保持逐条一致。**两份实现必须同步改**，
 * 否则会出现「Python 校验评估集说没问题，Java 评估器却判失败」这种最难查的 bug。
 * 之所以不共用一份实现，是因为 Python 侧是离线标注工具、Java 侧是运行时组件，
 * 强行共用会把离线依赖拖进运行时。
 *
 * <p>三条规则，每条都对应一个真实的假失败来源：
 *
 * <ol>
 *   <li><b>浮点精度</b>：{@code 15843553.24} 与 {@code 15843553.240000001} 是同一个答案。
 *       统一四舍五入到 4 位小数。注意不能用「科学计数法」输出，
 *       否则 610 会变成 6.1E+2，同一个数字两种写法又成了假失败。</li>
 *   <li><b>NULL</b>：JDBC 给的是 Java null，psql 给的是空串，两者含义不同。
 *       统一成字面量 {@code NULL}，避免「真 NULL」和「空字符串」混淆。</li>
 *   <li><b>行顺序</b>：只有 {@code ORDER BY} 与 {@code LIMIT} 同时出现时，
 *       顺序才承载语义（「销量最高的 10 个」顺序错了就是错的）。
 *       单纯的 ORDER BY 只是展示用，不应影响判定。</li>
 * </ol>
 *
 * <p>第三条是最容易被忽略、也最容易被面试官抓住的一条。不加它，
 * 「每个州有多少订单（按州名排序）」会因为模型少写一个 ORDER BY 被判错，
 * 而那个答案其实是对的。
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
     * <p>时间值不特殊处理：PostgreSQL 返回的 timestamp 字符串形如
     * {@code 2018-01-01 00:00:00}，只要模型和标准 SQL 都从同一列取值，
     * 字符串形式就是稳定的。真正的坑在于「同一天不同写法」
     * （{@code 2018-01-01} vs {@code 2018-01-01 00:00:00}）——
     * 那属于 SQL 口径差异，应该判为错误，不该被规范化掩盖掉。
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
            return text;
        }
    }

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
