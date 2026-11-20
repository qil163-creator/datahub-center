-- Migration: 0301 删除数据服务应用的“是否公开”字段。
-- Purpose: dh_supply_app.public_flag 未参与鉴权、可见范围或数据市场选择等任何业务判断，仅作展示和筛选，易让用户误以为有访问控制作用，故从平台库中删除。
-- Depends on: 全量基线中已存在 dh_supply_app 表；本迁移不依赖其他编号迁移的结果。
-- Target: 已有 PostgreSQL 平台库的 "datahub-center" 模式；新库使用已同步的全量基线。
-- Repeatable: 是；列不存在时 DROP COLUMN IF EXISTS 不做任何操作。
-- Rollback: 不可逆，删除列会丢弃各应用原有的公开标记值。如需保留，执行前先备份 id 与 public_flag；回滚时重新添加 public_flag int2 NOT NULL DEFAULT 1 并按备份回填。需先停用或升级仍读写该列的旧版应用服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';

ALTER TABLE dh_supply_app
    DROP COLUMN IF EXISTS public_flag;

COMMIT;
