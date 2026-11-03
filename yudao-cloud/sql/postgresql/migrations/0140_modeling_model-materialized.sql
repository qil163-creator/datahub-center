-- Migration: 0140 modeling model materialized
-- Purpose: 新建「发布模型」物化记录表与发布日志表（对齐 qData dp_model_materialized，独立日志表便于页签查询）
-- Depends on: 0139_modeling_drop-design-check.sql
-- Target: existing PostgreSQL database, schema datahub-center
-- Repeatable: yes; CREATE IF NOT EXISTS
-- Rollback: DROP TABLE/SEQUENCE（会丢失已发布记录与日志）
-- Note: 仓内 0139 已被 drop-design-check 占用，本迁移用 0140；删旧发布见 0141

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

CREATE SEQUENCE IF NOT EXISTS "datahub-center"."dh_modeling_model_materialized_seq";

CREATE TABLE IF NOT EXISTS "datahub-center"."dh_modeling_model_materialized" (
  "id" bigint NOT NULL DEFAULT nextval('"datahub-center".dh_modeling_model_materialized_seq'::regclass),
  "model_id" bigint NOT NULL,
  "datasource_id" bigint NOT NULL,
  "datasource_type" varchar(64) DEFAULT NULL,
  "datasource_name" varchar(128) DEFAULT NULL,
  "model_name" varchar(128) DEFAULT NULL,
  "model_alias" varchar(128) DEFAULT NULL,
  "schema_name" varchar(128) DEFAULT NULL,
  "table_name" varchar(128) DEFAULT NULL,
  "publish_mode" varchar(32) NOT NULL,
  "status" varchar(32) NOT NULL,
  "ddl_sql" text DEFAULT NULL,
  "message" varchar(2000) DEFAULT NULL,
  "description" varchar(500) DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  "creator" varchar(64) DEFAULT '',
  "create_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updater" varchar(64) DEFAULT '',
  "update_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "deleted" smallint NOT NULL DEFAULT 0,
  "tenant_id" bigint NOT NULL DEFAULT 0,
  CONSTRAINT "dh_modeling_model_materialized_pkey" PRIMARY KEY ("id")
);

COMMENT ON TABLE "datahub-center"."dh_modeling_model_materialized" IS '数据建模-模型物化发布记录（一模型一数据源一行）';
COMMENT ON COLUMN "datahub-center"."dh_modeling_model_materialized"."model_id" IS '逻辑模型编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_model_materialized"."datasource_id" IS '目标数据源编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_model_materialized"."publish_mode" IS '发布模式：REBUILD 删除重建 / INCREMENT 增量';
COMMENT ON COLUMN "datahub-center"."dh_modeling_model_materialized"."status" IS '状态：RUNNING / SUCCESS / FAIL';

CREATE UNIQUE INDEX IF NOT EXISTS "uk_dh_modeling_model_materialized_model_ds"
  ON "datahub-center"."dh_modeling_model_materialized" ("tenant_id", "model_id", "datasource_id")
  WHERE "deleted" = 0;

CREATE INDEX IF NOT EXISTS "idx_dh_modeling_model_materialized_model"
  ON "datahub-center"."dh_modeling_model_materialized" ("tenant_id", "model_id")
  WHERE "deleted" = 0;

CREATE SEQUENCE IF NOT EXISTS "datahub-center"."dh_modeling_model_materialized_log_seq";

CREATE TABLE IF NOT EXISTS "datahub-center"."dh_modeling_model_materialized_log" (
  "id" bigint NOT NULL DEFAULT nextval('"datahub-center".dh_modeling_model_materialized_log_seq'::regclass),
  "materialized_id" bigint NOT NULL,
  "model_id" bigint NOT NULL,
  "publish_mode" varchar(32) NOT NULL,
  "status" varchar(32) NOT NULL,
  "ddl_sql" text DEFAULT NULL,
  "message" varchar(2000) DEFAULT NULL,
  "operator" varchar(64) DEFAULT NULL,
  "start_time" timestamp DEFAULT NULL,
  "end_time" timestamp DEFAULT NULL,
  "creator" varchar(64) DEFAULT '',
  "create_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updater" varchar(64) DEFAULT '',
  "update_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "deleted" smallint NOT NULL DEFAULT 0,
  "tenant_id" bigint NOT NULL DEFAULT 0,
  CONSTRAINT "dh_modeling_model_materialized_log_pkey" PRIMARY KEY ("id")
);

COMMENT ON TABLE "datahub-center"."dh_modeling_model_materialized_log" IS '数据建模-模型物化发布日志（每次发布一行）';
COMMENT ON COLUMN "datahub-center"."dh_modeling_model_materialized_log"."materialized_id" IS '物化记录编号';

CREATE INDEX IF NOT EXISTS "idx_dh_modeling_model_materialized_log_mat"
  ON "datahub-center"."dh_modeling_model_materialized_log" ("tenant_id", "materialized_id")
  WHERE "deleted" = 0;

COMMIT;
