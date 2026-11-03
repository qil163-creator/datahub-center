-- Migration: 0167 采集计划分类目录
-- Purpose: 新增租户内共享的多级分类目录及计划分类关联，存量计划保持未分类。
-- Depends on: 已有 datahub-center 基线中的 dh_dataforge_fill_plan 表；编号迁移执行至 0166。
-- Target: 已有 PostgreSQL 数据库；新库使用已包含本迁移的 datahub-center.sql。
-- Repeatable: 是；结构与索引使用 IF NOT EXISTS，可重复执行且不更改已有分类数据。
-- Rollback: 加法式迁移无数据删除；回滚应用后需先备份分类数据，再删除计划分类列、分类表及序列。
-- 事务边界：全部结构变更在同一事务中执行；新增列会短暂取得表锁，建议低峰期执行。

BEGIN;

CREATE SEQUENCE IF NOT EXISTS "datahub-center"."dh_dataforge_fill_plan_category_seq";

CREATE TABLE IF NOT EXISTS "datahub-center"."dh_dataforge_fill_plan_category" (
    "id" bigint NOT NULL DEFAULT nextval('"datahub-center".dh_dataforge_fill_plan_category_seq'::regclass),
    "parent_id" bigint NOT NULL DEFAULT 0,
    "name" varchar(100) NOT NULL,
    "sort" integer NOT NULL DEFAULT 0,
    "tenant_id" bigint NOT NULL DEFAULT 1,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" smallint NOT NULL DEFAULT 0,
    CONSTRAINT "dh_dataforge_fill_plan_category_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ck_dh_fill_plan_category_parent" CHECK ("parent_id" >= 0 AND "parent_id" <> "id"),
    CONSTRAINT "ck_dh_fill_plan_category_name" CHECK (length(btrim("name")) > 0),
    CONSTRAINT "ck_dh_fill_plan_category_sort" CHECK ("sort" BETWEEN 0 AND 9999)
);

COMMENT ON TABLE "datahub-center"."dh_dataforge_fill_plan_category" IS '采集计划-分类目录';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."id" IS '分类编号';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."parent_id" IS '父分类编号，根目录为 0';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."name" IS '分类名称';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."sort" IS '同级显示顺序';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."tenant_id" IS '租户编号';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."updater" IS '最后更新者';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan_category"."deleted" IS '逻辑删除标识：0-未删除 1-已删除';

CREATE UNIQUE INDEX IF NOT EXISTS "uk_dh_fill_plan_category_name"
    ON "datahub-center"."dh_dataforge_fill_plan_category" ("tenant_id", "parent_id", "name")
    WHERE "deleted" = 0;

ALTER TABLE "datahub-center"."dh_dataforge_fill_plan" ADD COLUMN IF NOT EXISTS "category_id" bigint;
COMMENT ON COLUMN "datahub-center"."dh_dataforge_fill_plan"."category_id" IS '计划分类编号，空值表示未分类';

CREATE INDEX IF NOT EXISTS "idx_dh_fill_plan_category"
    ON "datahub-center"."dh_dataforge_fill_plan" ("tenant_id", "category_id", "id")
    WHERE "deleted" = 0;

COMMIT;
