package com.text2sql.agent.retrieval;

import com.text2sql.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 阶段 1 的 SchemaProvider：把整库 schema 直接塞进上下文，**不做任何筛选**。
 *
 * <p>这是有意为之的「笨实现」，它有两个作用：
 *
 * <ol>
 *   <li>给出一个诚实的 baseline。不做检索、不做 join 规划、不做语义层，
 *       模型只靠全量 schema 生成 SQL，准确率是多少就记多少。</li>
 *   <li>提供阶段 2 的对比对象。阶段 2 上线检索后，如果准确率上升且
 *       prompt token 下降，才能证明检索真的在起作用。如果一开始就上检索，
 *       就没有参照点，「提升」也就无从谈起。</li>
 * </ol>
 *
 * <p>代价是显而易见的：37 张表、201 列全塞进去，光 schema 就 4–5k token，
 * 而单表问题实际只需要其中一张表。噪声会稀释模型的注意力，这正是阶段 2 要解决的问题。
 *
 * <p>启动时读一次并缓存。为什么不每次请求都读：schema 在一次运行内不会变，
 * 每请求查一次元数据是纯粹的浪费，且会让延迟数字变脏。
 */
@Component
public class FullSchemaProvider implements SchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(FullSchemaProvider.class);

    private final DatabaseSchemaReader reader;
    private final AgentProperties properties;

    private volatile SchemaContext cached;

    public FullSchemaProvider(DatabaseSchemaReader reader, AgentProperties properties) {
        this.reader = reader;
        this.properties = properties;
    }

    @PostConstruct
    void warmUp() {
        SchemaContext context = load();
        cached = context;
        log.info("schema 已加载：{} 张表 / {} 列 / {} 条外键 / DDL {} 字符",
                context.tables().size(), context.columnCount(),
                context.foreignKeys().size(), context.ddlText().length());
    }

    @Override
    public SchemaContext provide(String question) {
        // 参数 question 在阶段 1 被忽略——整库塞入，没有筛选，自然也不需要问题。
        return cached != null ? cached : load();
    }

    private SchemaContext load() {
        List<SchemaContext.Table> tables = reader.readTables();
        List<SchemaContext.ForeignKey> fks = properties.getPrompt().isIncludeForeignKeys()
                ? reader.readForeignKeys()
                : List.of();
        String profile = properties.getPrompt().isIncludeDataProfile() ? reader.readDataProfile() : "";
        return new SchemaContext(tables, fks, profile, renderDdl(tables, fks));
    }

    /**
     * 把结构化 schema 渲染成给模型看的文本。
     *
     * <p>格式选择：用「表名 / 列清单」的紧凑写法，而不是原样贴 CREATE TABLE。
     * 原因有两个：一是 CREATE TABLE 里的类型细节、约束名对生成 SQL 没用，
     * 白占 token；二是列注释（藏着枚举值口径）在 CREATE TABLE 里不显眼，
     * 单独提出来才容易被模型利用。
     *
     * <p>被否掉的方案：直接 dump 建表语句。它更省事，但会把 8KB 里一大半
     * 花在 `character varying(255) NOT NULL` 这种信息量极低的字符上。
     */
    private String renderDdl(List<SchemaContext.Table> tables, List<SchemaContext.ForeignKey> fks) {
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
