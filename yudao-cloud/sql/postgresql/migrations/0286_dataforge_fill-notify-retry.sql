-- Migration: 0286 在线填报通知失败重试
-- Purpose: dh_dataforge_fill_notify_record 新增两列，支撑站内信发送失败后的自动重试：
--          template_params 保存发送时的模板参数快照（JSON），重试时按原内容重发；
--          retry_count 记录已重试次数，超过上限后不再重试。
--          历史记录的 template_params 为空，不会被重试；不回填、不修改任何已有数据行。
-- Depends on: 0285_dataforge_fill-merge-return-rework.sql（编号前驱）；dh_dataforge_fill_notify_record 已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；列使用 ADD COLUMN IF NOT EXISTS，注释可重复执行。单事务执行；新增列带常量默认值或可空，
--             不重写表，只在加列瞬间持有表锁（锁等待上限 10 秒，超时即失败，可稍后重试）。
-- Rollback: 应用回退到不含通知重试的版本后，可执行
--           ALTER TABLE "datahub-center".dh_dataforge_fill_notify_record DROP COLUMN IF EXISTS template_params,
--           DROP COLUMN IF EXISTS retry_count; 删除列会丢失参数快照与重试次数，不影响已发送的站内信。
--           旧版本应用不读写这两列，保留它们同样兼容。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_dataforge_fill_notify_record
    ADD COLUMN IF NOT EXISTS template_params text,
    ADD COLUMN IF NOT EXISTS retry_count int2 NOT NULL DEFAULT 0;

COMMENT ON COLUMN dh_dataforge_fill_notify_record.template_params IS '模板参数快照（JSON），发送失败后按原内容重试；历史记录为空';
COMMENT ON COLUMN dh_dataforge_fill_notify_record.retry_count IS '已重试次数';

COMMIT;
