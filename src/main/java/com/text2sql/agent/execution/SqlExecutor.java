package com.text2sql.agent.execution;

import com.text2sql.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行层：把校验通过的 SQL 送到数据库跑，拿回结果。
 *
 * <p><b>这一层的设计目标只有一个：让「能跑」不变成「闯祸」。</b>
 *
 * <p>具体做了三件事，对应三道独立的防线（这是回答「怎么防止删库」的第二层）：
 *
 * <ol>
 *   <li><b>只读事务</b>：{@code setReadOnly(true)} 让 PostgreSQL 在协议层
 *       拒绝写操作。就算前面所有校验都被绕过，数据库自己也会拦下来。</li>
 *   <li><b>查询超时</b>：{@code setQueryTimeout} 防止一个没写好条件的 join
 *       把连接占满几十秒。超时值来自配置，可调。</li>
 *   <li><b>行数上限</b>：读取时最多取 maxRows 行，多出来的直接丢弃并标记
 *       truncated。防止 {@code SELECT * FROM geolocation}（100 万行）
 *       把内存打满。</li>
 * </ol>
 *
 * <p><b>为什么超时和行数上限不依赖校验层</b>：校验层管的是「语句形态」，
 * 它无法判断「这条合法 SELECT 会不会跑一小时」。形态合法 ≠ 资源可控，
 * 这是两个正交的问题，必须分别处理。生产环境还有第三层——只读数据库账号，
 * 属于阶段 5 的部署内容。
 */
@Component
public class SqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutor.class);

    private final JdbcTemplate jdbcTemplate;
    private final AgentProperties properties;

    public SqlExecutor(JdbcTemplate jdbcTemplate, AgentProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 执行一条只读 SQL。
     *
     * @throws SqlExecutionException 执行失败（语法错、表不存在、超时等）
     */
    public QueryResult execute(String sql) {
        //写在配置文件里面的配置，最大允许行数，最长时间
        int maxRows = properties.getDb().getMaxRows();
        int timeout = properties.getDb().getQueryTimeoutSeconds();

        long started = System.nanoTime();

        try {
            // 必须显式声明成 ResultSetExtractor：JdbcTemplate 有两个参数列表
            // 相同的重载（RowMapper / ResultSetExtractor），lambda 无法自行消歧，
            // 会被推断成「逐行映射」从而报类型错误。
            ResultSetExtractor<RowBuffer> extractor = rs -> {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>(columnCount);
                //读列名
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    //读值
                    List<String> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        row.add(value == null ? null : String.valueOf(value));
                    }
                    rows.add(row);
                }
                return new RowBuffer(columns, rows);
            };

            RowBuffer result = jdbcTemplate.query(connection -> {
                // 只读事务：数据库层面的兜底，不依赖代码正确性
                connection.setReadOnly(true);
                var statement = connection.prepareStatement(sql);
                statement.setQueryTimeout(timeout);
                // 多取一行，用来判断「是不是被截断了」——只取 maxRows 行的话，
                // 结果刚好等于 maxRows 时无法区分「恰好这么多」和「还有更多」。
                statement.setMaxRows(maxRows + 1);
                return statement;
            }, extractor);

            boolean truncated = result.rows().size() > maxRows;
            List<List<String>> kept = truncated ? result.rows().subList(0, maxRows) : result.rows();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (truncated) {
                log.warn("结果集超过 {} 行，已截断", maxRows);
            }
            return new QueryResult(result.columns(), List.copyOf(kept), truncated, elapsedMs);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            log.warn("SQL 执行失败（{}ms）：{}", elapsedMs, e.getMessage());
            throw new SqlExecutionException(e.getMessage(), e);
        }
    }

    private record RowBuffer(List<String> columns, List<List<String>> rows) {
    }
}
