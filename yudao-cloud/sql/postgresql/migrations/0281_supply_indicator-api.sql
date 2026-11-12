-- Migration: 0281 数据服务发布建模指标（INDICATOR 配置方式）
-- Purpose: dh_supply_api 新增 indicator_config_json，保存 INDICATOR 配置方式下发布时固定的指标口径
--          （指标、维度、粒度、行数上限，以及请求参数到时间范围/维度过滤的映射）；config_type 说明补充 INDICATOR。
--          发布版本快照（dh_supply_api_release.config_json）与契约摘要由应用写入该字段，本脚本不改动历史版本。
-- Depends on: 0280_asset_data-export.sql（编号前驱）；0278_modeling_composite-indicator-query.sql（指标查询服务）。
--             运行时 supply-server 经 modeling-server 的 /rpc-api/modeling/indicator-query/query 出数。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；列使用 IF NOT EXISTS，注释可重复设置。
-- Rollback: 先停用并删除 config_type = 'INDICATOR' 的接口及其发布版本，再执行
--           ALTER TABLE dh_supply_api DROP COLUMN indicator_config_json；该列只服务指标接口，删除不影响其他配置方式。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_supply_api ADD COLUMN IF NOT EXISTS indicator_config_json text;
ALTER TABLE dh_supply_api DROP CONSTRAINT IF EXISTS ck_supply_api_indicator_config;
ALTER TABLE dh_supply_api ADD CONSTRAINT ck_supply_api_indicator_config
    CHECK (indicator_config_json IS NULL OR length(indicator_config_json) <= 4000);
COMMENT ON COLUMN dh_supply_api.indicator_config_json IS '指标配置 JSON（INDICATOR 模式）：{indicatorIds, dimensions, timeGrain, limit, startParam, endParam, filters:[{param, fieldUid}]}';
COMMENT ON COLUMN dh_supply_api.config_type IS '配置方式：TABLE 单表、SQL 脚本、FORWARD 转发、ETL_BATCH 指定成果批次、INDICATOR 发布建模指标';
COMMIT;
