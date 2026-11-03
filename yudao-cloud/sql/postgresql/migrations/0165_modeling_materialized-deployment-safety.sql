-- Migration: 0165 模型物化部署版本冻结、目标互斥与未知结果恢复。
-- Purpose: 增加已发布版本关联和持久部署尝试，跨租户以权威物理目标互斥；历史运行中状态转为未知，禁止推断部署成功或直接重建。
-- Depends on: 0164_dataforge_dev-dolphinscheduler-schedules.sql 已执行或核验达到对应状态；0140 的物化记录及日志表、逻辑模型版本表存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；加列、建表、索引按存在性保护；历史运行中状态仅首次转换，已有尝试和冻结证据不覆盖。
-- Rollback: 失败整笔回滚；提交后回退应用前停止部署节点并核对所有 RUNNING/UNKNOWN 外部结果，保留尝试与证据列。不能删除未知占用后直接重跑破坏性部署。
-- 风险：短时结构锁与索引创建；升级前停止旧物化发布节点，备份物化记录。旧记录未记录冻结版本和外部证据，不按当前版本猜测回填。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_modeling_model_materialized ADD COLUMN IF NOT EXISTS version_id bigint;
ALTER TABLE dh_modeling_model_materialized ADD COLUMN IF NOT EXISTS attempt_id bigint;
COMMENT ON COLUMN dh_modeling_model_materialized.version_id IS '本次物化使用的已发布逻辑模型冻结版本；历史未知不回填';
COMMENT ON COLUMN dh_modeling_model_materialized.attempt_id IS '当前持久部署尝试编号；终态按尝试编号更新防止迟到结果覆盖';
COMMENT ON COLUMN dh_modeling_model_materialized.status IS '状态：RUNNING执行中、SUCCESS已确认成功、FAIL已确认失败、UNKNOWN外部结果待对账';
ALTER TABLE dh_modeling_model_materialized_log ADD COLUMN IF NOT EXISTS audit_evidence_json jsonb;
COMMENT ON COLUMN dh_modeling_model_materialized_log.audit_evidence_json IS '历史未知人工核验的操作者、租户、物理目标观察、理由和外部证据；不包含连接凭据';
UPDATE dh_modeling_model_materialized SET status = 'UNKNOWN',
    message = '旧部署缺少冻结证据，须先人工核验外部结果，禁止直接重建',
    updater = 'migration:0165', update_time = CURRENT_TIMESTAMP
WHERE status = 'RUNNING' AND attempt_id IS NULL;

CREATE SEQUENCE IF NOT EXISTS dh_modeling_deployment_attempt_seq;
CREATE TABLE IF NOT EXISTS dh_modeling_deployment_attempt (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_deployment_attempt_seq'::regclass),
    materialized_id bigint NOT NULL,
    version_id bigint NOT NULL,
    datasource_id bigint NOT NULL,
    target_key varchar(64) NOT NULL,
    schema_name varchar(128) NOT NULL,
    table_name varchar(128) NOT NULL,
    configuration_revision varchar(100) NOT NULL,
    publish_mode varchar(32) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'RUNNING',
    phase varchar(24) NOT NULL DEFAULT 'PREPARED',
    lease_owner varchar(64) NOT NULL,
    lease_until timestamp NOT NULL,
    before_observation_json jsonb NOT NULL,
    ddl_sql text NOT NULL,
    fields_json jsonb NOT NULL,
    model_snapshot_json jsonb NOT NULL,
    message varchar(2000),
    finished_time timestamp,
    creator varchar(64) DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    tenant_id bigint NOT NULL,
    CONSTRAINT ck_modeling_deployment_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAIL', 'UNKNOWN')),
    CONSTRAINT ck_modeling_deployment_phase CHECK (phase IN ('PREPARED', 'EXECUTING', 'FINISHED')),
    CONSTRAINT ck_modeling_deployment_deleted CHECK (deleted IN (0, 1))
);
ALTER SEQUENCE dh_modeling_deployment_attempt_seq OWNED BY dh_modeling_deployment_attempt.id;
DO $contract$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (VALUES
            ('id', 'bigint', true), ('materialized_id', 'bigint', true), ('version_id', 'bigint', true),
            ('datasource_id', 'bigint', true), ('target_key', 'character varying(64)', true),
            ('schema_name', 'character varying(128)', true), ('table_name', 'character varying(128)', true),
            ('configuration_revision', 'character varying(100)', true), ('publish_mode', 'character varying(32)', true),
            ('status', 'character varying(32)', true), ('phase', 'character varying(24)', true),
            ('lease_owner', 'character varying(64)', true), ('lease_until', 'timestamp without time zone', true),
            ('before_observation_json', 'jsonb', true), ('ddl_sql', 'text', true), ('fields_json', 'jsonb', true),
            ('model_snapshot_json', 'jsonb', true), ('tenant_id', 'bigint', true)
        ) expected(name, type_name, required)
        LEFT JOIN pg_attribute actual ON actual.attrelid = 'dh_modeling_deployment_attempt'::regclass
            AND actual.attname = expected.name AND NOT actual.attisdropped
        WHERE actual.attnum IS NULL OR format_type(actual.atttypid, actual.atttypmod) <> expected.type_name
            OR actual.attnotnull <> expected.required
    ) THEN RAISE EXCEPTION '0165 部署尝试表不满足冻结证据和围栏契约，禁止接受同名不完整表'; END IF;
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_modeling_model_materialized'::regclass
        AND attname IN ('version_id', 'attempt_id') AND NOT attisdropped AND atttypid <> 'bigint'::regtype)
        OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_modeling_model_materialized_log'::regclass
        AND attname = 'audit_evidence_json' AND NOT attisdropped AND atttypid = 'jsonb'::regtype) THEN
        RAISE EXCEPTION '0165 物化版本、尝试编号或审计证据列类型冲突';
    END IF;
