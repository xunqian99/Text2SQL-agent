-- ============================================================================
-- 只读账号（阶段 5 的第三层防线）
--
-- 为什么需要它：应用连库用的是 text2sql，那是 docker 镜像按 POSTGRES_USER
-- 创建的**超级用户**。也就是说 AST 校验一旦被绕过，攻击者拿到的是最高权限。
-- 三层防护的意义正在于此：不依赖任何单一层的正确性。
--   1) AST 校验挡住绝大多数畸形/恶意 SQL，但它是个软件组件，可能有洞；
--   2) 只读账号是数据库层面的硬约束，代码写错也删不掉数据；
--   3) 超时与行数上限限制单次查询的资源占用。
--
-- 这一层的边界也要说清楚：它挡不住"读"。只读账号照样能读全库，
-- 防数据外泄要靠应用层的行级/列级授权，那属于多租户范畴，
-- ROADMAP 1.3 节明确不做。
--
-- 幂等：可重复执行，角色已存在时跳过创建。
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'text2sql_ro') THEN
        CREATE ROLE text2sql_ro LOGIN PASSWORD 'text2sql_ro';
    END IF;
END
$$;

ALTER ROLE text2sql_ro NOSUPERUSER NOCREATEDB NOCREATEROLE;

GRANT CONNECT ON DATABASE olist TO text2sql_ro;
GRANT USAGE ON SCHEMA public TO text2sql_ro;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO text2sql_ro;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO text2sql_ro;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO text2sql_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO text2sql_ro;

REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public FROM text2sql_ro;
REVOKE CREATE ON SCHEMA public FROM text2sql_ro;
