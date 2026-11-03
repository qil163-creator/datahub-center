-- Migration: 0224 资产质量任务内评测定义持久化。
-- Purpose: 持久保存用户完整规则配置，允许与治理质量引擎同步失败后按原任务编号核对和重试。
-- Depends on: 0223_asset_registration-sources.sql；dh_asset_quality_task 已包含质量引擎关联和远端错误列。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的完整基线。
-- Repeatable: 是；仅新增可空列，不改写已有旧规则或任务配置。
-- Rollback: 事务失败完整回滚；提交后删除此列会丢失任务内配置，回滚前必须备份，不能自动破坏性回滚。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
ALTER TABLE dh_asset_quality_task ADD COLUMN IF NOT EXISTS evaluation_json text;
COMMENT ON COLUMN dh_asset_quality_task.evaluation_json IS '任务内完整评测配置；旧规则任务为空，远端同步失败不丢弃此定义';
ALTER TABLE dh_asset_quality_task ADD COLUMN IF NOT EXISTS pending_run_key varchar(64);
COMMENT ON COLUMN dh_asset_quality_task.pending_run_key IS '尚未收到回执的稳定运行键，复用治理幂等执行接口核对同一次运行，成功取得实例编号后清空';
COMMIT;
