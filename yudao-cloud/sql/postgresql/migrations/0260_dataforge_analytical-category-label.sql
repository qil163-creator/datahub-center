-- Migration: 0260 统一分析性数据库分类名称
-- Purpose: 将连接分类 ANALYTICAL 的显示名称调整为分析性数据库，保留分类编码。
-- Depends on: 0173_dataforge_connection-type-catalog.sql 已执行；平台库已升级至 0259。
-- Target: 已有 PostgreSQL 平台库；新库基线包含本迁移。
-- Repeatable: 是；仅更新需要调整的有效字典项。
-- Rollback: 按需将对应字典项的显示名称恢复为原值。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

UPDATE system_dict_data
SET label = '分析性数据库', updater = 'migration:0260', update_time = CURRENT_TIMESTAMP
WHERE dict_type = 'category_code' AND value = 'ANALYTICAL' AND deleted = 0
  AND label IS DISTINCT FROM '分析性数据库';
COMMIT;
