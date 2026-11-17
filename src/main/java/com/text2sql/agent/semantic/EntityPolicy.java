package com.text2sql.agent.semantic;

import java.util.List;

/** Entity identity is distinct from the storage row/join key. Advisory, never a SQL rewrite. */
public record EntityPolicy(String name, String table, String rowKey, String identityColumn,
                           PopulationCountUnit populationCountUnit, List<String> aliases, String notes) {
    /** Counting a relation population and grouping an entity's activity are separate contracts. */
    public enum PopulationCountUnit { ROW_KEY, ENTITY_IDENTITY }

    public EntityPolicy {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (name == null || name.isBlank() || !identifier(table) || !identifier(rowKey)
                || !identifier(identityColumn) || populationCountUnit == null || aliases.isEmpty()
                || aliases.stream().anyMatch(String::isBlank) || notes == null || notes.isBlank()) {
            throw new IllegalArgumentException("Incomplete entity policy");
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    public String render() {
        return "ENTITY_POLICY " + name + " -- " + table + "\n"
                + "  存储行/关联键：" + table + "." + rowKey + "；业务实体身份：" + table + "." + identityColumn + "。\n"
                + "  populationCountUnit=" + populationCountUnit + "；该默认只作用于实体表记录集合的规模/属性分布计数，不覆盖已注册指标的分子分母。\n"
                + (populationCountUnit == PopulationCountUnit.ROW_KEY
                ? "  未指定自然人/人数/业务身份去重的实体表规模及属性分布，默认统计存储记录：单独统计该表可COUNT(*)，一对多关联后按" + table + "." + rowKey + "去重避免放大；列名应体现记录单位，不把记录数宣称为自然人数。\n"
                : "  实体集合规模默认统计非NULL业务身份的不同值，采用COUNT(DISTINCT " + table + "." + identityColumn + ")。\n")
                + "  明确问自然人、人数或业务身份去重时按" + table + "." + identityColumn + "；按每个实体汇总订单/消费等行为仍默认按业务实体身份分组，跨关联键识别同一实体。\n"
                + "  用户明确指定按存储行/关联键统计时服从该要求，不擅自改成实体级统计。\n"
                + "  显式字段/单位优先；同一请求同时要求记录数和人数时分别计算，不用一个默认覆盖所有指标。集合计数不授权改变过滤或JOIN覆盖。\n"
                + "  " + notes.strip();
    }
}
