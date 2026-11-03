-- Migration: 0258 作业级持久周期与并发策略队列
-- Purpose: 持久化周期代次、执行身份、冻结运行与串行占位，支持跨实例恢复与无重复业务触发。
-- Depends on: 0256_dataforge_job-native-children.sql；0257_dataforge_job-child-record-states.sql。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；迁移时停止旧版本作业写入口，新库基线同步包含本迁移。
-- Repeatable: 是；仅加法字段、索引及已停旧写入口的过期控制面占位修复，不修改运行审计或示例。
-- Rollback: 先关闭所有平台周期并确认队列及真实运行结束；移除字段会丢失恢复链，不能自动回退到旧 DS 直接业务周期。
BEGIN;
SET LOCAL search_path TO "datahub-center",pg_catalog;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='120s';
ALTER TABLE dh_dataforge_job
 ADD COLUMN IF NOT EXISTS schedule_generation bigint NOT NULL DEFAULT 0,
 ADD COLUMN IF NOT EXISTS schedule_user_id bigint,
 ADD COLUMN IF NOT EXISTS next_run_time timestamp,
 ADD COLUMN IF NOT EXISTS schedule_snapshot_json jsonb;
COMMENT ON COLUMN dh_dataforge_job.schedule_generation IS '周期配置代次；槽位唯一身份的一部分';
COMMENT ON COLUMN dh_dataforge_job.schedule_user_id IS '启用周期的实际登录执行人，不使用负责人替代';
COMMENT ON COLUMN dh_dataforge_job.next_run_time IS '上海时区下一个尚未持久受理的 Quartz 槽位';
COMMENT ON COLUMN dh_dataforge_job.schedule_snapshot_json IS '周期冻结的作业图、子版本与执行目标引用，不含凭据';
ALTER TABLE dh_dataforge_job_record
 ADD COLUMN IF NOT EXISTS run_snapshot_json jsonb,
 ADD COLUMN IF NOT EXISTS queue_state varchar(32),
 ADD COLUMN IF NOT EXISTS queue_reason varchar(1000),
 ADD COLUMN IF NOT EXISTS queued_time timestamp,
 ADD COLUMN IF NOT EXISTS scheduled_time timestamp,
 ADD COLUMN IF NOT EXISTS schedule_generation bigint,
 ADD COLUMN IF NOT EXISTS serial_slot boolean NOT NULL DEFAULT false,
 ADD COLUMN IF NOT EXISTS stop_requested boolean NOT NULL DEFAULT false,
 ADD COLUMN IF NOT EXISTS lease_owner varchar(64),
 ADD COLUMN IF NOT EXISTS lease_until timestamp;
COMMENT ON COLUMN dh_dataforge_job_record.run_snapshot_json IS '入队时冻结的实际执行人、作业图、子版本及目标引用';
COMMENT ON COLUMN dh_dataforge_job_record.queue_state IS '队列状态；业务终态仍由真实父子和原生门禁共同确定';
COMMENT ON COLUMN dh_dataforge_job_record.queue_reason IS '真实受理、等待、拒绝、抢占或恢复原因，不伪造 Worker 输出';
COMMENT ON COLUMN dh_dataforge_job_record.queued_time IS '平台持久受理时间';
COMMENT ON COLUMN dh_dataforge_job_record.scheduled_time IS '实际业务 Cron 槽位，人工运行为空';
COMMENT ON COLUMN dh_dataforge_job_record.schedule_generation IS '该周期槽所属配置代次';
COMMENT ON COLUMN dh_dataforge_job_record.serial_slot IS '作业级串行占位；仅真实终态或可证明未触发时释放';
COMMENT ON COLUMN dh_dataforge_job_record.stop_requested IS '持久停止意图；准备者必须在触发前再次核验';
COMMENT ON COLUMN dh_dataforge_job_record.lease_owner IS '本次准备派发的领取令牌';
COMMENT ON COLUMN dh_dataforge_job_record.lease_until IS '派发租约截止；失租约不证明远端已停止';
CREATE UNIQUE INDEX IF NOT EXISTS uk_job_record_serial_slot ON dh_dataforge_job_record(job_id) WHERE deleted=0 AND serial_slot;
CREATE UNIQUE INDEX IF NOT EXISTS uk_job_record_cron_slot ON dh_dataforge_job_record(job_id,schedule_generation,scheduled_time) WHERE deleted=0 AND scheduled_time IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_job_record_durable_queue ON dh_dataforge_job_record(queue_state,queued_time,id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_job_due_schedule ON dh_dataforge_job(next_run_time,id) WHERE deleted=0 AND schedule_status=1 AND schedule_snapshot_json IS NOT NULL;
-- 旧版 BUSY 仅为请求级控制面锁；旧写入口停用后清除它，新版使用事务行锁与持久运行租约。
UPDATE dh_dataforge_job SET operation_state='IDLE' WHERE operation_state='BUSY';
COMMIT;
