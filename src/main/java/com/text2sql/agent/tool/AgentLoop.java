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
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体侦察与多轮推理循环（Agent Execution Loop）。
 *
 * <p>实现 Act → Observe → Reason → Finalize 循环：允许模型在给出最终 SQL 之前，
 * 主动调用工具侦察真实的表结构、枚举值与连表可达性，再把真实观测回传给模型。
 *
 * <p><b>工具调用走原生 function calling，不走文本标记。</b>
 * 早期版本让模型输出 {@code [TOOL_CALL] 工具名(参数)} 再用正则解析，实测有三个问题：
 * 一条消息连发两个调用会互相干扰、参数里含括号要手工配对、解析失败时整段文本会被
 * 当成 SQL 送进校验层并报 {@code PARSE_ERROR}（归因错写成「模型写错 SQL」）。
 * 改成 {@code tools} + {@code tool_calls} 之后这三类问题在协议层就不存在了。
 *
 * <p><b>收口由协议保证，不靠提示词。</b>
 * 最后一轮 {@code toolCallbacks} 传空列表——模型物理上没有工具可调，只能输出文本。
 * 这比在 prompt 里写「禁止调用工具」可靠。
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
     * @param maxRounds 最大轮次（最后一轮强制收口，不提供工具）
     * @param modelOverride 模型覆盖（若有）
     */
    public GeneratedSql run(String question, SchemaContext schema, int maxRounds, String modelOverride) {
        return run(question, schema, maxRounds, modelOverride, null);
    }

    /**
     * 运行 Agent 侦察循环（支持指定 API Key 覆盖）。
     */
    public GeneratedSql run(String question, SchemaContext schema, int maxRounds,
                            String modelOverride, String apiKeyOverride) {
        String systemPrompt = promptTemplate.systemPrompt() + toolInstruction();
        String userPrompt = promptTemplate.userPrompt(schema, question);

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt));
        history.add(new UserMessage(userPrompt));

        // 工具需要当前请求的 schema（表白名单/列白名单都从它来），所以每请求新建一份，
        // 不能做成单例 Bean——否则上一个请求的 schema 会串到下一个请求。
        List<ToolCallback> callbacks = List.of(
                ToolCallbacks.from(new AgentToolFunctions(toolExecutor, schema)));
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback cb : callbacks) {
            byName.put(cb.getToolDefinition().name(), cb);
        }

        LlmCallRecord combinedRecord = null;
        String lastRaw = "";
        String finalSql = null;

        for (int round = 1; round <= maxRounds; round++) {
            boolean finalRound = (round == maxRounds);
            // 最后一轮**仍然给工具**。
            //
            // 早期版本在最后一轮把 tools 置空，想用「协议层没有工具可调」强制收口。
            // 实测 DeepSeek 思考模式在这种情况下不会乖乖给 SQL，而是把它想调的工具
            // 以内部 DSML 文本形式吐出来（`<｜｜DSML｜｜ calls>...`），被当成 SQL
            // 送进校验层直接 REJECTED。所以收口改成「轮次用完后再要一次」，
            // 而不是靠抽掉工具。
            List<ToolCallback> available = callbacks;

            // 第一轮追加一次性指令，逼模型先侦察。
            //
            // 为什么需要：实测模型即使手里有工具，也会直接凭 schema 猜——
            // 问"加州"时它没查 customer_state，直接断言"加州 = 'CA'"（实际是巴西州），
            // 自信地给出 0 行。DeepSeek 思考模式不支持 tool_choice（400），
            // 协议层强制不了，只能在第一轮明确要求。
            //
            // 这条指令放在临时列表里、不写进 history：否则它会在后续轮次继续生效，
            // 模型会一直侦察不肯定稿。
            List<Message> roundMessages = history;
            if (round == 1 && maxRounds > 1) {
                roundMessages = new ArrayList<>(history);
                roundMessages.add(new UserMessage(
                        "【第一轮要求】这一轮先不要写 SQL。请调用工具确认你写这条 SQL 之前最不确定的一件事："
                                + "某个字段的实际取值、两张表该怎么连、或者数据的时间范围。"
                                + "只调用工具，不要输出 SQL。"));
            }

            LlmSqlGenerator.AgentTurn turn = generator.callWithTools(
                    roundMessages, available, "AGENT_ROUND_" + round, modelOverride, apiKeyOverride);
            combinedRecord = LlmCallRecord.combine(combinedRecord, turn.call());
            lastRaw = turn.raw();

            AssistantMessage output = turn.response().getResult().getOutput();
            if (!output.hasToolCalls()) {
                finalSql = SqlExtractor.extract(stripToolMarkup(output.getText()));
                log.info("Agent 第 {} 轮直接给出最终 SQL：{}", round, finalSql);
                break;
            }

            log.info("Agent 第 {} 轮请求 {} 个工具：{}", round, output.getToolCalls().size(),
                    output.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList());

            history.add(output);
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                responses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), invoke(byName, toolCall)));
            }
            history.add(ToolResponseMessage.builder().responses(responses).build());

            if (finalRound) {
                // 轮次用完还在侦察：这批工具结果已经执行，再要一次，这次明确禁止调用。
                log.warn("Agent {} 轮用尽仍在调用工具，发起一次强制收口", maxRounds);
                history.add(new UserMessage(
                        "工具调用轮次已用完，现在没有工具可用了。不要再输出任何工具调用或标记，"
                                + "直接根据已有信息输出最终 SQL 语句。"));
                LlmSqlGenerator.AgentTurn finish = generator.callWithTools(
                        history, List.of(), "AGENT_FINALIZE", modelOverride, apiKeyOverride);
                combinedRecord = LlmCallRecord.combine(combinedRecord, finish.call());
                lastRaw = finish.raw();
                finalSql = SqlExtractor.extract(stripToolMarkup(finish.response().getResult().getOutput().getText()));
                log.info("Agent 强制收口产出：{}", finalSql);
            }
        }

        if (finalSql == null || finalSql.isBlank()) {
            throw new GenerationException(GenerationException.Reason.UNPARSEABLE,
                    "Agent 在 " + maxRounds + " 轮内未产出可解析的 SQL");
        }
        return new GeneratedSql(finalSql, lastRaw, combinedRecord);
    }

    /**
     * 剥掉 DeepSeek 思考模式在「没有工具可用」时吐出的内部标记。
     *
     * <p>实测：最后一轮如果不给 tools，模型不会乖乖输出 SQL，而是把它想调用的工具
     * 以 {@code <｜｜DSML｜｜ calls>...<｜｜DSML｜｜ invoke ...>} 的形式当文本吐出来。
     * 这段文本会被 {@code SqlExtractor} 当成 SQL 送进校验层，报 {@code PARSE_ERROR}
     * 或 {@code REJECTED}，归因会错写成「模型写错 SQL」。
     *
     * <p>截断而不是删除中间片段：标记之后的内容本来就是工具调用参数，没有 SQL。
     */
    private static String stripToolMarkup(String text) {
        if (text == null) {
            return null;
        }
        int dsml = text.indexOf("DSML");
        if (dsml >= 0) {
            int start = text.lastIndexOf('<', dsml);
            return text.substring(0, Math.max(start, 0)).strip();
        }
        return text.strip();
    }

    /**
     * 执行一次工具调用。
     *
     * <p>工具失败（表名不存在、SQL 写错）不向上冒泡，而是把错误文本当成 Observation
     * 返回给模型——让它看到错误再改，比让整次请求失败有用。
     */
    private static String invoke(Map<String, ToolCallback> byName, AssistantMessage.ToolCall toolCall) {
        ToolCallback callback = byName.get(toolCall.name());
        if (callback == null) {
            return "未知工具：" + toolCall.name() + "。可用工具：" + byName.keySet();
        }
        try {
            return callback.call(toolCall.arguments());
        } catch (Exception e) {
            log.warn("工具 {} 执行异常：{}", toolCall.name(), e.getMessage());
            return "工具执行失败：" + e.getMessage();
        }
    }

    /**
     * 工具使用说明，追加在 system prompt 末尾。
     *
     * <p>工具本身已有 description，这里只补「什么情况下**必须**用」——
     * 实测模型倾向于凭 schema 猜而不去查，所以把触发条件写成规则而不是建议。
     */
    private static String toolInstruction() {
        return """

                【侦察工具】
                你可以调用工具查看数据库真实数据。以下三种情况**必须先调用工具再定稿**：
                1. 问题里的地区、状态、类目等取值，在 schema 或枚举提示里找不到对应写法
                   （例：用户说"加州"，但你不确定库里存的是 'CA' 还是全称）；
                2. 涉及 3 张以上表，且你不确定它们之间该怎么连；
                3. 问题的时间范围可能超出「数据画像」给出的区间
                   （例：数据只到 2018 年，用户却问 2024 年）。

                拿到工具结果后：如果实际值与你的假设不一致，**必须按实际值改写 SQL**，不要沿用假设。

                确认无误后，直接输出最终 SQL 语句，不要加任何标记前缀。
                """;
    }
}
