-- Migration: 0158 退役独立接口管理，接口配置归属采集任务和 ETL 节点。
-- Purpose: 删除旧独立 API 定义表和序列，清理旧菜单、角色授权与套餐引用，更新 API 输入组件说明。
-- Depends on: 0157_asset_governance-terms.sql 已执行，或已核验平台达到对应状态；系统菜单、角色菜单、租户套餐和 ETL 工具表已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；旧独立 API 定义已由用户确认为可删除的测试数据。新库使用包含本迁移的全量基线。
-- Repeatable: 是；目标菜单按功能标识定位，表和序列使用 IF EXISTS，工具说明仅在发生变化时更新。
-- Rollback: 失败整笔回滚；提交后旧 API 测试记录不可恢复。如需回滚，先从执行前备份恢复定义表、序列、菜单授权、套餐引用和工具说明，再恢复旧应用版本。
-- 不可逆操作：直接删除旧独立 API 定义表中的全部测试数据，包括逻辑删除记录；采集任务、运行记录、逐表执行结果及 Supply API 服务不受影响。
-- 删除表获取排他锁，菜单与授权清理期间阻止并发写入；锁等待超过十秒或语句执行超过两分钟即回滚。不使用 CASCADE，存在未预期依赖时拒绝执行。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMPORARY TABLE dataforge_0158_retired_menus (id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO dataforge_0158_retired_menus (id)
SELECT id FROM system_menu
WHERE permission LIKE 'dataforge:data-api:%'
   OR trim(both '/' from coalesce(component, '')) = 'dataforge/dataapi/index'
   OR component_name = 'DataforgeDataApi';

-- 补齐页面下无权限码的专用按钮；存在其他功能子项时拒绝连带清理。
WITH RECURSIVE buttons (id) AS (
    SELECT id FROM dataforge_0158_retired_menus
    UNION
    SELECT child.id FROM system_menu child JOIN buttons parent ON child.parent_id = parent.id
    WHERE child.type = 3 AND coalesce(child.permission, '') = ''
)
INSERT INTO dataforge_0158_retired_menus (id)
SELECT id FROM buttons ON CONFLICT (id) DO NOTHING;

DO $menu_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM system_menu child JOIN dataforge_0158_retired_menus parent ON child.parent_id = parent.id
        WHERE NOT EXISTS (SELECT 1 FROM dataforge_0158_retired_menus retired WHERE retired.id = child.id)
    ) THEN
        RAISE EXCEPTION '0158 旧 API 管理页面下存在其他功能子项，禁止连带删除';
    END IF;
END $menu_preflight$;

-- 保留其余套餐条目的顺序和数字或字符串类型；拼接紧凑 JSON，避免超过 menu_ids 长度限制。
UPDATE system_tenant_package package
SET menu_ids = (
        SELECT '[' || coalesce(string_agg(entry.value::text, ',' ORDER BY entry.ordinality), '') || ']'
        FROM jsonb_array_elements(package.menu_ids::jsonb) WITH ORDINALITY entry(value, ordinality)
        WHERE NOT EXISTS (
            SELECT 1 FROM dataforge_0158_retired_menus retired WHERE retired.id::text = entry.value #>> '{}'
        )
    ),
    updater = 'migration:0158', update_time = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
    JOIN dataforge_0158_retired_menus retired ON retired.id::text = entry.value
);

DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM dataforge_0158_retired_menus);
DELETE FROM system_menu WHERE id IN (SELECT id FROM dataforge_0158_retired_menus);

UPDATE dh_dataforge_etl_tool
SET description = '在节点中独立配置 HTTP 接口、测试请求并解析返回字段，支持分页拉取',
    updater = 'migration:0158', update_time = CURRENT_TIMESTAMP
WHERE code = 'rest-api'
  AND description IS DISTINCT FROM '在节点中独立配置 HTTP 接口、测试请求并解析返回字段，支持分页拉取';

-- 接口定义已经随采集任务和 ETL 节点保存；直接清理确认可删除的旧测试数据。
DROP TABLE IF EXISTS "datahub-center".dh_dataforge_api;
DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_api_seq;

COMMIT;
