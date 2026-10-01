-- 导出完整 schema（表、列、类型、注释、主外键），供编写评估集时参考
\pset pager off

\echo '### TABLES'
SELECT t.table_name
FROM information_schema.tables t
WHERE t.table_schema='public' AND t.table_type='BASE TABLE'
ORDER BY 1;

\echo '### COLUMNS'
SELECT c.table_name || '.' || c.column_name || ' : ' || c.data_type ||
       CASE WHEN c.is_nullable='NO' THEN ' NOT NULL' ELSE '' END ||
       COALESCE('  -- ' || pg_catalog.col_description(
           (SELECT oid FROM pg_class WHERE relname=c.table_name LIMIT 1),
           c.ordinal_position), '') AS col
FROM information_schema.columns c
JOIN information_schema.tables t
  ON t.table_name=c.table_name AND t.table_schema=c.table_schema
WHERE c.table_schema='public' AND t.table_type='BASE TABLE'
ORDER BY c.table_name, c.ordinal_position;

\echo '### FOREIGN_KEYS'
SELECT tc.table_name || '.' || kcu.column_name || ' -> ' ||
       ccu.table_name || '.' || ccu.column_name AS fk
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage ccu
  ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema
WHERE tc.constraint_type='FOREIGN KEY' AND tc.table_schema='public'
ORDER BY 1;
