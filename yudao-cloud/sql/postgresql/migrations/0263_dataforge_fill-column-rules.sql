-- Migration: 0263 数据填报字段校验规则与字典来源。
-- Purpose: 为填报表字段增加取值范围、正则格式、业务唯一、规则级别和系统字典来源，供发布版本固化并在提交时强校验。
-- Depends on: 填报表字段表 dh_dataforge_fill_table_column 已存在（0262 所在版本的结构）。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；所有列使用 ADD COLUMN IF NOT EXISTS，重复执行不改变已有数据。
-- Rollback: 事务失败整笔回滚；提交后如需撤销，先停止使用新规则的应用版本，再手工删除新增列，
--           删除会永久丢失已配置的规则与字典来源，须先备份该表。
-- 不可逆操作：无；仅新增可空或带默认值的列，不修改、不删除既有数据。
-- 锁：ALTER TABLE 获取短暂排他锁；锁等待超过十秒或语句超过一分钟则回滚。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE "dh_dataforge_fill_table_column"
  ADD COLUMN IF NOT EXISTS "min_value" varchar(40),
  ADD COLUMN IF NOT EXISTS "max_value" varchar(40),
  ADD COLUMN IF NOT EXISTS "regex_pattern" varchar(200),
  ADD COLUMN IF NOT EXISTS "regex_message" varchar(100),
  ADD COLUMN IF NOT EXISTS "unique_flag" int2 NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS "rule_severity" int2 NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS "dict_type" varchar(100);

COMMENT ON COLUMN "dh_dataforge_fill_table_column"."min_value" IS '最小值（数值或日期时间，按字段类型比较；空表示不限）';
COMMENT ON COLUMN "dh_dataforge_fill_table_column"."max_value" IS '最大值（数值或日期时间，按字段类型比较；空表示不限）';
COMMENT ON COLUMN "dh_dataforge_fill_table_column"."regex_pattern" IS '格式正则（仅字符串字段，整体匹配）';
COMMENT ON COLUMN "dh_dataforge_fill_table_column"."regex_message" IS '格式不符时的提示语';
COMMENT ON COLUMN "dh_dataforge_fill_table_column"."unique_flag" IS '同一次填报内该字段取值是否必须唯一：0-否 1-是';
COMMENT ON COLUMN "dh_dataforge_fill_table_column"."rule_severity" IS '取值范围、格式、唯一规则的级别：1-错误（阻断提交） 2-警告';
COMMENT ON COLUMN "dh_dataforge_fill_table_column"."dict_type" IS '选择值来源的系统字典类型；设置后发布时固化字典项，优先于 fill_values';

COMMIT;
