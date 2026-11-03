-- Migration: 0196 清理无调用的旧资产持久化对象
-- Purpose: 删除旧资源数据项、旧交付台账及其序列；当前交付继续使用 dh_market_delivery_operation。
-- Depends on: 0195_governance_government-datameta-categories.sql；资产旧表来自既有平台基线。
-- Target: 已升级至 0195 的 PostgreSQL 平台数据库；新库由完整基线包含本变更。
-- Repeatable: 是；对象缺失时跳过，全部操作位于同一事务。
-- Rollback: 不可自动回滚；恢复结构需使用执行前备份。非空旧表会中止，须先独立核实并归档数据。
-- 风险：删除表需要排他锁；等待超过 5 秒即失败。保留外部依赖检查，不使用 CASCADE。

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path = "datahub-center", pg_catalog;

DO $retire_unused_asset_tables$
DECLARE
    retired_table text;
    contains_data boolean;
BEGIN
    FOREACH retired_table IN ARRAY ARRAY['dh_asset_data_item', 'dh_asset_delivery_op']
    LOOP
        IF to_regclass(format('%I.%I', 'datahub-center', retired_table)) IS NOT NULL THEN
            -- 锁定后再检查数据，避免检查与删除之间出现并发写入。
            EXECUTE format('LOCK TABLE %I.%I IN ACCESS EXCLUSIVE MODE', 'datahub-center', retired_table);
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I LIMIT 1)', 'datahub-center', retired_table)
                INTO contains_data;
            IF contains_data THEN
                RAISE EXCEPTION '旧资产表 % 非空，已中止清理；请先核实并归档存量数据', retired_table;
            END IF;
        END IF;
    END LOOP;
END $retire_unused_asset_tables$;

DROP TABLE IF EXISTS "datahub-center".dh_asset_data_item;
DROP TABLE IF EXISTS "datahub-center".dh_asset_delivery_op;
DROP SEQUENCE IF EXISTS "datahub-center".dh_asset_data_item_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_asset_delivery_op_seq;

COMMIT;
