-- Migration: 0268 数据服务接口新增并发上限与查询超时配置。
-- Purpose: dh_supply_api 新增 max_concurrency（同一时刻最多执行中的请求数，全部节点合计）与
--          timeout_seconds（每条 SQL 语句的执行超时）；均可为空，为空时分别表示不限制、使用平台默认超时。
-- Depends on: 平台库已升级至 0267；dh_supply_api 表存在。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；新增列带 IF NOT EXISTS，约束先删除再创建。
-- Rollback: 加法迁移；回滚应用版本时可保留新列（旧版本不读取）。如需删除，先发布不使用这两列的版本，
--           再 DROP CONSTRAINT 与 DROP COLUMN。
-- 说明：已发布接口的运行配置来自发布快照，新配置需重新发布后生效；平台默认超时与上限由
--       yudao.supply.data-api.default-query-timeout-seconds / max-query-timeout-seconds 配置（默认 30 / 300 秒）。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_supply_api ADD COLUMN IF NOT EXISTS max_concurrency int4;
ALTER TABLE dh_supply_api ADD COLUMN IF NOT EXISTS timeout_seconds int4;

ALTER TABLE dh_supply_api DROP CONSTRAINT IF EXISTS ck_supply_api_max_concurrency;
ALTER TABLE dh_supply_api ADD CONSTRAINT ck_supply_api_max_concurrency
    CHECK (max_concurrency IS NULL OR max_concurrency BETWEEN 0 AND 1000);
ALTER TABLE dh_supply_api DROP CONSTRAINT IF EXISTS ck_supply_api_timeout_seconds;
ALTER TABLE dh_supply_api ADD CONSTRAINT ck_supply_api_timeout_seconds
    CHECK (timeout_seconds IS NULL OR timeout_seconds BETWEEN 1 AND 600);

COMMENT ON COLUMN dh_supply_api.max_concurrency IS '最大并发：同一时刻最多执行中的请求数（全部节点、全部调用方合计），为空或 0 不限制';
COMMENT ON COLUMN dh_supply_api.timeout_seconds IS '查询超时秒数：每条 SQL 语句的执行上限，为空使用平台默认值，且不超过平台上限';

COMMIT;
