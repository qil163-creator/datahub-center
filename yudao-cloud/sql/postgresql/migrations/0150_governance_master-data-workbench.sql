-- Migration: 0150 主数据域与维护工作台
-- Purpose: 增加主数据域、编码规则、数据变更审批及变更日志，迁移历史模型归属并拆分六个页面入口。
-- Depends on: 0149_datahub_concise-table-comments.sql 已执行；主数据目录、模型及系统菜单的当前基线结构存在。
-- Target: 已有 PostgreSQL 平台库，固定结构为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；新增结构、约束、索引、归属回填和菜单按存在性处理，重放不覆盖新增域、审批数据或角色授权。
-- Rollback: 失败整笔回滚；提交后回退应用可保留新增结构，旧目录及目录编号均保留。删除新增表或列前须备份域、规则、审批和日志。
-- 加列、回填和索引会短时锁表；锁等待超过十秒或执行超过两分钟则回滚。历史有效模型共用同一物理表时明确失败，须先核对绑定。
-- 不读取参考系统账号、数据源凭据或演示数据；仅登记权限，不新增角色及套餐授权。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_governance_master_domain_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_domain (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_domain_seq'),
    code varchar(100) NOT NULL,
    name varchar(100) NOT NULL,
    icon varchar(100),
    description varchar(500),
    sort integer NOT NULL DEFAULT 99,
    status smallint NOT NULL DEFAULT 0,
    legacy_catalog_id bigint,
    tenant_id bigint NOT NULL DEFAULT 1,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_domain_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_master_domain_sort CHECK (sort >= 0)
);
ALTER SEQUENCE dh_governance_master_domain_seq OWNED BY dh_governance_master_domain.id;
COMMENT ON TABLE dh_governance_master_domain IS '主数据管理-主数据域';
COMMENT ON COLUMN dh_governance_master_domain.id IS '主数据域编号';
COMMENT ON COLUMN dh_governance_master_domain.code IS '域编码';
COMMENT ON COLUMN dh_governance_master_domain.name IS '域名称';
COMMENT ON COLUMN dh_governance_master_domain.icon IS '域图标';
COMMENT ON COLUMN dh_governance_master_domain.description IS '域描述';
COMMENT ON COLUMN dh_governance_master_domain.sort IS '排序号';
COMMENT ON COLUMN dh_governance_master_domain.status IS '状态：零启用、一停用';
COMMENT ON COLUMN dh_governance_master_domain.legacy_catalog_id IS '迁移前目录编号';
COMMENT ON COLUMN dh_governance_master_domain.tenant_id IS '租户编号';
COMMENT ON COLUMN dh_governance_master_domain.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_domain.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_domain.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_domain.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_domain.deleted IS '是否删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_domain_tenant_id ON dh_governance_master_domain (tenant_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_domain_code ON dh_governance_master_domain (tenant_id, code) WHERE deleted = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_domain_name ON dh_governance_master_domain (tenant_id, name) WHERE deleted = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_domain_legacy ON dh_governance_master_domain (tenant_id, legacy_catalog_id) WHERE legacy_catalog_id IS NOT NULL;

ALTER TABLE dh_governance_master_model
    ADD COLUMN IF NOT EXISTS domain_id bigint,
    ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS approval_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS approval_process_key varchar(100),
    ADD COLUMN IF NOT EXISTS publish_state varchar(16) NOT NULL DEFAULT 'READY',
    ADD COLUMN IF NOT EXISTS publish_message varchar(500);
