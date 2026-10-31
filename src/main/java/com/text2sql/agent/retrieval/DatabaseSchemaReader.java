package com.text2sql.agent.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从数据库元数据里读取 schema。
 *
 * <p>关键决策：**schema 从数据库实时读，而不是读 data/schema/*.sql 文件**。
 *
 * <p>被否掉的方案是「直接读建表 SQL 文件」——看起来更简单，但有一个致命问题：
 * 文件是「意图」，数据库是「事实」。一旦有人手工改过库、或某次 DDL 执行失败，
 * 两者就会分叉，模型会基于错误的事实生成 SQL，而错误会以「表不存在」的形式
 * 在最后一步才暴露。从 information_schema / pg_catalog 读，保证模型看到的
 * 永远等于它将要查询的那个库。
 *
 * <p>代价是每次启动多几条元数据查询。因此加了缓存，见 {@link FullSchemaProvider}。
 */
@Component
public class DatabaseSchemaReader {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 内部表：它们属于**本系统自己**，不是业务对象。
     *
     * <p>为什么必须显式排除：这些表是在运行时才被创建的（持久化开启后
     * {@code ensureTables()} 建会话表、缓存与日志表在建库脚本里）。
     * 不排除的话会出现两个问题：
     * <ul>
     *   <li><b>数字对不上</b>：简历与评估报告说的是「37 张业务表」，
     *       开了持久化之后 schema 里会变成 39 张，接口返回的
     *       {@code schemaTableCount} 也跟着漂移；</li>
     *   <li><b>污染 prompt</b>：模型会看到 {@code semantic_cache}、
     *       {@code llm_call_log} 这种与业务问题无关的表，既浪费 token，
     *       也给出「去查缓存表」这类荒谬 SQL 的机会。</li>
     * </ul>
     *
     * <p>这属于「配置开关之间互相影响」的典型：持久化开关本来只该影响
     * 写路径，却经由 schema 读取反向影响了读路径。
     */
    private static final Set<String> INTERNAL_TABLES = Set.of(
            "schema_chunks",        // 早期向量检索实验遗留
            "semantic_cache",       // L2 语义缓存
            "llm_call_log",         // 逐条模型调用记录
            "conversation_session", // 多轮会话（运行时创建）
            "conversation_turn"     // 多轮会话轮次（运行时创建）
    );

    public DatabaseSchemaReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取所有业务表与列。
     *
     * <p>只查 public schema，且排除内部表与 pgvector 的扩展表——那些不是业务对象，
     * 混进 prompt 只会稀释注意力。
     */
    public List<SchemaContext.Table> readTables() {
        String sql = """
                SELECT c.table_name,
                       c.column_name,
                       c.data_type,
                       c.is_nullable,
                       c.ordinal_position,
                       col_description(('public.' || c.table_name)::regclass, c.ordinal_position) AS col_comment,
                       obj_description(('public.' || c.table_name)::regclass) AS table_comment
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public'
                  AND t.table_type = 'BASE TABLE'
                  AND c.table_name <> 'schema_chunks'
                ORDER BY c.table_name, c.ordinal_position
                """;

        Map<String, List<SchemaContext.Column>> columnsByTable = new LinkedHashMap<>();
        Map<String, String> commentByTable = new LinkedHashMap<>();

        jdbcTemplate.query(sql, rs -> {
            String table = rs.getString("table_name");
            if (INTERNAL_TABLES.contains(table)) {
                return;
            }
            columnsByTable.computeIfAbsent(table, k -> new ArrayList<>());
            commentByTable.putIfAbsent(table, rs.getString("table_comment"));
            columnsByTable.get(table).add(new SchemaContext.Column(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    rs.getString("col_comment")));
        });

        List<SchemaContext.Table> tables = new ArrayList<>();
        columnsByTable.forEach((name, cols) ->
                tables.add(new SchemaContext.Table(name, commentByTable.get(name), List.copyOf(cols))));
        return tables;
    }

    /**
     * 读取外键关系。
     *
     * <p>为什么值得单独查：外键是**数据库自己声明的真相**，比任何文档都可靠。
     * 阶段 1 把它当提示给模型，阶段 3 会用它构图构造生成树。
     */
    public List<SchemaContext.ForeignKey> readForeignKeys() {
        String sql = """
                SELECT src.relname  AS from_table,
                       sa.attname   AS from_column,
                       tgt.relname  AS to_table,
                       ta.attname   AS to_column
                FROM pg_constraint con
                JOIN pg_class src ON src.oid = con.conrelid
                JOIN pg_class tgt ON tgt.oid = con.confrelid
                JOIN pg_namespace n ON n.oid = src.relnamespace
                JOIN unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
                JOIN unnest(con.confkey) WITH ORDINALITY AS f(attnum, ord) ON f.ord = k.ord
                JOIN pg_attribute sa ON sa.attrelid = con.conrelid AND sa.attnum = k.attnum
                JOIN pg_attribute ta ON ta.attrelid = con.confrelid AND ta.attnum = f.attnum
                WHERE n.nspname = 'public'
                  AND con.contype = 'f'
                ORDER BY from_table, from_column
                """;

        return jdbcTemplate.query(sql, (rs, i) -> new SchemaContext.ForeignKey(
                        rs.getString("from_table"),
                        rs.getString("from_column"),
                        rs.getString("to_table"),
                        rs.getString("to_column")))
                .stream()
                // 外键也要过滤：内部表如果恰好有外键，会跟着混进关系图，
                // 进而影响连接规划的候选边。
                .filter(fk -> !INTERNAL_TABLES.contains(fk.fromTable())
                        && !INTERNAL_TABLES.contains(fk.toTable()))
                .toList();
    }

    /**
     * 数据画像：行数 + 时间范围。
     *
     * <p>这不是可有可无的装饰。Olist 数据截止 2018-10-17，而模型默认认为
     * 「最近 30 天」= 相对今天。不给这个事实，T3 时间层会整层归零，
     * 得到的 baseline 会低到失真，阶段 2 的「提升」也就失去意义。
     *
     * <p>这里硬编码了 orders 表的时间列，因为它是全库唯一的时间锚点。
     * 更通用的做法是从 schema 里猜哪个是时间列——那属于阶段 2 的枚举值 linking。
     */
    public String readDataProfile() {
        Long orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        Map<String, Object> range = jdbcTemplate.queryForMap(
                "SELECT MIN(order_purchase_timestamp) AS min_ts, MAX(order_purchase_timestamp) AS max_ts FROM orders");
        return """
                数据规模：orders 共 %s 行。
                时间范围：order_purchase_timestamp 从 %s 到 %s。
                【重要】这是历史快照数据，不是实时库。凡是"最近 N 天/本月/上个月/今年"
                这类相对时间，必须锚定到该表的最大时间，不能用 current_date / now()。
                """
                .formatted(orderCount, range.get("min_ts"), range.get("max_ts"))
                .strip();
    }
}
