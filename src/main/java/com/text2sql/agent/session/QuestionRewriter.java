package com.text2sql.agent.session;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.LlmSqlGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段 D：指代消解与多轮问题改写器。
 *
 * <p>核心解决「追问无法独立成句」的问题。例如：
 * <ul>
 *   <li>首问：「2018年销售额最高的5个州是哪些？」</li>
 *   <li>追问：「那2017年的呢？」</li>
 * </ul>
 * 检索层若直接拿「那2017年的呢？」去算词频和向量，无法召回订单和客户表。
 * 改写器将其重写为「2017年销售额最高的5个州是哪些？」，保证后续链路完全复用标准流程。
 */
@Component
public class QuestionRewriter {

    private static final Logger log = LoggerFactory.getLogger(QuestionRewriter.class);

    private static final Pattern FOLLOW_UP_PREFIX = Pattern.compile(
            "^(那|那么|然后再|再看|换成|改成|只看|如果|去掉|加上|其中|按照|按|除了|对比)");

    private static final Pattern PRONOUNS = Pattern.compile(
            "(这个|这些|上述|前述|上面的|它们|它)");

    private static final Pattern TAIL_PARTICLES = Pattern.compile(
            "(呢|怎么样|如何)[？?]?$");

    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\b20\\d{2}年?\\b)");

    private final LlmSqlGenerator generator;
    private final AgentProperties properties;

    public QuestionRewriter(LlmSqlGenerator generator, AgentProperties properties) {
        this.generator = generator;
        this.properties = properties;
    }

    /**
     * 判断当前问题是否为需要结合前文的追加提问。
     */
    public boolean isFollowUp(String question, ConversationSession session) {
        if (session == null || session.lastTurn() == null || question == null || question.isBlank()) {
            return false;
        }

        String q = question.trim();

        // 1. 前缀引导词命中（那、换成、只看...）
        if (FOLLOW_UP_PREFIX.matcher(q).find()) {
            return true;
        }

        // 2. 指代代词命中（这个、上述...）
        if (PRONOUNS.matcher(q).find()) {
            return true;
        }

        // 3. 简短条件追问（短于 20 字符且带语气词或排序/限定）
        if (q.length() <= 20) {
            if (TAIL_PARTICLES.matcher(q).find()) {
                return true;
            }
            if (q.contains("前") || q.contains("top") || q.contains("排序")
                    || q.contains("升序") || q.contains("降序") || q.contains("最高") || q.contains("最低")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否可以安全复用上一轮检索到的 Schema 上下文。
     *
     * <p>当追问仅针对时间过滤、排序、LIMIT、同实体切片时，所涉及的表完全一致，
     * 直接复用 SchemaContext 可以实现 0ms 检索耗时，并杜绝短问题表召回漂移。
     */
    public boolean canReuseSchema(String question, ConversationSession session) {
        if (session == null || session.lastSchema() == null) {
            return false;
        }
        if (!properties.getConversation().isReuseSchema()) {
            return false;
        }

        // 只要是判定为上下文依赖的追问，通常属于同一主题细化
        return isFollowUp(question, session);
    }

    /**
     * 执行意图补全与改写。
     */
    public String rewrite(String question, ConversationSession session) {
        if (!isFollowUp(question, session)) {
            return question;
        }

        ConversationTurn lastTurn = session.lastTurn();
        String prevQuestion = lastTurn.rewrittenQuestion() != null && !lastTurn.rewrittenQuestion().isBlank()
                ? lastTurn.rewrittenQuestion()
                : lastTurn.question();

        // 优先使用 LLM 改写
        if (properties.getConversation().isRewriteWithLlm() && generator.isConfigured()) {
            try {
                String llmRewritten = rewriteWithLlm(prevQuestion, lastTurn.sql(), question);
                if (llmRewritten != null && !llmRewritten.isBlank() && llmRewritten.length() < 300) {
                    log.info("LLM 意图补全改写: '{}' -> '{}'", question, llmRewritten);
                    return llmRewritten;
                }
            } catch (Exception e) {
                log.warn("LLM 改写失败，降级为规则改写：{}", e.getMessage());
            }
        }

        // 规则补全降级
        String ruleRewritten = rewriteWithRules(prevQuestion, question);
        log.info("规则意图补全改写: '{}' -> '{}'", question, ruleRewritten);
        return ruleRewritten;
    }

    private String rewriteWithLlm(String prevQuestion, String prevSql, String currentQuestion) {
        String system = """
                你是一个数据分析与 Text2SQL 系统的多轮对话意图补全助手。
                请结合上一轮的问答上下文，将用户的追加提问（存在省略、代词指代、条件切换）补全为一个语义完整独立的单句分析问题。

                规则：
                1. 结合上一轮问题，将当前简略追问补全为独立问句（如「那2017年的呢？」补全为「2017年销售额最高的5个州是哪些？」；「只看前3名」补全为「2018年销售额最高的前3个州是哪些？」）。
                2. 若当前提问本身已具备完整独立语义，则原样返回。
                3. 直接输出补全后的问题，严禁输出任何解释、分析说明、双引号或 markdown 标记。
                """;

        String user = String.format("""
                上一轮问题：%s
                上一轮生成的 SQL：%s
                当前追问：%s
                """, prevQuestion, prevSql == null ? "无" : prevSql, currentQuestion);

        List<Message> messages = List.of(new SystemMessage(system), new UserMessage(user));
        GeneratedSql result = generator.callMessages(messages, "CONVERSATION_REWRITE", null);
        String text = result.rawOutput();
        if (text == null) {
            return null;
        }
        // 清理首尾可能产生的问号、引号或前缀
        return text.trim()
                .replaceAll("^[\"“'‘`]+", "")
                .replaceAll("[\"”'’`]+$", "")
                .replaceAll("^(改写后(的问题)?|补全后(的问题)?)[:：]\\s*", "")
                .trim();
    }

    /**
     * 确定性规则改写降级，覆盖常见追问模态。
     */
    String rewriteWithRules(String prevQuestion, String currentQuestion) {
        String q = currentQuestion.trim();

        // 1. 年份替换（如「那2017年的呢？」且上一句有 2018 年）
        Matcher currentYearMatcher = YEAR_PATTERN.matcher(q);
        if (currentYearMatcher.find()) {
            String newYear = currentYearMatcher.group(1);
            Matcher prevYearMatcher = YEAR_PATTERN.matcher(prevQuestion);
            if (prevYearMatcher.find()) {
                return prevQuestion.replace(prevYearMatcher.group(1), newYear);
            }
        }

        // 2. 数量限制修改（如「只看前3名」、「前10个」）
        Pattern topPattern = Pattern.compile("(只看前|前|top\\s*)(\\d+)(名|个)?");
        Matcher topMatcher = topPattern.matcher(q);
        if (topMatcher.find()) {
            int n = Integer.parseInt(topMatcher.group(2));
            String cleaned = prevQuestion.replaceAll("(前|top\\s*)\\d+(名|个)?", "");
            return cleaned + "，只看前 " + n + " 名";
        }

        // 3. 条件切换（如「换成圣保罗州」）
        if (q.startsWith("换成") || q.startsWith("改成")) {
            String cond = q.substring(2).trim();
            return prevQuestion + "，条件换成 " + cond;
        }

        // 4. 通用剥离语气词拼接
        String stripped = q.replaceFirst("^[那那么]+", "").trim();
        stripped = TAIL_PARTICLES.matcher(stripped).replaceFirst("").trim();
        if (!stripped.isBlank()) {
            return prevQuestion + "，" + stripped;
        }

        return prevQuestion + "，" + q;
    }
}
