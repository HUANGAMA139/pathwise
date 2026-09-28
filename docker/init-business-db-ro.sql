-- ============================================================================
-- smart-data-qa · 业务库「只读账号」（迭代 21）
-- ----------------------------------------------------------------------------
-- 这是 SQL 安全边界里【最硬的一层】。
--
-- 为什么需要它：应用层护栏（SqlGuard）拦得住「模型写错」和「手滑」，
--   但拦不住铁了心要绕的人 —— dollar-quoting、E'' 转义、还有它没想到的新写法。
--   而一个只有 SELECT 权限的账号，**即使应用层完全被绕过，写操作也执行不了**。
--   纵深防御的意思是「假设每一层都会被绕过」，而不是「我这一层写得够好」。
--
-- 怎么用：执行完这个脚本后，把应用改成用这个账号连库（一行配置）：
--     sdaq.business-db.username: sdaq_readonly
--     sdaq.business-db.password: sdaq_ro_pwd
--   应用侧只做 SELECT（表结构读 information_schema + 跑模型生成的查询），所以换了账号照样跑。
--
-- 【注意】这个脚本不建表、不改任何表结构，所以它不会影响 迭代 18 的基线。
--   （只有「扩表」和「改表注释」那类动 schema 的操作才会毁基线。）
--
-- 幂等：可以重复执行。挂载为 /docker-entrypoint-initdb.d/30-readonly-role.sql
-- ============================================================================

-- 建角色（PostgreSQL 没有 CREATE ROLE IF NOT EXISTS，用 DO 块）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdaq_readonly') THEN
        CREATE ROLE sdaq_readonly LOGIN PASSWORD 'sdaq_ro_pwd';
        RAISE NOTICE '已创建只读角色 sdaq_readonly';
    ELSE
        RAISE NOTICE '只读角色 sdaq_readonly 已存在，跳过创建';
    END IF;
END
$$;

-- 允许连接 + 允许看到 public schema 里的对象
GRANT CONNECT ON DATABASE business_db TO sdaq_readonly;
GRANT USAGE ON SCHEMA public TO sdaq_readonly;

-- 只给 SELECT
GRANT SELECT ON ALL TABLES IN SCHEMA public TO sdaq_readonly;
-- 以后新建的表也自动给 SELECT
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO sdaq_readonly;

-- 明确把写权限收掉（就算之前手滑给过，这里也清一遍；没给过则无副作用）
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public FROM sdaq_readonly;
-- 序列/函数也一并收掉
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM sdaq_readonly;
REVOKE CREATE ON SCHEMA public FROM sdaq_readonly;

-- 自检：这个账号对每张表有哪些权限（应当全是 SELECT）
SELECT table_name, string_agg(DISTINCT privilege_type, ',' ORDER BY privilege_type) AS privs
FROM information_schema.role_table_grants
WHERE grantee = 'sdaq_readonly'
GROUP BY table_name
ORDER BY table_name;
