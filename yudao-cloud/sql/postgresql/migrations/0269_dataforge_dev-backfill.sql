-- Migration: 0269 数据开发补数据：按业务日期区间逐日重跑开发任务。
-- Purpose: 新增补数据批次 dh_dataforge_dev_backfill 与日期明细 dh_dataforge_dev_backfill_item。批次冻结发起时的
--          任务保存版本，由 devTaskJob 逐日登记普通运行（trigger_type=BACKFILL，运行键 BACKFILL:<批次>:<yyyyMMdd>），
--          沿用单任务单活跃运行、上游同业务日期依赖与质量检查护栏。
-- Depends on: 平台库已升级至 0268；dh_dataforge_dev_record 存在 trigger_type 列。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；序列、表、索引均带存在性保护，注释可重复覆盖。
-- Rollback: 加法迁移。回滚应用版本前应先取消进行中的批次；两张表可保留，旧版本不读取。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_dev_backfill_seq;

CREATE TABLE IF NOT EXISTS dh_dataforge_dev_backfill (
    id int8 NOT NULL DEFAULT nextval('dh_dataforge_dev_backfill_seq'::regclass),
    task_id int8 NOT NULL,
    task_name varchar(100),
    version_id int8 NOT NULL,
    execution_user_id int8 NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    date_order varchar(8) NOT NULL DEFAULT 'ASC',
    failure_policy varchar(16) NOT NULL DEFAULT 'STOP',
    status varchar(16) NOT NULL DEFAULT 'RUNNING',
    total_count int4 NOT NULL DEFAULT 0,
    success_count int4 NOT NULL DEFAULT 0,
    failed_count int4 NOT NULL DEFAULT 0,
    cancelled_count int4 NOT NULL DEFAULT 0,
    cancel_requested bool NOT NULL DEFAULT false,
    request_id varchar(100) NOT NULL,
    remark varchar(255),
    last_error varchar(512),
    finish_time timestamp(6),
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_dataforge_dev_backfill PRIMARY KEY (id),
    CONSTRAINT ck_dev_backfill_range CHECK (start_date <= end_date AND end_date - start_date < 366),
    CONSTRAINT ck_dev_backfill_order CHECK (date_order IN ('ASC', 'DESC')),
    CONSTRAINT ck_dev_backfill_policy CHECK (failure_policy IN ('STOP', 'CONTINUE')),
    CONSTRAINT ck_dev_backfill_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dev_backfill_request
    ON dh_dataforge_dev_backfill (request_id) WHERE deleted = 0;
-- 同一任务同时只有一个进行中的补数据批次。
CREATE UNIQUE INDEX IF NOT EXISTS uk_dev_backfill_running_task
    ON dh_dataforge_dev_backfill (task_id) WHERE deleted = 0 AND status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_dev_backfill_task
    ON dh_dataforge_dev_backfill (task_id, id DESC) WHERE deleted = 0;

COMMENT ON TABLE dh_dataforge_dev_backfill IS '数据开发-补数据批次（历史业务日期区间逐日重跑）';
COMMENT ON COLUMN dh_dataforge_dev_backfill.id IS '批次编号';
COMMENT ON COLUMN dh_dataforge_dev_backfill.task_id IS '开发任务编号';
COMMENT ON COLUMN dh_dataforge_dev_backfill.task_name IS '任务名称（冗余）';
COMMENT ON COLUMN dh_dataforge_dev_backfill.version_id IS '发起时冻结的任务保存版本；任务版本变更后批次终止';
COMMENT ON COLUMN dh_dataforge_dev_backfill.execution_user_id IS '冻结版本的执行身份，推进时按此身份复核权限';
COMMENT ON COLUMN dh_dataforge_dev_backfill.start_date IS '起始业务日期（含）';
COMMENT ON COLUMN dh_dataforge_dev_backfill.end_date IS '截止业务日期（含），单批次最多 366 天';
COMMENT ON COLUMN dh_dataforge_dev_backfill.date_order IS '日期顺序：ASC从早到晚、DESC从晚到早';
COMMENT ON COLUMN dh_dataforge_dev_backfill.failure_policy IS '失败策略：STOP失败即停止并取消剩余日期、CONTINUE继续后续日期';
COMMENT ON COLUMN dh_dataforge_dev_backfill.status IS '状态：RUNNING进行中、SUCCESS全部成功、FAILED存在失败日期或被终止、CANCELLED已取消';
COMMENT ON COLUMN dh_dataforge_dev_backfill.total_count IS '日期总数';
COMMENT ON COLUMN dh_dataforge_dev_backfill.success_count IS '成功日期数（运行成功且质量通过或未配置）';
COMMENT ON COLUMN dh_dataforge_dev_backfill.failed_count IS '失败日期数';
COMMENT ON COLUMN dh_dataforge_dev_backfill.cancelled_count IS '取消日期数';
COMMENT ON COLUMN dh_dataforge_dev_backfill.cancel_requested IS '是否已请求取消；已提交日期等待引擎终态后批次转 CANCELLED';
COMMENT ON COLUMN dh_dataforge_dev_backfill.request_id IS '发起请求标识（幂等）';
COMMENT ON COLUMN dh_dataforge_dev_backfill.remark IS '备注';
COMMENT ON COLUMN dh_dataforge_dev_backfill.last_error IS '最近一次失败或终止原因';
COMMENT ON COLUMN dh_dataforge_dev_backfill.finish_time IS '结束时间';
COMMENT ON COLUMN dh_dataforge_dev_backfill.creator IS '创建者';
COMMENT ON COLUMN dh_dataforge_dev_backfill.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_dev_backfill.updater IS '更新者';
COMMENT ON COLUMN dh_dataforge_dev_backfill.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_dev_backfill.deleted IS '逻辑删除：0未删除、1已删除';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_dev_backfill_item_seq;

CREATE TABLE IF NOT EXISTS dh_dataforge_dev_backfill_item (
    id int8 NOT NULL DEFAULT nextval('dh_dataforge_dev_backfill_item_seq'::regclass),
    backfill_id int8 NOT NULL,
    task_id int8 NOT NULL,
    seq int4 NOT NULL,
    business_date date NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    record_id int8,
    last_error varchar(512),
    start_time timestamp(6),
    end_time timestamp(6),
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_dataforge_dev_backfill_item PRIMARY KEY (id),
    CONSTRAINT ck_dev_backfill_item_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dev_backfill_item_date
    ON dh_dataforge_dev_backfill_item (backfill_id, business_date) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dev_backfill_item_status
    ON dh_dataforge_dev_backfill_item (backfill_id, status, seq) WHERE deleted = 0;

COMMENT ON TABLE dh_dataforge_dev_backfill_item IS '数据开发-补数据批次日期明细';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.id IS '明细编号';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.backfill_id IS '补数据批次编号';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.task_id IS '开发任务编号（冗余）';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.seq IS '执行顺序，从 1 开始';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.business_date IS '业务日期';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.status IS '状态：PENDING待执行、RUNNING已登记运行、SUCCESS成功、FAILED失败、CANCELLED已取消';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.record_id IS '运行实例编号（dh_dataforge_dev_record，运行键 BACKFILL:<批次>:<yyyyMMdd>）';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.last_error IS '失败或取消原因';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.start_time IS '登记运行时间';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.end_time IS '结束时间';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.creator IS '创建者';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.updater IS '更新者';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_dev_backfill_item.deleted IS '逻辑删除：0未删除、1已删除';

COMMENT ON COLUMN dh_dataforge_dev_record.trigger_type IS '触发方式：MANUAL-手动 SCHEDULE-调度 BACKFILL-补数据 JOB-作业';

COMMIT;
