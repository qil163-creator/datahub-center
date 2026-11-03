-- Migration: 0257 作业真实集成子记录状态契约
-- Purpose: 扩展旧集成记录检查约束，允许受管 DS 等待、未知、停止状态及经核验的作业触发来源。
-- Depends on: 0252_dataforge_integration-ds-runtime.sql；0256_dataforge_job-native-children.sql。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；事务内重建同名检查约束，不修改既有业务记录。
-- Rollback: 先确认不存在新状态及 JOB 触发记录；恢复旧约束会拒绝这些真实运行，不自动重写审计数据。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='120s';
ALTER TABLE dh_dataforge_integration_record DROP CONSTRAINT IF EXISTS dh_dataforge_integration_record_status_check;
ALTER TABLE dh_dataforge_integration_record ADD CONSTRAINT dh_dataforge_integration_record_status_check
 CHECK (status IN ('WAITING','RUNNING','SUCCESS','FAILURE','IDLE','STOPPED','UNKNOWN'));
ALTER TABLE dh_dataforge_integration_record DROP CONSTRAINT IF EXISTS dh_dataforge_integration_record_trigger_type_check;
ALTER TABLE dh_dataforge_integration_record ADD CONSTRAINT dh_dataforge_integration_record_trigger_type_check
 CHECK (trigger_type IN ('IMPORTED','MANUAL','SCHEDULE','JOB'));
COMMENT ON COLUMN dh_dataforge_integration_record.status IS '执行状态；等待、运行、成功、失败、停止、未知及兼容旧停止标记 IDLE';
COMMENT ON COLUMN dh_dataforge_integration_record.trigger_type IS '触发来源；JOB 仅用于双向核验父子身份后的真实作业运行';
COMMIT;
