package com.text2sql.agent.retrieval;

import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 实体与枚举值检索器（Value Retrieval）。
 *
 * <p><b>解决的核心痛点：自然语言实体词与数仓底层编码之间的语义鸿沟</b>
 *
 * <p>在数仓中，字段多以短编码存储（如状态 'canceled'，州简写 'SP'，支付方式 'credit_card'），
 * 而用户提问时使用的是中文自然语言（如“已取消”、“圣保罗”、“信用卡”）。
 * 检索器若只找表和列，大模型在生成 WHERE 过滤条件时极易产生幻觉：
 * 例如猜成 {@code customer_state = '圣保罗'} 或 {@code order_status = '已取消'}，
 * 导致 SQL 语法虽完全合法，但查出的结果永远是 0 行（静默错误）。
 *
 * <p>本组件在应用启动时自动聚合：
 * <ol>
 *   <li>从 {@link GlossaryLoader} 自动抽取的各业务表低基数列枚举定义（表名、列名、label 与物理 value）；</li>
 *   <li>数仓标准地理编码（如巴西 27 个联邦州的中文名/简称与标准大写二字码的精确对照）。</li>
 * </ol>
 *
 * <p>并在检索阶段针对用户问题执行最长前缀实体扫描，输出确定性的字段取值映射，注入 Prompt。
 */
@Component
public class ValueRetriever {

    private static final Logger log = LoggerFactory.getLogger(ValueRetriever.class);

    private final GlossaryLoader glossaryLoader;

    /** 内存倒排索引项，按别名长度降序排列以支持最长匹配。 */
    private volatile List<ValueEntry> entries;

    public ValueRetriever(GlossaryLoader glossaryLoader) {
        this.glossaryLoader = glossaryLoader;
    }

    /**
     * 命中的枚举实体匹配结果。
     *
     * @param matchedWord 提问中命中的中文词或别名（如 "圣保罗"）
     * @param table       归属表名（如 "customers"）
     * @param column      归属列名（如 "customer_state"）
     * @param value       数据库物理取值（如 "SP"）
     * @param condition   标准 SQL 条件片段（如 "customer_state = 'SP'"）
     */
    public record ValueMatch(String matchedWord, String table, String column, String value, String condition) {
    }

    /** 内部索引项条目。 */
    public record ValueEntry(String alias, String table, String column, String physicalValue) {
    }

