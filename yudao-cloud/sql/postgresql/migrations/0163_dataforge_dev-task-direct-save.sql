-- Migration: 0163 数据开发保存即生效，退役独立草稿发布阶段。
-- Purpose: 将发布版本指针改为当前保存版本指针，合并旧发布授权至既有保存权限；保留历史版本、运行记录及原始快照。
-- Depends on: 0162_dataforge_task-monitor-drawers.sql 已执行，或已核验平台达到对应状态；数据开发任务、版本、运行记录和系统权限表已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；只允许旧指针列或新指针列之一存在，权限按唯一有效保存按钮定位，已退役的发布引用不会再次合并。
-- Rollback: 失败整笔回滚；提交后须先停新版应用，从执行前备份恢复发布菜单、角色与套餐引用，并将 current_version_id 改回 published_version_id 后回退应用。新版产生的保存版本不得删除，历史数据不自动逆向转换。
-- 风险：列重命名取得任务表排他锁，权限清理会删除旧发布菜单及引用。升级前停止旧执行与调度节点并备份；锁等待超过十秒或单语句超过两分钟则回滚，升级后刷新权限缓存并重新登录。
-- 旧指针原值保持不变，只表示此前已授权的冻结版本；未发布的旧定义不会被本脚本激活，首次保存由服务重新校验并冻结完整定义。
-- 不启用管理员已停用的按钮或套餐，不继承已停用发布按钮的授权，不增加运行、原生执行或 Schema 执行权限。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

DO $definition_preflight$
DECLARE
    old_pointer boolean;
    new_pointer boolean;
BEGIN
    IF to_regclass('"datahub-center".dh_dataforge_dev_task') IS NULL
       OR to_regclass('"datahub-center".dh_dataforge_dev_task_version') IS NULL
       OR to_regclass('"datahub-center".dh_dataforge_dev_record') IS NULL THEN
        RAISE EXCEPTION '0163 数据开发任务、历史版本或运行记录前置表缺失';
    END IF;
    SELECT EXISTS (SELECT 1 FROM pg_attribute
        WHERE attrelid = 'dh_dataforge_dev_task'::regclass AND attname = 'published_version_id' AND NOT attisdropped)
        INTO old_pointer;
    SELECT EXISTS (SELECT 1 FROM pg_attribute
        WHERE attrelid = 'dh_dataforge_dev_task'::regclass AND attname = 'current_version_id' AND NOT attisdropped)
        INTO new_pointer;
    IF old_pointer = new_pointer THEN
        RAISE EXCEPTION '0163 任务版本指针缺失或旧新列并存，禁止推断并覆盖';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_dataforge_dev_task'::regclass
        AND attname IN ('published_version_id', 'current_version_id') AND NOT attisdropped
        AND atttypid <> 'bigint'::regtype)
       OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_dataforge_dev_task'::regclass
           AND attname = 'revision' AND NOT attisdropped AND atttypid = 'integer'::regtype)
       OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_dataforge_dev_task_version'::regclass
           AND attname = 'snapshot_json' AND NOT attisdropped AND atttypid = 'jsonb'::regtype)
       OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_dataforge_dev_record'::regclass
           AND attname = 'version_id' AND NOT attisdropped AND atttypid = 'bigint'::regtype) THEN
        RAISE EXCEPTION '0163 任务修订号、版本指针或历史快照字段与前置契约不一致';
    END IF;
    IF old_pointer THEN
        ALTER TABLE dh_dataforge_dev_task RENAME COLUMN published_version_id TO current_version_id;
    END IF;
END $definition_preflight$;

COMMENT ON COLUMN dh_dataforge_dev_task.current_version_id IS '当前已校验保存版本编号；旧指针保留此前已授权的冻结版本';
COMMENT ON COLUMN dh_dataforge_dev_task.revision IS '任务定义乐观锁修订号';
COMMENT ON COLUMN dh_dataforge_dev_task_version.snapshot_json IS '不可变保存快照及冻结的上游版本；历史快照原文保留';
COMMENT ON COLUMN dh_dataforge_dev_record.version_id IS '实际执行的保存版本；历史运行仍关联原冻结版本';

LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMPORARY TABLE dataforge_0163_retired_publish (id bigint PRIMARY KEY, inherit_grants boolean NOT NULL) ON COMMIT DROP;
INSERT INTO dataforge_0163_retired_publish (id, inherit_grants)
SELECT id, deleted = 0 AND status = 0 FROM system_menu WHERE permission = 'dataforge:dev-task:publish';

