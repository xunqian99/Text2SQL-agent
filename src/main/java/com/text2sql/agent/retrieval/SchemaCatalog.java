package com.text2sql.agent.retrieval;

import com.text2sql.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全库 schema 的内存目录，检索层与全量层共用的唯一数据源。
 *
 * <p><b>为什么把它从 {@link FullSchemaProvider} 里抽出来</b>
 *
 * <p>阶段 1 只有一个 provider，它自己缓存 schema 就够了。阶段 2 变成两个：
 * 全量版（baseline）和检索版（优化）。如果各自缓存一份，同一个进程里会读两次
 * 元数据、存两份对象，更糟的是——两份缓存可能不同步，导致消融实验的
 * 两个配置看到的 schema 不是同一个，数字就失去可比性。
 *
 * <p>抽出来之后，两个 provider 共享同一份不可变目录，差异只剩「选哪些表」，
 * 这是消融实验唯一应该变化的变量。
 *
 * <p>启动时读一次并缓存。为什么不每次请求都读：schema 在一次运行内不会变，
 * 每请求查一次元数据是纯粹的浪费，且会让检索延迟数字变脏。
 */
@Component
public class SchemaCatalog {

    private static final Logger log = LoggerFactory.getLogger(SchemaCatalog.class);

    private final DatabaseSchemaReader reader;
    private final AgentProperties properties;

    private volatile SchemaContext cached;

    public SchemaCatalog(DatabaseSchemaReader reader, AgentProperties properties) {
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

    public SchemaContext full() {
        SchemaContext context = cached;
        return context != null ? context : load();
    }

    /** 表名（小写）到表的映射。检索层要按名字取表，所以预先建好索引。 */
    public Map<String, SchemaContext.Table> tablesByName() {
        Map<String, SchemaContext.Table> map = new LinkedHashMap<>();
        for (SchemaContext.Table table : full().tables()) {
            map.put(table.name().toLowerCase(Locale.ROOT), table);
        }
        return Map.copyOf(map);
    }

    private SchemaContext load() {
        List<SchemaContext.Table> tables = reader.readTables();
        List<SchemaContext.ForeignKey> fks = properties.getPrompt().isIncludeForeignKeys()
                ? reader.readForeignKeys()
                : List.of();
        String profile = properties.getPrompt().isIncludeDataProfile() ? reader.readDataProfile() : "";
        return new SchemaContext(tables, fks, profile, SchemaDdlRenderer.render(tables, fks));
    }
}
