package com.text2sql.agent.session;

import com.text2sql.agent.retrieval.SchemaContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用户多轮对话会话对象。
 *
 * <p>核心职责：
 * <ul>
 *   <li>维护多轮问答的历史轮次（限制在 maxTurns 以内，防止 Prompt 上下文过载和内存泄漏）；</li>
 *   <li>缓存上一轮检索出的 {@link SchemaContext}，使后续追问可以实现 0ms Schema 复用；</li>
 *   <li>维护会话活跃时间与关闭状态，支持超时自动淘汰与会话结束落库。</li>
 * </ul>
 */
public class ConversationSession {

    private final String sessionId;
    private final Instant createdAt;
    private volatile Instant lastActiveAt;
    private volatile boolean closed;
    private volatile SchemaContext lastSchema;
    private volatile SessionContextSummary contextSummary = SessionContextSummary.empty();
    private final List<ConversationTurn> turns = Collections.synchronizedList(new ArrayList<>());

    public ConversationSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastActiveAt = this.createdAt;
        this.closed = false;
    }

    public String sessionId() {
        return sessionId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActiveAt() {
        return lastActiveAt;
    }

    public boolean isClosed() {
        return closed;
    }

    public SchemaContext lastSchema() {
        return lastSchema;
    }

    public void setLastSchema(SchemaContext schema) {
        this.lastSchema = schema;
    }

    public SessionContextSummary contextSummary() {
        return contextSummary;
    }

    public void setContextSummary(SessionContextSummary contextSummary) {
        this.contextSummary = contextSummary != null ? contextSummary : SessionContextSummary.empty();
    }

    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    public void close() {
        this.closed = true;
        touch();
    }

    /**
     * 追加一轮对话记录，并淘汰超限的早期轮次。
     */
    public void addTurn(ConversationTurn turn, int maxTurns) {
        addTurn(turn, maxTurns, null);
    }

    /**
     * 追加一轮对话记录，并将被淘汰滑出窗口的早期轮次自动压缩入上下文摘要中。
     */
    public void addTurn(ConversationTurn turn, int maxTurns, SessionContextCompressor compressor) {
        touch();
        turns.add(turn);
        while (turns.size() > maxTurns && maxTurns > 0) {
            ConversationTurn evicted = turns.remove(0);
            if (compressor != null) {
                this.contextSummary = compressor.compress(this.contextSummary, evicted);
            }
        }
    }


    /**
     * 获取历史所有轮次快照（线程安全拷贝）。
     */
    public List<ConversationTurn> turns() {
        synchronized (turns) {
            return new ArrayList<>(turns);
        }
    }

    /**
     * 获取最新一轮对话记录，无历史则返回 null。
     */
    public ConversationTurn lastTurn() {
        synchronized (turns) {
            if (turns.isEmpty()) {
                return null;
            }
            return turns.get(turns.size() - 1);
        }
    }
}
