-- Migration: 0251 删除数据元审批与数据元、代码表版本能力
-- Purpose: 移除专用快照、审批、版本字段和权限；保留标准文件快照、质量规则及任务引用。
-- Depends on: 0250_dataforge_remove-access-overview-menu.sql；相关旧版服务及任务已停止。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的全量基线。
-- Repeatable: 是；对象按存在性删除，权限清理和状态转换可重复执行。
-- Rollback: 失败时整笔回滚；提交后版本与审批记录不可恢复，须从执行前独立备份恢复数据、结构和权限。
-- 执行前停止旧版应用和任务并备份相关表；落标及质量规则改为跟随数据元当前内容，不再冻结历史版本。
-- 删除和索引重建需要表锁；不使用级联删除，未知依赖将阻止事务提交。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMPORARY TABLE governance_0251_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO governance_0251_retired_menus (id)
SELECT id FROM system_menu WHERE permission = 'governance:standard-element:approve';
UPDATE system_tenant_package package
SET menu_ids = (
    SELECT coalesce(jsonb_agg(entry.value ORDER BY entry.ordinality), '[]'::jsonb)::text
    FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
    WHERE NOT EXISTS (SELECT 1 FROM governance_0251_retired_menus retired WHERE retired.id::text = entry.value #>> '{}')
), updater = 'migration:0251', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
              JOIN governance_0251_retired_menus retired ON retired.id::text = entry.value);
DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM governance_0251_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM governance_0251_retired_menus);

-- 审核中的对象恢复为草稿，保留已发布、已废止及乐观锁版本。
UPDATE dh_governance_standard_element SET lifecycle_status = 'DRAFT', revision = revision + 1,
    updater = 'migration:0251', update_time = CURRENT_TIMESTAMP
WHERE lifecycle_status = 'REVIEWING';
COMMENT ON COLUMN dh_governance_standard_element.lifecycle_status IS '生命周期状态：DRAFT-草稿 PUBLISHED-已发布 DEPRECATED-已废止';

CREATE OR REPLACE FUNCTION standard_workbench_child_guard() RETURNS trigger LANGUAGE plpgsql AS $guard$
BEGIN
 IF NEW.deleted <> 0 THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME = 'dh_governance_standard_element_rule' THEN
  PERFORM 1 FROM dh_governance_standard_element WHERE id = NEW.element_id AND deleted = 0 AND lifecycle_status <> 'DEPRECATED' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '数据元不存在或当前生命周期不可编辑'; END IF;
  IF NEW.kind = 'AUDIT' THEN
   PERFORM 1 FROM dh_governance_audit_rule WHERE id = NEW.template_id AND deleted = 0;
  ELSE
   PERFORM 1 FROM dh_governance_clean_rule WHERE id = NEW.template_id AND deleted = 0;
  END IF;
  IF NOT FOUND THEN RAISE EXCEPTION '关联规则模板不存在'; END IF;
 ELSE
  PERFORM 1 FROM dh_governance_standard_code_set WHERE id = NEW.code_set_id AND deleted = 0 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '代码表不存在'; END IF;
  PERFORM 1 FROM dh_governance_standard_code_item WHERE id = NEW.code_item_id AND code_set_id = NEW.code_set_id AND deleted = 0 AND status = 0;
  IF NOT FOUND THEN RAISE EXCEPTION '映射码值不属于该代码表或已停用'; END IF;
 END IF;
 RETURN NEW;
END $guard$;

-- 历史规则可能被质量任务引用；允许相同摘要的多条历史记录，保留规则编号和参数。
DROP INDEX IF EXISTS uk_dh_governance_quality_rule_std_constraint;
ALTER TABLE dh_governance_quality_rule DROP CONSTRAINT IF EXISTS ck_quality_standard_template_identity;
ALTER TABLE dh_governance_quality_rule DROP COLUMN IF EXISTS element_version;
ALTER TABLE dh_governance_quality_rule ADD CONSTRAINT ck_quality_standard_template_identity
 CHECK (standard_element_id IS NULL OR (constraint_hash IS NOT NULL AND constraint_hash ~ '^[0-9a-f]{64}$'));
CREATE INDEX IF NOT EXISTS idx_quality_rule_standard_constraint
 ON dh_governance_quality_rule (standard_element_id, constraint_hash)
 WHERE deleted = 0 AND standard_element_id IS NOT NULL;
COMMENT ON COLUMN dh_governance_quality_rule.constraint_hash IS '生成规则时的约束摘要，用于匹配当前数据元配置';
ALTER TABLE dh_governance_standard_mapping DROP COLUMN IF EXISTS element_version;

-- 对象版本表由标准文件与代码表共用，只删除代码表快照。
DELETE FROM dh_governance_standard_object_version WHERE object_type = 'CODE_SET';
DROP TABLE IF EXISTS dh_governance_standard_element_approval;
DROP TABLE IF EXISTS dh_governance_standard_element_version;
DROP FUNCTION IF EXISTS reject_standard_element_version_mutation();
DROP SEQUENCE IF EXISTS dh_governance_standard_element_approval_seq;
DROP SEQUENCE IF EXISTS dh_governance_standard_element_version_seq;
COMMIT;