DO $retire_publish$
DECLARE
    save_menu system_menu%ROWTYPE;
    publish_menu system_menu%ROWTYPE;
    matches integer;
    package record;
    entries jsonb;
    next_entries text;
    first_inherited_ordinal bigint;
    save_present boolean;
BEGIN
    SELECT count(*) INTO matches FROM system_menu WHERE deleted = 0 AND permission = 'dataforge:dev-task:save';
    IF matches <> 1 THEN
        RAISE EXCEPTION '0163 需要唯一有效的既有任务保存权限，禁止创建或选择冲突权限';
    END IF;
    SELECT * INTO save_menu FROM system_menu WHERE deleted = 0 AND permission = 'dataforge:dev-task:save';
    IF save_menu.type <> 3 THEN
        RAISE EXCEPTION '0163 保存权限不是既有按钮，禁止覆盖其他入口';
    END IF;
    SELECT count(*) INTO matches FROM system_menu WHERE deleted = 0 AND permission = 'dataforge:dev-task:publish';
    IF matches > 1 THEN
        RAISE EXCEPTION '0163 有效任务发布权限重复，须先核对授权来源';
    END IF;
    IF matches = 1 THEN
        SELECT * INTO publish_menu FROM system_menu WHERE deleted = 0 AND permission = 'dataforge:dev-task:publish';
        IF publish_menu.type <> 3 OR publish_menu.parent_id <> save_menu.parent_id THEN
            RAISE EXCEPTION '0163 发布与保存按钮归属冲突，禁止跨入口合并';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM system_menu child JOIN dataforge_0163_retired_publish retired ON retired.id = child.parent_id) THEN
        RAISE EXCEPTION '0163 发布按钮仍挂有子项，禁止连带删除';
    END IF;

    -- 复用原授权行编号，不分配序列；同租户同角色已有保存授权时直接保留已有授权。
    UPDATE system_role_menu grant_row
    SET menu_id = save_menu.id, updater = 'migration:0163', update_time = CURRENT_TIMESTAMP
    WHERE grant_row.id IN (
        SELECT min(source.id) FROM system_role_menu source
        JOIN dataforge_0163_retired_publish retired ON retired.id = source.menu_id AND retired.inherit_grants
        WHERE source.deleted = 0 AND NOT EXISTS (
            SELECT 1 FROM system_role_menu existing WHERE existing.tenant_id = source.tenant_id
                AND existing.role_id = source.role_id AND existing.menu_id = save_menu.id AND existing.deleted = 0
        )
        GROUP BY source.tenant_id, source.role_id
    );

    -- 在原位置替换首个有效发布条目；数字和字符串类型、其他条目及顺序保持不变，已有保存条目不重复追加。
    FOR package IN SELECT id, menu_ids FROM system_tenant_package
    WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(menu_ids::jsonb) entry(value)
        JOIN dataforge_0163_retired_publish retired ON retired.id::text = entry.value #>> '{}')
    LOOP
        entries := package.menu_ids::jsonb;
        SELECT EXISTS (SELECT 1 FROM jsonb_array_elements(entries) entry(value)
            WHERE entry.value #>> '{}' = save_menu.id::text) INTO save_present;
        SELECT min(entry.ordinality) INTO first_inherited_ordinal
        FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinality)
        JOIN dataforge_0163_retired_publish retired ON retired.id::text = entry.value #>> '{}' AND retired.inherit_grants;

        SELECT '[' || coalesce(string_agg(value::text, ',' ORDER BY ordinality), '') || ']' INTO next_entries
        FROM (
            SELECT entry.ordinality, CASE
                WHEN retired.id IS NOT NULL THEN CASE WHEN jsonb_typeof(entry.value) = 'string'
                    THEN to_jsonb(save_menu.id::text) ELSE to_jsonb(save_menu.id) END
                ELSE entry.value END AS value
            FROM jsonb_array_elements(entries) WITH ORDINALITY entry(value, ordinality)
            LEFT JOIN dataforge_0163_retired_publish retired ON retired.id::text = entry.value #>> '{}'
            WHERE retired.id IS NULL OR (NOT save_present AND entry.ordinality = first_inherited_ordinal)
        ) replacement;
        UPDATE system_tenant_package SET menu_ids = next_entries,
            updater = 'migration:0163', update_time = CURRENT_TIMESTAMP
        WHERE id = package.id AND menu_ids IS DISTINCT FROM next_entries;
    END LOOP;

    DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM dataforge_0163_retired_publish);
    DELETE FROM system_menu WHERE id IN (SELECT id FROM dataforge_0163_retired_publish);
END $retire_publish$;

COMMIT;
