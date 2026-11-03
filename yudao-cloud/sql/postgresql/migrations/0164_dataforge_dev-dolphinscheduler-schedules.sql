-- Migration: 0164 数据开发周期触发改由 DolphinScheduler 维护。
-- Purpose: 建立调度绑定、租约和同步状态台账，切换任务调度枚举；停用旧 XXL 周期触发并备份旧 Cron，保留历史版本及运行快照。
-- Depends on: 0163_dataforge_dev-task-direct-save.sql 已执行，或已核验平台达到对应状态；数据开发任务、历史版本和运行记录表存在，已停旧执行和调度节点。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；新表和索引按存在性创建，验证已有表契约；只切换仍标识 XXL_JOB 的任务，重放不关闭新版已登记的调度。
-- Rollback: 失败整笔回滚；提交后回退前先通过新版关闭已登记的 DolphinScheduler 外部调度并核对在途实例，再恢复执行前任务和枚举约束备份。绑定台账及历史记录应保留，不能仅回退数据库列而遗留外部周期触发。
-- 风险：任务枚举修改获取短时排他锁，旧周期任务升级后保持关闭，管理员须核对 Quartz Cron、可信执行身份与引擎档案后重新启用。锁等待超过十秒或单语句超过两分钟则整笔回滚。
-- 不在 SQL 中创建、删除或上线外部调度，不把旧 Spring Cron 直接视为已登记的 Quartz Cron；调度登记、取消、未知结果和触发导入由维护任务对账。
-- 不修改历史版本、运行记录或 JSON 原文，不新增运行、原生执行、保存或调度权限。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

DO $schedule_preflight$
DECLARE
    scheduler_check text;
BEGIN
    IF to_regclass('"datahub-center".dh_dataforge_dev_task') IS NULL
       OR to_regclass('"datahub-center".dh_dataforge_dev_task_version') IS NULL
       OR to_regclass('"datahub-center".dh_dataforge_dev_record') IS NULL THEN
        RAISE EXCEPTION '0164 缺少数据开发任务、历史版本或运行记录前置表';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_dataforge_dev_task'::regclass
        AND attname = 'current_version_id' AND NOT attisdropped AND atttypid = 'bigint'::regtype) THEN
        RAISE EXCEPTION '0164 缺少当前保存版本指针，须先完成 0163';
    END IF;
    SELECT pg_get_expr(conbin, conrelid) INTO scheduler_check FROM pg_constraint
    WHERE conrelid = 'dh_dataforge_dev_task'::regclass AND conname = 'ck_dataforge_dev_scheduler' AND contype = 'c';
    IF scheduler_check IS NULL OR scheduler_check NOT IN (
        '((scheduler)::text = ''XXL_JOB''::text)', '((scheduler)::text = ''DOLPHIN_SCHEDULER''::text)') THEN
        RAISE EXCEPTION '0164 调度枚举约束缺失或含未知定制规则，禁止静默覆盖';
    END IF;
    IF EXISTS (SELECT 1 FROM dh_dataforge_dev_task WHERE scheduler IS NULL
        OR scheduler NOT IN ('XXL_JOB', 'DOLPHIN_SCHEDULER')) THEN
        RAISE EXCEPTION '0164 存在未知任务调度系统，须先核对迁移来源';
    END IF;
END $schedule_preflight$;

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_dev_schedule_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_dev_schedule (
    id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_dev_schedule_seq'::regclass),
    tenant_id bigint NOT NULL,
    task_id bigint NOT NULL,
    version_id bigint NOT NULL,
    generation integer NOT NULL,
    cron_expression varchar(100) NOT NULL,
    ds_project_code bigint NOT NULL,
    worker_group varchar(100) NOT NULL,
    tenant_code varchar(100) NOT NULL,
    timezone_id varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    desired_enabled boolean NOT NULL DEFAULT false,
    synced_enabled boolean NOT NULL DEFAULT false,
    ds_workflow_code bigint,
    ds_schedule_id integer,
    sync_status varchar(24) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    lease_owner varchar(64),
    lease_until timestamp,
    last_error varchar(1000),
    last_ds_instance_id bigint NOT NULL DEFAULT 0,
    poll_end_time timestamp,
    poll_page_no integer NOT NULL DEFAULT 1,
    poll_high_water_id bigint NOT NULL DEFAULT 0,
    last_poll_time timestamp,
    schedule_start_time timestamp NOT NULL,
    creator varchar(64) DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_dataforge_dev_schedule_sync_status CHECK (sync_status IN ('PENDING', 'SYNCED', 'ERROR')),
    CONSTRAINT ck_dataforge_dev_schedule_counters CHECK (generation >= 0 AND attempts >= 0 AND last_ds_instance_id >= 0
        AND poll_page_no >= 1 AND poll_high_water_id >= 0),
    CONSTRAINT ck_dataforge_dev_schedule_deleted CHECK (deleted IN (0, 1))
);
ALTER SEQUENCE dh_dataforge_dev_schedule_seq OWNED BY dh_dataforge_dev_schedule.id;

