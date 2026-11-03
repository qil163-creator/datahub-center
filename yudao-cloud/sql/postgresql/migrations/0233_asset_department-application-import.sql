-- Migration: 0233 部门共享的来源真实申请迁入。
-- Purpose: 在真实申请表建立明确的来源工单追溯，将来源申请二十一复制为本地市级部门申请；不伪造本地申请人、冻结合同、授权凭证或数据交付。
-- Depends on: 0225_asset_market-approval-work-order.sql；0228_asset_qdata-approval-import.sql；0229_asset_market-approval-snapshot-correction.sql；0221_asset_qdata-map-datasets.sql；现有单租户真实申请、系统部门及资产表。
-- Target: 已导入上述来源工单和资产的 PostgreSQL 平台库；新库使用同步后的完整基线。
-- Repeatable: 是；来源工单唯一关联包括软删除记录，存在即保留，不覆盖后续状态、部门归属、修订或删除；不恢复人工撤回。
-- Rollback: 单事务失败完整回滚；提交后须先备份，再人工撤销本次未被后续维护的来源申请及结构。不可删除其他真实申请或来源工单，序列已前进时不回退。
-- 部门依据：来源原申请人当前真实部门为研发部门八七二，原历史申请未保存独立部门；按用户明确选择，将此次复制归入本地市级部门一百，非同名匹配，不映射来源人员为本地账号。
-- 来源边界：只复制来源申请二十一及其集市二十二、来源资产六十六；来源申请二十不属于本次范围。历史字段二十项、河流预览三行及接口十六字段继续按既有来源工单真实差异读取。
-- 状态边界：来源已通过状态映射为本地已通过，但实际授权仍关闭，接口、应用、冻结快照、批准人及交付信息均不补造。
-- 事务与锁：本地加列、约束、索引及单行迁入使用一个事务；短时锁定申请表，超时完整回滚，不调用任何外部系统。
BEGIN;
SET LOCAL search_path TO "datahub-center",pg_catalog;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='120s';
SELECT pg_advisory_xact_lock(1500,35);
LOCK TABLE dh_market_resource_apply IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE dh_market_resource_apply ADD COLUMN IF NOT EXISTS source_work_order_id bigint;
COMMENT ON COLUMN dh_market_resource_apply.source_work_order_id IS '来源真实申请对应的隔离工单主键，仅追溯历史业务数据，不赋予调用授权或建立本地申请人映射';
ALTER TABLE dh_market_resource_apply ALTER COLUMN apply_user_id DROP NOT NULL;
COMMENT ON COLUMN dh_market_resource_apply.apply_user_id IS '本地真实申请人；仅明确关联来源工单的复制申请可为空，不把来源人员编号当作本地账号';

DO $constraints$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_market_resource_apply'::regclass AND conname='fk_market_apply_source_work_order') THEN
        ALTER TABLE dh_market_resource_apply ADD CONSTRAINT fk_market_apply_source_work_order
            FOREIGN KEY(source_work_order_id) REFERENCES dh_market_approval_work_order(id);
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_market_resource_apply'::regclass AND conname='ck_market_apply_owner_or_source') THEN
        ALTER TABLE dh_market_resource_apply ADD CONSTRAINT ck_market_apply_owner_or_source
            CHECK(apply_user_id IS NOT NULL OR source_work_order_id IS NOT NULL);
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_market_resource_apply'::regclass AND conname='ck_market_apply_source_department') THEN
        ALTER TABLE dh_market_resource_apply ADD CONSTRAINT ck_market_apply_source_department
            CHECK(source_work_order_id IS NULL OR apply_dept_id IS NOT NULL);
    END IF;
END;
$constraints$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_market_apply_source_work_order
    ON dh_market_resource_apply(source_work_order_id) WHERE source_work_order_id IS NOT NULL;
COMMENT ON INDEX uk_market_apply_source_work_order IS '来源申请在真实申请表唯一，包含删除记录以防迁移重放恢复';
CREATE INDEX IF NOT EXISTS idx_market_department_source_apply
    ON dh_market_resource_apply(apply_dept_id,create_time DESC,id DESC)
    WHERE deleted=0 AND source_work_order_id IS NOT NULL;
COMMENT ON INDEX idx_market_department_source_apply IS '来源复制申请按明确部门归属分页，不根据来源人员或相同资源猜测归属';

DO $source_apply$
DECLARE source_id_value bigint; asset_id_value bigint; source_matches integer;
        source_row dh_market_approval_work_order%ROWTYPE;
BEGIN
    SELECT count(*),min(id) INTO source_matches,source_id_value
    FROM dh_market_approval_work_order WHERE source_system='QDATA' AND source_record_id='21' AND mart_source_id='22';
    IF source_matches<>1 THEN
        RAISE EXCEPTION '部门申请迁入缺少唯一来源申请二十一及集市二十二，禁止使用同资源的其他申请代替';
    END IF;
    -- 已有来源身份即保留，包括被人工删除或调整归属的申请。
    IF EXISTS(SELECT 1 FROM dh_market_resource_apply WHERE source_work_order_id=source_id_value) THEN
        RETURN;
    END IF;
    PERFORM id FROM system_dept WHERE id=100 AND parent_id=0 AND name='市级' AND status=0 AND deleted=0 FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION '用户指定的本地市级部门一百不存在或不可用，禁止猜测其他归属'; END IF;
    SELECT * INTO source_row FROM dh_market_approval_work_order WHERE id=source_id_value AND deleted=0 FOR SHARE;
    IF NOT FOUND OR source_row.source_status<>'3' OR jsonb_typeof(source_row.detail_snapshot->'resource') IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION '来源申请二十一缺少已确认的真实申请及资源快照，禁止补造数据';
    END IF;
    SELECT count(*),min(a.id) INTO source_matches,asset_id_value
    FROM dh_asset_import_dataset d JOIN dh_asset_data_asset a ON a.id=d.asset_id AND a.deleted=0
    JOIN dh_asset_market_listing l ON l.asset_id=a.id AND l.deleted=0
    WHERE d.source_asset_id='66' AND d.deleted=0 AND l.source_record_id=source_row.mart_source_id;
    IF source_matches<>1 THEN RAISE EXCEPTION '来源资产六十六与集市二十二缺少唯一真实本地映射'; END IF;
    IF EXISTS(SELECT 1 FROM dh_market_resource_apply WHERE apply_no='QDATA-APPLY-21') THEN
        RAISE EXCEPTION '来源申请展示编号已被其他申请使用，禁止认领或覆盖已有申请';
    END IF;
    INSERT INTO dh_market_resource_apply(apply_no,title,asset_id,asset_type,apply_dept_id,apply_user_id,
        purpose,apply_start_time,apply_end_time,apply_use_type,status,if_auth,check_note,revision,
        snapshot,catalog_version_id,delivery_status,paused,source_work_order_id,creator,create_time,updater)
    SELECT 'QDATA-APPLY-21',source_row.mart_name,asset_id_value,a.asset_type,100,NULL,
        source_row.apply_reason,source_row.start_time,source_row.end_time,0,2,0,source_row.source_approval_reason,1,
        NULL,NULL,0,0,source_id_value,'migration:0233',source_row.apply_time,'migration:0233'
    FROM dh_asset_data_asset a WHERE a.id=asset_id_value
    ON CONFLICT(source_work_order_id) WHERE source_work_order_id IS NOT NULL DO NOTHING;
END;
$source_apply$;

COMMIT;
