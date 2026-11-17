package com.text2sql.agent.semantic;

import java.util.List;

/** Entity identity is distinct from the storage row/join key. Advisory, never a SQL rewrite. */
public record EntityPolicy(String name, String table, String rowKey, String identityColumn,
                           List<String> aliases, String notes) {
    public EntityPolicy {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (name == null || name.isBlank() || !identifier(table) || !identifier(rowKey)
                || !identifier(identityColumn) || aliases.isEmpty()
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
                + "  实体数量去重、按实体分组和跨记录识别同一实体，默认采用业务实体身份；关联键不能替代实体身份。\n"
                + "  用户明确指定按存储行/关联键统计时服从该要求，不擅自改成实体级统计。\n"
                + "  " + notes.strip();
    }
}
