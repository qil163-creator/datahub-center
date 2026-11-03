-- Migration: 0193 删除数据标准贯标稽核与整改功能。
-- Purpose: 删除贯标稽核运行、稽核结果、整改问题、运营度量快照和发布门禁表及其序列、门禁不可变触发函数，清理页面菜单、稽核与门禁按钮、角色授权、租户套餐引用及平台旧调度配置和日志。
-- Depends on: 0192_dataforge_batch-file-import.sql 已执行，或已核验平台达到对应状态；系统菜单、角色菜单和租户套餐表已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；按功能标识定位菜单，表、函数与序列使用 IF EXISTS，重复执行不会修改标准数据元、命名规范及其他菜单。
-- Rollback: 失败整笔回滚；提交后稽核记录、问题、度量快照和门禁审计不可恢复，须从执行前备份恢复上述表、序列、函数、菜单授权、套餐及旧调度配置和日志，并恢复对应应用版本。
-- 不可逆操作：永久删除全部贯标稽核与发布门禁记录，包括逻辑删除记录。执行前备份上述对象，部署不再调用门禁的应用版本，并停用 XXL-Job 的 governanceStandardComplianceJob 和 governanceStandardMetricSnapshotJob。
-- 仅清理平台自身配置；外部 XXL-Job 管理库中的任务须在部署时停用，本脚本不连接或修改该管理库。
-- 删除表获取排他锁；菜单授权清理期间阻止并发写入。锁等待超过十秒或语句执行超过两分钟则回滚。
-- 不使用级联删除；发现其他功能菜单子项或数据库依赖时拒绝执行，避免连带删除。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

-- 页面菜单及其按钮，另含挂在标准数据元管理下的门禁查询和豁免按钮。
CREATE TEMPORARY TABLE governance_0193_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO governance_0193_retired_menus (id)
SELECT menu.id FROM system_menu menu
WHERE permission LIKE 'governance:standard-compliance:%'
   OR permission LIKE 'governance:standard-gate:%'
   OR trim(both '/' from coalesce(component, '')) = 'governance/standardcompliance'
   OR trim(both '/' from coalesce(component, '')) LIKE 'governance/standardcompliance/%'
   OR component_name = 'GovernanceStandardCompliance'
   OR (type = 2 AND name = '贯标稽核与整改'
       AND trim(both '/' from coalesce(path, '')) = 'standard-compliance');

-- 补齐专属页面下无权限码的按钮；逻辑删除菜单也必须清理，避免残留授权。
WITH RECURSIVE buttons (id) AS (
    SELECT id FROM governance_0193_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3 AND coalesce(child.permission, '') = ''
)
INSERT INTO governance_0193_retired_menus (id)
SELECT id FROM buttons ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN governance_0193_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM governance_0193_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0193 贯标稽核菜单下存在其他功能子项，禁止连带删除';
    END IF;
END $menu_preflight$;

-- 仅移除目标菜单编号，保留其他套餐条目的顺序和数字或字符串类型。
-- 拼接紧凑 JSON，避免序列化增加分隔空格而超过 menu_ids 的长度限制。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT '[' || coalesce(string_agg(entry.value::text, ',' ORDER BY entry.ordinality), '') || ']'
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM governance_0193_retired_menus retired WHERE retired.id::text = entry.value #>> '{}'
        )
    ),
    updater = 'migration:0193', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
    JOIN governance_0193_retired_menus retired ON retired.id::text = entry.value
);

DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM governance_0193_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM governance_0193_retired_menus);

-- 清理平台中可能保留的旧调度配置，保留其他治理任务。
DO $legacy_jobs$
BEGIN
    IF to_regclass('"datahub-center".infra_job_log') IS NOT NULL THEN
        DELETE FROM infra_job_log
        WHERE handler_name IN ('governanceStandardComplianceJob', 'governanceStandardMetricSnapshotJob');
    END IF;
    IF to_regclass('"datahub-center".infra_job') IS NOT NULL THEN
        DELETE FROM infra_job
        WHERE handler_name IN ('governanceStandardComplianceJob', 'governanceStandardMetricSnapshotJob');
    END IF;
END $legacy_jobs$;

-- 先删除引用稽核运行的门禁和结果，再删除运行、问题及度量快照；外部依赖会使整笔事务失败。
DROP TABLE IF EXISTS "datahub-center".dh_governance_standard_release_gate;
DROP TABLE IF EXISTS "datahub-center".dh_governance_standard_check_result;
DROP TABLE IF EXISTS "datahub-center".dh_governance_standard_check_run;
DROP TABLE IF EXISTS "datahub-center".dh_governance_standard_issue;
DROP TABLE IF EXISTS "datahub-center".dh_governance_standard_metric_snapshot;

-- 触发器已随门禁表删除；仍有其他对象使用该函数时整笔失败。
DROP FUNCTION IF EXISTS "datahub-center".reject_standard_release_gate_mutation();

DROP SEQUENCE IF EXISTS "datahub-center".dh_governance_standard_release_gate_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_governance_standard_check_result_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_governance_standard_check_run_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_governance_standard_issue_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_governance_standard_metric_snapshot_seq;

COMMIT;
