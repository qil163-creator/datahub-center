-- Migration: 0175 删除数据连接的驱动配置选择字段。
-- Purpose: 删除连接级 driver_profile_id 及其列约束，所有业务连接统一使用 DB Switch 的产品默认驱动。
-- Depends on: 已有平台库完成 0174_dataforge_connection-icons.sql，且 dh_dataforge_database 表已存在。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；字段已不存在时不修改其他结构或数据，删除列会自动删除其所属的检查约束。
-- Rollback: 单事务失败完整回滚；提交后原驱动选择值不可自动恢复，恢复旧字段及选择值须使用执行前备份。
-- 风险与部署：删除列需短暂独占表锁；原先指定的驱动配置将改为产品默认配置。停止旧版服务后执行本脚本，再启动配套新版服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE "datahub-center".dh_dataforge_database
    DROP COLUMN IF EXISTS driver_profile_id;

COMMIT;
