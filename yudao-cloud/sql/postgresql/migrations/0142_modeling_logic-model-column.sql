-- Migration: 0142 modeling logic model column
-- Purpose: 新建逻辑模型独立字段表，并从最新版本 JSON fields 回填（对齐 qData dp_model_column）
-- Depends on: 0141_modeling_drop-legacy-publish.sql
-- Target: existing PostgreSQL database, schema datahub-center
-- Repeatable: yes; CREATE IF NOT EXISTS；回填按模型软删后重插，可重复执行
-- Rollback: DROP TABLE/SEQUENCE（会丢失字段表数据；版本表 JSON 仍保留）
-- Note: 执行前请备份。本任务不自动对生产库执行。field_uid 原样保留，空才 gen_random_uuid。

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

CREATE SEQUENCE IF NOT EXISTS "datahub-center"."dh_modeling_logic_model_column_seq";

CREATE TABLE IF NOT EXISTS "datahub-center"."dh_modeling_logic_model_column" (
  "id" bigint NOT NULL DEFAULT nextval('"datahub-center".dh_modeling_logic_model_column_seq'::regclass),
  "model_id" bigint NOT NULL,
  "field_uid" varchar(64) NOT NULL,
  "source_attr_uid" varchar(64) DEFAULT NULL,
  "field_name" varchar(256) NOT NULL,
  "cn_name" varchar(512) DEFAULT NULL,
  "data_type" varchar(128) NOT NULL,
  "data_length" int DEFAULT NULL,
  "data_precision" int DEFAULT NULL,
  "data_scale" int DEFAULT NULL,
  "data_format" varchar(64) DEFAULT NULL,
  "measure_unit" varchar(64) DEFAULT NULL,
  "value_range" varchar(256) DEFAULT NULL,
  "default_value" varchar(256) DEFAULT NULL,
  "pk_flag" smallint NOT NULL DEFAULT 0,
  "nullable_flag" smallint NOT NULL DEFAULT 1,
  "sort" int DEFAULT NULL,
  "comment" varchar(1000) DEFAULT NULL,
  "remark" varchar(512) DEFAULT NULL,
  "agg_type" varchar(64) DEFAULT NULL,
  "standard_id" bigint DEFAULT NULL,
  "standard_code" varchar(128) DEFAULT NULL,
  "standard_name" varchar(256) DEFAULT NULL,
  "standard_column_type" varchar(128) DEFAULT NULL,
  "creator" varchar(64) DEFAULT '',
  "create_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updater" varchar(64) DEFAULT '',
  "update_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "deleted" smallint NOT NULL DEFAULT 0,
  "tenant_id" bigint NOT NULL DEFAULT 0,
  CONSTRAINT "dh_modeling_logic_model_column_pkey" PRIMARY KEY ("id")
);

COMMENT ON TABLE "datahub-center"."dh_modeling_logic_model_column" IS '数据建模-逻辑模型字段（真相源；版本表 fields 仅作历史快照）';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_column"."model_id" IS '逻辑模型编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_column"."field_uid" IS '字段稳定标识（= ModelFieldItem.fieldUid）';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_column"."source_attr_uid" IS '历史来源属性稳定标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_column"."field_name" IS '字段英文名';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_column"."cn_name" IS '字段中文名';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_column"."pk_flag" IS '是否主键：1/0';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_column"."nullable_flag" IS '是否可空：1=可空 0=非空';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_column"."standard_id" IS '关联数据标准编号';

CREATE UNIQUE INDEX IF NOT EXISTS "uk_dh_modeling_logic_model_column_uid"
  ON "datahub-center"."dh_modeling_logic_model_column" ("tenant_id", "model_id", "field_uid")
  WHERE "deleted" = 0;

CREATE INDEX IF NOT EXISTS "idx_dh_modeling_logic_model_column_model"
  ON "datahub-center"."dh_modeling_logic_model_column" ("tenant_id", "model_id")
  WHERE "deleted" = 0;

-- 幂等回填：先软删未删行，再按「优先 PUBLISHED 最新，否则 version 最大」解析 fields JSON 插入
UPDATE "datahub-center"."dh_modeling_logic_model_column"
SET "deleted" = 1, "update_time" = CURRENT_TIMESTAMP
WHERE "deleted" = 0;

