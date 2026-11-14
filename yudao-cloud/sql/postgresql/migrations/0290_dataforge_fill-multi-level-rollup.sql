-- Migration: 0290 在线填报多级下发与逐级汇总
-- Purpose: 1) dh_dataforge_fill_work_item 新增 parent_work_item_id（上级组织实例，空表示由发起者直接下发的顶层实例）
--             与 level（层级，顶层为 0）；status 增加 11-已汇入上级（下级数据经上级单位审核通过，等待随上级一起上报，
--             不单独写入正式表）。
--          2) dh_dataforge_fill_draft_row、dh_dataforge_fill_submission_row 新增来源列 source_kind（空-本单位录入
--             1-下级明细 2-下级分组合计）、source_org_id（数据最初的报送组织）、source_work_item_id 与 source_row_id
--             （原始录入的实例与行）、source_submission_id（直接下级审核通过的提交），逐级汇总后正式表仍可追溯到最底层单位。
--          3) dh_dataforge_fill_submission 新增 review_mode（1-审批流程 2-上级单位审核）与 reviewer_user_id（上级审核人）。
--          4) 新建 dh_dataforge_fill_dispatch_template：中间单位的默认下发名单（按计划 + 上级组织），周期任务每期生成时
--             自动沿用，截止时间按上级截止时间提前 deadline_offset_hours 小时。
--          5) 新建 dh_dataforge_fill_rollup_source：中间单位最近一次汇总纳入的下级提交，用于识别“下级数据已变化、
--             需重新汇总”。
--          6) dh_dataforge_fill_table 新增 rollup_mode（DETAIL 明细汇集 / GROUP 分组合计）、rollup_group_fields（分组字段）；
--             dh_dataforge_fill_table_column 新增 rollup_func（分组合计时的计算方式）；
--             dh_dataforge_fill_table_version 新增 rollup_schema（发布时固化的汇总规则）。
--          7) 新增站内信模板 fill_child_submitted（下级提交待审核）、fill_returned_by_parent（上级单位退回）。
--          下发、审核、汇总、退回复用已有按钮 dataforge:fill-work-item:edit，并在服务端按“上级实例的有效填报人”做对象级校验；
--          汇总规则复用 dataforge:fill-table:update。不新增菜单与权限。
-- Depends on: 0289_dataforge_fill-sms-mail-templates.sql（编号前驱）；上述 dh_dataforge_fill_* 表、system_notify_template
--             及其主键序列已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；列使用 ADD COLUMN IF NOT EXISTS，表、序列、索引带存在性保护，约束先删后建，注释可重复执行；
--             站内信模板按 code 判重，已存在且内容一致时跳过，内容不一致或已停用时报错中止，不静默覆盖。
--             单事务执行；新增列可空或带常量默认值，不重写表（锁等待上限 10 秒，超时即失败，可稍后重试）。
-- Rollback: 回退前须确认没有状态为 11 的实例、没有 parent_work_item_id 非空的实例（否则下级数据会失去上报路径）。
--           应用回退后可删除两张新表与序列、上述新增列和两条站内信模板；会丢失下发名单、汇总来源与汇总规则配置。
--           已写入正式表的数据不受影响。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 1. 组织实例树
ALTER TABLE dh_dataforge_fill_work_item
    ADD COLUMN IF NOT EXISTS parent_work_item_id int8,
    ADD COLUMN IF NOT EXISTS level int2 NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_fill_work_item_parent
    ON dh_dataforge_fill_work_item (parent_work_item_id) WHERE deleted = 0 AND parent_work_item_id IS NOT NULL;
COMMENT ON COLUMN dh_dataforge_fill_work_item.parent_work_item_id IS '上级组织实例编号；空表示由发起者直接下发的顶层实例';
COMMENT ON COLUMN dh_dataforge_fill_work_item.level IS '层级：顶层为 0，每下发一级加 1';
COMMENT ON COLUMN dh_dataforge_fill_work_item.status IS '主状态：0-待填报 1-填报中 2-提交校验中 3-待启动审批 4-审批中（含上级单位审核中） 5-整改中 6-待合并 7-合并中 8-合并失败 9-已合并 10-已取消 11-已汇入上级';