END $contract$;
COMMENT ON TABLE dh_modeling_deployment_attempt IS '数据建模-冻结部署尝试、物理目标互斥与未知结果对账';
COMMENT ON COLUMN dh_modeling_deployment_attempt.id IS '部署尝试编号';
COMMENT ON COLUMN dh_modeling_deployment_attempt.materialized_id IS '物化发布记录编号';
COMMENT ON COLUMN dh_modeling_deployment_attempt.version_id IS '经设计发布的不可变逻辑模型版本编号';
COMMENT ON COLUMN dh_modeling_deployment_attempt.datasource_id IS '平台目标数据源编号';
COMMENT ON COLUMN dh_modeling_deployment_attempt.target_key IS '数据库组件读取的权威物理目标摘要；不含用户、平台编号或连接凭据';
COMMENT ON COLUMN dh_modeling_deployment_attempt.schema_name IS '冻结目标模式名称';
COMMENT ON COLUMN dh_modeling_deployment_attempt.table_name IS '冻结最终物理表名';
COMMENT ON COLUMN dh_modeling_deployment_attempt.configuration_revision IS '冻结数据源配置修订；恢复时必须一致';
COMMENT ON COLUMN dh_modeling_deployment_attempt.publish_mode IS '冻结发布模式：REBUILD重建、INCREMENT增量';
COMMENT ON COLUMN dh_modeling_deployment_attempt.status IS '尝试状态；RUNNING和UNKNOWN保持物理目标占用';
COMMENT ON COLUMN dh_modeling_deployment_attempt.phase IS '阶段：PREPARED未进入DDL、EXECUTING可能已执行、FINISHED已完成';
COMMENT ON COLUMN dh_modeling_deployment_attempt.lease_owner IS '执行或对账的随机围栏令牌';
COMMENT ON COLUMN dh_modeling_deployment_attempt.lease_until IS '租约截止；过期仅允许接管对账，不允许直接执行新DDL';
COMMENT ON COLUMN dh_modeling_deployment_attempt.before_observation_json IS '执行前无密原生结构及关系身份冻结证据';
COMMENT ON COLUMN dh_modeling_deployment_attempt.ddl_sql IS '冻结的完整DDL计划；重建包含同事务DROP与CREATE';
COMMENT ON COLUMN dh_modeling_deployment_attempt.fields_json IS '已发布版本字段快照';
COMMENT ON COLUMN dh_modeling_deployment_attempt.model_snapshot_json IS '已发布版本模型及业务口径快照';
COMMENT ON COLUMN dh_modeling_deployment_attempt.message IS '受控结果或对账摘要，不存连接错误原文';
COMMENT ON COLUMN dh_modeling_deployment_attempt.finished_time IS '已确认终态时间；未知保持空';
COMMENT ON COLUMN dh_modeling_deployment_attempt.creator IS '创建者';
COMMENT ON COLUMN dh_modeling_deployment_attempt.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_deployment_attempt.updater IS '更新者';
COMMENT ON COLUMN dh_modeling_deployment_attempt.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_deployment_attempt.deleted IS '逻辑删除标识；未知尝试不得删除';
COMMENT ON COLUMN dh_modeling_deployment_attempt.tenant_id IS '平台租户编号';
-- 物理目标可能被不同租户或不同数据源编号共享，因此互斥唯一键不包含平台租户编号。
CREATE UNIQUE INDEX IF NOT EXISTS uk_modeling_deployment_active_target
    ON dh_modeling_deployment_attempt(target_key) WHERE deleted = 0 AND status IN ('RUNNING', 'UNKNOWN');
CREATE INDEX IF NOT EXISTS idx_modeling_deployment_materialized
    ON dh_modeling_deployment_attempt(tenant_id, materialized_id, id DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_modeling_deployment_recovery
    ON dh_modeling_deployment_attempt(tenant_id, status, lease_until) WHERE deleted = 0;
COMMIT;
