-- Migration: 0189
-- Purpose: 质量任务独立评测配置、历史报告关联和问题行处置审计。
-- Depends on: 已执行至 0188 的单租户平台结构，包含质量任务、对象、审计日志与问题汇总表。
-- Target: 已有数据库增量升级；新库由 datahub-center.sql 一次性安装。
-- Repeatable: 是；结构使用存在性判断，整个脚本在一个事务内提交。
-- Rollback: 无删除或回填业务数据。回滚应用前先备份新表；有评测或处置记录后不得直接删表。
-- 风险：新增外键和字段需短时表锁；请在低峰执行。问题行仅存授权投影的摘要，不写回源业务数据。
BEGIN;
SET LOCAL search_path TO "datahub-center", public;

-- 历史导出表仅保留非空编号，先补全编号唯一性才能建立可验证引用；若已有重复编号则整笔中止，禁止静默去重。
CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_task_id ON dh_governance_quality_task(id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_check_object_id ON dh_governance_quality_check_object(id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_issue_data_id ON dh_governance_quality_issue_data(id);
COMMENT ON INDEX uk_quality_task_id IS '质量任务编号全局唯一，支撑任务评测引用';
COMMENT ON INDEX uk_quality_check_object_id IS '稽查对象编号全局唯一，支撑任务评测引用';
COMMENT ON INDEX uk_quality_issue_data_id IS '问题汇总编号全局唯一，支撑行处置审计引用';

CREATE SEQUENCE IF NOT EXISTS dh_governance_quality_task_evaluation_seq;
COMMENT ON SEQUENCE dh_governance_quality_task_evaluation_seq IS '质量任务评测规则主键序列';
CREATE TABLE IF NOT EXISTS dh_governance_quality_task_evaluation (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_quality_task_evaluation_seq'),
    task_id bigint NOT NULL REFERENCES dh_governance_quality_task(id),
    object_id bigint NOT NULL REFERENCES dh_governance_quality_check_object(id),
    evaluation_name varchar(128) NOT NULL,
    rule_code varchar(64) NOT NULL,
    strategy_key varchar(64) NOT NULL,
    quality_dim varchar(32) NOT NULL,
    severity varchar(16) NOT NULL,
    status integer NOT NULL DEFAULT 0 CHECK (status IN (0, 1)),
    description varchar(256) NOT NULL DEFAULT '',
    error_example varchar(256) NOT NULL DEFAULT '',
    repair_advice varchar(256) NOT NULL DEFAULT '',
    where_clause varchar(256) NOT NULL DEFAULT '',
    column_names jsonb NOT NULL CHECK (jsonb_typeof(column_names) = 'array'),
    config jsonb NOT NULL CHECK (jsonb_typeof(config) = 'object'),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1))
);
COMMENT ON TABLE dh_governance_quality_task_evaluation IS '任务专属评测配置，执行时冻结为实例快照';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.id IS '评测配置编号';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.task_id IS '质量任务编号';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.object_id IS '本任务绑定的稽查对象编号';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.evaluation_name IS '任务内唯一评测名称';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.rule_code IS '稽查规则目录编码';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.strategy_key IS '质量组件执行策略';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.quality_dim IS '质量维度';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.severity IS '告警等级';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.status IS '规则状态：零上线，一下线';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.description IS '规则描述';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.error_example IS '错误示例';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.repair_advice IS '修复建议';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.where_clause IS '经质量组件解析的只读筛选条件';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.column_names IS '检查字段名数组';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.config IS '规则参数；精确数值使用十进制字符串';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.creator IS '创建人';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.updater IS '更新人';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_quality_task_evaluation.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_task_evaluation_name
    ON dh_governance_quality_task_evaluation(task_id, evaluation_name) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_quality_task_evaluation_object ON dh_governance_quality_task_evaluation(object_id);

ALTER TABLE dh_governance_quality_audit_log ADD COLUMN IF NOT EXISTS evaluation_id bigint;
-- 多字段评测与限定 schema 的物理表名可能超过历史单字段长度；只放宽类型，不改写已有值。
ALTER TABLE dh_governance_quality_audit_log ALTER COLUMN check_field TYPE text,
    ALTER COLUMN table_name TYPE varchar(512);
