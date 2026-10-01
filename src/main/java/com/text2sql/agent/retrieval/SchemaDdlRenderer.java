package com.text2sql.agent.retrieval;

import java.util.List;

/**
 * 把结构化 schema 渲染成给模型看的文本。
 *
 * <p>格式选择：用「表名 / 列清单」的紧凑写法，而不是原样贴 CREATE TABLE。
 * 原因有两个：一是 CREATE TABLE 里的类型细节、约束名对生成 SQL 没用，
 * 白占 token；二是列注释（藏着枚举值口径）在 CREATE TABLE 里不显眼，
 * 单独提出来才容易被模型利用。
 *
 * <p>被否掉的方案：直接 dump 建表语句。它更省事，但会把 8KB 里一大半
 * 花在 {@code character varying(255) NOT NULL} 这种信息量极低的字符上。
 *
 * <p>为什么单独抽成一个类：阶段 2 的检索层要渲染「召回表的子集」，
 * 阶段 1 的全量实现要渲染「全部表」。渲染逻辑必须只有一份，
 * 否则「检索版和 baseline 的 prompt 差异」里会混入格式差异，
 * 消融实验的数字就不再干净。
 */
public final class SchemaDdlRenderer {

    private SchemaDdlRenderer() {
    }

    public static String render(List<SchemaContext.Table> tables, List<SchemaContext.ForeignKey> fks) {
        StringBuilder sb = new StringBuilder();
        for (SchemaContext.Table table : tables) {
            sb.append("TABLE ").append(table.name());
            if (table.comment() != null && !table.comment().isBlank()) {
                sb.append("  -- ").append(table.comment().strip());
            }
            sb.append('\n');
            for (SchemaContext.Column col : table.columns()) {
                sb.append("  ").append(col.name()).append(' ').append(col.type());
                if (col.comment() != null && !col.comment().isBlank()) {
                    sb.append("  -- ").append(col.comment().strip());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (!fks.isEmpty()) {
            sb.append("FOREIGN KEYS\n");
            for (SchemaContext.ForeignKey fk : fks) {
                sb.append("  ").append(fk.fromTable()).append('.').append(fk.fromColumn())
                        .append(" -> ").append(fk.toTable()).append('.').append(fk.toColumn())
                        .append('\n');
            }
        }
        return sb.toString().strip();
    }
}
