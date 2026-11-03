-- Migration: 0256 作业真实父子执行与开发受管编排
-- Purpose: 持久化作业父流程、冻结执行人及逐节点平台记录与真实 DS 身份，支持可核对的混合作业。
-- Depends on: 0255_dataforge_job-management-operations.sql；0252_dataforge_integration-ds-runtime.sql；现有开发任务版本与运行记录表。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；加法字段、序列、表及索引按存在性创建，不修改既有作业或演示数据。
-- Rollback: 确认所有父子运行已结束后再移除新字段与关系表；删除关系会丢失运行核对链，不自动删除远端定义。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='120s';
ALTER TABLE dh_dataforge_job_record
 ADD COLUMN IF NOT EXISTS ds_definition_code bigint,
 ADD COLUMN IF NOT EXISTS execution_user_id bigint,
 ADD COLUMN IF NOT EXISTS submission_stage varchar(32),
 ADD COLUMN IF NOT EXISTS runtime_target_json jsonb;
COMMENT ON COLUMN dh_dataforge_job_record.ds_definition_code IS '本次父工作流定义编号，不与后续运行复用';
COMMENT ON COLUMN dh_dataforge_job_record.execution_user_id IS '服务端冻结的实际执行人，不接受客户端覆盖';
COMMENT ON COLUMN dh_dataforge_job_record.submission_stage IS '准备、触发意图与已确认阶段，未知触发禁止盲目重试';
COMMENT ON COLUMN dh_dataforge_job_record.runtime_target_json IS '本次冻结的受管项目、工作组、外部执行用户与环境引用，不含凭据';
CREATE SEQUENCE IF NOT EXISTS dh_dataforge_job_node_run_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_job_node_run (
 id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_job_node_run_seq'),
 job_record_id bigint NOT NULL REFERENCES dh_dataforge_job_record(id),
 node_id varchar(80) NOT NULL,kind varchar(20) NOT NULL CHECK(kind IN('INTEGRATION','DEVELOPMENT')),
 task_id bigint NOT NULL,task_name varchar(200),task_version bigint,platform_record_id bigint,
 ds_project_code bigint NOT NULL,ds_definition_code bigint,parent_task_code bigint,
 parent_task_instance_id integer,ds_instance_id integer,status varchar(20) NOT NULL DEFAULT 'WAITING',
 creator varchar(64) NOT NULL DEFAULT '',create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '',update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0);
ALTER SEQUENCE dh_dataforge_job_node_run_seq OWNED BY dh_dataforge_job_node_run.id;
COMMENT ON TABLE dh_dataforge_job_node_run IS '作业运行内冻结节点及真实平台、调度器父子实例关联';
COMMENT ON COLUMN dh_dataforge_job_node_run.id IS '记录编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.job_record_id IS '本次作业父记录编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.node_id IS '保存图中的稳定节点编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.kind IS '集成或开发节点类型';
COMMENT ON COLUMN dh_dataforge_job_node_run.task_id IS '平台任务编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.task_name IS '准备运行时冻结的真实任务名称';
COMMENT ON COLUMN dh_dataforge_job_node_run.task_version IS '冻结开发版本或集成修订号';
COMMENT ON COLUMN dh_dataforge_job_node_run.platform_record_id IS '对应模块的真实运行记录编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.ds_project_code IS '冻结受管调度器项目编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.ds_definition_code IS '本次节点实际工作流定义编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.parent_task_code IS '父工作流内稳定任务编码';
COMMENT ON COLUMN dh_dataforge_job_node_run.parent_task_instance_id IS '已验证的父任务实例编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.ds_instance_id IS '已双向核验的子工作流实例编号';
COMMENT ON COLUMN dh_dataforge_job_node_run.status IS '真实执行或等待状态，不从提交响应推断成功';
COMMENT ON COLUMN dh_dataforge_job_node_run.creator IS '创建人';
COMMENT ON COLUMN dh_dataforge_job_node_run.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_job_node_run.updater IS '更新人';
COMMENT ON COLUMN dh_dataforge_job_node_run.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_job_node_run.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX IF NOT EXISTS uk_job_node_run_identity ON dh_dataforge_job_node_run(job_record_id,node_id) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_job_node_parent_code ON dh_dataforge_job_node_run(job_record_id,parent_task_code) WHERE deleted=0 AND parent_task_code IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_job_node_real_child ON dh_dataforge_job_node_run(kind,ds_project_code,ds_instance_id) WHERE deleted=0 AND ds_instance_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_job_node_platform_record ON dh_dataforge_job_node_run(kind,platform_record_id) WHERE deleted=0;
COMMIT;