WITH picked_version AS (
  SELECT DISTINCT ON (v."model_id")
         v."model_id",
         v."tenant_id",
         v."fields",
         v."creator",
         v."updater"
  FROM "datahub-center"."dh_modeling_logic_model_version" v
  WHERE v."deleted" = 0
    AND v."fields" IS NOT NULL
    AND NULLIF(BTRIM(v."fields"::text), '') IS NOT NULL
    AND NULLIF(BTRIM(v."fields"::text), 'null') IS NOT NULL
    AND NULLIF(BTRIM(v."fields"::text), '[]') IS NOT NULL
  ORDER BY v."model_id",
           CASE WHEN v."status" = 'PUBLISHED' THEN 0 ELSE 1 END,
           v."version" DESC,
           v."id" DESC
),
expanded AS (
  SELECT
    p."model_id",
    p."tenant_id",
    p."creator",
    p."updater",
    elem AS "elem",
    ord AS "ord",
    NULLIF(BTRIM(elem->>'fieldUid'), '') AS "raw_field_uid",
    NULLIF(BTRIM(elem->>'fieldName'), '') AS "field_name",
    NULLIF(BTRIM(elem->>'dataType'), '') AS "data_type"
  FROM picked_version p
  CROSS JOIN LATERAL jsonb_array_elements(
    CASE
      WHEN jsonb_typeof(p."fields"::jsonb) = 'array' THEN p."fields"::jsonb
      ELSE '[]'::jsonb
    END
  ) WITH ORDINALITY AS t(elem, ord)
),
deduped AS (
  SELECT DISTINCT ON (e."tenant_id", e."model_id", COALESCE(e."raw_field_uid", e."field_name", e."ord"::text))
         e.*
  FROM expanded e
  WHERE e."field_name" IS NOT NULL
    AND e."data_type" IS NOT NULL
  ORDER BY e."tenant_id", e."model_id",
           COALESCE(e."raw_field_uid", e."field_name", e."ord"::text),
           e."ord"
)
INSERT INTO "datahub-center"."dh_modeling_logic_model_column" (
  "model_id", "field_uid", "source_attr_uid", "field_name", "cn_name", "data_type",
  "data_length", "data_precision", "data_scale", "data_format", "measure_unit",
  "value_range", "default_value", "pk_flag", "nullable_flag", "sort", "comment", "remark",
  "agg_type", "standard_id", "standard_code", "standard_name", "standard_column_type",
  "creator", "create_time", "updater", "update_time", "deleted", "tenant_id"
)
SELECT
  d."model_id",
  COALESCE(d."raw_field_uid", replace(gen_random_uuid()::text, '-', '')),
  NULLIF(BTRIM(d."elem"->>'sourceAttrUid'), ''),
  d."field_name",
  NULLIF(BTRIM(d."elem"->>'cnName'), ''),
  d."data_type",
  CASE WHEN (d."elem"->>'dataLength') ~ '^-?[0-9]+$' THEN (d."elem"->>'dataLength')::int ELSE NULL END,
  CASE WHEN (d."elem"->>'dataPrecision') ~ '^-?[0-9]+$' THEN (d."elem"->>'dataPrecision')::int ELSE NULL END,
  CASE WHEN (d."elem"->>'dataScale') ~ '^-?[0-9]+$' THEN (d."elem"->>'dataScale')::int ELSE NULL END,
  NULLIF(BTRIM(d."elem"->>'dataFormat'), ''),
  NULLIF(BTRIM(d."elem"->>'measureUnit'), ''),
  NULLIF(BTRIM(d."elem"->>'valueRange'), ''),
  NULLIF(BTRIM(d."elem"->>'defaultValue'), ''),
  CASE
    WHEN lower(COALESCE(d."elem"->>'pkFlag', 'false')) IN ('true', '1', 't', 'yes') THEN 1
    ELSE 0
  END,
  CASE
    WHEN lower(COALESCE(d."elem"->>'nullableFlag', 'true')) IN ('false', '0', 'f', 'no') THEN 0
    ELSE 1
  END,
  CASE
    WHEN (d."elem"->>'sort') ~ '^-?[0-9]+$' THEN (d."elem"->>'sort')::int
    ELSE d."ord"::int
  END,
  NULLIF(BTRIM(d."elem"->>'comment'), ''),
  NULLIF(BTRIM(d."elem"->>'remark'), ''),
  NULLIF(BTRIM(d."elem"->>'aggType'), ''),
  CASE WHEN (d."elem"->>'standardId') ~ '^[0-9]+$' THEN (d."elem"->>'standardId')::bigint ELSE NULL END,
  NULLIF(BTRIM(d."elem"->>'standardCode'), ''),
  NULLIF(BTRIM(d."elem"->>'standardName'), ''),
  NULLIF(BTRIM(d."elem"->>'standardColumnType'), ''),
  COALESCE(d."creator", ''),
  CURRENT_TIMESTAMP,
  COALESCE(d."updater", ''),
  CURRENT_TIMESTAMP,
  0,
  COALESCE(d."tenant_id", 0)
FROM deduped d;

COMMIT;
