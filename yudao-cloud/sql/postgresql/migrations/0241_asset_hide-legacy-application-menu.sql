-- Migration: 0241 隐藏旧的部门申请菜单。
-- Purpose: 将个人申请入口整合到我的资产，仅隐藏原页面菜单，不修改权限、角色和业务数据。
-- Depends on: 0240_governance_category-term-menu-identity-repair.sql，以及已有 system_menu 表。
-- Target: 已有 PostgreSQL 数据库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；仅对身份匹配且当前可见的菜单执行一次更新。
-- Rollback: 从执行前备份恢复对应菜单的 visible 值；不自动更改其他菜单或授权。
-- 事务边界：以下操作在单一事务内完成；不存在身份匹配的菜单时不做修改。
-- 执行前备份菜单表；提交后刷新权限缓存并重新登录。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu IN SHARE ROW EXCLUSIVE MODE;

DO $hide_legacy_application_menu$
DECLARE
    matches integer;
BEGIN
    SELECT count(*) INTO matches
    FROM system_menu
    WHERE deleted = 0
      AND trim(both '/' from coalesce(component, '')) = 'market/apply/index';

    IF matches > 1 THEN
        RAISE EXCEPTION '0241 旧申请菜单组件身份重复，请核对后再执行';
    END IF;

    IF EXISTS (
        SELECT 1 FROM system_menu
        WHERE deleted = 0
          AND trim(both '/' from coalesce(component, '')) = 'market/apply/index'
          AND (type IS DISTINCT FROM 2
               OR component_name IS DISTINCT FROM 'MarketApply'
               OR permission IS DISTINCT FROM 'asset:market:query')
    ) THEN
        RAISE EXCEPTION '0241 旧申请菜单身份冲突，请核对类型、组件名和权限';
    END IF;

    UPDATE system_menu
    SET visible = false,
        updater = 'migration:0241',
        update_time = CURRENT_TIMESTAMP
    WHERE deleted = 0
      AND type = 2
      AND visible = true
      AND trim(both '/' from coalesce(component, '')) = 'market/apply/index'
      AND component_name = 'MarketApply'
      AND permission = 'asset:market:query';
END $hide_legacy_application_menu$;

COMMIT;