-- 防止同名遗留表或不完整手工建表被 IF NOT EXISTS 静默接受。
DO $binding_contract$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (VALUES
            ('id', 'bigint', true), ('tenant_id', 'bigint', true), ('task_id', 'bigint', true),
            ('version_id', 'bigint', true), ('generation', 'integer', true), ('cron_expression', 'character varying(100)', true),
            ('ds_project_code', 'bigint', true), ('worker_group', 'character varying(100)', true), ('tenant_code', 'character varying(100)', true),
            ('timezone_id', 'character varying(64)', true), ('desired_enabled', 'boolean', true), ('synced_enabled', 'boolean', true),
            ('ds_workflow_code', 'bigint', false), ('ds_schedule_id', 'integer', false), ('sync_status', 'character varying(24)', true),
            ('attempts', 'integer', true), ('lease_owner', 'character varying(64)', false), ('lease_until', 'timestamp without time zone', false),
            ('last_error', 'character varying(1000)', false), ('last_ds_instance_id', 'bigint', true),
            ('poll_end_time', 'timestamp without time zone', false), ('poll_page_no', 'integer', true),
            ('poll_high_water_id', 'bigint', true), ('last_poll_time', 'timestamp without time zone', false),
            ('schedule_start_time', 'timestamp without time zone', true),
            ('creator', 'character varying(64)', false), ('create_time', 'timestamp without time zone', true),
            ('updater', 'character varying(64)', false), ('update_time', 'timestamp without time zone', true), ('deleted', 'smallint', true)
        ) expected(name, type_name, required)
        LEFT JOIN pg_attribute actual ON actual.attrelid = 'dh_dataforge_dev_schedule'::regclass
            AND actual.attname = expected.name AND NOT actual.attisdropped
        WHERE actual.attnum IS NULL OR format_type(actual.atttypid, actual.atttypmod) <> expected.type_name
            OR actual.attnotnull <> expected.required
    ) THEN
        RAISE EXCEPTION '0164 调度绑定表字段与当前契约不一致，禁止覆盖或截断既有台账';
    END IF;
END $binding_contract$;

