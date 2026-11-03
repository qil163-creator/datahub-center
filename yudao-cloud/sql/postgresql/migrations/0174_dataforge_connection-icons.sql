-- Migration: 0174 补齐连接图标并统一连接术语。
-- Purpose: 为九种新增连接类型回填随管理端发布的本地图标，统一连接图标与连接名称的字段注释。
-- Depends on: 0173_dataforge_connection-type-catalog.sql 已执行；对应管理端包含 public/connection-icons 静态资源。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；只补空白图标路径，按产品编码定位，保留自定义图标、类型编号及现有连接引用。
-- Rollback: 单事务失败完整回滚；提交后如需恢复原空图标，按执行前快照恢复对应字段，其他连接配置不变。
-- 事务边界：目录更新与字段注释在同一事务内执行；配置表会短暂加锁，十秒内未取得锁即失败。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

WITH connection_icon_seed(product_code, icon_path) AS (
    VALUES
        ('SQLSERVER2000', '/connection-icons/sqlserver2000.svg'),
        ('DB2', '/connection-icons/db2.svg'),
        ('SQLITE3', '/connection-icons/sqlite3.svg'),
        ('SYBASE', '/connection-icons/sybase.svg'),
        ('MONGODB', '/connection-icons/mongodb.svg'),
        ('TIDB', '/connection-icons/tidb.svg'),
        ('TDENGINE', '/connection-icons/tdengine.svg'),
        ('INFLUXDB3', '/connection-icons/influxdb3.svg'),
        ('ELASTICSEARCH', '/connection-icons/elasticsearch.svg')
)
UPDATE dh_dataforge_database_type t
SET icon_path = s.icon_path, update_time = CURRENT_TIMESTAMP
FROM connection_icon_seed s
WHERE t.deleted = 0 AND coalesce(btrim(t.icon_path), '') = ''
  AND CASE regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g')
      WHEN 'SQLITE' THEN 'SQLITE3'
      WHEN 'INFLUXDB' THEN 'INFLUXDB3'
      WHEN 'ES' THEN 'ELASTICSEARCH'
      ELSE regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g')
      END = s.product_code;

COMMENT ON COLUMN dh_dataforge_database_type.icon_path IS '连接图标路径';
COMMENT ON COLUMN dh_dataforge_database.name IS '连接名称';
COMMIT;

