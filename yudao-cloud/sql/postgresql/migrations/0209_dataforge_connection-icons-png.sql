-- Migration: 0209 连接图标统一为本地 PNG 并补齐占位图标。
-- Purpose: 为 18 种仍使用通用占位图标的连接类型补齐对应的 PNG 图标，并把内置 SVG 连接图标统一更换为 PNG 格式。
-- Depends on: 0208_modeling_logic-table-qdata.sql 已执行；管理端已发布 public/connection-icons 下的 PNG 静态资源。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；仅按产品编码更新空图标或仍指向内置 /connection-icons/ 目录的图标路径，保留类型编号、连接引用和用户上传的自定义图标。
-- Rollback: 单个事务失败时整笔回滚；提交后如需恢复原图标，按执行前快照恢复对应 icon_path，其他连接配置、目录编号和引用不变。
-- 事务边界：只更新目录图标列，短事务；不连接、不修改任何外部数据源。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- 内置图标按产品编码归位，供连接器列表、连接向导和连接详情共用同一份本地 PNG 资源。
UPDATE dh_dataforge_database_type AS t
SET icon_path = s.icon_path,
    updater = 'migration:0209',
    update_time = CURRENT_TIMESTAMP
FROM (VALUES
    ('SQLSERVER2000', '/connection-icons/sqlserver2000.png'),
    ('DB2',           '/connection-icons/db2.png'),
    ('SQLITE3',       '/connection-icons/sqlite3.png'),
    ('SYBASE',        '/connection-icons/sybase.png'),
    ('TIDB',          '/connection-icons/tidb.png'),
    ('MONGODB',       '/connection-icons/mongodb.png'),
    ('ELASTICSEARCH', '/connection-icons/elasticsearch.png'),
    ('TDENGINE',      '/connection-icons/tdengine.png'),
    ('INFLUXDB3',     '/connection-icons/influxdb3.png'),
    ('FTP',           '/connection-icons/ftp.png'),
    ('HDFS',          '/connection-icons/hdfs.png'),
    ('GBASE8C',       '/connection-icons/gbase8c.png'),
    ('GAUSSDB',       '/connection-icons/gaussdb.png'),
    ('PHOENIX',       '/connection-icons/phoenix.png'),
    ('SAPHANA',       '/connection-icons/saphana.png'),
    ('PRESTO',        '/connection-icons/presto.png'),
    ('ODPS',          '/connection-icons/odps.png'),
    ('OSS',           '/connection-icons/oss.png'),
    ('MINIO',         '/connection-icons/minio.png'),
    ('REDIS',         '/connection-icons/redis.png'),
    ('KAFKA',         '/connection-icons/kafka.png'),
    ('RABBITMQ',      '/connection-icons/rabbitmq.png'),
    ('ROCKETMQ',      '/connection-icons/rocketmq.png'),
    ('NEO4J',         '/connection-icons/neo4j.png'),
    ('MILVUS',        '/connection-icons/milvus.png'),
    ('INFLUXDB2',     '/connection-icons/influxdb2.png'),
    ('SFTP',          '/connection-icons/sftp.png')
) AS s(product_code, icon_path)
WHERE t.deleted = 0
  AND CASE regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g')
      WHEN 'SQLITE' THEN 'SQLITE3'
      WHEN 'INFLUXDB' THEN 'INFLUXDB3'
      WHEN 'ES' THEN 'ELASTICSEARCH'
      ELSE regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g')
      END = s.product_code
  AND (
      coalesce(btrim(t.icon_path), '') = ''
      OR t.icon_path LIKE '/connection-icons/%'
  )
  AND t.icon_path IS DISTINCT FROM s.icon_path;

COMMIT;
