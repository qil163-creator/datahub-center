-- Migration: 0225 资产服务审批来源工单
-- Purpose: 隔离保存来源审批快照和本地审核意见，不建立真实申请、批准凭证或数据交付。
-- Depends on: 0224_asset_quality-inline-evaluations.sql；已有单租户资产、申请与市场审计表。
-- Target: 已有 PostgreSQL 数据库；新库由同步后的全量基线创建。
-- Repeatable: yes；对象使用存在性保护，已存在的来源数据和本地意见不被覆盖。
-- Rollback: 确认无须保留来源意见与审计后可删除本表及序列；删除会不可逆丢失来源快照和本地审核意见，真实授权表不受影响。
-- 事务边界：本迁移全部在单一事务内执行；只新增独立表及索引，不改写历史申请。

BEGIN;

CREATE SEQUENCE IF NOT EXISTS dh_market_approval_work_order_seq;

CREATE TABLE IF NOT EXISTS dh_market_approval_work_order (
    id bigint PRIMARY KEY DEFAULT nextval('dh_market_approval_work_order_seq'),
    source_system varchar(32) NOT NULL,
    source_record_id varchar(128) NOT NULL,
    mart_source_id varchar(128),
    mart_name varchar(256) NOT NULL,
    applicant_key varchar(128) NOT NULL,
    applicant_name varchar(128) NOT NULL,
    applicant_dept_name varchar(256),
    apply_type smallint NOT NULL,
    start_time timestamp,
    end_time timestamp,
    apply_time timestamp NOT NULL,
    apply_reason text,
    source_status varchar(16) NOT NULL,
    source_approval_reason text,
    source_approval_time timestamp,
    detail_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    local_status varchar(16),
    local_approval_reason varchar(500),
    local_check_user_id bigint,
    local_check_time timestamp,
    revision integer NOT NULL DEFAULT 1,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT uk_market_approval_source UNIQUE (source_system, source_record_id),
    CONSTRAINT ck_market_approval_source_system CHECK (source_system = 'QDATA'),
    CONSTRAINT ck_market_approval_source_id CHECK (length(trim(source_record_id)) > 0),
    CONSTRAINT ck_market_approval_applicant CHECK (length(trim(applicant_key)) > 0),
    CONSTRAINT ck_market_approval_apply_type CHECK (apply_type IN (1, 2)),
    CONSTRAINT ck_market_approval_source_status CHECK (source_status IN ('1', '2', '3', '4', '5', '6')),
    CONSTRAINT ck_market_approval_local_status CHECK (local_status IS NULL OR local_status IN ('3', '4')),
    CONSTRAINT ck_market_approval_local_actor CHECK (
        (local_status IS NULL AND local_check_user_id IS NULL AND local_check_time IS NULL)
        OR (local_status IS NOT NULL AND local_check_user_id IS NOT NULL
            AND local_check_user_id > 0 AND local_check_time IS NOT NULL)),
    CONSTRAINT ck_market_approval_snapshot CHECK (jsonb_typeof(detail_snapshot) = 'object'),
    CONSTRAINT ck_market_approval_revision CHECK (revision > 0),
    CONSTRAINT ck_market_approval_deleted CHECK (deleted IN (0, 1))
);

ALTER SEQUENCE dh_market_approval_work_order_seq OWNED BY dh_market_approval_work_order.id;

COMMENT ON TABLE dh_market_approval_work_order IS '来源审批工单隔离快照；本地意见不代表正式数据授权';
COMMENT ON COLUMN dh_market_approval_work_order.id IS '本地隔离工单主键，仅用于审计关联';
COMMENT ON COLUMN dh_market_approval_work_order.source_system IS '来源系统，目前为QDATA';
COMMENT ON COLUMN dh_market_approval_work_order.source_record_id IS '来源工单编号字符串，不是本地申请编号';
COMMENT ON COLUMN dh_market_approval_work_order.mart_source_id IS '来源集市资源编号字符串，不作为本地资产编号';
COMMENT ON COLUMN dh_market_approval_work_order.mart_name IS '来源集市资产名称快照';
COMMENT ON COLUMN dh_market_approval_work_order.applicant_key IS '来源人员标识字符串，不映射本地登录身份';
COMMENT ON COLUMN dh_market_approval_work_order.applicant_name IS '来源申请人名称快照';
COMMENT ON COLUMN dh_market_approval_work_order.applicant_dept_name IS '来源申请机构名称快照';
COMMENT ON COLUMN dh_market_approval_work_order.apply_type IS '来源申请周期：1长期有效、2自定义';
COMMENT ON COLUMN dh_market_approval_work_order.start_time IS '来源申请生效时间；长期有效时可空';
COMMENT ON COLUMN dh_market_approval_work_order.end_time IS '来源申请截止时间；长期有效时可空';
COMMENT ON COLUMN dh_market_approval_work_order.apply_time IS '来源申请时间快照';
COMMENT ON COLUMN dh_market_approval_work_order.apply_reason IS '来源申请理由快照';
COMMENT ON COLUMN dh_market_approval_work_order.source_status IS '不可变来源状态：1未授权、2待授权、3已通过、4已驳回、5已下架、6已过期';
COMMENT ON COLUMN dh_market_approval_work_order.source_approval_reason IS '来源审批意见快照';
COMMENT ON COLUMN dh_market_approval_work_order.source_approval_time IS '来源审批时间快照';
COMMENT ON COLUMN dh_market_approval_work_order.detail_snapshot IS '脱敏的资源、字段、预览和接口文档展示快照；不得包含远端凭证';
COMMENT ON COLUMN dh_market_approval_work_order.local_status IS '本地保存的工单意见结果：3通过、4驳回；不生成正式数据授权';
COMMENT ON COLUMN dh_market_approval_work_order.local_approval_reason IS '本地审批意见';
COMMENT ON COLUMN dh_market_approval_work_order.local_check_user_id IS '可信当前登录的本地审批人编号，区别于来源人员标识';
COMMENT ON COLUMN dh_market_approval_work_order.local_check_time IS '本地意见保存时间';
COMMENT ON COLUMN dh_market_approval_work_order.revision IS '本地工单意见并发版本号';
COMMENT ON COLUMN dh_market_approval_work_order.creator IS '创建者审计标识';
COMMENT ON COLUMN dh_market_approval_work_order.create_time IS '本地导入创建时间';
COMMENT ON COLUMN dh_market_approval_work_order.updater IS '更新者审计标识';
COMMENT ON COLUMN dh_market_approval_work_order.update_time IS '本地更新时间';
COMMENT ON COLUMN dh_market_approval_work_order.deleted IS '逻辑删除标记：0有效、1已删除';

CREATE INDEX IF NOT EXISTS idx_market_approval_status_time
    ON dh_market_approval_work_order ((COALESCE(local_status, source_status)), apply_time DESC, id DESC)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_market_approval_applicant_time
    ON dh_market_approval_work_order (applicant_key, apply_time DESC, id DESC)
    WHERE deleted = 0;

COMMIT;
