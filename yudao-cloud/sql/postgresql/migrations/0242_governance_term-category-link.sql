-- Migration: 0242，业务术语关联术语类目。
-- Purpose: 新增独立的术语类目关联，保留既有数据元类目关系，不推断历史归属。
-- Depends on: 0241_asset_hide-legacy-application-menu.sql；术语类目表已由 0238 创建。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；列、索引和外键均使用存在性保护。
-- Rollback: 删除列会丢失新建立的术语类目关联，应先备份后再删除索引和列；旧数据元类目不受影响。
-- 风险：新增外键会校验现有记录并锁定相关表；数据量大时应评估维护窗口。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
ALTER TABLE dh_governance_standard_term ADD COLUMN IF NOT EXISTS term_category_id bigint;
COMMENT ON COLUMN dh_governance_standard_term.term_category_id IS '业务术语类目编号';
CREATE INDEX IF NOT EXISTS idx_standard_term_term_category ON dh_governance_standard_term(term_category_id) WHERE deleted = 0;
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_standard_term_term_category'
                   AND conrelid = 'dh_governance_standard_term'::regclass) THEN
        ALTER TABLE dh_governance_standard_term ADD CONSTRAINT fk_standard_term_term_category
            FOREIGN KEY (term_category_id) REFERENCES dh_governance_category_term(id);
    END IF;
END $migration$;
COMMIT;
