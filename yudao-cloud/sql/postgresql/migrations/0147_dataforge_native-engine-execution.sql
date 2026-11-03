-- Migration: 0147 数据开发原生计算执行授权
-- Purpose: 为 Flink、Spark 原生程序增加独立执行权限，沿用已有任务、版本快照和运行实例，不导入参考系统的账号、集群或资源。
-- Depends on: 0146_dataforge_data-development-workbench.sql 已执行，数据开发工作台菜单及平台系统菜单结构存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；已有有效权限保留，重放不重复插入、不覆盖角色及套餐授权。
-- Rollback: 失败整笔回滚；提交后回退应用可保留权限定义。撤销授权通过角色管理完成；删除菜单前须先处理其角色和套餐引用。
-- 原生程序拥有运行集群代码的能力，不能从普通 SQL 的保存、发布、运行或表级读写权限推导授权。
-- 此迁移仅登记权限，不授予任何角色或套餐；运行环境、租户、项目及可用资源范围由服务端运行配置限制。
-- 系统菜单短时锁等待上限十秒，失败后可排查并重试；不修改既有任务数据。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

DO $preflight$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM system_menu
                   WHERE deleted = 0 AND type = 2
                     AND trim(both '/' from coalesce(component, '')) = 'dataforge/dev/index') THEN
        RAISE EXCEPTION '0147 缺少数据开发菜单，请先执行 0146 或完成等价平台升级';
    END IF;
END $preflight$;

LOCK TABLE system_menu IN SHARE ROW EXCLUSIVE MODE;
SELECT setval('"datahub-center".system_menu_id_seq',
              GREATEST((SELECT last_value FROM "datahub-center".system_menu_id_seq),
                       COALESCE((SELECT max(id) FROM system_menu), 1)), true);

INSERT INTO system_menu (name, permission, type, sort, parent_id, status, creator, updater)
SELECT '原生计算执行', 'dataforge:dev-task:execute-native', 3, 7, page.id, 0,
       'migration:0147', 'migration:0147'
FROM (
    SELECT id FROM system_menu
    WHERE deleted = 0 AND type = 2
      AND trim(both '/' from coalesce(component, '')) = 'dataforge/dev/index'
    ORDER BY id LIMIT 1
) page
WHERE NOT EXISTS (SELECT 1 FROM system_menu
                   WHERE deleted = 0 AND permission = 'dataforge:dev-task:execute-native');

COMMIT;
