-- Migration: 0211 固化模型发布日志的部署关联和展示快照。
-- Purpose: 每次写入发布日志时保存部署尝试编号、模型名称、目标数据源和发布说明，防止重新发布篡改历史展示。
-- Depends on: 0210_modeling_logic-table-menu-icons.sql 已执行；前置状态为已完成冻结部署与单租户升级，存在部署尝试表及物化日志表。
-- Target: 已有 PostgreSQL 平台库的 datahub-center schema；新库由包含本增量的全量基线安装。
-- Repeatable: 是；所有列使用 IF NOT EXISTS，重复执行只重申字段注释，不回填或改写日志。
-- Rollback: 事务失败自动回滚；提交后撤销需先停用依赖新列的应用并备份日志，再删除本迁移新增列，删除会丢失发布快照。
-- 事务边界：加法式短事务，限制锁等待；历史日志保持空值，不通过时间或当前物化状态猜测原始关联。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $preflight$
BEGIN
    IF to_regclass('dh_modeling_model_materialized_log') IS NULL
       OR to_regclass('dh_modeling_deployment_attempt') IS NULL THEN
        RAISE EXCEPTION '发布日志快照迁移缺少冻结部署前置表，请先完成平台基线升级';
    END IF;
END $preflight$;

ALTER TABLE dh_modeling_model_materialized_log
    ADD COLUMN IF NOT EXISTS attempt_id bigint,
    ADD COLUMN IF NOT EXISTS model_name varchar(255),
    ADD COLUMN IF NOT EXISTS model_alias varchar(255),
    ADD COLUMN IF NOT EXISTS datasource_id bigint,
    ADD COLUMN IF NOT EXISTS datasource_name varchar(255),
    ADD COLUMN IF NOT EXISTS datasource_type varchar(64),
    ADD COLUMN IF NOT EXISTS description varchar(500);

COMMENT ON COLUMN dh_modeling_model_materialized_log.attempt_id IS '本条日志对应的持久部署尝试编号；历史未知不回填';
COMMENT ON COLUMN dh_modeling_model_materialized_log.model_name IS '发布时模型英文名称快照';
COMMENT ON COLUMN dh_modeling_model_materialized_log.model_alias IS '发布时模型中文名称快照';
COMMENT ON COLUMN dh_modeling_model_materialized_log.datasource_id IS '发布时目标数据源编号快照';
COMMENT ON COLUMN dh_modeling_model_materialized_log.datasource_name IS '发布时目标数据源名称快照';
COMMENT ON COLUMN dh_modeling_model_materialized_log.datasource_type IS '发布时目标数据库产品编码快照';
COMMENT ON COLUMN dh_modeling_model_materialized_log.description IS '本次发布说明快照';

COMMIT;
