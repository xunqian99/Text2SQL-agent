package com.text2sql.agent.retrieval.glossary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 中文语义词典的内存形态，对应 {@code src/main/resources/schema/glossary.yml}。
 *
 * <p>这是阶段 2 的领域知识载体。它回答一个具体问题：用户嘴里的「复购」「大区」
 * 「满减」分别对应数据库里的哪张表、哪一列、哪个枚举值。
 *
 * <p>为什么是 YAML 而不是 Java 常量表：词典要频繁增删同义词，改动应该是一次
 * 文本编辑 + 重跑测试，而不是一次编译。同时 YAML 可以被非程序员审阅——标注
 * 业务口径这件事本来就该由懂业务的人做，代码只负责读。
 *
 * @param relations 数据库外键之外的「约定关联」，见 glossary.yml 的说明
 * @param tables 表名 -> 该表的中文说法与列说法。键就是数据库里的真实表名
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Glossary(List<Relation> relations, Map<String, TableEntry> tables) {

    public Glossary {
        relations = relations == null ? List.of() : List.copyOf(relations);
        tables = tables == null ? Map.of() : Map.copyOf(tables);
    }

    public static Glossary empty() {
        return new Glossary(List.of(), Map.of());
    }

    public int tableCount() {
        return tables.size();
    }

    /** 词典里对一张表的中文描述。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableEntry(
            List<String> aliases,
            String comment,
            Map<String, ColumnEntry> columns) {

        public TableEntry {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            columns = columns == null ? Map.of() : Map.copyOf(columns);
        }
    }

    /**
     * 词典里对一列的中文描述。
     *
     * <p>注意字段名是 {@code enumValues} 而 YAML 里写的是 {@code enum}——
     * {@code enum} 是 Java 关键字，不能做字段名，所以必须显式映射。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnEntry(
            List<String> aliases,
            @JsonProperty("enum") List<EnumValue> enumValues) {

        public ColumnEntry {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    /**
     * 一个枚举取值。
     *
     * @param value 数据库里真实存的值，如 {@code "1"}、{@code "canceled"}
     * @param label 中文含义，如「待发货」
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnumValue(String value, String label) {
    }

    /**
     * 一条约定关联。
     *
     * <p>{@code from} / {@code to} 用 {@code "表.列"} 表示，而不是拆成四个字段。
     * 理由：拆成 fromTable / fromColumn / toTable / toColumn 后，YAML 里写的是
     * 四个键，任何一处拼错（比如 from 表写对、列写错）都要靠代码发现；
     * 用一个字符串，就能在加载时统一校验「这张表、这个列是否真实存在」，
     * 一条规则覆盖所有拼写错误。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relation(String from, String to) {
    }
}
