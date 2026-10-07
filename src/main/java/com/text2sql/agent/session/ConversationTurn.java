package com.text2sql.agent.session;

import java.time.Instant;

/**
 * 多轮会话中的单轮交互明细。
 *
 * <p>用于记录问答历史，供后续追问的指代消解与会话落库审计使用。
 *
 * @param turnIndex         第几轮交互（1-based）
 * @param question          用户本轮输入的原始问题（如「那2017年的呢？」）
 * @param rewrittenQuestion 指代消解补全后的完整问题（如「2017年销售额最高的5个州是哪些？」）
 * @param sql               本轮生成的 SQL（若未成功生成则为 null）
 * @param status            本轮执行终态（SUCCESS, REJECTED, etc.）
 * @param resultSummary     结果集简要摘要（如行数、列名与前 3 行预览），不存全量大对象以防打爆内存
 * @param createdAt         交互发生的时间戳
 */
public record ConversationTurn(
        int turnIndex,
        String question,
        String rewrittenQuestion,
        String sql,
        String status,
        String resultSummary,
        Instant createdAt) {

    public static ConversationTurn of(int turnIndex, String question, String rewrittenQuestion,
                                      String sql, String status, String resultSummary) {
        return new ConversationTurn(turnIndex, question, rewrittenQuestion, sql, status,
                resultSummary, Instant.now());
    }
}
