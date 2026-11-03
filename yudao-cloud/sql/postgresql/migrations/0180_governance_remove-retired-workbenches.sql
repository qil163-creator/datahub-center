-- Migration: 0180 删除智能落标、跨系统码值映射和血缘能力。
-- Purpose: 清理三个退役工作台及其权限、授权、套餐引用、推荐专属数据、码值映射和血缘持久化与调度配置。
-- Depends on: 0179_asset_restore-role-catalog-navigation.sql 已执行；正式落标映射、标准码集和标准码项继续保留。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql，不操作外部业务库或独立调度中心库。
-- Repeatable: 是；对象按组件、权限或精确对象名清理，缺失对象不重建，不影响其余菜单、数据与任务。
-- Rollback: 单事务失败完整回滚；提交后推荐证据、码值映射和血缘数据不可恢复，必须用执行前整表及权限表备份恢复，不能靠重跑旧迁移恢复数据。
-- 执行前停止治理、研发和建模旧实例及相关任务，备份被删表、菜单、角色菜单、套餐和任务配置；存在未知依赖时以 RESTRICT 中止，禁止使用 CASCADE 绕过。
-- 与删除相关接口和执行路径的新代码同步发布；独立调度中心也须停用三个退役处理器，禁止旧实例在表删除后继续写入。
-- 原始 SQL 不触发 Spring 缓存失效；提交后按真实 Redis 前缀清理受影响的 permission_menu_ids、menu_role_ids，并重启相关服务清除本地权限缓存。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

DO $retire_governance_workbenches$
DECLARE
    retired_ids bigint[];
