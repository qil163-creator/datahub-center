-- Migration: 0259 补充多协议连接器地址模板
-- Purpose: 为缺少地址模板的内置连接器提供符合现有连接配置的格式示例。
-- Depends on: 0186_dataforge_remote-file-management.sql 和 0197_dataforge_connect-only-connectors.sql 已执行；平台库已升级至 0258。
-- Target: 已有 PostgreSQL 平台库；新库基线包含本迁移。
-- Repeatable: 是；仅填充有效记录中的空模板，保留已有自定义值。
-- Rollback: 事务失败自动回滚；提交后如需恢复空值，应先核对记录和模板，再按记录撤销，避免覆盖后续修改。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

UPDATE dh_dataforge_database_type AS t
SET url_templates = s.template,
    updater = 'migration:0259',
    update_time = CURRENT_TIMESTAMP
FROM (VALUES
    ('FTP', 'ftp://${host}:${port}'),
    ('HDFS', 'http://${host}:${port}/webhdfs/v1'),
    ('SFTP', 'sftp://${host}:${port}'),
    ('ODPS', 'https://${host}:${port}'),
    ('OSS', 'https://${host}:${port}'),
    ('MINIO', 'http://${host}:${port}'),
    ('REDIS', 'redis://${host}:${port}/${dbName}'),
    ('KAFKA', '${host}:${port}'),
    ('RABBITMQ', 'amqp://${host}:${port}'),
    ('ROCKETMQ', '${host}:${port}'),
    ('NEO4J', 'bolt://${host}:${port}'),
    ('MILVUS', 'http://${host}:${port}'),
    ('INFLUXDB2', 'http://${host}:${port}')
) AS s(code, template)
WHERE t.deleted = 0
  AND regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g') = s.code
  AND coalesce(btrim(t.url_templates), '') = '';
COMMIT;
