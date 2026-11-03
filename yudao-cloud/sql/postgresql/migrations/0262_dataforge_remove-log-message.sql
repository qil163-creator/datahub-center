-- Migration: 0262 删除日志采集与消息集成功能。
-- Purpose: 删除两项功能的六张专属表及序列，清理菜单、按钮、角色授权、套餐引用和平台旧调度配置及日志。
-- Depends on: 0261_dataforge_clear-local-development-history.sql 所在版本的结构，或已核验等价状态；无需开启 0261 的定向历史清理开关，系统权限表须已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；按功能身份定位菜单，表与序列使用 IF EXISTS，重复执行不影响其他数据接入和推送功能。
-- Rollback: 事务失败整笔回滚；提交后专属任务、运行记录、失败消息和文件位点不可恢复，须从执行前备份恢复专属表、序列、菜单授权、套餐及旧调度配置，并恢复对应应用版本。
-- 不可逆操作：永久删除两项功能全部记录，包括逻辑删除记录。执行前备份上述对象，停止旧版采集和消息消费实例。
-- 外部 XXL-Job 管理库中的 dataforgeLogCollectJob 必须停用；本脚本仅清理平台自身配置，不连接其他管理库。
-- 删除表获取排他锁；权限清理期间阻止并发写入。锁等待超过十秒或语句执行超过两分钟则回滚。
-- 不使用级联删除；遇到其他功能菜单子项或数据库依赖时拒绝执行，避免连带删除。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMPORARY TABLE dataforge_0262_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO dataforge_0262_retired_menus (id)
SELECT menu.id FROM system_menu menu
WHERE permission LIKE 'dataforge:log-job:%'
   OR permission LIKE 'dataforge:log-run:%'
   OR permission LIKE 'dataforge:message-job:%'
   OR permission LIKE 'dataforge:message-run:%'
   OR trim(both '/' from coalesce(component, '')) IN ('dataforge/logcollect', 'dataforge/message')
   OR trim(both '/' from coalesce(component, '')) LIKE 'dataforge/logcollect/%'
   OR trim(both '/' from coalesce(component, '')) LIKE 'dataforge/message/%'
   OR component_name LIKE 'DataforgeLogCollect%'
   OR component_name LIKE 'DataforgeMessage%'
   OR (type = 1 AND ((name = '日志采集' AND trim(both '/' from coalesce(path, '')) = 'logs')
                 OR (name = '消息集成' AND trim(both '/' from coalesce(path, '')) = 'message')));

-- 补齐专属页面下没有权限码的按钮；逻辑删除菜单也清理，避免残留授权。
WITH RECURSIVE buttons (id) AS (
    SELECT id FROM dataforge_0262_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3 AND coalesce(child.permission, '') = ''
)
INSERT INTO dataforge_0262_retired_menus (id)
SELECT id FROM buttons ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN dataforge_0262_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM dataforge_0262_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0262 日志采集或消息集成菜单下存在其他功能子项，禁止连带删除';
    END IF;
END $menu_preflight$;

-- 仅移除目标菜单编号，保留其他套餐条目的顺序和数字或字符串类型。
-- 拼接紧凑 JSON，避免额外分隔空格超过 menu_ids 的长度限制。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT '[' || coalesce(string_agg(entry.value::text, ',' ORDER BY entry.ordinality), '') || ']'
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM dataforge_0262_retired_menus retired WHERE retired.id::text = entry.value #>> '{}'
        )
    ),
    updater = 'migration:0262', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
    JOIN dataforge_0262_retired_menus retired ON retired.id::text = entry.value
);

DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM dataforge_0262_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM dataforge_0262_retired_menus);

-- 清理平台中可能保留的旧采集调度配置，保留其他处理器。
DO $legacy_jobs$
BEGIN
    IF to_regclass('"datahub-center".infra_job_log') IS NOT NULL THEN
        DELETE FROM infra_job_log WHERE handler_name = 'dataforgeLogCollectJob';
    END IF;
    IF to_regclass('"datahub-center".infra_job') IS NOT NULL THEN
        DELETE FROM infra_job WHERE handler_name = 'dataforgeLogCollectJob';
    END IF;
END $legacy_jobs$;

-- 先删除明细和运行，再删除任务；外部依赖会使整笔事务失败。
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_log_file_state;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_log_run;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_log_job;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_message_log;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_message_run;
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_message_job;

DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_log_file_state_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_log_run_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_log_job_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_message_log_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_message_run_seq;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_message_job_seq;

COMMIT;
