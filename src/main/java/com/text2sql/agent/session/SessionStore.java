package com.text2sql.agent.session;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.persistence.PersistenceSupport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮会话存储管理器。
 *
 * <p><b>存储架构：内存缓存 + 结束落库</b>
 * <ul>
 *   <li><b>会话进行中</b>：全在内存 {@link ConcurrentHashMap} 中高速存取，
 *       不向数据库发起任何 I/O 操作，确保每一轮问答响应不受数据库写入影响；</li>
 *   <li><b>会话结束时</b>：当用户/调用方显式调用关闭接口、请求携带结束标识、
 *       或者会话超时被淘汰时，将完整会话及所有轮次写入 PostgreSQL 持久化表；</li>
 *   <li><b>平滑降级</b>：若未配置持久化或数据库离线，记录日志并优雅降级，主流程不抛异常。</li>
 * </ul>
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final AgentProperties properties;
    private final JdbcTemplate db;
    private final Map<String, ConversationSession> cache = new ConcurrentHashMap<>();
    private volatile boolean tablesInitialized = false;

    public SessionStore(AgentProperties properties) {
        this.properties = properties;
        this.db = PersistenceSupport.jdbcTemplateOrNull(properties, "多轮会话存储");
    }

    public boolean persisted() {
        return db != null;
    }

    /**
     * 获取或创建会话。
     *
     * <p>如果是新会话，放入内存缓存；若是已关闭的旧会话，重新激活或返回新实例。
     */
    public ConversationSession getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return cache.compute(sessionId, (id, existing) -> {
            if (existing == null || existing.isClosed()) {
                return new ConversationSession(id);
            }
            existing.touch();
            return existing;
        });
    }

    /**
     * 查询已有会话。
     */
    public Optional<ConversationSession> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(sessionId));
    }

    /**
     * 关闭并持久化会话。
     *
     * <p>硬性设计：会话进行中完全停留在缓存，只有在此处明确结束时才落库。
     */
    public Optional<ConversationSession> closeAndPersist(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        ConversationSession session = cache.get(sessionId);
        if (session == null) {
            log.warn("尝试关闭不存在的会话: {}", sessionId);
            return Optional.empty();
        }

        session.close();

        if (db != null) {
            ensureTables();
            persistToDb(session);
        } else {
            log.info("会话 {} 结束（未启用持久化连接，仅保留内存关闭状态）", sessionId);
        }

        return Optional.of(session);
    }

    /**
     * 清理超过指定非活跃时长的超时会话，并在淘汰前落库。
     */
    public int cleanExpired(Duration timeout) {
        Instant threshold = Instant.now().minus(timeout);
        int evicted = 0;
        for (Map.Entry<String, ConversationSession> entry : cache.entrySet()) {
            ConversationSession session = entry.getValue();
            if (session.lastActiveAt().isBefore(threshold)) {
                if (!session.isClosed()) {
                    session.close();
                    if (db != null) {
                        ensureTables();
                        persistToDb(session);
                    }
                }
                cache.remove(entry.getKey());
                evicted++;
            }
        }
        return evicted;
    }

    /**
     * 定时任务：默认每分钟扫描一次，若会话超过 30 分钟无操作，自动持久化落库并从内存缓存淘汰。
     */
    @Scheduled(fixedRate = 60000)
    public void scheduledEvictExpired() {
        int timeoutMinutes = properties.getConversation().getTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            timeoutMinutes = 30;
        }
        int evicted = cleanExpired(Duration.ofMinutes(timeoutMinutes));
        if (evicted > 0) {
            log.info("会话超时管理：检测到 {} 个会话超过 {} 分钟未操作，已自动持久化落库并清理缓存", evicted, timeoutMinutes);
        }
    }

    /**
     * 容器关闭前钩子：落库所有尚未保存的活跃会话，防止数据丢失。
     */
    @PreDestroy
    public void onShutdown() {
        if (db == null) {
            return;
        }
        log.info("容器停止中，正在将内存中的多轮会话持久化入库...");
        for (ConversationSession session : cache.values()) {
            if (!session.isClosed()) {
                session.close();
                ensureTables();
                persistToDb(session);
            }
        }
    }

    public int activeCount() {
        return (int) cache.values().stream().filter(s -> !s.isClosed()).count();
    }

    public void clearMemory() {
        cache.clear();
    }

    private synchronized void ensureTables() {
        if (db == null || tablesInitialized) {
            return;
        }
        try {
            db.execute("""
                CREATE TABLE IF NOT EXISTS conversation_session (
                    session_id VARCHAR(64) PRIMARY KEY,
                    created_at TIMESTAMP,
                    closed_at TIMESTAMP,
                    turn_count INT,
                    last_question TEXT,
                    status VARCHAR(32)
                );
                CREATE TABLE IF NOT EXISTS conversation_turn (
                    id SERIAL PRIMARY KEY,
                    session_id VARCHAR(64),
                    turn_index INT,
                    question TEXT,
                    rewritten_question TEXT,
                    sql_text TEXT,
                    status VARCHAR(32),
                    result_summary TEXT,
                    created_at TIMESTAMP
                );
            """);
            tablesInitialized = true;
            log.info("多轮会话持久化表 conversation_session / conversation_turn 初始化完成");
        } catch (Exception e) {
            log.warn("初始化多轮会话持久化表失败（会话将仅在内存中保留）：{}", e.getMessage());
        }
    }

    private void persistToDb(ConversationSession session) {
        try {
            var lastTurn = session.lastTurn();
            String lastQ = lastTurn != null ? lastTurn.question() : "";
            String status = session.isClosed() ? "CLOSED" : "ACTIVE";

            db.update("""
                INSERT INTO conversation_session(session_id, created_at, closed_at, turn_count, last_question, status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO UPDATE SET
                    closed_at = EXCLUDED.closed_at,
                    turn_count = EXCLUDED.turn_count,
                    last_question = EXCLUDED.last_question,
                    status = EXCLUDED.status
            """,
                session.sessionId(),
                Timestamp.from(session.createdAt()),
                Timestamp.from(session.lastActiveAt()),
                session.turns().size(),
                lastQ,
                status
            );

            // 删除旧轮次，保证幂等重写
            db.update("DELETE FROM conversation_turn WHERE session_id = ?", session.sessionId());

            for (ConversationTurn turn : session.turns()) {
                db.update("""
                    INSERT INTO conversation_turn(session_id, turn_index, question, rewritten_question, sql_text, status, result_summary, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    session.sessionId(),
                    turn.turnIndex(),
                    turn.question(),
                    turn.rewrittenQuestion(),
                    turn.sql(),
                    turn.status(),
                    turn.resultSummary(),
                    Timestamp.from(turn.createdAt())
                );
            }
            log.info("会话 {} 成功持久化入库，共 {} 轮对话", session.sessionId(), session.turns().size());
        } catch (Exception e) {
            log.warn("会话 {} 落库失败（不影响主响应）：{}", session.sessionId(), e.getMessage());
        }
    }

    public record SessionSummary(
            String sessionId,
            String title,
            int turnCount,
            String status,
            Instant createdAt,
            Instant lastActiveAt
    ) {}

    /**
     * 查询历史与当前会话列表（内存高速缓存 + 数据库持久化记录双层融合）。
     */
    public List<SessionSummary> listSessions() {
        Map<String, SessionSummary> map = new LinkedHashMap<>();

        // 1. 内存中活跃或近期的会话优先
        cache.values().stream()
                .sorted((a, b) -> b.lastActiveAt().compareTo(a.lastActiveAt()))
                .forEach(s -> {
                    String title = s.turns().isEmpty() ? "新会话" : s.turns().get(0).question();
                    map.put(s.sessionId(), new SessionSummary(
                            s.sessionId(),
                            title,
                            s.turns().size(),
                            s.isClosed() ? "CLOSED" : "ACTIVE",
                            s.createdAt(),
                            s.lastActiveAt()
                    ));
                });

        // 2. 若数据库可用，补充已入库的持久化历史会话
        if (db != null) {
            try {
                ensureTables();
                db.query("SELECT session_id, created_at, closed_at, turn_count, last_question, status FROM conversation_session ORDER BY closed_at DESC LIMIT 50",
                        rs -> {
                            String id = rs.getString("session_id");
                            if (!map.containsKey(id)) {
                                Timestamp created = rs.getTimestamp("created_at");
                                Timestamp closed = rs.getTimestamp("closed_at");
                                map.put(id, new SessionSummary(
                                        id,
                                        rs.getString("last_question") != null && !rs.getString("last_question").isBlank()
                                                ? rs.getString("last_question") : "历史会话",
                                        rs.getInt("turn_count"),
                                        rs.getString("status"),
                                        created != null ? created.toInstant() : Instant.now(),
                                        closed != null ? closed.toInstant() : Instant.now()
                                ));
                            }
                        });
            } catch (Exception e) {
                log.warn("查询持久化会话列表失败：{}", e.getMessage());
            }
        }

        return new ArrayList<>(map.values());
    }

    /**
     * 删除指定会话（内存与数据库同步清理）。
     */
    public boolean deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        cache.remove(sessionId);
        if (db != null) {
            try {
                ensureTables();
                db.update("DELETE FROM conversation_turn WHERE session_id = ?", sessionId);
                db.update("DELETE FROM conversation_session WHERE session_id = ?", sessionId);
            } catch (Exception e) {
                log.warn("删除会话 {} 失败：{}", sessionId, e.getMessage());
            }
        }
        return true;
    }
}
