-- Migration: 0267 质量工单通知按幂等键自动重试与耗尽处置；例外到期处理失败账本。
-- Purpose: 1) 工单通知：UNKNOWN 也按同一幂等键自动重试，FAILED/UNKNOWN 共用 8 次上限，耗尽转 EXHAUSTED
--             等待人工核验；投递扫描索引纳入 UNKNOWN。
--          2) 新增 dh_governance_quality_expiry_failure：例外到期逐单失败的持久账本（退避、5 次耗尽、
--             人工授权重试），耗尽或退避中的提交不再占用扫描批次名额。
-- Depends on: 0266_system_notify-idempotency-key.sql（通知服务幂等键，必须先于新版 governance-server 部署）；
--             dh_governance_quality_work_order_notify 已含人工核验列（resolution_status 等）。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；索引、序列、表均带存在性保护，注释可重复覆盖。
-- Rollback: 加法迁移。回滚应用版本前须确认没有 EXHAUSTED 通知（旧版本不识别该状态，会使其既不发送也不可核验）；
--           失败账本表可保留，旧版本不读取。
-- 部署顺序：执行 0266、0267 → 先发布 system-server → 再发布 governance-server。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 1) 工单通知：扫描索引纳入可自动重试的 UNKNOWN
DROP INDEX IF EXISTS idx_quality_work_order_notify_dispatch;
CREATE INDEX IF NOT EXISTS idx_quality_work_order_notify_dispatch
    ON dh_governance_quality_work_order_notify (next_retry_time, id)
    WHERE deleted = 0 AND send_status IN ('PENDING', 'FAILED', 'UNKNOWN');

COMMENT ON COLUMN dh_governance_quality_work_order_notify.send_status IS
    '投递状态：PENDING待发送、SENDING已认领、SENT已确认、FAILED明确未发送、UNKNOWN结果不明；FAILED/UNKNOWN 按幂等键自动重试，共 8 次后转 EXHAUSTED 待人工核验';
COMMENT ON COLUMN dh_governance_quality_work_order_notify.retry_count IS '自动投递已失败次数（FAILED 与 UNKNOWN 共用），达到 8 次转 EXHAUSTED；人工授权重试时清零';
COMMENT ON COLUMN dh_governance_quality_work_order_notify.lease_until IS '上海本地时间戳通知认领租约截止时间；租约到期收敛为 UNKNOWN，退避后按同一幂等键重试';

-- 2) 例外到期处理失败账本
CREATE SEQUENCE IF NOT EXISTS dh_governance_quality_expiry_failure_seq;

CREATE TABLE IF NOT EXISTS dh_governance_quality_expiry_failure (
    id int8 NOT NULL DEFAULT nextval('dh_governance_quality_expiry_failure_seq'::regclass),
    submission_id int8 NOT NULL,
    work_order_id int8 NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count int4 NOT NULL DEFAULT 0,
    last_error varchar(512),
    first_failed_at timestamp(6),
    last_failed_at timestamp(6),
    next_attempt_at timestamp(6),
    resolution_action varchar(32),
    resolution_reason varchar(500),
    resolved_by int8,
    resolved_at timestamp(6),
    version int4 NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_quality_expiry_failure PRIMARY KEY (id),
    CONSTRAINT ck_quality_expiry_failure_status CHECK (status IN ('RETRYING', 'EXHAUSTED', 'RESOLVED')),
    CONSTRAINT ck_quality_expiry_failure_attempts CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_expiry_failure_submission
    ON dh_governance_quality_expiry_failure (submission_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_quality_expiry_failure_status
    ON dh_governance_quality_expiry_failure (status, last_failed_at) WHERE deleted = 0;

COMMENT ON TABLE dh_governance_quality_expiry_failure IS '数据质量-例外到期处理失败账本';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.id IS '账本编号';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.submission_id IS '例外提交编号（每个提交一条有效账本）';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.work_order_id IS '原工单编号';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.status IS '状态：RETRYING退避重试中、EXHAUSTED次数耗尽待人工、RESOLVED已关闭';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.attempt_count IS '自动处理已失败次数，达到 5 次转 EXHAUSTED，人工授权重试时清零';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.last_error IS '最近一次失败的安全摘要，不含驱动或厂商原文';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.first_failed_at IS '本轮首次失败时间';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.last_failed_at IS '最近一次失败时间';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.next_attempt_at IS '下次允许自动处理时间：5 分钟 × 2^(次数-1)，封顶 6 小时';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.resolution_action IS '处置动作：SUCCEEDED自动成功、RETRY_AUTHORIZED人工授权重试';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.resolution_reason IS '人工授权理由';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.resolved_by IS '最近一次人工处置操作人';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.resolved_at IS '最近一次关闭或人工处置时间';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.version IS 'CAS 版本，防止旧页面覆盖新状态';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.creator IS '创建者';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.updater IS '更新者';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_quality_expiry_failure.deleted IS '逻辑删除：0未删除、1已删除';

COMMIT;
