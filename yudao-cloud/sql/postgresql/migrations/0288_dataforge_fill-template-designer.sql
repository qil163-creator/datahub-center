-- Migration: 0288 填报模板设计：公式、表达式校验规则、录入布局与控件
-- Purpose: 1) dh_dataforge_fill_table_column 新增 formula_expr（行内公式，设置后为只读计算列，提交时由服务端重算）、
--             column_width（录入列宽）、header_group（表头分组，相邻同名字段合并为上一级表头）；
--             fill_type 注释补充 3-多选（多选取值为英文逗号分隔，提交与导入时逐项校验）；
--          2) dh_dataforge_fill_table 新增 freeze_column_count（录入页面冻结的前几列）；
--          3) 新建 dh_dataforge_fill_table_rule：模板级表达式校验规则（ROW 行级 / TABLE 表级汇总 / PERIOD 与上期比较），
--             发布时编译校验并固化进模板版本的 validationSchema，已发布版本不受影响。
--          4) dh_dataforge_fill_table_column 新增数据表下拉与级联配置（ref_database_id、ref_table_name、ref_value_column、
--             ref_parent_field、ref_parent_column；发布时读取取值固化进模板版本）与人员/部门选择范围 ref_scope；
--             fill_type 增加 4-人员 5-部门 6-附件 7-图片（单元格存编号）；
--          5) 新建 dh_dataforge_fill_attachment：附件、图片字段的附件登记（两阶段：PENDING 意图 → 受管文件回执核对后 READY；
--             提交时绑定提交快照，未绑定且过期、草稿不再引用的附件由 fillAttachmentCleanupJob 删除受管文件）。
--          附件上传复用 dataforge:fill-work-item:edit，下载复用填报、提交预览、任务查询、部门查看权限；
--          规则维护复用已有按钮 dataforge:fill-table:query / dataforge:fill-table:update，不新增菜单与权限。
-- Depends on: 0287_dataforge_fill-table-merge-strategy.sql（编号前驱）；dh_dataforge_fill_table、
--             dh_dataforge_fill_table_column 已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；列使用 ADD COLUMN IF NOT EXISTS，序列、表、索引带存在性保护，约束先删后建，注释可重复执行。
--             单事务执行；新增列可空或带常量默认值，不重写表（锁等待上限 10 秒，超时即失败，可稍后重试）。
-- Rollback: 附件表含已上传文件的登记，回退前须先确认受管文件的保留或清理方案；应用回退后可执行
--           DROP TABLE IF EXISTS "datahub-center".dh_dataforge_fill_attachment、dh_dataforge_fill_attachment_seq、
--           DROP TABLE IF EXISTS "datahub-center".dh_dataforge_fill_table_rule、
--           DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_fill_table_rule_seq，并删除上述四列；
--           会丢失尚未发布的公式、规则与布局配置，已发布模板版本保留各自固化的定义。旧版本应用不读写这些对象。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_dataforge_fill_table_column
    ADD COLUMN IF NOT EXISTS formula_expr varchar(1000) DEFAULT '',
    ADD COLUMN IF NOT EXISTS column_width int4,
    ADD COLUMN IF NOT EXISTS header_group varchar(50) DEFAULT '';
COMMENT ON COLUMN dh_dataforge_fill_table_column.formula_expr IS '行内公式（引用本表字段码）；设置后为只读计算列，提交时由服务端重算';
COMMENT ON COLUMN dh_dataforge_fill_table_column.column_width IS '录入页面列宽（像素，空值按默认宽度）';
ALTER TABLE dh_dataforge_fill_table_column
    ADD COLUMN IF NOT EXISTS ref_database_id int8,
    ADD COLUMN IF NOT EXISTS ref_table_name varchar(128) DEFAULT '',
    ADD COLUMN IF NOT EXISTS ref_value_column varchar(128) DEFAULT '',
    ADD COLUMN IF NOT EXISTS ref_parent_field varchar(64) DEFAULT '',
    ADD COLUMN IF NOT EXISTS ref_parent_column varchar(128) DEFAULT '',
    ADD COLUMN IF NOT EXISTS ref_scope varchar(16) DEFAULT '';
COMMENT ON COLUMN dh_dataforge_fill_table_column.fill_type IS '录入类型：1-手工录入 2-选择 3-多选（取值为英文逗号分隔） 4-人员 5-部门 6-附件 7-图片（单元格存编号）';
COMMENT ON COLUMN dh_dataforge_fill_table_column.ref_database_id IS '数据表下拉：取值来源数据源编号（发布时读取取值并固化进模板版本）';
COMMENT ON COLUMN dh_dataforge_fill_table_column.ref_table_name IS '数据表下拉：来源表名';
COMMENT ON COLUMN dh_dataforge_fill_table_column.ref_value_column IS '数据表下拉：取值列（去重后作为可选项）';
COMMENT ON COLUMN dh_dataforge_fill_table_column.ref_parent_field IS '级联：上级字段码（本模板内的另一个下拉字段）';
COMMENT ON COLUMN dh_dataforge_fill_table_column.ref_parent_column IS '级联：来源表中与上级字段取值对应的列';
COMMENT ON COLUMN dh_dataforge_fill_table_column.ref_scope IS '人员/部门选择范围：ORG-报送组织及下级（默认） ALL-全部启用的人员或部门';
COMMENT ON COLUMN dh_dataforge_fill_table_column.header_group IS '表头分组名称：相邻同名字段合并为上一级表头，空值表示不分组';

ALTER TABLE dh_dataforge_fill_table
    ADD COLUMN IF NOT EXISTS freeze_column_count int2 NOT NULL DEFAULT 0;