BEGIN
    IF EXISTS (SELECT 1 FROM system_tenant_package WHERE jsonb_typeof(menu_ids::jsonb) IS DISTINCT FROM 'array') THEN
        RAISE EXCEPTION '0180 套餐菜单编号必须为 JSON 数组';
    END IF;
    WITH RECURSIVE roots AS (
        SELECT menu.id FROM system_menu menu WHERE trim(both '/' from coalesce(menu.component, '')) IN (
            'governance/standardmapping/index', 'governance/standardcodemapping/index', 'governance/lineage/index')
        UNION
        SELECT menu.id FROM system_menu menu JOIN system_menu parent ON parent.id = menu.parent_id
        WHERE menu.type = 1 AND menu.name = '数据血缘' AND trim(both '/' from coalesce(menu.path, '')) = 'lineage'
            AND coalesce(menu.permission, '') = '' AND parent.type = 1 AND parent.parent_id = 0
            AND trim(both '/' from coalesce(parent.path, '')) IN ('govern', 'governance')
        UNION
        SELECT id FROM system_menu WHERE type = 3 AND (
            permission LIKE 'governance:standard-mapping:%' OR permission LIKE 'governance:standard-code-mapping:%'
            OR permission LIKE 'governance:lineage:%' OR permission = 'asset:data-asset:lineage')
    ), descendants AS (
        SELECT id FROM roots
        UNION
        SELECT child.id FROM system_menu child JOIN descendants parent ON child.parent_id = parent.id
    ) SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO retired_ids FROM descendants;

    IF EXISTS (SELECT 1 FROM system_menu WHERE id = ANY(retired_ids) AND type IN (1, 2)
        AND trim(both '/' from coalesce(component, '')) NOT IN (
            'governance/standardmapping/index', 'governance/standardcodemapping/index', 'governance/lineage/index')
        AND NOT (type = 1 AND name = '数据血缘' AND trim(both '/' from coalesce(path, '')) = 'lineage'
                 AND coalesce(permission, '') = '')) THEN
        RAISE EXCEPTION '0180 退役菜单包含未知页面或目录，禁止连带删除';
    END IF;
    -- 仅移除精确退役编号；保留其他元素的类型、顺序、重复项以及未知 JSON 元素。
    WITH cleaned AS (
        SELECT package.id,
            (SELECT coalesce(jsonb_agg(entry.value ORDER BY entry.ordinality), '[]'::jsonb)
             FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
             WHERE NOT EXISTS (SELECT 1 FROM unnest(retired_ids) retired(id)
                               WHERE entry.value #>> '{}' = retired.id::text)) AS entries
        FROM system_tenant_package package
    )
    UPDATE system_tenant_package package SET menu_ids = cleaned.entries::text,
        updater = 'migration:0180', update_time = CURRENT_TIMESTAMP
    FROM cleaned WHERE package.id = cleaned.id AND package.menu_ids::jsonb IS DISTINCT FROM cleaned.entries;
    DELETE FROM system_role_menu WHERE menu_id = ANY(retired_ids);
    DELETE FROM system_menu WHERE id = ANY(retired_ids);
END $retire_governance_workbenches$;

-- 推荐运行与候选仅供已删除的智能推荐工作台使用；正式手工落标事实保留。
DROP TABLE IF EXISTS dh_governance_standard_mapping_candidate RESTRICT;
DROP TABLE IF EXISTS dh_governance_standard_mapping_recommend_run RESTRICT;
DROP SEQUENCE IF EXISTS dh_governance_standard_mapping_candidate_seq RESTRICT;
DROP SEQUENCE IF EXISTS dh_governance_standard_mapping_recommend_run_seq RESTRICT;
-- 跨系统码值映射能力全部退役，引用的标准码集、标准码项及其数据保留。
DROP TABLE IF EXISTS dh_governance_standard_code_mapping RESTRICT;
DROP TABLE IF EXISTS dh_governance_standard_source_code_set RESTRICT;
DROP SEQUENCE IF EXISTS dh_governance_standard_code_mapping_seq RESTRICT;
DROP SEQUENCE IF EXISTS dh_governance_standard_source_code_set_seq RESTRICT;
-- 治理血缘及研发专属投递记录全部退役，其余同步、ETL 运行和质量记录保留。
DROP TABLE IF EXISTS dh_governance_lineage_relation RESTRICT;
DROP TABLE IF EXISTS dh_governance_lineage_node RESTRICT;
DROP TABLE IF EXISTS dh_governance_lineage_event_outbox RESTRICT;
DROP TABLE IF EXISTS dh_governance_lineage_apply_watermark RESTRICT;
DROP TABLE IF EXISTS dh_dataforge_access_lineage RESTRICT;
DROP TABLE IF EXISTS dh_dataforge_etl_lineage_delivery RESTRICT;
DROP SEQUENCE IF EXISTS dh_governance_lineage_relation_seq RESTRICT;
DROP SEQUENCE IF EXISTS dh_governance_lineage_node_seq RESTRICT;
DROP SEQUENCE IF EXISTS dh_governance_lineage_event_outbox_seq RESTRICT;
DROP SEQUENCE IF EXISTS dh_governance_lineage_apply_watermark_seq RESTRICT;
DROP SEQUENCE IF EXISTS dh_dataforge_etl_lineage_delivery_seq RESTRICT;
-- 已退役 framework 血缘组件的旧持久化对象，只处理平台当前 schema 中的精确对象名。
DROP TABLE IF EXISTS data_lineage_relation RESTRICT;
DROP TABLE IF EXISTS data_blood_column RESTRICT;
DROP TABLE IF EXISTS data_lineage_node RESTRICT;
DROP TABLE IF EXISTS data_blood_tb RESTRICT;
DROP SEQUENCE IF EXISTS seq_data_lineage_relation_id RESTRICT;
DROP SEQUENCE IF EXISTS seq_data_lineage_node_id RESTRICT;
-- 仅移除建模部署记录的血缘专属列，元数据登记状态、计划与执行证据继续保留。
ALTER TABLE IF EXISTS dh_modeling_deploy_record
    DROP COLUMN IF EXISTS lineage_status RESTRICT,
    DROP COLUMN IF EXISTS lineage_error RESTRICT,
    DROP COLUMN IF EXISTS lineage_reported_at RESTRICT,
    DROP COLUMN IF EXISTS lineage_payload RESTRICT;

DO $retire_lineage_jobs$
BEGIN
    IF to_regclass('infra_job') IS NOT NULL THEN
        DELETE FROM infra_job WHERE handler_name IN (
            'accessLineageDeliveryJob', 'governanceLineageOutboxRelayJob', 'modelingLineageReconcileJob');
    END IF;
    -- 注册移除后旧画布由新执行器显式拒绝，不把标准映射节点静默改写为普通转换。
    IF to_regclass('dh_dataforge_etl_tool') IS NOT NULL THEN
        DELETE FROM dh_dataforge_etl_tool WHERE code = 'standard-map';
    END IF;
END $retire_lineage_jobs$;
COMMIT;
