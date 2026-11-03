-- Migration: 0237_dataforge_full-sync-wizard.sql
-- Purpose: 持久化整库同步四步向导草稿及 DB Switch / DolphinScheduler DataX 提交边界。
-- Depends on: 0235_governance_qdata-whitelist-policies.sql；0236_modeling_remove-model-versions.sql（若已部署则继续沿用，未部署也不影响本表）。
-- Target: 已有 PostgreSQL 平台库；新库基线需包含本迁移。
-- Repeatable: 是；序列、表、索引和约束均使用存在性保护。
-- Rollback: 删除本表会丢失未提交向导草稿及外部工作流引用，执行前须确认没有 SUBMITTING/UNKNOWN 记录；不自动删除 DolphinScheduler 流程。
-- Security 安全说明：config_json 仅保存业务配置；DolphinScheduler 数据源编号作为外部引用，禁止保存密码、令牌或连接串。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_full_sync_wizard_seq;

CREATE TABLE IF NOT EXISTS dh_dataforge_full_sync_wizard (
    id bigint NOT NULL DEFAULT nextval('dh_dataforge_full_sync_wizard_seq'::regclass),
    current_step integer NOT NULL DEFAULT 1,
    revision bigint NOT NULL DEFAULT 1,
    status varchar(20) NOT NULL DEFAULT 'DRAFT',
    execution_mode varchar(32) NOT NULL DEFAULT 'DBSWITCH',
    data_access_id bigint,
    ds_project_code bigint,
    ds_worker_group varchar(128),
    ds_tenant_code varchar(128),
    ds_source_datasource_code bigint,
    ds_target_datasource_code bigint,
    ds_workflow_code bigint,
    ds_schedule_id integer,
    ds_instance_id integer,
    submit_request_id varchar(64),
    last_error varchar(512),
    config_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_dataforge_full_sync_wizard PRIMARY KEY (id),
    CONSTRAINT ck_dh_full_sync_wizard_step CHECK (current_step BETWEEN 1 AND 4),
    CONSTRAINT ck_dh_full_sync_wizard_revision CHECK (revision > 0),
    CONSTRAINT ck_dh_full_sync_wizard_status CHECK (status IN ('DRAFT','SUBMITTING','SUBMITTED','FAILED','UNKNOWN')),
    CONSTRAINT ck_dh_full_sync_wizard_mode CHECK (execution_mode IN ('DBSWITCH','DOLPHIN_DATAX')),
    CONSTRAINT ck_dh_full_sync_wizard_config CHECK (jsonb_typeof(config_json) = 'object')
);

ALTER SEQUENCE dh_dataforge_full_sync_wizard_seq OWNED BY dh_dataforge_full_sync_wizard.id;
CREATE INDEX IF NOT EXISTS idx_dh_full_sync_wizard_status
    ON dh_dataforge_full_sync_wizard(status, update_time DESC) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_dh_full_sync_wizard_data_access
    ON dh_dataforge_full_sync_wizard(data_access_id) WHERE deleted=0 AND data_access_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_dh_full_sync_wizard_ds_workflow
    ON dh_dataforge_full_sync_wizard(ds_project_code, ds_workflow_code)
    WHERE deleted=0 AND ds_workflow_code IS NOT NULL;

COMMENT ON TABLE dh_dataforge_full_sync_wizard IS '整库同步四步向导草稿及外部执行提交状态';
COMMENT ON COLUMN dh_dataforge_full_sync_wizard.config_json IS '四步业务配置 JSON；禁止保存密码、令牌和连接串';
COMMENT ON COLUMN dh_dataforge_full_sync_wizard.ds_source_datasource_code IS 'DolphinScheduler 数据源中心来源编号，仅外部引用';
COMMENT ON COLUMN dh_dataforge_full_sync_wizard.ds_target_datasource_code IS 'DolphinScheduler 数据源中心目标编号，仅外部引用';
COMMENT ON COLUMN dh_dataforge_full_sync_wizard.status IS 'DRAFT/SUBMITTING/SUBMITTED/FAILED/UNKNOWN；UNKNOWN 需人工核对外部调度器';

COMMIT;