-- 2. 行来源
ALTER TABLE dh_dataforge_fill_draft_row
    ADD COLUMN IF NOT EXISTS source_kind int2,
    ADD COLUMN IF NOT EXISTS source_org_id int8,
    ADD COLUMN IF NOT EXISTS source_work_item_id int8,
    ADD COLUMN IF NOT EXISTS source_submission_id int8,
    ADD COLUMN IF NOT EXISTS source_row_id varchar(64);
ALTER TABLE dh_dataforge_fill_submission_row
    ADD COLUMN IF NOT EXISTS source_kind int2,
    ADD COLUMN IF NOT EXISTS source_org_id int8,
    ADD COLUMN IF NOT EXISTS source_work_item_id int8,
    ADD COLUMN IF NOT EXISTS source_submission_id int8,
    ADD COLUMN IF NOT EXISTS source_row_id varchar(64);
ALTER TABLE dh_dataforge_fill_draft_row DROP CONSTRAINT IF EXISTS ck_dh_dataforge_fill_draft_row_source_kind;
ALTER TABLE dh_dataforge_fill_draft_row ADD CONSTRAINT ck_dh_dataforge_fill_draft_row_source_kind
    CHECK (source_kind IS NULL OR source_kind IN (1, 2));
ALTER TABLE dh_dataforge_fill_submission_row DROP CONSTRAINT IF EXISTS ck_dh_dataforge_fill_submission_row_source_kind;
ALTER TABLE dh_dataforge_fill_submission_row ADD CONSTRAINT ck_dh_dataforge_fill_submission_row_source_kind
    CHECK (source_kind IS NULL OR source_kind IN (1, 2));
COMMENT ON COLUMN dh_dataforge_fill_draft_row.source_kind IS '行来源：空-本单位录入 1-下级明细 2-下级分组合计（汇总生成的行只读，修改须退回下级）';
COMMENT ON COLUMN dh_dataforge_fill_draft_row.source_org_id IS '数据最初的报送组织编号（逐级汇总时向上传递；分组合计行为汇总单位）';
COMMENT ON COLUMN dh_dataforge_fill_draft_row.source_work_item_id IS '数据原始录入的组织实例编号（分组合计行为汇总单位实例）';
COMMENT ON COLUMN dh_dataforge_fill_draft_row.source_submission_id IS '直接下级审核通过的提交编号';
COMMENT ON COLUMN dh_dataforge_fill_draft_row.source_row_id IS '数据原始录入的行标识（分组合计行为分组键摘要）；与 source_work_item_id 一起作为数据身份，重复汇总保持不变';
COMMENT ON COLUMN dh_dataforge_fill_submission_row.source_kind IS '行来源：空-本单位录入 1-下级明细 2-下级分组合计';
COMMENT ON COLUMN dh_dataforge_fill_submission_row.source_org_id IS '数据最初的报送组织编号；合并时写入正式表的来源组织列';
COMMENT ON COLUMN dh_dataforge_fill_submission_row.source_work_item_id IS '数据原始录入的组织实例编号（分组合计行为汇总单位实例）';
COMMENT ON COLUMN dh_dataforge_fill_submission_row.source_submission_id IS '直接下级审核通过的提交编号';
COMMENT ON COLUMN dh_dataforge_fill_submission_row.source_row_id IS '数据原始录入的行标识（分组合计行为分组键摘要）；与 source_work_item_id 一起作为数据身份，重复汇总保持不变';

-- 3. 审核方式
ALTER TABLE dh_dataforge_fill_submission
    ADD COLUMN IF NOT EXISTS review_mode int2 NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS reviewer_user_id int8;
ALTER TABLE dh_dataforge_fill_submission DROP CONSTRAINT IF EXISTS ck_dh_dataforge_fill_submission_review_mode;
ALTER TABLE dh_dataforge_fill_submission ADD CONSTRAINT ck_dh_dataforge_fill_submission_review_mode
    CHECK (review_mode IN (1, 2));
COMMENT ON COLUMN dh_dataforge_fill_submission.review_mode IS '审核方式：1-审批流程（顶层实例报给发起者） 2-上级单位审核（下级实例报给上级单位，不启动审批流程）';
COMMENT ON COLUMN dh_dataforge_fill_submission.reviewer_user_id IS '上级单位审核人用户编号（review_mode=2）';

