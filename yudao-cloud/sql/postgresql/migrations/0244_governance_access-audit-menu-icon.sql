-- Migration: 0244 修复访问审计菜单图标。
-- Purpose: 将缺失或不可用的访问审计图标替换为项目支持的图标，保留自定义图标。
-- Depends on: 0243_governance_remove-safety-dashboard-menu.sql；system_menu 表已存在。
-- Target: 已有 PostgreSQL 数据库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；仅更新旧图标或空图标，重复执行不会产生额外变更。
-- Rollback: 按执行前备份恢复目标菜单图标；原图标不可用，不建议回退。
-- 事务边界：单条更新语句；执行后重新登录以刷新菜单缓存。

UPDATE "datahub-center".system_menu
SET icon = 'carbon:document-view', updater = 'migration:0244', update_time = CURRENT_TIMESTAMP
WHERE deleted = 0
  AND type = 2
  AND trim(both '/' from coalesce(component, '')) = 'governance/security/accesslog/index'
  AND trim(coalesce(icon, '')) IN ('', 'carbon:document-audit');
