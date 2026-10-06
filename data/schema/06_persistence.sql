-- ============================================================================
-- 持久化表与专用写账号（阶段 6）
--
-- 为什么不是让应用直接用一个「读写账号」：
--   阶段 5 把业务账号改成了 text2sql_ro（只读），目的是让「AST 校验被绕过」
--   这个最坏情况的上界止步于「读到不该读的数据」，而不是「删掉业务数据」。
--   到了阶段 6 要落库写记录，这两件事就撞上了：同一个账号既不该也不能写。
--
--   结论是**按用途拆账号**，而不是重新给业务账号开写权限：
--     · text2sql_ro   业务查询。只有 SELECT，没有任何写权限。
--     · text2sql_rw   持久化。只对下面两张表有权限，业务表一行都碰不到。
--   这样「删业务数据」的路径依然不存在，而观测和缓存能落库。
--
-- 权限细节也是有意的：
--   · semantic_cache 需要读+写（查缓存、回填缓存）；
--   · llm_call_log 只给 INSERT——日志是追加型事实，不该被后来的代码修改或删除。
--     连 UPDATE/DELETE 都不给，是因为「审计记录可被改写」等于没有审计。
--
-- 幂等：可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS semantic_cache (
    cache_key   TEXT PRIMARY KEY,
    sql_text    TEXT        NOT NULL,
    model       TEXT,
    hit_count   INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 记录每次模型调用。字段与 reports/llm-calls/*.jsonl 保持一致，
-- 两个数据源可以互相校验。
CREATE TABLE IF NOT EXISTS llm_call_log (
    id                 BIGSERIAL PRIMARY KEY,
    called_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    model              TEXT        NOT NULL,
    status             TEXT        NOT NULL,
    prompt_tokens      INTEGER     NOT NULL,
    completion_tokens  INTEGER     NOT NULL,
    total_tokens       INTEGER     NOT NULL,
    prompt_chars       INTEGER     NOT NULL,
    latency_ms         BIGINT      NOT NULL,
    cost_yuan          NUMERIC(12, 6) NOT NULL DEFAULT 0
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'text2sql_rw') THEN
        CREATE ROLE text2sql_rw LOGIN PASSWORD 'text2sql_rw';
    END IF;
END
$$;

ALTER ROLE text2sql_rw NOSUPERUSER NOCREATEDB NOCREATEROLE;

GRANT CONNECT ON DATABASE olist TO text2sql_rw;
GRANT USAGE ON SCHEMA public TO text2sql_rw;

-- 缓存表：读 + 写
GRANT SELECT, INSERT, UPDATE, DELETE ON semantic_cache TO text2sql_rw;

-- 日志表：INSERT + SELECT，但不给 UPDATE / DELETE。
--
-- 「追加型事实」的完整性靠的是**没有修改和删除权限**，而不是不给读。
-- 不给 SELECT 保护不了任何东西（这个账号本来就能查业务数据），
-- 反而让应用永远读不到自己的日志，还会让 INSERT ... RETURNING 直接报权限错误
-- ——那个报错信息是「permission denied for table」，很容易被误判成没授权。
GRANT INSERT, SELECT ON llm_call_log TO text2sql_rw;
GRANT USAGE ON SEQUENCE llm_call_log_id_seq TO text2sql_rw;
