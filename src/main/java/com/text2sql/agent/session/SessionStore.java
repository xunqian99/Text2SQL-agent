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

    /**
     * 会话内存缓存。
     *
     * <p><b>为什么必须有容量上限</b>：`sessionId` 由调用方提供，
     * 而 {@link #getOrCreate} 是「先建会话、后生成 SQL」，所以**失败的请求也会占位**。
     * 早先这里是无界 ConcurrentHashMap，只靠 30 分钟的定时任务清理——
     * 在这 30 分钟里，脚本只要不停换 sessionId 就能堆任意多个会话，
     * 每个会话还持有整份 {@link com.text2sql.agent.retrieval.SchemaContext}。
     * 这与 BYOK 客户端池曾经的问题属于同一类：内存占用由外部输入决定。
     *
     * <p>改为 LRU（accessOrder=true）+ 上限，超限时淘汰最久未使用的会话。
     * 淘汰前不落库——能活到被 LRU 挤出去的多半是低价值会话，
     * 而为它们各写一次数据库反而会拖慢主流程。
     */
    private static final int MAX_CACHED_SESSIONS = 1000;

    private final Map<String, ConversationSession> cache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ConversationSession> eldest) {
                    return size() > MAX_CACHED_SESSIONS;
                }
            });

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

        // 已经关过就不再落库一次。否则同一个会话被关闭两次（前端重试、脚本重复调用）
        // 会把同样的轮次再写一遍，历史里出现重复。
        boolean alreadyClosed = session.isClosed();
        session.close();

        if (db != null && !alreadyClosed) {
            ensureTables();
            if (persistToDb(session)) {
                // 落库成功后把它移出内存。
                //
                // 两个理由：一是列表改从库里读，能拿到**累计**轮次——否则同一
                // sessionId 复用两段对话时，内存里只有最新一段、库里有两段，
                // 接口显示 1 轮、数据库 2 轮，用户会觉得「我明明问了两段」；
                // 二是已关闭的会话继续挂在内存里没有任何用处，只是占位。
                synchronized (cache) {
                    cache.remove(sessionId, session);
                }
            }
        } else if (db == null) {
            log.info("会话 {} 结束（未启用持久化连接，仅保留内存关闭状态）", sessionId);
        }

        return Optional.of(session);
    }

    /**
     * 清理超过指定非活跃时长的超时会话，并在淘汰前落库。
     */
    public int cleanExpired(Duration timeout) {
        Instant threshold = Instant.now().minus(timeout);

        // 先在锁内挑出过期项的快照，再在锁外做落库。
        // 不直接边遍历边 remove：LinkedHashMap 的迭代器是 fail-fast，
        // 遍历中修改会抛 ConcurrentModificationException；
        // 也不在锁内落库：那是网络 I/O，会把整个会话存储卡住。
        List<Map.Entry<String, ConversationSession>> expired;
        synchronized (cache) {
            expired = cache.entrySet().stream()
                    // 用 !isAfter（即 <=）而不是 isBefore：超时为 0 时 threshold 就等于“此刻”，
                    // 严格小于会把「恰好落在阈值上」的会话漏掉，使清理结果依赖于两次
                    // Instant.now() 是否落在同一个时钟刻度（测试里表现为偶发失败）。
                    .filter(e -> !e.getValue().lastActiveAt().isAfter(threshold))
                    .toList();
        }

        for (Map.Entry<String, ConversationSession> entry : expired) {
            ConversationSession session = entry.getValue();
            if (!session.isClosed()) {
                session.close();
                if (db != null) {
                    ensureTables();
                    persistToDb(session);
                }
            }
            // 用 remove(key, value) 而不是 remove(key)：如果这期间同名会话被重新创建，
            // 不能把新的那个误删。
            synchronized (cache) {
                cache.remove(entry.getKey(), session);
            }
        }
        return expired.size();
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
        List<ConversationSession> snapshot;
        synchronized (cache) {
            snapshot = new ArrayList<>(cache.values());
        }
        for (ConversationSession session : snapshot) {
            if (!session.isClosed()) {
                session.close();
                ensureTables();
                persistToDb(session);
            }
        }
    }

    public int activeCount() {
        synchronized (cache) {
            return (int) cache.values().stream().filter(s -> !s.isClosed()).count();
        }
    }

    public void clearMemory() {
        cache.clear();
    }

    /**
     * 确认会话持久化表可用；缺表时尝试自愈（仅在账号有建表权限时才会成功）。
     *
     * <p><b>这里修的是一个「功能其实一直是死的」的缺陷</b>：早先的实现直接执行
     * 两条 {@code CREATE TABLE} 当兜底，但 {@code text2sql_rw} 按最小权限原则
     * **没有 schema 的 CREATE 权限**，所以兜底永远失败；而建表脚本里又没有这两张表。
     * 结果是「开了持久化，会话历史一条都没进库」，唯一线索是一条容易被忽略的 WARN。
     *
     * <p>现在的顺序是：
     * <ol>
     *   <li>先用 {@code to_regclass} 探测表在不在（这是正常路径，DDL 由
     *       {@code data/schema/06_persistence.sql} 统一执行）；</li>
     *   <li>真的缺表才尝试创建，失败时的日志要能直接指到「去跑哪个脚本」。</li>
     * </ol>
     */
    private synchronized void ensureTables() {
        if (db == null || tablesInitialized) {
            return;
        }
        try {
            Boolean present = db.queryForObject(
                    "SELECT to_regclass('public.conversation_session') IS NOT NULL "
                            + "AND to_regclass('public.conversation_turn') IS NOT NULL",
                    Boolean.class);
            if (Boolean.TRUE.equals(present)) {
                tablesInitialized = true;
                return;
            }

            // 兜底自愈：分两条语句执行，不用分号串成一条。
            // 串成一条时 PostgreSQL 的报错信息会退化成笼统的 "bad SQL grammar"，
            // 把真正的 "permission denied for schema public" 盖掉，排查要多花很久。
            db.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_session (
                        session_id VARCHAR(64) PRIMARY KEY,
                        created_at TIMESTAMP,
                        closed_at TIMESTAMP,
                        turn_count INT,
                        last_question TEXT,
                        status VARCHAR(32)
                    )
                    """);
            db.execute("""
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
                    )
                    """);
            tablesInitialized = true;
            log.info("多轮会话持久化表已由应用创建；建议改为执行 data/schema/06_persistence.sql 统一管理");
        } catch (Exception e) {
            log.warn("多轮会话持久化表不可用，会话只会保留在内存中（关闭后不落库）。"
                    + "请先执行 data/schema/06_persistence.sql 建表并授权。原因：{}", e.getMessage());
        }
    }

    /**
     * 落库。
     *
     * @return 是否真的写成功。调用方据此决定能不能把它移出内存——
     *         写失败时必须留在内存里，否则会话就彻底丢了。
     */
    private boolean persistToDb(ConversationSession session) {
        try {
            var lastTurn = session.lastTurn();
            String lastQ = lastTurn != null ? lastTurn.question() : "";
            String status = session.isClosed() ? "CLOSED" : "ACTIVE";

            // 先算出这个 session_id 在库里已有的最大轮次号，新轮次从它之后接着编号。
            //
            // 早先这里用的是「先 DELETE 再 INSERT」的幂等重写。幂等没问题，
            // 但它有一个真实的数据丢失场景：用户结束会话（已落库）后，
            // 前端又用**同一个 sessionId**发起新一轮对话——getOrCreate 会新建实例，
            // 再次结束时 DELETE 会把上一段已经落库的历史整段删掉，而且只留一条
            // 「落库成功」的日志，谁也不会发现历史少了。
            // 改成追加式编号后，同一 session_id 的多段对话都留在库里的不同轮次上。
            int offset = existingTurnCount(session.sessionId());
            int totalTurns = offset + session.turns().size();

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
                totalTurns,
                lastQ,
                status
            );

            for (ConversationTurn turn : session.turns()) {
                db.update("""
                    INSERT INTO conversation_turn(session_id, turn_index, question, rewritten_question, sql_text, status, result_summary, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    session.sessionId(),
                    turn.turnIndex() + offset,
                    turn.question(),
                    turn.rewrittenQuestion(),
                    turn.sql(),
                    turn.status(),
                    turn.resultSummary(),
                    Timestamp.from(turn.createdAt())
                );
            }
            log.info("会话 {} 成功持久化入库，本次 {} 轮（累计 {} 轮）",
                    session.sessionId(), session.turns().size(), totalTurns);
            return true;
        } catch (Exception e) {
            log.warn("会话 {} 落库失败（不影响主响应）：{}", session.sessionId(), e.getMessage());
            return false;
        }
    }

    /** 查这个 session_id 已经落库的轮次数（用作新轮次的编号偏移）。失败时按 0 处理。 */
    private int existingTurnCount(String sessionId) {
        try {
            Integer max = db.queryForObject(
                    "SELECT COALESCE(MAX(turn_index), 0) FROM conversation_turn WHERE session_id = ?",
                    Integer.class, sessionId);
            return max == null ? 0 : max;
        } catch (Exception e) {
            log.warn("查询会话 {} 历史轮次失败，按 0 处理：{}", sessionId, e.getMessage());
            return 0;
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

        // 1. 内存中活跃或近期的会话优先。
        //    先同步拷一份快照再排序：cache 现在是同步包装的 LinkedHashMap，
        //    直接流式遍历会在并发写入时抛 ConcurrentModificationException。
        List<ConversationSession> snapshot;
        synchronized (cache) {
            snapshot = new ArrayList<>(cache.values());
        }
        snapshot.stream()
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