ALTER TABLE dh_governance_master_model ALTER COLUMN catalog_id DROP NOT NULL;
COMMENT ON COLUMN dh_governance_master_model.domain_id IS '所属主数据域编号';
COMMENT ON COLUMN dh_governance_master_model.catalog_id IS '历史目录编号';
COMMENT ON COLUMN dh_governance_master_model.version IS '已发布结构版本';
COMMENT ON COLUMN dh_governance_master_model.approval_enabled IS '数据变更是否需要审批';
COMMENT ON COLUMN dh_governance_master_model.approval_process_key IS '可选审批流程标识';
COMMENT ON COLUMN dh_governance_master_model.publish_state IS '建表结果：就绪、执行中或待核查';
COMMENT ON COLUMN dh_governance_master_model.publish_message IS '建表结果说明';
COMMENT ON COLUMN dh_governance_master_model.status IS '模型状态：零草稿、一已发布、二已下线';
COMMENT ON COLUMN dh_governance_master_model.org_id IS '所属机构编号';
COMMENT ON COLUMN dh_governance_master_column.field_type IS '字段类型：一近似数、二字符串、三日期、四布尔、五整数、六精确小数、九日期时间、十时间';

-- 同租户旧主数据节点逐个转为域；重复名称保留原名称主体并附目录编号，不丢失节点和模型。
DO $legacy_domains$
DECLARE
    legacy record;
    domain_name varchar(100);
    suffix_no integer;
BEGIN
    FOR legacy IN SELECT * FROM dh_governance_master_catalog WHERE type = 1 AND deleted = 0 ORDER BY tenant_id, id LOOP
        IF EXISTS (SELECT 1 FROM dh_governance_master_domain WHERE tenant_id = legacy.tenant_id AND legacy_catalog_id = legacy.id) THEN
            CONTINUE;
        END IF;
        domain_name := legacy.name;
        suffix_no := 0;
        WHILE EXISTS (SELECT 1 FROM dh_governance_master_domain WHERE tenant_id = legacy.tenant_id AND name = domain_name AND deleted = 0) LOOP
            suffix_no := suffix_no + 1;
            domain_name := left(legacy.name, 65) || '（旧目录' || legacy.id || '-' || suffix_no || '）';
        END LOOP;
        INSERT INTO dh_governance_master_domain (code, name, description, sort, legacy_catalog_id, tenant_id, creator, updater)
        VALUES ('LEGACY_' || legacy.id, domain_name, legacy.description, greatest(legacy.order_no, 0), legacy.id, legacy.tenant_id,
                'migration:0150', 'migration:0150');
    END LOOP;
END $legacy_domains$;

UPDATE dh_governance_master_model model SET domain_id = domain.id
FROM dh_governance_master_domain domain
WHERE model.domain_id IS NULL AND domain.tenant_id = model.tenant_id AND domain.legacy_catalog_id = model.catalog_id;

-- 目录已删除、不属主数据节点或缺失时按租户创建兜底域；逻辑删除模型也保留可恢复的归属。
DO $unassigned_domains$
DECLARE
    tenant record;
    domain_id_value bigint;
    domain_name varchar(100);
    suffix_no integer;
BEGIN
    FOR tenant IN SELECT DISTINCT tenant_id FROM dh_governance_master_model WHERE domain_id IS NULL ORDER BY tenant_id LOOP
        SELECT id INTO domain_id_value FROM dh_governance_master_domain
        WHERE tenant_id = tenant.tenant_id AND code = 'LEGACY_UNASSIGNED' AND deleted = 0;
        IF domain_id_value IS NULL THEN
            domain_name := '历史未归属主数据域';
            suffix_no := 0;
            WHILE EXISTS (SELECT 1 FROM dh_governance_master_domain WHERE tenant_id = tenant.tenant_id AND name = domain_name AND deleted = 0) LOOP
                suffix_no := suffix_no + 1;
                domain_name := '历史未归属主数据域（' || suffix_no || '）';
            END LOOP;
            INSERT INTO dh_governance_master_domain (code, name, description, tenant_id, creator, updater)
            VALUES ('LEGACY_UNASSIGNED', domain_name, '保留历史未有效关联目录的模型，后续可重新选择主数据域', tenant.tenant_id,
                    'migration:0150', 'migration:0150') RETURNING id INTO domain_id_value;
        END IF;
        UPDATE dh_governance_master_model SET domain_id = domain_id_value WHERE tenant_id = tenant.tenant_id AND domain_id IS NULL;
    END LOOP;
