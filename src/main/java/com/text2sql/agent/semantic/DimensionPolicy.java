package com.text2sql.agent.semantic;

import java.util.List;

/** Business grouping policy. It guides generation; it never rewrites SQL or removes result rows. */
public record DimensionPolicy(String name, String table, String column, List<String> aliases,
                              String defaultFilter, String notes) {
    public DimensionPolicy {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public String render() {
        return "DIMENSION_POLICY " + name + " -- " + table + "." + column + "\n"
                + "  适用范围：仅当该字段作为统计分组维度时生效；不改变未按该维度分组的总数或总额。\n"
                + "  默认空值口径：排除该字段为SQL NULL的数据，附加 " + defaultFilter + "。\n"
                + "  用户明确要求包含缺失/未知类目时，以用户要求为准：不附加上述过滤，保留NULL分组；"
                + "仅在用户要求显示标签时使用COALESCE，不把原始NULL擅自改成字符串。\n"
                + "  " + notes.strip();
    }
}