ALTER TABLE dh_dataforge_fill_table DROP CONSTRAINT IF EXISTS ck_dh_dataforge_fill_table_freeze_column_count;
ALTER TABLE dh_dataforge_fill_table
    ADD CONSTRAINT ck_dh_dataforge_fill_table_freeze_column_count CHECK (freeze_column_count BETWEEN 0 AND 10);
COMMENT ON COLUMN dh_dataforge_fill_table.freeze_column_count IS '录入页面冻结的前几列（0 表示不冻结）';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_fill_table_rule_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_fill_table_rule (
    id int8 NOT NULL DEFAULT nextval('dh_dataforge_fill_table_rule_seq'::regclass),
    table_id int8 NOT NULL,
    name varchar(100) NOT NULL,
    scope varchar(16) NOT NULL,
    expression varchar(1000) NOT NULL,
    message varchar(200) NOT NULL,
    severity int2 NOT NULL DEFAULT 1,
    field_code varchar(64) DEFAULT '',
    sort int4 NOT NULL DEFAULT 0,
    enabled int2 NOT NULL DEFAULT 1,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_dataforge_fill_table_rule PRIMARY KEY (id),
    CONSTRAINT ck_dh_dataforge_fill_table_rule_scope CHECK (scope IN ('ROW', 'TABLE', 'PERIOD')),
    CONSTRAINT ck_dh_dataforge_fill_table_rule_severity CHECK (severity IN (1, 2)),
    CONSTRAINT ck_dh_dataforge_fill_table_rule_enabled CHECK (enabled IN (0, 1))
);
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_fill_table_rule_table
    ON dh_dataforge_fill_table_rule (table_id) WHERE deleted = 0;
COMMENT ON TABLE dh_dataforge_fill_table_rule IS '数据填报-填报表校验规则（行级、表级、跨期表达式规则，发布时固化进模板版本）';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.id IS '主键id';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.table_id IS '填报表id';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.name IS '规则名称';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.scope IS '范围：ROW-行级 TABLE-表级（汇总） PERIOD-跨期（与上期比较）';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.expression IS '规则表达式（结果为假即违反；空值视为通过）';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.message IS '违反时的提示语';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.severity IS '级别：1-错误（阻断提交） 2-警告';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.field_code IS '问题定位字段码（行级规则高亮该单元格）';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.sort IS '排序';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.enabled IS '是否启用：0-否 1-是';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.creator IS '创建者';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.updater IS '更新者';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_fill_table_rule.deleted IS '是否删除';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_fill_attachment_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_fill_attachment (
    id int8 NOT NULL DEFAULT nextval('dh_dataforge_fill_attachment_seq'::regclass),
    work_item_id int8 NOT NULL,
    field_code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    content_type varchar(100) NOT NULL,
    size int8 NOT NULL,
    sha256 varchar(64) NOT NULL,
    operation_key varchar(128) NOT NULL,
    storage_file_id int8,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    uploader_user_id int8 NOT NULL,
    submission_id int8,
    expires_at timestamp(6),
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_dataforge_fill_attachment PRIMARY KEY (id),
    CONSTRAINT uk_dh_dataforge_fill_attachment_operation UNIQUE (operation_key),
    CONSTRAINT ck_dh_dataforge_fill_attachment_state CHECK (state IN ('PENDING', 'READY', 'DELETED')),
    CONSTRAINT ck_dh_dataforge_fill_attachment_size CHECK (size > 0)
);
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_fill_attachment_work_item
    ON dh_dataforge_fill_attachment (work_item_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_fill_attachment_cleanup
    ON dh_dataforge_fill_attachment (state, expires_at) WHERE deleted = 0 AND submission_id IS NULL;
COMMENT ON TABLE dh_dataforge_fill_attachment IS '在线填报-附件（附件、图片字段的单元格存附件编号；文件本体在 infra 受管文件中）';
COMMENT ON COLUMN dh_dataforge_fill_attachment.id IS '主键id';
COMMENT ON COLUMN dh_dataforge_fill_attachment.work_item_id IS '组织实例（工作项）编号';
COMMENT ON COLUMN dh_dataforge_fill_attachment.field_code IS '字段码';
COMMENT ON COLUMN dh_dataforge_fill_attachment.name IS '文件名';
COMMENT ON COLUMN dh_dataforge_fill_attachment.content_type IS '内容类型（图片按文件头识别）';
COMMENT ON COLUMN dh_dataforge_fill_attachment.size IS '文件大小（字节）';
COMMENT ON COLUMN dh_dataforge_fill_attachment.sha256 IS '内容 SHA-256，用于核对受管文件回执';
COMMENT ON COLUMN dh_dataforge_fill_attachment.operation_key IS '受管文件操作键（全局唯一，幂等上传与对账）';
COMMENT ON COLUMN dh_dataforge_fill_attachment.storage_file_id IS '受管文件编号（READY 后写入）';
COMMENT ON COLUMN dh_dataforge_fill_attachment.state IS '状态：PENDING-上传意图 READY-已上传并核对 DELETED-已删除';
COMMENT ON COLUMN dh_dataforge_fill_attachment.uploader_user_id IS '上传人编号';
COMMENT ON COLUMN dh_dataforge_fill_attachment.submission_id IS '绑定的提交快照编号（绑定后不再过期清理）';
COMMENT ON COLUMN dh_dataforge_fill_attachment.expires_at IS '未绑定附件的过期时间（过期且草稿不再引用时清理）';
COMMENT ON COLUMN dh_dataforge_fill_attachment.creator IS '创建者';
COMMENT ON COLUMN dh_dataforge_fill_attachment.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_fill_attachment.updater IS '更新者';
COMMENT ON COLUMN dh_dataforge_fill_attachment.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_fill_attachment.deleted IS '是否删除';

COMMIT;