END $unassigned_domains$;
ALTER TABLE dh_governance_master_model ALTER COLUMN domain_id SET NOT NULL;

DO $model_binding_preflight$
BEGIN
    IF EXISTS (SELECT 1 FROM dh_governance_master_model WHERE deleted = 0 GROUP BY database_id, table_name HAVING count(*) > 1) THEN
        RAISE EXCEPTION '0150 存在有效模型共用物理表绑定，请核对数据源和表名后重试；迁移不会删除或合并模型';
    END IF;
END $model_binding_preflight$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_model_tenant_id ON dh_governance_master_model (tenant_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_model_physical_binding ON dh_governance_master_model (database_id, table_name) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_master_model_domain ON dh_governance_master_model (tenant_id, domain_id, status, id DESC) WHERE deleted = 0;

CREATE SEQUENCE IF NOT EXISTS dh_governance_master_code_rule_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_code_rule (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_code_rule_seq'),
    model_id bigint NOT NULL,
    code varchar(100) NOT NULL,
    name varchar(100) NOT NULL,
    field_name varchar(100) NOT NULL,
    description varchar(500),
    segments_json text NOT NULL,
    status smallint NOT NULL DEFAULT 1,
    tenant_id bigint NOT NULL DEFAULT 1,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_code_rule_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_master_code_rule_segments CHECK (jsonb_typeof(segments_json::jsonb) = 'array'),
    CONSTRAINT fk_master_code_rule_model FOREIGN KEY (tenant_id, model_id) REFERENCES dh_governance_master_model (tenant_id, id)
);
ALTER SEQUENCE dh_governance_master_code_rule_seq OWNED BY dh_governance_master_code_rule.id;
COMMENT ON TABLE dh_governance_master_code_rule IS '主数据管理-编码规则';
COMMENT ON COLUMN dh_governance_master_code_rule.id IS '编码规则编号';
COMMENT ON COLUMN dh_governance_master_code_rule.model_id IS '所属模型编号';
COMMENT ON COLUMN dh_governance_master_code_rule.code IS '规则编码';
COMMENT ON COLUMN dh_governance_master_code_rule.name IS '规则名称';
COMMENT ON COLUMN dh_governance_master_code_rule.field_name IS '自动编码字段名';
COMMENT ON COLUMN dh_governance_master_code_rule.description IS '规则描述';
COMMENT ON COLUMN dh_governance_master_code_rule.segments_json IS '有序编码段配置';
COMMENT ON COLUMN dh_governance_master_code_rule.status IS '状态：零启用、一停用';
COMMENT ON COLUMN dh_governance_master_code_rule.tenant_id IS '租户编号';
COMMENT ON COLUMN dh_governance_master_code_rule.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_code_rule.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_code_rule.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_code_rule.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_code_rule.deleted IS '是否删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_code_rule_code ON dh_governance_master_code_rule (tenant_id, code) WHERE deleted = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_code_rule_enabled ON dh_governance_master_code_rule (tenant_id, model_id) WHERE deleted = 0 AND status = 0;
CREATE INDEX IF NOT EXISTS idx_master_code_rule_model ON dh_governance_master_code_rule (tenant_id, model_id, id DESC) WHERE deleted = 0;

CREATE SEQUENCE IF NOT EXISTS dh_governance_master_code_counter_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_code_counter (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_code_counter_seq'),
    model_id bigint NOT NULL,
    next_value bigint NOT NULL DEFAULT 1,
    tenant_id bigint NOT NULL DEFAULT 1,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_code_counter_positive CHECK (next_value > 0),
    CONSTRAINT fk_master_code_counter_model FOREIGN KEY (tenant_id, model_id) REFERENCES dh_governance_master_model (tenant_id, id)
);
ALTER SEQUENCE dh_governance_master_code_counter_seq OWNED BY dh_governance_master_code_counter.id;
COMMENT ON TABLE dh_governance_master_code_counter IS '主数据管理-编码流水';
COMMENT ON COLUMN dh_governance_master_code_counter.id IS '编码流水编号';
COMMENT ON COLUMN dh_governance_master_code_counter.model_id IS '所属模型编号';
COMMENT ON COLUMN dh_governance_master_code_counter.next_value IS '下一流水号';
COMMENT ON COLUMN dh_governance_master_code_counter.tenant_id IS '租户编号';
COMMENT ON COLUMN dh_governance_master_code_counter.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_code_counter.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_code_counter.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_code_counter.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_code_counter.deleted IS '是否删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_code_counter_model ON dh_governance_master_code_counter (tenant_id, model_id) WHERE deleted = 0;

CREATE SEQUENCE IF NOT EXISTS dh_governance_master_data_change_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_data_change (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_data_change_seq'),
    model_id bigint NOT NULL,
    request_id varchar(36) NOT NULL,
    operation varchar(16) NOT NULL,
    status varchar(24) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,
    payload_hash varchar(64) NOT NULL,
    rows_json text NOT NULL,
    before_json text NOT NULL,
    row_keys_json text NOT NULL,
    applicant_id bigint NOT NULL,
    approval_required boolean NOT NULL,
    bpm_process_key varchar(100),
    process_instance_id varchar(64),
    reviewer_id bigint,
    review_time timestamp,
    review_comment varchar(500),
    last_error varchar(500),
    apply_actor_id bigint,
    apply_token varchar(36),
    apply_started_at timestamp,
    applied_at timestamp,
    resolution_outcome varchar(16),
    resolution_reason varchar(500),
    resolution_evidence varchar(200),
    resolved_by bigint,
    resolved_at timestamp,
    tenant_id bigint NOT NULL DEFAULT 1,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_data_change_operation CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT ck_master_data_change_status CHECK (status IN ('WAIT_START', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'APPLYING', 'UNKNOWN', 'APPLIED')),
    CONSTRAINT ck_master_data_change_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_master_data_change_rows CHECK (jsonb_typeof(rows_json::jsonb) = 'array'),
    CONSTRAINT ck_master_data_change_before CHECK (jsonb_typeof(before_json::jsonb) = 'array'),
    CONSTRAINT ck_master_data_change_keys CHECK (jsonb_typeof(row_keys_json::jsonb) = 'array'),
    CONSTRAINT fk_master_data_change_model FOREIGN KEY (tenant_id, model_id) REFERENCES dh_governance_master_model (tenant_id, id)
);
ALTER SEQUENCE dh_governance_master_data_change_seq OWNED BY dh_governance_master_data_change.id;
COMMENT ON TABLE dh_governance_master_data_change IS '主数据管理-数据变更审批';
COMMENT ON COLUMN dh_governance_master_data_change.id IS '数据变更编号';
COMMENT ON COLUMN dh_governance_master_data_change.model_id IS '所属模型编号';
COMMENT ON COLUMN dh_governance_master_data_change.request_id IS '客户端幂等请求编号';
COMMENT ON COLUMN dh_governance_master_data_change.operation IS '数据变更类型';
COMMENT ON COLUMN dh_governance_master_data_change.status IS '审批与应用状态';
COMMENT ON COLUMN dh_governance_master_data_change.schema_version IS '申请时模型结构版本';
COMMENT ON COLUMN dh_governance_master_data_change.payload_hash IS '申请内容摘要';
COMMENT ON COLUMN dh_governance_master_data_change.rows_json IS '有序申请行快照';
COMMENT ON COLUMN dh_governance_master_data_change.before_json IS '同序变更前行快照';
COMMENT ON COLUMN dh_governance_master_data_change.row_keys_json IS '同序行主键快照';
COMMENT ON COLUMN dh_governance_master_data_change.applicant_id IS '申请人编号';
COMMENT ON COLUMN dh_governance_master_data_change.approval_required IS '是否需要审批';
COMMENT ON COLUMN dh_governance_master_data_change.bpm_process_key IS '申请时审批流程标识';
COMMENT ON COLUMN dh_governance_master_data_change.process_instance_id IS '审批流程实例编号';
COMMENT ON COLUMN dh_governance_master_data_change.reviewer_id IS '审批人编号';
COMMENT ON COLUMN dh_governance_master_data_change.review_time IS '审批时间';
COMMENT ON COLUMN dh_governance_master_data_change.review_comment IS '审批意见';
COMMENT ON COLUMN dh_governance_master_data_change.last_error IS '最近失败说明';
COMMENT ON COLUMN dh_governance_master_data_change.apply_actor_id IS '实际应用执行人编号';
COMMENT ON COLUMN dh_governance_master_data_change.apply_token IS '应用执行令牌';
COMMENT ON COLUMN dh_governance_master_data_change.apply_started_at IS '开始应用时间';
COMMENT ON COLUMN dh_governance_master_data_change.applied_at IS '成功应用时间';
COMMENT ON COLUMN dh_governance_master_data_change.resolution_outcome IS '未知结果核查结论';
COMMENT ON COLUMN dh_governance_master_data_change.resolution_reason IS '未知结果核查原因';
COMMENT ON COLUMN dh_governance_master_data_change.resolution_evidence IS '未知结果核查凭据';
COMMENT ON COLUMN dh_governance_master_data_change.resolved_by IS '未知结果核查人编号';
COMMENT ON COLUMN dh_governance_master_data_change.resolved_at IS '未知结果核查时间';
COMMENT ON COLUMN dh_governance_master_data_change.tenant_id IS '租户编号';
COMMENT ON COLUMN dh_governance_master_data_change.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_data_change.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_data_change.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_data_change.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_data_change.deleted IS '是否删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_data_change_request ON dh_governance_master_data_change (tenant_id, request_id) WHERE deleted = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_data_change_tenant_id ON dh_governance_master_data_change (tenant_id, id);
CREATE INDEX IF NOT EXISTS idx_master_data_change_query ON dh_governance_master_data_change (tenant_id, model_id, status, id DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_master_data_change_process ON dh_governance_master_data_change (tenant_id, process_instance_id) WHERE deleted = 0 AND process_instance_id IS NOT NULL;

DO $change_constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_master_data_change_operation' AND conrelid = 'dh_governance_master_data_change'::regclass) THEN
        ALTER TABLE dh_governance_master_data_change ADD CONSTRAINT ck_master_data_change_operation CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_master_data_change_status' AND conrelid = 'dh_governance_master_data_change'::regclass) THEN
        ALTER TABLE dh_governance_master_data_change ADD CONSTRAINT ck_master_data_change_status CHECK (status IN ('WAIT_START', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'APPLYING', 'UNKNOWN', 'APPLIED'));
    END IF;
END $change_constraints$;

CREATE SEQUENCE IF NOT EXISTS dh_governance_master_change_log_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_change_log (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_change_log_seq'),
    change_id bigint NOT NULL,
    model_id bigint NOT NULL,
    row_ordinal integer NOT NULL,
    data_id varchar(1000) NOT NULL,
    change_type varchar(16) NOT NULL,
    property_name varchar(100) NOT NULL,
    before_value text,
    after_value text,
    changed_by bigint NOT NULL,
    changed_at timestamp NOT NULL,
    tenant_id bigint NOT NULL DEFAULT 1,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_change_log_ordinal CHECK (row_ordinal >= 0),
    CONSTRAINT fk_master_change_log_change FOREIGN KEY (tenant_id, change_id) REFERENCES dh_governance_master_data_change (tenant_id, id),
    CONSTRAINT fk_master_change_log_model FOREIGN KEY (tenant_id, model_id) REFERENCES dh_governance_master_model (tenant_id, id)
);
ALTER SEQUENCE dh_governance_master_change_log_seq OWNED BY dh_governance_master_change_log.id;
COMMENT ON TABLE dh_governance_master_change_log IS '主数据管理-变更日志';
COMMENT ON COLUMN dh_governance_master_change_log.id IS '变更日志编号';
COMMENT ON COLUMN dh_governance_master_change_log.change_id IS '数据变更编号';
COMMENT ON COLUMN dh_governance_master_change_log.model_id IS '所属模型编号';
COMMENT ON COLUMN dh_governance_master_change_log.row_ordinal IS '申请行序号';
COMMENT ON COLUMN dh_governance_master_change_log.data_id IS '数据行标识';
COMMENT ON COLUMN dh_governance_master_change_log.change_type IS '变更类型';
COMMENT ON COLUMN dh_governance_master_change_log.property_name IS '变更字段名称';
COMMENT ON COLUMN dh_governance_master_change_log.before_value IS '变更前值';
COMMENT ON COLUMN dh_governance_master_change_log.after_value IS '变更后值';
COMMENT ON COLUMN dh_governance_master_change_log.changed_by IS '变更人编号';
COMMENT ON COLUMN dh_governance_master_change_log.changed_at IS '变更时间';
COMMENT ON COLUMN dh_governance_master_change_log.tenant_id IS '租户编号';
COMMENT ON COLUMN dh_governance_master_change_log.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_change_log.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_change_log.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_change_log.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_change_log.deleted IS '是否删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_change_log_property ON dh_governance_master_change_log (tenant_id, change_id, row_ordinal, property_name);
CREATE INDEX IF NOT EXISTS idx_master_change_log_query ON dh_governance_master_change_log (tenant_id, model_id, change_type, changed_at DESC, id DESC) WHERE deleted = 0;

-- 复合引用保证模型与域、规则和变更账本处于同一租户；已有模型回填后再建立约束。
DO $model_constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_master_model_domain' AND conrelid = 'dh_governance_master_model'::regclass) THEN
        ALTER TABLE dh_governance_master_model ADD CONSTRAINT fk_master_model_domain
            FOREIGN KEY (tenant_id, domain_id) REFERENCES dh_governance_master_domain (tenant_id, id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_master_model_status' AND conrelid = 'dh_governance_master_model'::regclass) THEN
        ALTER TABLE dh_governance_master_model ADD CONSTRAINT ck_master_model_status CHECK (status IN (0, 1, 2));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_master_model_version' AND conrelid = 'dh_governance_master_model'::regclass) THEN
        ALTER TABLE dh_governance_master_model ADD CONSTRAINT ck_master_model_version CHECK (version > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_master_model_publish_state' AND conrelid = 'dh_governance_master_model'::regclass) THEN
        ALTER TABLE dh_governance_master_model ADD CONSTRAINT ck_master_model_publish_state CHECK (publish_state IN ('READY', 'APPLYING', 'UNKNOWN'));
    END IF;
END $model_constraints$;

-- 沿用原模型菜单编号和授权，按实际父菜单补齐新页面，避免固定编号碰撞。
DO $menu_preflight$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM system_menu WHERE type = 2 AND deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'governance/masterdata/index') THEN
        RAISE EXCEPTION '0150 缺少主数据模型菜单，请先完成当前平台基线或等价升级';
    END IF;
END $menu_preflight$;
LOCK TABLE system_menu IN SHARE ROW EXCLUSIVE MODE;
SELECT setval('"datahub-center".system_menu_id_seq',
              greatest((SELECT last_value FROM "datahub-center".system_menu_id_seq), coalesce((SELECT max(id) FROM system_menu), 1)), true);

UPDATE system_menu SET name = '主数据模型', sort = 2, updater = 'migration:0150', update_time = CURRENT_TIMESTAMP
WHERE type = 2 AND deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'governance/masterdata/index';
UPDATE system_menu SET sort = 7, updater = 'migration:0150', update_time = CURRENT_TIMESTAMP
WHERE type = 2 AND deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'governance/masterdistribute/index';

INSERT INTO system_menu (name, permission, type, sort, parent_id, path, icon, component, component_name, status, creator, updater)
SELECT page.name, page.permission, 2, page.sort, model.parent_id, page.path, page.icon, page.component, page.component_name, 0, 'migration:0150', 'migration:0150'
FROM (VALUES
    ('主数据域', 'governance:master-domain:query', 1, 'masterdomain', 'carbon:folder', 'governance/masterdomain/index', 'GovernanceMasterDomain'),
    ('主数据维护', 'governance:master-data:query', 3, 'mastermaintain', 'carbon:data-table', 'governance/mastermaintain/index', 'GovernanceMasterMaintain'),
    ('编码规则', 'governance:master-code-rule:query', 4, 'mastercoderule', 'carbon:barcode', 'governance/mastercoderule/index', 'GovernanceMasterCodeRule'),
    ('主数据审批', 'governance:master-approval:query', 5, 'masterapproval', 'carbon:task-approved', 'governance/masterapproval/index', 'GovernanceMasterApproval'),
    ('变更日志', 'governance:master-change-log:query', 6, 'masterchangelog', 'carbon:change-catalog', 'governance/masterchangelog/index', 'GovernanceMasterChangeLog')
) page(name, permission, sort, path, icon, component, component_name)
JOIN LATERAL (SELECT parent_id FROM system_menu WHERE type = 2 AND deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'governance/masterdata/index' ORDER BY id LIMIT 1) model ON true
WHERE NOT EXISTS (SELECT 1 FROM system_menu existing WHERE existing.type = 2 AND existing.deleted = 0 AND trim(both '/' from coalesce(existing.component, '')) = page.component);

-- 数据按钮移到维护页面，保留原编号及权限；不改已有角色菜单关系。
UPDATE system_menu button SET parent_id = page.id, updater = 'migration:0150', update_time = CURRENT_TIMESTAMP
FROM system_menu page
WHERE page.type = 2 AND page.deleted = 0 AND trim(both '/' from coalesce(page.component, '')) = 'governance/mastermaintain/index'
  AND button.type = 3 AND button.deleted = 0 AND button.permission IN ('governance:master-data:query', 'governance:master-data:create', 'governance:master-data:update', 'governance:master-data:delete');

INSERT INTO system_menu (name, permission, type, sort, parent_id, status, creator, updater)
SELECT button.name, button.permission, 3, button.sort, page.id, 0, 'migration:0150', 'migration:0150'
FROM (VALUES
    ('主数据域新增', 'governance:master-domain:create', 1, 'governance/masterdomain/index'),
    ('主数据域修改', 'governance:master-domain:update', 2, 'governance/masterdomain/index'),
    ('主数据域删除', 'governance:master-domain:delete', 3, 'governance/masterdomain/index'),
    ('编码规则新增', 'governance:master-code-rule:create', 1, 'governance/mastercoderule/index'),
    ('编码规则修改', 'governance:master-code-rule:update', 2, 'governance/mastercoderule/index'),
    ('编码规则删除', 'governance:master-code-rule:delete', 3, 'governance/mastercoderule/index'),
    ('审批通过', 'governance:master-approval:approve', 1, 'governance/masterapproval/index'),
    ('审批驳回', 'governance:master-approval:reject', 2, 'governance/masterapproval/index'),
    ('审批流程对账', 'governance:master-approval:reconcile', 3, 'governance/masterapproval/index'),
    ('数据变更结果核查', 'governance:master-approval:resolve', 4, 'governance/masterapproval/index')
) button(name, permission, sort, component)
JOIN system_menu page ON page.type = 2 AND page.deleted = 0 AND trim(both '/' from coalesce(page.component, '')) = button.component
WHERE NOT EXISTS (SELECT 1 FROM system_menu existing WHERE existing.deleted = 0 AND existing.permission = button.permission);

COMMIT;