COMMENT ON TABLE dh_dataforge_dev_schedule IS '数据开发-DolphinScheduler周期绑定与同步恢复台账';
COMMENT ON COLUMN dh_dataforge_dev_schedule.id IS '调度绑定编号';
COMMENT ON COLUMN dh_dataforge_dev_schedule.tenant_id IS '平台租户编号';
COMMENT ON COLUMN dh_dataforge_dev_schedule.task_id IS '数据开发任务编号';
COMMENT ON COLUMN dh_dataforge_dev_schedule.version_id IS '本代周期触发绑定的不可变保存版本编号';
COMMENT ON COLUMN dh_dataforge_dev_schedule.generation IS '任务修订号对应的调度代次；旧代先取消，当前代再登记';
COMMENT ON COLUMN dh_dataforge_dev_schedule.cron_expression IS '经校验的 DolphinScheduler Quartz Cron 表达式';
COMMENT ON COLUMN dh_dataforge_dev_schedule.ds_project_code IS 'DolphinScheduler调度项目编号';
COMMENT ON COLUMN dh_dataforge_dev_schedule.worker_group IS '调度登记时冻结的执行工作组';
COMMENT ON COLUMN dh_dataforge_dev_schedule.tenant_code IS '调度登记时冻结的引擎租户代码';
COMMENT ON COLUMN dh_dataforge_dev_schedule.timezone_id IS '外部周期触发及实例计划时间使用的时区';
COMMENT ON COLUMN dh_dataforge_dev_schedule.desired_enabled IS '平台期望启用状态；旧代取消也通过此状态对账';
COMMENT ON COLUMN dh_dataforge_dev_schedule.synced_enabled IS '最后已确认的外部周期启用状态';
COMMENT ON COLUMN dh_dataforge_dev_schedule.ds_workflow_code IS '平台托管的无业务副作用触发流程编号；未知创建结果可为空';
COMMENT ON COLUMN dh_dataforge_dev_schedule.ds_schedule_id IS '外部调度登记编号；创建和启停结果由维护任务确认';
COMMENT ON COLUMN dh_dataforge_dev_schedule.sync_status IS '同步状态：PENDING待同步、SYNCED已确认、ERROR需重试';
COMMENT ON COLUMN dh_dataforge_dev_schedule.attempts IS '当前同步累计尝试次数';
COMMENT ON COLUMN dh_dataforge_dev_schedule.lease_owner IS '当前同步或轮询执行者租约令牌';
COMMENT ON COLUMN dh_dataforge_dev_schedule.lease_until IS '租约截止时间；过期后允许其他执行者恢复';
COMMENT ON COLUMN dh_dataforge_dev_schedule.last_error IS '最近受控同步错误摘要；不得存储连接凭据';
COMMENT ON COLUMN dh_dataforge_dev_schedule.last_ds_instance_id IS '最后已导入的外部触发实例编号；运行服务仍以实例编号幂等去重';
COMMENT ON COLUMN dh_dataforge_dev_schedule.poll_end_time IS '本轮分页轮询冻结的截止时间；未完成时从同一时间窗口恢复';
COMMENT ON COLUMN dh_dataforge_dev_schedule.poll_page_no IS '本轮下一页编号，从一开始；失败或未完成时保留恢复位置';
COMMENT ON COLUMN dh_dataforge_dev_schedule.poll_high_water_id IS '本轮已处理的最大实例编号；所有页成功后才推进全局导入游标';
COMMENT ON COLUMN dh_dataforge_dev_schedule.last_poll_time IS '最后成功完成全部分页的轮询截止时间';
COMMENT ON COLUMN dh_dataforge_dev_schedule.schedule_start_time IS '本代调度允许接收的计划触发起点';
COMMENT ON COLUMN dh_dataforge_dev_schedule.creator IS '创建者';
COMMENT ON COLUMN dh_dataforge_dev_schedule.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_dev_schedule.updater IS '最后更新者';
COMMENT ON COLUMN dh_dataforge_dev_schedule.update_time IS '最后更新时间';
COMMENT ON COLUMN dh_dataforge_dev_schedule.deleted IS '逻辑删除标识：0未删除、1已删除；外部取消前不得删除绑定';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dataforge_dev_schedule_generation
    ON dh_dataforge_dev_schedule (tenant_id, task_id, generation) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dataforge_dev_schedule_sync
    ON dh_dataforge_dev_schedule (sync_status, lease_until, id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dataforge_dev_schedule_task_version
    ON dh_dataforge_dev_schedule (tenant_id, task_id, version_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dataforge_dev_schedule_poll
    ON dh_dataforge_dev_schedule (tenant_id, ds_project_code, ds_workflow_code, last_ds_instance_id)
    WHERE deleted = 0 AND synced_enabled;

ALTER TABLE dh_dataforge_dev_task DROP CONSTRAINT ck_dataforge_dev_scheduler;
ALTER TABLE dh_dataforge_dev_task ALTER COLUMN scheduler SET DEFAULT 'DOLPHIN_SCHEDULER';
ALTER TABLE dh_dataforge_dev_task ADD COLUMN IF NOT EXISTS legacy_cron_expression varchar(100);
DO $legacy_cron_contract$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_dataforge_dev_task'::regclass
        AND attname = 'legacy_cron_expression' AND NOT attisdropped
        AND format_type(atttypid, atttypmod) = 'character varying(100)') THEN
        RAISE EXCEPTION '0164 旧 Cron 备份列类型冲突，禁止截断或覆盖';
    END IF;
END $legacy_cron_contract$;
COMMENT ON COLUMN dh_dataforge_dev_task.legacy_cron_expression IS '只读原 XXL Spring Cron 备份；不代表可直接启用的 DolphinScheduler Quartz Cron';
-- 原表达式转存只读备份；不覆盖非空备份，不推断数字星期转换，不制造外部调度成功记录。
UPDATE dh_dataforge_dev_task
SET scheduler = 'DOLPHIN_SCHEDULER', schedule_status = 0, next_run_time = NULL,
    legacy_cron_expression = CASE WHEN nullif(legacy_cron_expression, '') IS NULL THEN cron_expression ELSE legacy_cron_expression END,
    cron_expression = NULL,
    updater = 'migration:0164', update_time = CURRENT_TIMESTAMP
WHERE scheduler = 'XXL_JOB';
ALTER TABLE dh_dataforge_dev_task ADD CONSTRAINT ck_dataforge_dev_scheduler CHECK (scheduler IN ('DOLPHIN_SCHEDULER'));
COMMENT ON COLUMN dh_dataforge_dev_task.scheduler IS '调度系统：DOLPHIN_SCHEDULER；外部周期触发经绑定台账和维护任务导入平台运行链路';

COMMIT;
