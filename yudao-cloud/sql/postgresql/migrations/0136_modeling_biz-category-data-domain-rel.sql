-- Migration: 0136 恢复业务分类↔数据域多对多关联表
-- Purpose: 0131 简化阶段已 DROP dh_modeling_biz_category_data_domain_rel；同名同结构恢复。
--          若遗留列 domain_ids(JSON) 仍有值，回填进关联表后只读遗留（本期不 DROP）。
-- Depends on: 0135_modeling_shorten-core-table-comments.sql
-- Target: schema "datahub-center"（PostgreSQL）
-- Repeatable: 否（CREATE IF NOT EXISTS + 条件回填幂等）
-- Rollback: DROP TABLE/SEQUENCE（会丢关联；domain_ids 列若存在保留）

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

CREATE SEQUENCE IF NOT EXISTS "datahub-center"."dh_modeling_biz_category_data_domain_rel_seq";

CREATE TABLE IF NOT EXISTS "datahub-center"."dh_modeling_biz_category_data_domain_rel" (
  "id" bigint NOT NULL DEFAULT nextval('"datahub-center".dh_modeling_biz_category_data_domain_rel_seq'::regclass),
  "biz_category_id" bigint NOT NULL,
  "data_domain_id" bigint NOT NULL,
  "sort_order" integer NOT NULL DEFAULT 0,
  "creator" varchar(64) DEFAULT '',
  "create_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updater" varchar(64) DEFAULT '',
  "update_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "deleted" smallint NOT NULL DEFAULT 0,
  "tenant_id" bigint NOT NULL DEFAULT 0,
  CONSTRAINT "dh_modeling_biz_category_data_domain_rel_pkey" PRIMARY KEY ("id")
);

COMMENT ON TABLE "datahub-center"."dh_modeling_biz_category_data_domain_rel"
  IS '数据建模-业务分类覆盖数据域关系';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."biz_category_id"
  IS '业务分类编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."data_domain_id"
  IS '数据域编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."sort_order"
  IS '展示排序';

CREATE UNIQUE INDEX IF NOT EXISTS "uk_dh_modeling_biz_category_data_domain_rel"
  ON "datahub-center"."dh_modeling_biz_category_data_domain_rel"
      ("tenant_id", "biz_category_id", "data_domain_id")
  WHERE "deleted" = 0;

CREATE INDEX IF NOT EXISTS "idx_dh_modeling_biz_category_data_domain_rel_domain"
  ON "datahub-center"."dh_modeling_biz_category_data_domain_rel"
      ("tenant_id", "data_domain_id")
  WHERE "deleted" = 0;

-- 遗留 domain_ids(JSON) 有值则回填；已存在同键关联则跳过
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'datahub-center'
      AND table_name = 'dh_modeling_biz_category'
      AND column_name = 'domain_ids'
  ) THEN
    INSERT INTO "datahub-center"."dh_modeling_biz_category_data_domain_rel"
      ("biz_category_id", "data_domain_id", "sort_order",
       "creator", "create_time", "updater", "update_time", "deleted", "tenant_id")
    SELECT
      c."id",
      item."domain_id_text"::bigint,
      (item."ordinality" - 1)::integer,
      COALESCE(c."creator", ''),
      COALESCE(c."create_time", CURRENT_TIMESTAMP),
      COALESCE(c."updater", ''),
      COALESCE(c."update_time", CURRENT_TIMESTAMP),
      0,
      c."tenant_id"
    FROM "datahub-center"."dh_modeling_biz_category" c
    CROSS JOIN LATERAL jsonb_array_elements_text(
      CASE WHEN jsonb_typeof(c."domain_ids") = 'array'
           THEN c."domain_ids" ELSE '[]'::jsonb END
    ) WITH ORDINALITY AS item("domain_id_text", "ordinality")
    WHERE c."deleted" = 0
      AND item."domain_id_text" ~ '^[0-9]+$'
      AND NOT EXISTS (
        SELECT 1
        FROM "datahub-center"."dh_modeling_biz_category_data_domain_rel" r
        WHERE r."tenant_id" = c."tenant_id"
          AND r."biz_category_id" = c."id"
          AND r."data_domain_id" = item."domain_id_text"::bigint
          AND r."deleted" = 0
      );
  END IF;
END $$;

COMMIT;
