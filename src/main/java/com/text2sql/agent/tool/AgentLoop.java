package com.text2sql.agent.tool;

import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.SchemaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体侦察与多轮推理循环（Agent Execution Loop）。
 *
 * <p>实现 Act -> Observe -> Reason -> Finalize 循环：
 * 允许大模型在给出最终 SQL 之前，主动调用工具侦察数据库中的真实表结构、枚举值、连表可达性，
 * 并将真实的数据库观测数据回传给模型。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final LlmSqlGenerator generator;
    private final PromptTemplate promptTemplate;
    private final ToolExecutor toolExecutor;

    public AgentLoop(LlmSqlGenerator generator, PromptTemplate promptTemplate, ToolExecutor toolExecutor) {
        this.generator = generator;
        this.promptTemplate = promptTemplate;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 运行 Agent 侦察循环。
     *
     * @param question 用户问题
     * @param schema Schema 上下文
     * @param maxRounds 最大工具交互轮次（如 3 轮）
     * @param modelOverride 模型覆盖（若有）
     * @return 最终产出的 SQL 与累计的审计记录
     */
    public GeneratedSql run(String question, SchemaContext schema, int maxRounds, String modelOverride) {
        return run(question, schema, maxRounds, modelOverride, null);
    }

    /**
     * 运行 Agent 侦察循环（支持指定 API Key 覆盖）。
     */
    public GeneratedSql run(String question, SchemaContext schema, int maxRounds, String modelOverride, String apiKeyOverride) {
        String baseSystem = promptTemplate.systemPrompt();
        String toolInstruction = """

                【侦察工具支持】
                在编写复杂多表 SQL 前，你如果不确定枚举值、字段名或连表关系，可以先调用工具探查数据库真实数据：
                - INSPECT_TABLE(表名) — 查看表的真实结构和前 5 行样例数据
                - INSPECT_COLUMN(表名.列名) — 查看某列的高频枚举值与数据分布
                - SAMPLE_QUERY(sql) — 试跑一条小验证查询（自动限 5 行）
                - CHECK_JOIN(表1, 表2, 连接条件) — 验证连表是否能匹配出数据
                - EXPLAIN_QUERY(sql) — 探测查询执行计划与预估代价，排查笛卡尔积与慢查询

                调用格式必须单独占行，如下：
                [TOOL_CALL] 工具名(参数)

                当探查充分并确认最终 SQL 时，请输出：
                [FINAL_SQL] SELECT ...
                """;

        String systemPrompt = baseSystem + toolInstruction;
        String userPrompt = promptTemplate.userPrompt(schema, question);

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt));
        history.add(new UserMessage(userPrompt));

        LlmCallRecord combinedRecord = null;
        String finalSql = null;
        String lastRaw = "";

        for (int round = 1; round <= maxRounds; round++) {
            log.info("Agent 循环轮次 [{}/{}]...", round, maxRounds);
            GeneratedSql step = (apiKeyOverride != null && !apiKeyOverride.isBlank())
                    ? generator.callMessages(history, "AGENT_ROUND_" + round, modelOverride, apiKeyOverride)
                    : generator.callMessages(history, "AGENT_ROUND_" + round, modelOverride);
            combinedRecord = LlmCallRecord.combine(combinedRecord, step.call());
            lastRaw = step.rawOutput();

            ToolCall toolCall = ToolParser.parse(step.rawOutput());

            // 1. 如果模型给出了最终 SQL，直接终止循环
            if (toolCall != null && toolCall.type() == ToolType.FINAL_SQL) {
                log.info("Agent 明确提交最终 SQL：{}", toolCall.argument());
                finalSql = toolCall.argument();
                break;
            }

            // 2. 如果模型请求调用工具，执行工具并将结果追加为 Observation
            if (toolCall != null) {
                log.info("Agent 请求调用工具：{} 参数：{}", toolCall.type(), toolCall.argument());
                ToolResult result = toolExecutor.execute(toolCall, schema);
                history.add(new AssistantMessage(step.rawOutput()));
                history.add(new UserMessage("【工具返回结果 (Observation)】:\n" + result.output()
                        + "\n请根据上述真实数据继续推导。若准备好最终答案，输出 [FINAL_SQL] SELECT ...，若仍需探查可继续调用工具。"));
                continue;
            }

            // 3. 模型未按格式调用工具，也没有打 [FINAL_SQL] 标签：尝试从输出中提取 SQL，若提取成功则结束
            if (step.sql() != null && !step.sql().isBlank()) {
                log.info("Agent 未带标签但直接输出了有效 SQL");
                finalSql = step.sql();
                break;
            }

            // 兜底追加提示
            history.add(new AssistantMessage(step.rawOutput()));
            history.add(new UserMessage("请按格式调用 [TOOL_CALL] 工具名(参数) 或给出 [FINAL_SQL] SELECT ..."));
        }

        if (finalSql == null || finalSql.isBlank()) {
            finalSql = com.text2sql.agent.generation.SqlExtractor.extract(lastRaw);
        }

        return new GeneratedSql(finalSql, lastRaw, combinedRecord);
    }
}
