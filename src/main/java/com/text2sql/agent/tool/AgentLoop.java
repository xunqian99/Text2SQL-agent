package com.text2sql.agent.tool;

import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.GenerationException;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.generation.SqlExtractor;
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
                在编写复杂多表 SQL 前，你可以调用工具探查数据库真实数据：
                - INSPECT_TABLE(表名) — 查看表的真实结构和前 5 行样例数据
                - INSPECT_COLUMN(表名.列名) — 查看某列的高频枚举值与数据分布
                - SAMPLE_QUERY(sql) — 试跑一条小验证查询（自动限 5 行）
                - CHECK_JOIN(表1, 表2, 连接条件) — 验证连表是否能匹配出数据
                - EXPLAIN_QUERY(sql) — 探测查询执行计划与预估代价，排查笛卡尔积与慢查询

                【必须先用工具的三种情况】——不是建议，是规则：
                1. 问题里的地区、状态、类目等取值，在 schema 或枚举提示里找不到对应写法
                   （例：用户说"加州"，但你不确定库里存的是 'CA' 还是全称）；
                2. 问题涉及 3 张以上表，且你不确定它们之间该怎么连；
                3. 问题的时间范围可能超出「数据画像」给出的时间区间
                   （例：数据只到 2018 年，用户却问 2024 年——先确认，不要直接生成必然为空的查询）。

                调用格式必须单独占行，可以一次调用多个工具，每个各占一行：
                [TOOL_CALL] 工具名(参数)

                拿到工具结果后，如果实际值与你的假设不一致，必须按实际值改写 SQL。
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
            boolean finalRound = (round == maxRounds);
            // 最后一轮先收口。实测：模型会一直调工具直到轮次耗尽，然后把
            // "[TOOL_CALL] ..." 当成 SQL 交出去，校验层报 PARSE_ERROR——
            // 用户侧看到的是「模型写错 SQL」，归因完全错位。
            if (finalRound) {
                history.add(new UserMessage(
                        "【最后一轮】轮次已用尽，现在必须直接给出最终 SQL，禁止再调用任何工具。\n"
                                + "只输出一行标记加一条完整 SQL：[FINAL_SQL] SELECT ..."));
            }

            log.info("Agent 循环轮次 [{}/{}]...", round, maxRounds);
            GeneratedSql step = (apiKeyOverride != null && !apiKeyOverride.isBlank())
                    ? generator.callMessages(history, "AGENT_ROUND_" + round, modelOverride, apiKeyOverride)
                    : generator.callMessages(history, "AGENT_ROUND_" + round, modelOverride);
            combinedRecord = LlmCallRecord.combine(combinedRecord, step.call());
            lastRaw = step.rawOutput();

            // 一条消息里可能连发多个 [TOOL_CALL]，必须全部解析出来。
            // 旧实现只取第一个，第二个会被丢掉，且两个调用的括号会互相干扰，
            // 导致解析失败、整段文本被当成 SQL 送进校验层。
            List<ToolCall> calls = ToolParser.parseAll(step.rawOutput());

            // 1. 如果模型给出了最终 SQL，直接终止循环
            ToolCall finalCall = calls.stream()
                    .filter(c -> c.type() == ToolType.FINAL_SQL)
                    .findFirst().orElse(null);
            if (finalCall != null) {
                // 必须再过一次 SqlExtractor：`[FINAL_SQL]` 后面模型经常会附一段解释
                // （prompt 本身就鼓励它说明口径），直接用整段当 SQL 会把解释也带进去，
                // 校验层报 PARSE_ERROR，最后被归因成「模型写错 SQL」——归因就错了。
                try {
                    finalSql = SqlExtractor.extract(finalCall.argument());
                    log.info("Agent 明确提交最终 SQL：{}", finalSql);
                    break;
                } catch (GenerationException e) {
                    // 标签后没有可解析的 SQL。按「本轮无效」处理继续循环，
                    // 而不是让整次请求失败：模型下一轮通常能改对。
                    log.warn("Agent 输出了 [FINAL_SQL] 但其中没有可解析的 SQL：{}", e.getMessage());
                    history.add(new AssistantMessage(step.rawOutput()));
                    history.add(new UserMessage(
                            "你在 [FINAL_SQL] 之后没有给出可解析的 SQL。请只输出一条完整的 SELECT 语句，例如：\n"
                                    + "[FINAL_SQL] SELECT ... FROM ... WHERE ..."));
                    continue;
                }
            }

            // 2. 如果模型请求调用工具，执行工具并将结果追加为 Observation
            if (!calls.isEmpty()) {
                if (finalRound) {
                    // 最后一轮还在调工具：执行了也没有下一轮消费结果，直接跳出走强制收口。
                    log.warn("最后一轮仍在请求工具（{} 个），放弃执行并强制收口", calls.size());
                    break;
                }
                StringBuilder observations = new StringBuilder();
                for (ToolCall call : calls) {
                    log.info("Agent 请求调用工具：{} 参数：{}", call.type(), call.argument());
                    ToolResult result = toolExecutor.execute(call, schema);
                    observations.append("· ").append(call.type()).append("(").append(call.argument()).append(")\n")
                            .append(result.output()).append("\n\n");
                }
                history.add(new AssistantMessage(step.rawOutput()));
                // 回灌文案是刻意写死的：实测模型查完值之后仍沿用原来的假设
                // （查了 customer_state 发现是 SP/RJ，SQL 里还是写 'CA'），
                // 所以必须显式要求「不一致就改」。
                history.add(new UserMessage("【工具返回结果 (Observation)】\n" + observations
                        + "先核对：上面的真实数据是否与你原先的假设一致？\n"
                        + "- 不一致（例如你以为州代码是 'CA'，实际是 'SP'/'RJ'）：必须按实际值改写 SQL，不要沿用假设。\n"
                        + "- 一致：输出 [FINAL_SQL] SELECT ... 定稿。\n"
                        + "- 仍需探查：继续输出 [TOOL_CALL] 工具名(参数)。"));
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

        // 轮次用尽仍未定稿：发起一次「禁止工具」的强制收口调用。
        // 不做这一步，下面只能拿最后一条 [TOOL_CALL] 文本去抽 SQL，必然 PARSE_ERROR。
        if (finalSql == null || finalSql.isBlank()) {
            log.warn("Agent 轮次用尽仍未定稿，发起一次强制收口调用");
            history.add(new UserMessage("你还没有给出最终 SQL。禁止调用任何工具，只输出：[FINAL_SQL] SELECT ..."));
            try {
                GeneratedSql last = (apiKeyOverride != null && !apiKeyOverride.isBlank())
                        ? generator.callMessages(history, "AGENT_FINALIZE", modelOverride, apiKeyOverride)
                        : generator.callMessages(history, "AGENT_FINALIZE", modelOverride);
                combinedRecord = LlmCallRecord.combine(combinedRecord, last.call());
                lastRaw = last.rawOutput();
                ToolCall c = ToolParser.parse(last.rawOutput());
                if (c != null && c.type() == ToolType.FINAL_SQL) {
                    finalSql = SqlExtractor.extract(c.argument());
                    log.info("Agent 强制收口成功：{}", finalSql);
                } else if (last.sql() != null && !last.sql().isBlank()) {
                    finalSql = last.sql();
                }
            } catch (GenerationException e) {
                log.warn("强制收口调用失败：{}", e.getMessage());
            }
        }

        if (finalSql == null || finalSql.isBlank()) {
            throw new GenerationException(GenerationException.Reason.UNPARSEABLE,
                    "Agent 在 " + maxRounds + " 轮内未产出可解析的 SQL，强制收口也未成功");
        }

        return new GeneratedSql(finalSql, lastRaw, combinedRecord);
    }
}