ALTER TABLE dh_governance_quality_issue_data ALTER COLUMN check_field TYPE text,
    ALTER COLUMN table_name TYPE varchar(512);
COMMENT ON COLUMN dh_governance_quality_audit_log.evaluation_id IS '执行时任务评测编号，具体配置以实例快照为准';
CREATE INDEX IF NOT EXISTS idx_quality_audit_evaluation ON dh_governance_quality_audit_log(evaluation_id);

CREATE SEQUENCE IF NOT EXISTS dh_governance_quality_issue_row_state_seq;
COMMENT ON SEQUENCE dh_governance_quality_issue_row_state_seq IS '质量问题行处置主键序列';
CREATE TABLE IF NOT EXISTS dh_governance_quality_issue_row_state (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_quality_issue_row_state_seq'),
    issue_id bigint NOT NULL REFERENCES dh_governance_quality_issue_data(id),
    row_key varchar(64) NOT NULL CHECK (row_key ~ '^[0-9a-f]{64}$'),
    state varchar(16) NOT NULL DEFAULT 'UNPROCESSED' CHECK (state IN ('UNPROCESSED', 'IGNORED')),
    revision integer NOT NULL DEFAULT 0 CHECK (revision >= 0),
    remark varchar(500) NOT NULL DEFAULT '',
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
    CONSTRAINT uk_quality_issue_row_state UNIQUE (issue_id, row_key)
);
COMMENT ON TABLE dh_governance_quality_issue_row_state IS '报告问题行授权投影的处置状态；内容或投影变化不继承旧结论';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.id IS '处置状态编号';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.issue_id IS '问题汇总编号';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.row_key IS '报告、执行快照、当前用户、权限策略指纹及脱敏后投影的摘要';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.state IS '未处理或已忽略';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.revision IS '乐观并发修订号';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.remark IS '当前处置备注';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.creator IS '创建人';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.updater IS '更新人';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_quality_issue_row_state.deleted IS '逻辑删除标记';

CREATE SEQUENCE IF NOT EXISTS dh_governance_quality_issue_row_audit_seq;
COMMENT ON SEQUENCE dh_governance_quality_issue_row_audit_seq IS '质量问题行审计主键序列';
CREATE TABLE IF NOT EXISTS dh_governance_quality_issue_row_audit (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_quality_issue_row_audit_seq'),
    issue_id bigint NOT NULL REFERENCES dh_governance_quality_issue_data(id),
    row_key varchar(64) NOT NULL CHECK (row_key ~ '^[0-9a-f]{64}$'),
    actor_id bigint NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('IGNORE', 'REMARK')),
    before_state varchar(16) NOT NULL CHECK (before_state IN ('UNPROCESSED', 'IGNORED')),
    after_state varchar(16) NOT NULL CHECK (after_state IN ('UNPROCESSED', 'IGNORED')),
    before_revision integer NOT NULL CHECK (before_revision >= 0),
    after_revision integer NOT NULL CHECK (after_revision = before_revision + 1),
    remark_digest varchar(64) NOT NULL CHECK (remark_digest ~ '^[0-9a-f]{64}$'),
    correlation_id varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quality_issue_row_audit_revision UNIQUE (issue_id, row_key, after_revision)
);
COMMENT ON TABLE dh_governance_quality_issue_row_audit IS '与处置状态原子提交的成功操作审计，只追加、不覆盖';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.id IS '审计编号';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.issue_id IS '问题汇总编号';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.row_key IS '被处置授权投影摘要';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.actor_id IS '当前登录操作人';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.action IS '忽略或添加备注动作';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.before_state IS '操作前状态';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.after_state IS '操作后状态';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.before_revision IS '操作前修订号';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.after_revision IS '操作后修订号';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.remark_digest IS '本次备注摘要，不复制备注明文';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.correlation_id IS '同批操作关联编号';
COMMENT ON COLUMN dh_governance_quality_issue_row_audit.create_time IS '成功提交时间';
CREATE INDEX IF NOT EXISTS idx_quality_issue_row_audit_time ON dh_governance_quality_issue_row_audit(create_time);
-- 复用现有质量任务、质量报告查询和问题处置权限；不扩大菜单授权。
COMMIT;
