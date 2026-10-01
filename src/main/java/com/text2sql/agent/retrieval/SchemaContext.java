package com.text2sql.agent.retrieval;

import java.util.List;

/**
 * 一次请求要用到的 schema 信息，是「检索层」唯一的输出类型。
 *
 * <p>为什么要有这个中间类型，而不是让检索层直接返回拼好的 prompt 字符串：
 * 阶段 2 引入混合检索后，需要单独衡量「表召回率」——也就是「该用的表有没有被召回」。
 * 如果检索层直接吐字符串，就没法对召回结果做统计，只能靠人肉看日志。
 * 所以检索层的职责边界是「选出相关信息」，怎么措辞交给生成层。
 *
 * <p>阶段 1 的实现是 {@link FullSchemaProvider}：把 37 张表全塞进去，故意不做筛选。
 */
public record SchemaContext(
        List<Table> tables,
        List<ForeignKey> foreignKeys,
        String dataProfile,
        String ddlText) {

    /** 一张表。comment 是表级注释，多数表为空——这本身就是需要模型面对的噪声。 */
    public record Table(String name, String comment, List<Column> columns) {
    }

    /** 一列。comment 里藏着枚举值口径（如 "会员状态：1=正常 2=冻结"），是语义层的雏形。 */
    public record Column(String name, String type, boolean nullable, String comment) {
    }

    /**
     * 一条外键。阶段 3 会用这些边构建表关系图并求最短路径。
     *
     * <p>阶段 1 只把它当作文本提示塞进 prompt，让模型自己决定怎么 join。
     * 这正是阶段 3 要改掉的地方：模型选错 join 时 SQL 照样能执行，错误是静默的。
     */
    public record ForeignKey(String fromTable, String fromColumn, String toTable, String toColumn) {
    }

    public int columnCount() {
        return tables.stream().mapToInt(t -> t.columns().size()).sum();
    }
}
