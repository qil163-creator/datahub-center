-- Migration: 0203 拆分稽查规则的独立质量维度并恢复原有规则关联。
-- Purpose: 稽查维度与业务数据质量类目完全独立；建立五个维度，恢复原二十八条规则按业务编码保存的原关联。
-- Depends on: 0202_asset_water-market-categories.sql 已执行；稽查规则表及其原有业务编码已就绪。
-- Target: PostgreSQL 平台库的 datahub-center schema；未知的未分类或非法维度引用须先根据执行前备份明确恢复，否则本迁移整体拒绝执行。
-- Repeatable: 是；仅补齐缺失维度和修复由 0201 置空的已知规则，不覆盖后续人工维护的名称、排序、状态或有效规则关联。
-- Rollback: 执行失败整体回滚；提交后须协调应用版本，从执行前备份恢复规则关联及审计字段和维度约束，再按实际引用决定是否移除新表，保留序列高水位。
-- 数据来源：原全量基线中的二十八条稽查规则编码及原五维度关联，已与 0201 执行前备份逐项核对；不按规则名称推断或重新划分类别。
-- 本迁移不读取、修改业务数据质量类目、质量任务或集市类目；不改规则策略、参数、内容、状态及历史快照。
-- 执行前备份稽查规则及独立维度表（如已存在）；事务内锁表，锁等待十秒、单语句两分钟超时即整体回滚。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_governance_audit_rule_dimension_seq;
CREATE TABLE IF NOT EXISTS dh_governance_audit_rule_dimension (
    id bigint NOT NULL DEFAULT nextval('dh_governance_audit_rule_dimension_seq'::regclass),
    name varchar(64) NOT NULL,
    code varchar(64) NOT NULL,
    status smallint NOT NULL DEFAULT 0,
    sort integer NOT NULL DEFAULT 0,
    remark varchar(500),
    creator varchar(64) DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_audit_rule_dimension PRIMARY KEY (id),
    CONSTRAINT uk_dh_governance_audit_rule_dimension_code UNIQUE (code),
    CONSTRAINT ck_dh_governance_audit_rule_dimension_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_dh_governance_audit_rule_dimension_sort CHECK (sort >= 0),
    CONSTRAINT ck_dh_governance_audit_rule_dimension_deleted CHECK (deleted IN (0, 1))
);
COMMENT ON TABLE dh_governance_audit_rule_dimension IS '稽查规则独立质量维度；与业务数据质量类目无关联';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.id IS '稽查规则维度编号';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.name IS '维度名称';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.code IS '维度稳定业务编码';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.status IS '状态：零启用，一停用';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.sort IS '显示顺序';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.remark IS '备注';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.creator IS '创建人';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.updater IS '更新人';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_audit_rule_dimension.deleted IS '逻辑删除标记：零有效，一删除';

LOCK TABLE dh_governance_audit_rule, dh_governance_audit_rule_dimension IN SHARE ROW EXCLUSIVE MODE;
ALTER SEQUENCE dh_governance_audit_rule_dimension_seq OWNED BY dh_governance_audit_rule_dimension.id;

CREATE TEMP TABLE audit_rule_dimension_seed (code varchar(64), name varchar(64), sort integer) ON COMMIT DROP;
INSERT INTO audit_rule_dimension_seed (code, name, sort) VALUES
    ('D05', '准确性', 0),
    ('D03', '规范性', 1),
    ('D04', '时效性', 2),
    ('D01', '一致性', 3),
    ('D02', '完整性', 4);

DO $seed_dimensions$
DECLARE
    item record;
    missing_count bigint;
    first_id numeric;
    sequence_value bigint;
    sequence_called boolean;
    sequence_increment bigint;
    sequence_max bigint;
    sequence_cycles boolean;
