-- Migration: 0139 modeling drop design check
-- Purpose: 删除逻辑模型「检查设计」使用的检测记录、定时配置和检测结果，并去掉对应菜单权限。
-- Depends on: 0138_modeling_logic-model-profile.sql
-- Target: existing PostgreSQL database, schema datahub-center
-- Repeatable: yes; DROP IF EXISTS and DELETE match zero rows on a second run
-- Rollback: 不能恢复已删除的检测历史和菜单。需要这些表时，只能从删除前的备份还原。

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

DROP TABLE IF EXISTS dh_modeling_model_check_result;
DROP TABLE IF EXISTS dh_modeling_model_check;
DROP TABLE IF EXISTS dh_modeling_model_check_config;
DROP SEQUENCE IF EXISTS dh_modeling_model_check_result_seq;
DROP SEQUENCE IF EXISTS dh_modeling_model_check_seq;
DROP SEQUENCE IF EXISTS dh_modeling_model_check_config_seq;

DELETE FROM system_role_menu
WHERE menu_id IN (
    SELECT id FROM system_menu
    WHERE permission IN ('modeling:model:check', 'modeling:model:check-config')
);
DELETE FROM system_menu
WHERE permission IN ('modeling:model:check', 'modeling:model:check-config');

COMMIT;
