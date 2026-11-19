package com.text2sql.agent.tool;

import com.text2sql.agent.retrieval.SchemaContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 把五个侦察工具暴露成 Spring AI 原生的 {@code @Tool} 方法。
 *
 * <p><b>为什么要有这一层</b>：之前 Agent 循环靠模型输出
 * {@code [TOOL_CALL] 工具名(参数)} 这样的文本标记，再用正则解析。这条路有三个固有缺陷：
 * 一条消息里多个调用会互相干扰、参数里含括号要手工配对、格式不对就退化成
 * 「把整段文本当 SQL」送进校验层。改成原生 function calling 之后，模型返回的是
 * 结构化 {@code tool_calls} 数组，上面三类问题在协议层就不存在了。
 *
 * <p><b>为什么不是 Spring Bean</b>：工具执行需要当前请求的 {@link SchemaContext}
 * （表白名单、列白名单都从它来），是**每请求一份**的状态。做成单例 Bean 会把
 * 上一个请求的 schema 串到下一个请求里。
 *
 * <p><b>description 是给模型看的</b>：模型选不选这个工具，几乎完全取决于 description
 * 写没写清「什么情况下该用」。所以每条都写了触发场景，而不是只写功能。
 */
public class AgentToolFunctions {

    private final ToolExecutor executor;
    private final SchemaContext schema;

    public AgentToolFunctions(ToolExecutor executor, SchemaContext schema) {
        this.executor = executor;
        this.schema = schema;
    }

    @Tool(name = "INSPECT_TABLE",
            description = "查看一张表的真实结构和前 5 行样例数据。"
                    + "当你不确定某张表有哪些列、或列名含义不明确时使用。参数是表名，例如 orders。")
    public String inspectTable(
            @ToolParam(description = "表名，例如 orders") String table) {
        return run(ToolType.INSPECT_TABLE, table);
    }

    @Tool(name = "INSPECT_COLUMN",
            description = "查看某一列的真实取值分布（高频值 + 出现次数）。"
                    + "当问题里的地区、状态、类目等取值你不确定库里实际怎么存（大小写、缩写还是全称）时，"
                    + "先用它确认，不要凭猜测写 WHERE 条件。参数格式：表名.列名，例如 customers.customer_state。")
    public String inspectColumn(
            @ToolParam(description = "表名.列名，例如 customers.customer_state") String tableColumn) {
        return run(ToolType.INSPECT_COLUMN, tableColumn);
    }

    @Tool(name = "SAMPLE_QUERY",
            description = "试跑一条只读 SQL（自动限制 5 行）。"
                    + "用于验证过滤条件能否命中数据、确认数据的时间范围（MIN/MAX），"
                    + "或确认某个分组维度的取值是否合理。参数是一条完整的 SELECT 语句。")
    public String sampleQuery(
            @ToolParam(description = "一条完整的 SELECT 语句") String sql) {
        return run(ToolType.SAMPLE_QUERY, sql);
    }

    @Tool(name = "CHECK_JOIN",
            description = "验证两张表在给定连接条件下能否匹配出数据。"
                    + "当问题涉及 3 张以上表、或你不确定两张表该用哪个键连接、"
                    + "或担心 INNER JOIN 之后结果为空时使用。"
                    + "参数格式：表1, 表2, 连接条件。")
    public String checkJoin(
            @ToolParam(description = "格式：表1, 表2, 连接条件，例如 "
                    + "orders, customers, orders.customer_id = customers.customer_id") String args) {
        return run(ToolType.CHECK_JOIN, args);
    }

    @Tool(name = "EXPLAIN_QUERY",
            description = "查看一条 SQL 的执行计划与预估代价，用于排查全表笛卡尔积或慢查询。"
                    + "参数是一条完整的 SELECT 语句。")
    public String explainQuery(
            @ToolParam(description = "一条完整的 SELECT 语句") String sql) {
        return run(ToolType.EXPLAIN_QUERY, sql);
    }

    /**
     * 统一出口：工具内部抛异常时**不向上冒泡**，而是把错误文本当作 Observation 返回。
     *
     * <p>工具失败（表名不存在、SQL 写错）是模型推理过程的一部分，让它看到错误再改，
     * 比让整次请求失败有用。真正致命的错误（数据库连不上）会由执行层统一处理。
     */
    private String run(ToolType type, String argument) {
        try {
            ToolResult result = executor.execute(new ToolCall(type, argument, ""), schema);
            return result.output();
        } catch (Exception e) {
            return "工具执行失败：" + e.getMessage();
        }
    }
}
