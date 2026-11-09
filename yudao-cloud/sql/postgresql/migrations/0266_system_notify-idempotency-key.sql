-- Migration: 0266 站内信支持调用方幂等键。
-- Purpose: system_notify_message 新增 idempotency_key 及部分唯一索引；同一键只创建一条站内信，
--          调用方在结果未知时可用同一键安全重试（重复调用返回已有消息编号）。
-- Depends on: 平台库已升级至 0265；system_notify_message 表存在。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；新增列与索引均带 IF NOT EXISTS。
-- Rollback: 加法迁移。回滚应用版本时可保留；如需删除，先停用依赖幂等重试的调用方（数据治理工单通知），
--           再 DROP INDEX uk_system_notify_message_idempotency_key 与该列。
-- 说明：建索引期间短时持有 system_notify_message 的写锁；平台尚未正式使用，表数据量小。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE system_notify_message ADD COLUMN IF NOT EXISTS idempotency_key varchar(128);

CREATE UNIQUE INDEX IF NOT EXISTS uk_system_notify_message_idempotency_key
    ON system_notify_message (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND deleted = 0;

COMMENT ON COLUMN system_notify_message.idempotency_key IS '调用方幂等键；非空时唯一，同一键只创建一条站内信，重复调用返回已有消息编号';

COMMIT;
