-- Migration: 0162 消息集成与日志采集任务内执行监控。
-- Purpose: 将独立运行监控页转为对应任务页的执行监控按钮，删除权限同级归入任务页；保留原权限编号、启用状态及角色和租户套餐关联。
-- Depends on: 0161_dataforge_single-push-task-entry.sql 已执行，或已核验平台达到对应状态；消息集成和日志采集任务、运行查询及删除菜单已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；按唯一权限和组件定位菜单，首次执行与重放均保留原编号，只有菜单布局变化时更新审计字段。
-- Rollback: 失败整笔回滚；提交后从执行前备份恢复受影响的 system_menu 记录并回退前端版本；不删除运行记录，不修改角色或套餐授权。
-- 所有语句在同一事务内执行；菜单锁等待超过十秒或单语句超过两分钟则回滚。部署后刷新权限缓存并重新登录。
-- 不启用被管理员禁用的权限，不新增任务查询授权；访问任务页仍须具备原任务查询权限。遇到重复权限、入口归属冲突或混挂其他子项时拒绝执行。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu IN SHARE ROW EXCLUSIVE MODE;

DO $task_monitor_entries$
DECLARE
    entry record;
    task_menu system_menu%ROWTYPE;
    query_menu system_menu%ROWTYPE;
    delete_menu system_menu%ROWTYPE;
    matches integer;
BEGIN
    FOR entry IN
        SELECT * FROM (VALUES
            ('dataforge/message/job/index', 'dataforge/message/run/index', 'dataforge:message-job:query',
             'dataforge:message-run:query', 'dataforge:message-run:delete', 5, 6),
            ('dataforge/logcollect/job/index', 'dataforge/logcollect/run/index', 'dataforge:log-job:query',
             'dataforge:log-run:query', 'dataforge:log-run:delete', 6, 7)
        ) AS entries(task_component, run_component, task_permission, query_permission, delete_permission, query_sort, delete_sort)
    LOOP
        SELECT count(*) INTO matches FROM system_menu
        WHERE deleted = 0 AND type = 2
          AND trim(both '/' from coalesce(component, '')) = entry.task_component;
        IF matches <> 1 THEN
            RAISE EXCEPTION '0162 需要唯一有效的任务页：%', entry.task_component;
        END IF;
        SELECT * INTO task_menu FROM system_menu
        WHERE deleted = 0 AND type = 2
          AND trim(both '/' from coalesce(component, '')) = entry.task_component;
        IF task_menu.permission <> entry.task_permission THEN
            RAISE EXCEPTION '0162 任务页绑定了其他权限，禁止覆盖：%', entry.task_component;
        END IF;

        SELECT count(*) INTO matches FROM system_menu
        WHERE deleted = 0 AND permission = entry.query_permission;
        IF matches <> 1 THEN
            RAISE EXCEPTION '0162 运行查询权限缺失或重复：%', entry.query_permission;
        END IF;
        SELECT * INTO query_menu FROM system_menu
        WHERE deleted = 0 AND permission = entry.query_permission;
        IF NOT (
            (query_menu.type = 2 AND query_menu.parent_id = task_menu.parent_id
             AND trim(both '/' from coalesce(query_menu.component, '')) = entry.run_component)
            OR (query_menu.type = 3 AND query_menu.parent_id = task_menu.id
                AND coalesce(query_menu.component, '') = '')
        ) THEN
            RAISE EXCEPTION '0162 运行查询入口归属冲突：%', entry.query_permission;
        END IF;

        SELECT count(*) INTO matches FROM system_menu
        WHERE deleted = 0 AND permission = entry.delete_permission;
        IF matches <> 1 THEN
            RAISE EXCEPTION '0162 运行删除权限缺失或重复：%', entry.delete_permission;
        END IF;
        SELECT * INTO delete_menu FROM system_menu
        WHERE deleted = 0 AND permission = entry.delete_permission;
        IF delete_menu.type <> 3 OR delete_menu.parent_id NOT IN (query_menu.id, task_menu.id) THEN
            RAISE EXCEPTION '0162 运行删除权限归属冲突：%', entry.delete_permission;
        END IF;
        IF EXISTS (
            SELECT 1 FROM system_menu
            WHERE parent_id = query_menu.id AND deleted = 0 AND id <> delete_menu.id
        ) THEN
            RAISE EXCEPTION '0162 运行监控页混挂其他子项，禁止移动：%', entry.query_permission;
        END IF;

        -- 查询入口沿用原编号和权限码，清除页面路由；角色授权和租户套餐无需改写。
        UPDATE system_menu
        SET name = '执行监控', type = 3, sort = entry.query_sort, parent_id = task_menu.id,
            path = '', icon = '#', component = NULL, component_name = NULL,
            updater = 'migration:0162', update_time = CURRENT_TIMESTAMP
        WHERE id = query_menu.id
          AND (name, type, sort, parent_id, path, icon, component, component_name)
              IS DISTINCT FROM ('执行监控', 3, entry.query_sort, task_menu.id, '', '#', NULL, NULL);

        -- 删除按钮与查询按钮同级，避免按钮下继续挂子按钮。
        UPDATE system_menu
        SET parent_id = task_menu.id, sort = entry.delete_sort,
            updater = 'migration:0162', update_time = CURRENT_TIMESTAMP
        WHERE id = delete_menu.id
          AND (parent_id, sort) IS DISTINCT FROM (task_menu.id, entry.delete_sort);
    END LOOP;
END $task_monitor_entries$;

COMMIT;
