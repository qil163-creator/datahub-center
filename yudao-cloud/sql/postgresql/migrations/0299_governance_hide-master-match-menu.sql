-- Migration: 0299 隐藏相似匹配与合并的侧栏入口。
-- Purpose: 从主数据维护的查重按钮进入相似匹配页面，保留原路由和角色授权。
-- Depends on: 0298_governance_work-order-and-term-menus.sql（编号前驱），以及 0295_governance_master-match-merge.sql 创建的页面。
-- Target: 已有 PostgreSQL 平台库的 "datahub-center" 模式。
-- Repeatable: 是；只更新仍可见的目标菜单。
-- Rollback: 提交前可回滚。提交后按执行前记录恢复对应菜单的 visible 值。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';

DO $hide_master_match_menu$
DECLARE
    page_id bigint;
BEGIN
    SELECT m.id INTO STRICT page_id
    FROM system_menu m
    JOIN system_menu master ON master.id = m.parent_id AND master.type = 1 AND master.deleted = 0
    JOIN system_menu govern ON govern.id = master.parent_id AND govern.type = 1 AND govern.deleted = 0
    WHERE m.type = 2 AND m.deleted = 0
      AND trim(both '/' from m.component) = 'governance/mastermatch/index'
      AND trim(both '/' from m.path) = 'mastermatch'
      AND trim(both '/' from master.path) = 'master'
      AND trim(both '/' from govern.path) = 'govern'
    FOR UPDATE OF m;

    UPDATE system_menu
    SET visible = false, updater = 'migration:0299', update_time = CURRENT_TIMESTAMP
    WHERE id = page_id AND visible = true;
EXCEPTION
    WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
        RAISE EXCEPTION '0299：主数据相似匹配菜单缺失或不唯一，请核对 0295 的菜单状态';
END;
$hide_master_match_menu$;

COMMIT;
