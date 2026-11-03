-- Migration: 0252 数据集成 DS 原生执行控制面
-- Purpose: 保存受管 DS 工作流与真实实例绑定，允许集成任务原生周期调度
-- Depends on: 0250_dataforge_remove-access-overview-menu.sql; 已存在数据集成任务和记录表
-- Target: 已有 PostgreSQL 数据中台库；新库基线已同步
-- Repeatable: yes; 加法字段和条件索引可重复执行，约束按固定最终定义重建
-- Rollback: 先下线 DS 工作流及调度，再移除新增绑定列与索引；不删除真实 DS 实例，历史实例需管理员保留

BEGIN;
ALTER TABLE dh_dataforge_integration_task
 ADD COLUMN IF NOT EXISTS ds_project_code bigint,
 ADD COLUMN IF NOT EXISTS ds_definition_code bigint,
 ADD COLUMN IF NOT EXISTS ds_schedule_id integer,
 ADD COLUMN IF NOT EXISTS ds_binding_hash varchar(64);
COMMENT ON COLUMN dh_dataforge_integration_task.ds_project_code IS '受管 DolphinScheduler 项目编码';
COMMENT ON COLUMN dh_dataforge_integration_task.ds_definition_code IS '正式保存流程生成的 DS 工作流编码';
COMMENT ON COLUMN dh_dataforge_integration_task.ds_schedule_id IS 'DS 周期调度编号';
COMMENT ON COLUMN dh_dataforge_integration_task.ds_binding_hash IS '正式发布时原生参数与受管运行环境摘要';
ALTER TABLE dh_dataforge_integration_record
 ADD COLUMN IF NOT EXISTS ds_project_code bigint,
 ADD COLUMN IF NOT EXISTS ds_instance_id integer;
COMMENT ON COLUMN dh_dataforge_integration_record.ds_project_code IS '真实执行的 DS 项目编码，迁入记录为空';
COMMENT ON COLUMN dh_dataforge_integration_record.ds_instance_id IS '真实执行的 DS 工作流实例编号，迁入记录为空';
CREATE UNIQUE INDEX IF NOT EXISTS uk_integration_record_ds_instance
 ON dh_dataforge_integration_record(ds_project_code,ds_instance_id) WHERE ds_instance_id IS NOT NULL AND deleted=0;
DO $constraints$
DECLARE item record;
BEGIN
 FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='dh_dataforge_integration_task'::regclass
  AND contype='c' AND position('pipeline_id' IN pg_get_constraintdef(oid))>0
  AND position('schedule_status' IN pg_get_constraintdef(oid))>0 LOOP
  EXECUTE format('ALTER TABLE dh_dataforge_integration_task DROP CONSTRAINT %I',item.conname);
 END LOOP;
 ALTER TABLE dh_dataforge_integration_task ADD CONSTRAINT ck_integration_ds_schedule
  CHECK(schedule_status=0 OR (status=1 AND (pipeline_id IS NOT NULL OR ds_definition_code IS NOT NULL)));
END $constraints$;
COMMIT;
