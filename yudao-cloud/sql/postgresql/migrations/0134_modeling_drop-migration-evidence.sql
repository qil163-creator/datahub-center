-- Migration: 0134 删除建模历史迁移证据/备份表（7 张）
-- Status: 产品认删 + 架构认草稿（2026-10-10）；合环境前必须先 pg_dump
-- Purpose: 清理 0131 白名单保留的历史迁移证据表；非日常业务查询入口。
-- Depends on: 0133_modeling_drop-planning-change.sql 已执行；
--   且 dh_modeling_unification_state 中 MODEL_SIMPLIFICATION_V1.phase = CLEANED。
-- Target: schema "datahub-center"（PostgreSQL）
-- Repeatable: 是（IF EXISTS）；重复执行无副作用。
-- Rollback: 提交后不可脚本回滚；删前请 pg_dump 留底（尤其 unification_state）。
-- 明确不删: dh_dataforge_etl_archive / _action、dh_supply_legacy_admin_disposal、
--   dh_modeling_physical_model_version、dh_modeling_design_report 等业务表。
--
-- 删前建议（人工）:
--   pg_dump -n "datahub-center" -t "datahub-center.dh_modeling_unification_state" \
--     -t "datahub-center.dh_modeling_unification_map" \
--     -t "datahub-center.dh_modeling_legacy_adaptation_run" \
--     -t "datahub-center.dh_modeling_legacy_adaptation_backup" \
--     -t "datahub-center.dh_modeling_legacy_adaptation_issue" \
--     -t "datahub-center.dh_modeling_unified_menu_backup" \
--     -t "datahub-center.dh_modeling_workspace_removal_archive" \
--     -f backup_0134_modeling_evidence_$(date +%Y%m%d).sql

BEGIN;
SET LOCAL search_path TO "datahub-center", public;
SET LOCAL lock_timeout = '10s';

-- 门禁：未 CLEANED 则拒绝删证据表
DO $gate$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM "datahub-center".dh_modeling_unification_state
        WHERE migration_key = 'MODEL_SIMPLIFICATION_V1'
          AND phase = 'CLEANED'
    ) THEN
        RAISE EXCEPTION '0134 拒绝执行：MODEL_SIMPLIFICATION_V1 未处于 CLEANED，禁止删除迁移证据表';
    END IF;
END $gate$;

-- unification_state 上有 0131 证据保护触发器，须先卸再 DROP
DROP TRIGGER IF EXISTS modeling_simplification_evidence_guard
    ON "datahub-center".dh_modeling_unification_state;
DROP TRIGGER IF EXISTS modeling_simplification_evidence_truncate_guard
    ON "datahub-center".dh_modeling_unification_state;
DROP FUNCTION IF EXISTS "datahub-center".dh_modeling_simplification_evidence_guard();

-- 7 张历史迁移证据/备份表 + 对应序列（幂等）
DROP TABLE IF EXISTS "datahub-center".dh_modeling_legacy_adaptation_backup CASCADE;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_legacy_adaptation_backup_seq;

DROP TABLE IF EXISTS "datahub-center".dh_modeling_legacy_adaptation_issue CASCADE;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_legacy_adaptation_issue_seq;

DROP TABLE IF EXISTS "datahub-center".dh_modeling_legacy_adaptation_run CASCADE;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_legacy_adaptation_run_seq;

DROP TABLE IF EXISTS "datahub-center".dh_modeling_unification_map CASCADE;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_unification_map_seq;

DROP TABLE IF EXISTS "datahub-center".dh_modeling_unified_menu_backup CASCADE;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_unified_menu_backup_seq;

DROP TABLE IF EXISTS "datahub-center".dh_modeling_workspace_removal_archive CASCADE;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_workspace_removal_archive_seq;

-- 最后删完成账本（含 MODEL_SIMPLIFICATION_V1 行）
DROP TABLE IF EXISTS "datahub-center".dh_modeling_unification_state CASCADE;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_unification_state_seq;

COMMIT;

-- 可选校验（迁移后手工跑）:
-- SELECT tablename FROM pg_tables
--  WHERE schemaname='datahub-center' AND tablename LIKE 'dh_modeling_%evidence%'
--     OR tablename IN (
--        'dh_modeling_legacy_adaptation_backup','dh_modeling_legacy_adaptation_issue',
--        'dh_modeling_legacy_adaptation_run','dh_modeling_unification_map',
--        'dh_modeling_unification_state','dh_modeling_unified_menu_backup',
--        'dh_modeling_workspace_removal_archive');