BEGIN
    SELECT seqincrement, seqmax, seqcycle INTO sequence_increment, sequence_max, sequence_cycles
    FROM pg_sequence WHERE seqrelid = 'dh_governance_audit_rule_dimension_seq'::regclass;
    IF sequence_increment <> 1 OR sequence_cycles THEN
        RAISE EXCEPTION '稽查维度编号序列必须使用步长一且禁止循环';
    END IF;
    SELECT count(*) INTO missing_count FROM audit_rule_dimension_seed seed
    WHERE NOT EXISTS (SELECT 1 FROM dh_governance_audit_rule_dimension dimension WHERE dimension.code = seed.code);
    IF missing_count > 0 THEN
        SELECT last_value, is_called INTO sequence_value, sequence_called FROM dh_governance_audit_rule_dimension_seq;
        SELECT greatest(coalesce(max(id), 0)::numeric + 1,
                        sequence_value::numeric + CASE WHEN sequence_called THEN 1 ELSE 0 END)
        INTO first_id FROM dh_governance_audit_rule_dimension;
        IF first_id + missing_count - 1 > sequence_max THEN
            RAISE EXCEPTION '稽查维度编号空间不足';
        END IF;
        -- 事务性重启保证后续任何恢复或约束校验失败时一并恢复序列。
        EXECUTE format('ALTER SEQUENCE dh_governance_audit_rule_dimension_seq RESTART WITH %s', first_id);
        FOR item IN SELECT * FROM audit_rule_dimension_seed ORDER BY sort LOOP
            INSERT INTO dh_governance_audit_rule_dimension (name, code, status, sort, remark, creator, updater, deleted)
            SELECT item.name, item.code, 0, item.sort, NULL, 'migration:0203', 'migration:0203', 0
            WHERE NOT EXISTS (SELECT 1 FROM dh_governance_audit_rule_dimension WHERE code = item.code);
        END LOOP;
    END IF;
    IF (SELECT count(*) FROM audit_rule_dimension_seed seed JOIN dh_governance_audit_rule_dimension dimension
        ON dimension.code = seed.code AND dimension.deleted = 0) <> 5 THEN
        RAISE EXCEPTION '原五个稽查维度未全部有效，禁止自动恢复规则关联';
    END IF;
END $seed_dimensions$;

-- 恢复以稳定规则编码标识的原始关联；编号由独立维度表决定，不复用业务类目的编号。
WITH original_rule_dimensions (rule_code, dimension_code) AS (VALUES
    ('107', 'D05'), ('108', 'D02'), ('109', 'D02'), ('110', 'D02'),
    ('111', 'D02'), ('112', 'D02'), ('113', 'D02'), ('114', 'D02'),
    ('115', 'D02'), ('116', 'D02'), ('117', 'D02'), ('118', 'D02'),
    ('119', 'D02'), ('203', 'D05'), ('204', 'D05'), ('205', 'D01'),
    ('206', 'D05'), ('207', 'D05'), ('306', 'D01'), ('307', 'D01'),
    ('308', 'D01'), ('309', 'D01'), ('310', 'D01'), ('311', 'D01'),
    ('403', 'D03'), ('404', 'D03'), ('405', 'D03'), ('504', 'D04')
)
UPDATE dh_governance_audit_rule rule
SET dimension_id = dimension.id, updater = 'migration:0203', update_time = CURRENT_TIMESTAMP
FROM original_rule_dimensions original JOIN dh_governance_audit_rule_dimension dimension
    ON dimension.code = original.dimension_code AND dimension.deleted = 0
WHERE rule.code = original.rule_code AND rule.dimension_id IS NULL AND rule.updater = 'migration:0201';

DO $validate_rule_dimensions$
BEGIN
    IF EXISTS (SELECT 1 FROM dh_governance_audit_rule rule
        LEFT JOIN dh_governance_audit_rule_dimension dimension ON dimension.id = rule.dimension_id AND dimension.deleted = 0
        WHERE dimension.id IS NULL) THEN
        RAISE EXCEPTION '存在无法自动恢复的稽查维度引用，请依据 0201 执行前备份明确恢复，禁止猜测分类';
    END IF;
END $validate_rule_dimensions$;

ALTER TABLE dh_governance_audit_rule ALTER COLUMN dimension_id SET NOT NULL;
COMMENT ON COLUMN dh_governance_audit_rule.dimension_id IS '稽查规则独立质量维度编号，引用 dh_governance_audit_rule_dimension；与业务数据质量类目无关';
DO $add_dimension_foreign_key$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'dh_governance_audit_rule'::regclass
        AND conname = 'fk_dh_governance_audit_rule_dimension') THEN
        ALTER TABLE dh_governance_audit_rule ADD CONSTRAINT fk_dh_governance_audit_rule_dimension
        FOREIGN KEY (dimension_id) REFERENCES dh_governance_audit_rule_dimension(id) ON DELETE RESTRICT;
    ELSIF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'dh_governance_audit_rule'::regclass
        AND conname = 'fk_dh_governance_audit_rule_dimension' AND contype = 'f'
        AND confrelid = 'dh_governance_audit_rule_dimension'::regclass) THEN
        RAISE EXCEPTION '稽查维度外键名称已被不同约束占用，禁止继续';
    END IF;
END $add_dimension_foreign_key$;

COMMIT;
