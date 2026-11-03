-- Migration: 0166 移除模型旧记录人工恢复的专用字段。
-- Purpose: 项目尚未投产，仅保留当前冻结部署尝试及 UNKNOWN 自动核验流程，移除旧记录人工登记证据列。
-- Depends on: 0165_modeling_materialized-deployment-safety.sql 已执行，物化记录、日志及部署尝试表存在。
-- Target: 达到 0165 的 PostgreSQL 平台库，schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；删除列使用存在性保护，字段注释可重复执行，不改部署尝试和运行状态。
-- Rollback: 失败整笔回滚；提交后可重新添加 audit_evidence_json jsonb 列，但无法恢复被删除的列数据。本项目未投产，不提供旧记录迁移或人工恢复流程。
-- 风险：删除专用证据列不可逆，ALTER TABLE 获取短时结构锁；执行期间应暂停物化日志写入。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_modeling_model_materialized_log DROP COLUMN IF EXISTS audit_evidence_json;
COMMENT ON COLUMN dh_modeling_model_materialized.version_id IS '本次物化使用的已发布逻辑模型冻结版本编号';
COMMENT ON COLUMN dh_modeling_model_materialized.attempt_id IS '当前持久部署尝试编号；终态按尝试编号更新防止迟到结果覆盖';
COMMIT;
