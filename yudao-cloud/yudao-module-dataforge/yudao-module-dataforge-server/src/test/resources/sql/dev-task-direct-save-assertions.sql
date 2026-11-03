-- 0163 首次执行和重放后都应满足；真实 PostgreSQL 的 JSONB、授权去重、禁用状态及大整数编号验证。
SET search_path TO "datahub-center", pg_catalog;
DO $assert_direct_save$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_dataforge_dev_task'::regclass
        AND attname = 'published_version_id' AND NOT attisdropped)
       OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'dh_dataforge_dev_task'::regclass
           AND attname = 'current_version_id' AND NOT attisdropped) THEN
        RAISE EXCEPTION '0163 指针列没有完成唯一重命名';
    END IF;
    IF EXISTS (SELECT 1 FROM fixture_0163_before previous
        LEFT JOIN dh_dataforge_dev_task task ON task.id = previous.id
        WHERE previous.kind = 'task' AND (task.id IS NULL
            OR (to_jsonb(task) - 'current_version_id') IS DISTINCT FROM (previous.row_value - 'published_version_id')
            OR coalesce(to_jsonb(task.current_version_id), 'null'::jsonb) IS DISTINCT FROM previous.row_value -> 'published_version_id')) THEN
        RAISE EXCEPTION '0163 改写了旧定义、未发布状态或版本指针，不能伪造旧定义生效';
    END IF;
    IF (SELECT count(*) FROM dh_dataforge_dev_task_version) <> 3
       OR EXISTS (SELECT 1 FROM fixture_0163_before previous LEFT JOIN dh_dataforge_dev_task_version historical_version ON historical_version.id = previous.id
           WHERE previous.kind = 'version' AND previous.row_value IS DISTINCT FROM to_jsonb(historical_version))
       OR (SELECT count(*) FROM dh_dataforge_dev_record) <> 2
       OR EXISTS (SELECT 1 FROM fixture_0163_before previous LEFT JOIN dh_dataforge_dev_record record ON record.id = previous.id
           WHERE previous.kind = 'record' AND previous.row_value IS DISTINCT FROM to_jsonb(record)) THEN
        RAISE EXCEPTION '0163 历史版本、运行记录或旧 JSON 字段原文变化';
    END IF;
    IF EXISTS (SELECT 1 FROM system_menu WHERE permission = 'dataforge:dev-task:publish')
       OR EXISTS (SELECT 1 FROM system_role_menu WHERE menu_id = 9007199254740995)
       OR EXISTS (SELECT 1 FROM system_tenant_package package, jsonb_array_elements(package.menu_ids::jsonb) entry(value)
           WHERE entry.value #>> '{}' = '9007199254740995') THEN
        RAISE EXCEPTION '0163 发布菜单或授权引用未清理';
    END IF;
    IF (SELECT count(*) FROM system_role_menu WHERE menu_id = 9007199254740993 AND deleted = 0) <> 3
       OR EXISTS (SELECT 1 FROM system_role_menu WHERE menu_id = 9007199254740993 AND deleted = 0
           GROUP BY tenant_id, role_id HAVING count(*) > 1)
       OR NOT EXISTS (SELECT 1 FROM system_role_menu WHERE id = 1 AND menu_id = 9007199254740993 AND tenant_id = 1)
       OR NOT EXISTS (SELECT 1 FROM system_role_menu WHERE id = 5 AND menu_id = 9007199254740993 AND tenant_id = 2)
       OR (SELECT count(*) FROM system_role_menu WHERE id IN (7, 8) AND deleted = 1) <> 2
       OR (SELECT count(*) FROM system_role_menu WHERE menu_id = 9007199254740997) <> 1 THEN
        RAISE EXCEPTION '0163 角色授权合并丢失、重复、跨租户或新增运行授权';
    END IF;
    IF (SELECT menu_ids FROM system_tenant_package WHERE id = 1) <> '[101,"9007199254740993",202]'
       OR (SELECT menu_ids FROM system_tenant_package WHERE id = 2) <> '["9007199254740993",303]'
       OR (SELECT menu_ids FROM system_tenant_package WHERE id = 3) <> '[9007199254740993,404]'
       OR (SELECT status FROM system_tenant_package WHERE id = 3) <> 1
       OR (SELECT deleted FROM system_tenant_package WHERE id = 4) <> 1
       OR (SELECT menu_ids FROM system_tenant_package WHERE id = 5) <> '[9007199254740997,606]' THEN
        RAISE EXCEPTION '0163 套餐编号类型、顺序、去重、停用状态或无关内容变化';
    END IF;
END $assert_direct_save$;
