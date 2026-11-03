-- Migration: 0157 资产字段统一引用治理业务术语
-- Purpose: 删除尚未使用的资产独立术语及类目，字段关系改用治理术语编号，撤销独立维护菜单与授权。
-- Depends on: 0156_dataforge_database-monitor.sql 已执行或平台达到对应完整状态；资产词库及绑定尚未使用，治理术语与系统权限表已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；旧结构删除后跳过空数据前置检查，不改变新版应用已经创建的治理术语绑定。
-- Rollback: 失败整笔回滚；提交后旧表、序列和权限删除不可逆，回退旧应用须从执行前备份恢复；新绑定不能由旧应用读取。
-- 本次按功能尚未使用执行，不提供旧术语迁移；若发现任何旧术语、类目或绑定（包括软删除数据），明确拒绝并整笔回滚。
-- 执行前停止旧版 Asset 实例并备份权限表；结构及权限锁等待十秒、单语句两分钟超时，建议维护窗口执行。
-- 不自动授予治理维护权限；保留资产字段绑定权限 asset:data-asset:term 及其原授权。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 仅首次从旧结构升级时核实未使用前提；不将旧编号误当成治理编号。
DO $unused_preflight$
DECLARE
    legacy_table text;
    has_rows boolean;
    old_structure boolean;
BEGIN
    old_structure := to_regclass('dh_asset_term') IS NOT NULL
        OR to_regclass('dh_asset_category_term') IS NOT NULL
        OR EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name = 'dh_asset_term_column_rel' AND column_name = 'term_id');
    IF old_structure THEN
        FOREACH legacy_table IN ARRAY ARRAY['dh_asset_term', 'dh_asset_category_term', 'dh_asset_term_column_rel'] LOOP
            IF to_regclass(legacy_table) IS NOT NULL THEN
                EXECUTE format('LOCK TABLE %I IN ACCESS EXCLUSIVE MODE', legacy_table);
                EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I)', legacy_table) INTO has_rows;
                IF has_rows THEN
                    RAISE EXCEPTION '0157 要求旧术语功能尚未使用，但表 % 存在数据；禁止删除或将旧编号解释为治理编号', legacy_table;
                END IF;
            END IF;
        END LOOP;
    END IF;
    IF to_regclass('dh_governance_standard_term') IS NULL THEN
        RAISE EXCEPTION '0157 缺少治理业务术语表，请先完成平台前置初始化';
    END IF;
END $unused_preflight$;

DROP TABLE IF EXISTS dh_asset_term;
DROP TABLE IF EXISTS dh_asset_category_term;
DROP SEQUENCE IF EXISTS dh_asset_term_seq;
DROP SEQUENCE IF EXISTS dh_asset_category_term_seq;

ALTER TABLE dh_asset_term_column_rel DROP COLUMN IF EXISTS term_id;
ALTER TABLE dh_asset_term_column_rel ADD COLUMN IF NOT EXISTS standard_term_id bigint NOT NULL;
COMMENT ON COLUMN dh_asset_term_column_rel.standard_term_id IS '治理业务术语编号，通过治理接口解析';
COMMENT ON TABLE dh_asset_term_column_rel IS '数据资产-治理业务术语与资产字段绑定';
CREATE INDEX IF NOT EXISTS idx_dh_asset_term_column_rel_standard_term
    ON dh_asset_term_column_rel (tenant_id, standard_term_id, deleted);

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;
-- 只识别退役入口及其专属权限；遇到混挂的其他页面或业务权限时阻断，不自动扩大清理范围。
CREATE TEMPORARY TABLE asset_0157_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO asset_0157_retired_menus (id)
SELECT id FROM system_menu
WHERE trim(both '/' from coalesce(component, '')) = 'asset/term/index'
   OR component_name = 'AssetTerm'
   OR permission LIKE 'asset:term:%' OR permission LIKE 'asset:category-term:%';
WITH RECURSIVE buttons (id) AS (
    SELECT id FROM asset_0157_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3 AND coalesce(child.permission, '') = ''
)
INSERT INTO asset_0157_retired_menus (id) SELECT id FROM buttons ON CONFLICT DO NOTHING;
DO $menu_preflight$
BEGIN
    IF EXISTS (SELECT 1 FROM system_menu child JOIN asset_0157_retired_menus parent ON child.parent_id = parent.id
               WHERE NOT EXISTS (SELECT 1 FROM asset_0157_retired_menus retired WHERE retired.id = child.id)) THEN
        RAISE EXCEPTION '0157 资产术语菜单下有其他业务页面或权限，请先确认归属，禁止连带清理';
    END IF;
END $menu_preflight$;
UPDATE system_tenant_package package
SET menu_ids = (SELECT coalesce(jsonb_agg(entry.value ORDER BY entry.ordinality), '[]'::jsonb)::text
               FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
               WHERE NOT EXISTS (SELECT 1 FROM asset_0157_retired_menus retired WHERE retired.id::text = entry.value #>> '{}')),
    updater = 'migration:0157', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
              JOIN asset_0157_retired_menus retired ON retired.id::text = entry.value);
DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM asset_0157_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM asset_0157_retired_menus);

COMMIT;