-- 4. 默认下发名单
CREATE SEQUENCE IF NOT EXISTS dh_dataforge_fill_dispatch_template_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_fill_dispatch_template (
    id int8 NOT NULL DEFAULT nextval('dh_dataforge_fill_dispatch_template_seq'::regclass),
    plan_id int8 NOT NULL,
    parent_org_id int8 NOT NULL,
    reporting_org_id int8 NOT NULL,
    org_name varchar(100) NOT NULL DEFAULT '',
    org_code varchar(64) DEFAULT '',
    org_path varchar(500) DEFAULT '',
    assignee_user_ids varchar(1000) NOT NULL DEFAULT '[]',
    deadline_offset_hours int4 NOT NULL DEFAULT 0,
    sort int4 NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_dataforge_fill_dispatch_template PRIMARY KEY (id),
    CONSTRAINT ck_dh_dataforge_fill_dispatch_template_offset CHECK (deadline_offset_hours BETWEEN 0 AND 720)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_dataforge_fill_dispatch_template
    ON dh_dataforge_fill_dispatch_template (plan_id, parent_org_id, reporting_org_id) WHERE deleted = 0;
COMMENT ON TABLE dh_dataforge_fill_dispatch_template IS '数据填报-中间单位默认下发名单（周期任务每期生成时自动下发到下一级）';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.id IS '编号';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.plan_id IS '采集计划编号';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.parent_org_id IS '下发单位（上级报送组织）编号';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.reporting_org_id IS '下级报送组织编号（须在下发单位的部门子树内）';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.org_name IS '下级组织名称快照';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.org_code IS '下级组织编码快照';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.org_path IS '下级组织路径快照';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.assignee_user_ids IS '默认填报人 JSON 数组：[{"userId","userName","deptId"}]（编号为字符串）';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.deadline_offset_hours IS '截止时间比下发单位提前的小时数（0~720）';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.sort IS '显示顺序';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.creator IS '创建者';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.updater IS '更新者';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_fill_dispatch_template.deleted IS '是否删除';

-- 5. 汇总来源
CREATE SEQUENCE IF NOT EXISTS dh_dataforge_fill_rollup_source_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_fill_rollup_source (
    id int8 NOT NULL DEFAULT nextval('dh_dataforge_fill_rollup_source_seq'::regclass),
    work_item_id int8 NOT NULL,
    child_work_item_id int8 NOT NULL,
    child_submission_id int8 NOT NULL,
    rolled_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_dataforge_fill_rollup_source PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_fill_rollup_source_item
    ON dh_dataforge_fill_rollup_source (work_item_id) WHERE deleted = 0;
COMMENT ON TABLE dh_dataforge_fill_rollup_source IS '数据填报-中间单位最近一次汇总纳入的下级提交（识别下级数据变化后需重新汇总）';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.id IS '编号';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.work_item_id IS '汇总单位的组织实例编号';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.child_work_item_id IS '下级组织实例编号';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.child_submission_id IS '汇总时纳入的下级提交编号';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.rolled_at IS '汇总时间';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.creator IS '创建者';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.updater IS '更新者';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_fill_rollup_source.deleted IS '是否删除';

-- 6. 汇总规则
ALTER TABLE dh_dataforge_fill_table
    ADD COLUMN IF NOT EXISTS rollup_mode varchar(16) NOT NULL DEFAULT 'DETAIL',
    ADD COLUMN IF NOT EXISTS rollup_group_fields varchar(1000) DEFAULT '';
ALTER TABLE dh_dataforge_fill_table DROP CONSTRAINT IF EXISTS ck_dh_dataforge_fill_table_rollup_mode;
ALTER TABLE dh_dataforge_fill_table ADD CONSTRAINT ck_dh_dataforge_fill_table_rollup_mode
    CHECK (rollup_mode IN ('DETAIL', 'GROUP'));
COMMENT ON COLUMN dh_dataforge_fill_table.rollup_mode IS '多级汇总方式：DETAIL-明细汇集（保留下级各行及来源组织） GROUP-分组合计（按分组字段计算）';
COMMENT ON COLUMN dh_dataforge_fill_table.rollup_group_fields IS '分组合计的分组字段码，英文逗号分隔（rollup_mode=GROUP 时必填）';
ALTER TABLE dh_dataforge_fill_table_column
    ADD COLUMN IF NOT EXISTS rollup_func varchar(16) DEFAULT '';
ALTER TABLE dh_dataforge_fill_table_column DROP CONSTRAINT IF EXISTS ck_dh_dataforge_fill_table_column_rollup_func;
ALTER TABLE dh_dataforge_fill_table_column ADD CONSTRAINT ck_dh_dataforge_fill_table_column_rollup_func
    CHECK (rollup_func IS NULL OR rollup_func IN ('', 'SUM', 'MAX', 'MIN', 'COUNT', 'FIRST'));
COMMENT ON COLUMN dh_dataforge_fill_table_column.rollup_func IS '分组合计的计算方式：SUM 合计 MAX 最大 MIN 最小 COUNT 计数（逐级相加） FIRST 取首个值；空表示不输出（分组字段除外）。不提供平均值：逐级对平均值再求平均会失真';
ALTER TABLE dh_dataforge_fill_table_version
    ADD COLUMN IF NOT EXISTS rollup_schema text;
COMMENT ON COLUMN dh_dataforge_fill_table_version.rollup_schema IS '发布时固化的多级汇总规则 JSON：{"mode","groupFields","funcs"}';

-- 7. 站内信模板与枚举注释
LOCK TABLE system_notify_template IN SHARE ROW EXCLUSIVE MODE;

DO $fill_rollup_templates$
DECLARE
    template_sequence regclass;
    matches integer;
    new_id bigint;
    item record;
BEGIN
    template_sequence := COALESCE(pg_get_serial_sequence('"datahub-center".system_notify_template', 'id')::regclass,
                                  to_regclass('"datahub-center".system_notify_template_seq'));
    IF template_sequence IS NULL THEN
        RAISE EXCEPTION '0290 缺少站内信模板主键序列';
    END IF;
    FOR item IN
        SELECT * FROM (VALUES
            ('fill_child_submitted', '填报-下级提交待审核',
             '「{taskName}（{periodKey}）」下级单位「{orgName}」已提交数据，请在工作台“下级单位”中审核。',
             '["taskName","periodKey","orgName"]'),
            ('fill_returned_by_parent', '填报-上级单位退回',
             '「{taskName}（{periodKey}）/ {orgName}」的数据被上级单位退回：{reason}。请修改后重新提交。',
             '["taskName","periodKey","orgName","reason"]')
        ) AS t(code, name, content, params)
    LOOP
        SELECT count(*) INTO matches FROM system_notify_template WHERE code = item.code AND deleted = 0;
        IF matches > 0 THEN
            IF matches <> 1 OR NOT EXISTS (SELECT 1 FROM system_notify_template
                                           WHERE code = item.code AND deleted = 0 AND status = 0
                                             AND content = item.content AND params = item.params) THEN
                RAISE EXCEPTION '0290 站内信模板 % 已存在但内容不同或已停用，请人工核查，禁止静默覆盖', item.code;
            END IF;
            CONTINUE;
        END IF;
        LOOP
            new_id := nextval(template_sequence);
            EXIT WHEN NOT EXISTS (SELECT 1 FROM system_notify_template WHERE id = new_id);
        END LOOP;
        INSERT INTO system_notify_template
            (id, name, code, nickname, content, type, params, status, remark, creator, updater, deleted)
        VALUES (new_id, item.name, item.code, '在线填报', item.content, 2, item.params, 0, '在线填报通知', '1', '1', 0);
    END LOOP;
END;
$fill_rollup_templates$;

COMMENT ON COLUMN dh_dataforge_fill_notify_record.notify_type IS '类型：1-指定填报人 2-转派/重新分配 3-任务开始 4-临近截止 5-已逾期 6-他人已提交 7-驳回 8-通过 9-合并成功 10-合并失败 11-催办 12-数据更正 13-截止延期 14-结束填报 15-合并失败退回整改 16-下级提交待审核 17-上级单位退回';
COMMENT ON COLUMN dh_dataforge_fill_assignee.source IS '来源：1-计划默认 2-发起者指定 3-转派接收 4-管理员重新分配 5-上级单位下发';

COMMIT;
