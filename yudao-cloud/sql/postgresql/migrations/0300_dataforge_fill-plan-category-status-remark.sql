-- Migration: 0300 为数据填报类目增加状态和备注。
-- Purpose: 保存类目启停状态及可选备注，将既有类目初始化为启用。
-- Depends on: 0167_dataforge_fill-plan-category.sql 已建立采集计划类目表。
-- Target: 已有 PostgreSQL 平台库的 "datahub-center" 模式；新库使用已同步的全量基线。
-- Repeatable: 是；列及约束已存在时保留已录入的状态和备注。
-- Rollback: 提交前可回滚。提交后先备份新增字段的数据，再删除两列及状态约束。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';

ALTER TABLE dh_dataforge_fill_plan_category
    ADD COLUMN IF NOT EXISTS status smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS remark varchar(256);

DO $fill_plan_category_status$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'dh_dataforge_fill_plan_category'::regclass
          AND conname = 'ck_fill_plan_category_status'
    ) THEN
        ALTER TABLE dh_dataforge_fill_plan_category
            ADD CONSTRAINT ck_fill_plan_category_status CHECK (status IN (0, 1));
    END IF;
END;
$fill_plan_category_status$;

COMMENT ON COLUMN dh_dataforge_fill_plan_category.status IS '类目状态：0-启用，1-停用';
COMMENT ON COLUMN dh_dataforge_fill_plan_category.remark IS '类目备注，最多 256 个字符';

COMMIT;
