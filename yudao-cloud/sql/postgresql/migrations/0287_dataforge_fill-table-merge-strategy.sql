-- Migration: 0287 填报表可配置合并策略
-- Purpose: dh_dataforge_fill_table 新增两列，使模板可以选择合并策略：
--          merge_strategy 保存 REPLACE_SCOPE（范围替换，默认）/ APPEND（追加）/ UPSERT（更新插入）；
--          business_key_fields 保存 UPSERT 业务键字段码（英文逗号分隔，组织与所属期由系统自动并入）。
--          两者在发布时校验并固化进模板版本（dh_dataforge_fill_table_version 已有同名列），已发布版本不受影响。
--          历史填报表回填为 REPLACE_SCOPE，与此前写死的行为一致。
-- Depends on: 0286_dataforge_fill-notify-retry.sql（编号前驱）；dh_dataforge_fill_table 已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；列使用 ADD COLUMN IF NOT EXISTS，约束先删后建，注释可重复执行。单事务执行；新增列带常量默认值
--             或可空，不重写表，只在加列瞬间持有表锁（锁等待上限 10 秒，超时即失败，可稍后重试）。
-- Rollback: 应用回退后可执行 ALTER TABLE "datahub-center".dh_dataforge_fill_table
--           DROP CONSTRAINT IF EXISTS ck_dh_dataforge_fill_table_merge_strategy,
--           DROP COLUMN IF EXISTS merge_strategy, DROP COLUMN IF EXISTS business_key_fields;
--           删除列只丢失尚未发布的策略配置，已发布的模板版本保留各自固化的策略。旧版本应用不读写这两列。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_dataforge_fill_table
    ADD COLUMN IF NOT EXISTS merge_strategy varchar(32) NOT NULL DEFAULT 'REPLACE_SCOPE',
    ADD COLUMN IF NOT EXISTS business_key_fields varchar(500) DEFAULT '';

ALTER TABLE dh_dataforge_fill_table DROP CONSTRAINT IF EXISTS ck_dh_dataforge_fill_table_merge_strategy;
ALTER TABLE dh_dataforge_fill_table
    ADD CONSTRAINT ck_dh_dataforge_fill_table_merge_strategy
        CHECK (merge_strategy IN ('REPLACE_SCOPE', 'APPEND', 'UPSERT'));

COMMENT ON COLUMN dh_dataforge_fill_table.merge_strategy IS '合并策略：REPLACE_SCOPE-范围替换（默认） APPEND-追加 UPSERT-更新插入；发布时固化进模板版本';
COMMENT ON COLUMN dh_dataforge_fill_table.business_key_fields IS 'UPSERT 业务键字段码（英文逗号分隔，须为必填字段；组织与所属期由系统自动并入业务键）';

COMMIT;