    /**
     * 在用户问题中扫描实体取值，且仅保留属于本次目标表集合的条目。
     *
     * @param question     用户中文问题原文
     * @param targetTables 当前请求召回或选中的目标表名集合（小写）
     * @return 命中的实体取值列表（按命中词去重）
     */
    public List<ValueMatch> findMatches(String question, Set<String> targetTables) {
        if (question == null || question.isBlank() || targetTables == null || targetTables.isEmpty()) {
            return List.of();
        }

        String text = question.toLowerCase(Locale.ROOT);
        List<ValueEntry> allEntries = entries();
        Set<String> lowerTargetTables = new HashSet<>();
        for (String t : targetTables) {
            if (t != null) lowerTargetTables.add(t.toLowerCase(Locale.ROOT));
        }

        List<ValueMatch> matches = new ArrayList<>();
        Set<String> matchedKeys = new HashSet<>();

        // 按词长降序遍历，执行包含检查
        for (ValueEntry entry : allEntries) {
            if (!lowerTargetTables.contains(entry.table().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (text.contains(entry.alias().toLowerCase(Locale.ROOT))) {
                // 唯一键约束：同一表同一列只取一个最贴切的值匹配
                String dedupeKey = entry.table() + "." + entry.column() + "=" + entry.physicalValue();
                if (matchedKeys.add(dedupeKey)) {
                    String cond = String.format("%s = '%s'", entry.column(), entry.physicalValue());
                    matches.add(new ValueMatch(entry.alias(), entry.table(), entry.column(), entry.physicalValue(), cond));
                }
            }
        }

        return matches;
    }

    /**
     * 将命中的实体匹配列表格式化为注入 Prompt 的提示文本。
     */
    public String renderValueHints(List<ValueMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ValueMatch m : matches) {
            sb.append(String.format("- 问题中的「%s」对应真实过滤条件：%s（表 %s）\n",
                    m.matchedWord(), m.condition(), m.table()));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 获取或惰性初始化全部实体枚举倒排项。
     */
    private List<ValueEntry> entries() {
        List<ValueEntry> local = entries;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (entries == null) {
                entries = buildEntries(glossaryLoader.get());
                log.info("ValueRetriever 枚举与实体倒排索引初始化完成，共收录 {} 个映射项", entries.size());
            }
            return entries;
        }
    }

    private List<ValueEntry> buildEntries(Glossary glossary) {
        List<ValueEntry> list = new ArrayList<>();

        // 1. 自动从 Glossary 中抽取已有枚举定义（表名、列名、label 与物理 value）
        if (glossary != null && glossary.tables() != null) {
            glossary.tables().forEach((tableName, tableEntry) -> {
                if (tableEntry.columns() != null) {
                    tableEntry.columns().forEach((colName, colEntry) -> {
                        if (colEntry.enumValues() != null) {
                            for (Glossary.EnumValue ev : colEntry.enumValues()) {
                                if (ev.value() != null && !ev.value().isBlank()
                                        && ev.label() != null && !ev.label().isBlank()) {
                                    list.add(new ValueEntry(ev.label().strip(), tableName, colName, ev.value().strip()));
                                }
                            }
                        }
                    });
                }
            });
        }

        // 2. 注入数仓高频业务实体：巴西 27 个联邦州标准代码（ISO 3166-2:BR）
        // 覆盖 customers.customer_state 与 sellers.seller_state 两大主要维度表
        addBrazilStateMappings(list);

        // 按别名长度降序排序（如“南马托格罗索”优先于“马托格罗索”）
        list.sort(Comparator.comparingInt((ValueEntry e) -> e.alias().length()).reversed()
                .thenComparing(ValueEntry::alias));
        return List.copyOf(list);
    }

    private void addBrazilStateMappings(List<ValueEntry> list) {
        // 州简写 -> 中文名称及常见别名列表
        Map<String, List<String>> states = new LinkedHashMap<>();
        states.put("SP", List.of("圣保罗", "圣保罗州"));
        states.put("RJ", List.of("里约", "里约热内卢", "里约热内卢州"));
        states.put("MG", List.of("米纳斯", "米纳斯吉拉斯", "米纳斯吉拉斯州"));
        states.put("RS", List.of("南里奥格兰德", "南里奥格兰德州", "南里奥"));
        states.put("PR", List.of("巴拉那", "巴拉那州"));
        states.put("SC", List.of("圣卡塔琳娜", "圣卡塔琳娜州"));
        states.put("BA", List.of("巴伊亚", "巴伊亚州"));
        states.put("DF", List.of("联邦区", "巴西利亚"));
        states.put("ES", List.of("圣埃斯皮里图", "圣埃斯皮里图州"));
        states.put("GO", List.of("戈亚斯", "戈亚斯州"));
        states.put("PE", List.of("伯南布哥", "伯南布哥州"));
        states.put("CE", List.of("塞阿腊", "塞阿腊州"));
        states.put("PA", List.of("帕拉", "帕拉州"));
        states.put("MT", List.of("马托格罗索", "马托格罗索州"));
        states.put("MA", List.of("马拉尼昂", "马拉尼昂州"));
        states.put("MS", List.of("南马托格罗索", "南马托格罗索州"));
        states.put("PB", List.of("帕拉伊巴", "帕拉伊巴州"));
        states.put("PI", List.of("皮奥伊", "皮奥伊州"));
        states.put("RN", List.of("北里奥格兰德", "北里奥格兰德州"));
        states.put("AL", List.of("阿拉戈斯", "阿拉戈斯州"));
        states.put("SE", List.of("塞尔希培", "塞尔希培州"));
        states.put("TO", List.of("托坎廷斯", "托坎廷斯州"));
        states.put("RO", List.of("朗多尼亚", "朗多尼亚州"));
        states.put("AM", List.of("亚马孙", "亚马孙州", "亚马逊", "亚马逊州"));
        states.put("AC", List.of("阿克里", "阿克里州"));
        states.put("AP", List.of("阿马帕", "阿马帕州"));
        states.put("RR", List.of("罗赖马", "罗赖马州"));

        // 同时挂载到买家表、卖家表与地理区域表
        for (Map.Entry<String, List<String>> entry : states.entrySet()) {
            String code = entry.getKey();
            for (String alias : entry.getValue()) {
                list.add(new ValueEntry(alias, "customers", "customer_state", code));
                list.add(new ValueEntry(alias, "sellers", "seller_state", code));
                list.add(new ValueEntry(alias, "geolocation", "geolocation_state", code));
                list.add(new ValueEntry(alias, "regions", "region_code", code));
            }
        }
    }
}
