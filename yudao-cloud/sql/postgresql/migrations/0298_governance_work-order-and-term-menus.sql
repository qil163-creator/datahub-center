-- Migration: 0298 修复治理术语类目入口并补齐问题工单操作授权
-- Purpose: 按组件和权限恢复术语类目菜单层级，保留既有授权，补齐六个工单按钮。
-- Depends on: 0297_system_upstream-sync-422a95bdb.sql；已有治理类目目录、问题工单页面和系统菜单序列。
-- Target: 已有 PostgreSQL 平台库的 "datahub-center" 模式。须能维护菜单、角色菜单、套餐和两个菜单序列。
-- Repeatable: 是；复用已存在的页面和按钮编号，重放不会追加授权或菜单。
-- Rollback: 提交前可回滚。提交后按升级前备份恢复受影响的菜单及重复按钮的授权引用；先核对升级后新增的授权。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu, system_tenant_package IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE governance_0298_menu_alias (old_id bigint PRIMARY KEY, kept_id bigint NOT NULL) ON COMMIT DROP;

DO $menus$
DECLARE
    category_id bigint;
    work_order_id bigint;
    term_id bigint;
    action_id bigint;
    misplaced_id bigint;
    high_id bigint;
    original_high bigint;
    menu_seq record;
    action record;
BEGIN
    -- 0240 允许不同安装使用不同目录编号，用已安装的治理类目页面识别其目录。
    SELECT d.id INTO STRICT category_id FROM system_menu d
     WHERE d.type = 1 AND d.deleted = 0 AND trim(both '/' from d.path) = 'category'
       AND EXISTS (SELECT 1 FROM system_menu s WHERE s.parent_id = d.id AND s.deleted = 0 AND s.type = 2
                   AND trim(both '/' from s.component) IN ('governance/categorydata/index',
                       'governance/categorycleanrule/index','governance/categorydatameta/index',
                       'governance/categorydataquality/index','asset/categorytag/index'));
    SELECT id INTO STRICT work_order_id FROM system_menu WHERE type = 2 AND deleted = 0
       AND trim(both '/' from component) = 'governance/quality/work-order/index';

    -- 默认值与框架分别使用两个序列。按既有缓存大小取得事务锁，再从共同高位分配，避免与并发发号冲突。
    FOR menu_seq IN SELECT seqrelid::regclass AS seq, seqcache FROM pg_sequence
            WHERE seqrelid IN ('system_menu_seq'::regclass, 'system_menu_id_seq'::regclass) ORDER BY seqrelid LOOP
        EXECUTE format('ALTER SEQUENCE %s CACHE %s', menu_seq.seq, menu_seq.seqcache);
    END LOOP;
    SELECT greatest(max(id), (SELECT last_value FROM system_menu_seq),
                             (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    original_high := high_id;

    -- 标量子查询在多个页面使用相同组件时会中止事务，避免误选用户菜单。
    term_id := (SELECT id FROM system_menu WHERE deleted = 0 AND type = 2
                    AND trim(both '/' from component) = 'governance/categoryterm/index');
    IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = category_id
                  AND trim(both '/' from path) = 'category-term' AND id IS DISTINCT FROM term_id) THEN
        RAISE EXCEPTION '0298：治理目录的 category-term 路径已被其他菜单占用';
    END IF;
    IF term_id IS NULL THEN
        high_id := high_id + 1;
        term_id := high_id;
        INSERT INTO system_menu(id, name, type, sort, parent_id, path, icon, component, component_name, creator, updater)
        VALUES (term_id, '术语类目管理', 2, 60, category_id, 'category-term', 'carbon:term',
                'governance/categoryterm/index', 'CategoryTerm', 'migration:0298', 'migration:0298');
    ELSE
        UPDATE system_menu SET parent_id = category_id, updater = 'migration:0298', update_time = CURRENT_TIMESTAMP
         WHERE id = term_id AND parent_id <> category_id;
    END IF;

    FOR action IN SELECT * FROM (VALUES
        (term_id,'governance:category-term:query','术语类目管理查询',1),
        (term_id,'governance:category-term:create','术语类目管理创建',2),
        (term_id,'governance:category-term:update','术语类目管理更新',3),
        (term_id,'governance:category-term:delete','术语类目管理删除',4),
        (work_order_id,'governance:quality-work-order:assign','问题工单认责',5),
        (work_order_id,'governance:quality-work-order:claim','问题工单认领',6),
        (work_order_id,'governance:quality-work-order:confirm','问题工单验收',7),
        (work_order_id,'governance:quality-work-order:reopen','问题工单重新打开',8),
        (work_order_id,'governance:quality-work-order:coordinate','问题工单协调',9),
        (work_order_id,'governance:quality-work-order:raw-query','问题工单原文明细查询',10)
    ) a(page_id, permission, label, sort) LOOP
        action_id := (SELECT id FROM system_menu WHERE parent_id = action.page_id AND deleted = 0
                         AND type = 3 AND permission = action.permission);
        -- 只修复 0238 在编号冲突时误挂到“研发写入调试”下的按钮，保留其他页面可能复用的业务权限。
        misplaced_id := (SELECT m.id FROM system_menu m JOIN system_menu p ON p.id = m.parent_id
                          WHERE action.page_id = term_id AND m.deleted = 0 AND m.type = 3 AND m.permission = action.permission
                            AND m.creator = 'migration:0238' AND p.type = 3 AND p.permission = 'dataforge:dev-task:write');
        IF misplaced_id IS NOT NULL AND action_id IS NULL THEN
            UPDATE system_menu SET parent_id = term_id, updater = 'migration:0298', update_time = CURRENT_TIMESTAMP
             WHERE id = misplaced_id;
        ELSIF misplaced_id IS NOT NULL THEN
            -- 受影响库若曾补跑 0240，正确按钮和历史错挂按钮可能同时存在；合并相同权限的引用。
            INSERT INTO governance_0298_menu_alias VALUES (misplaced_id, action_id);
        ELSIF action_id IS NULL THEN
            high_id := high_id + 1;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, creator, updater)
            VALUES (high_id, action.label, action.permission, 3, action.sort, action.page_id, 'migration:0298', 'migration:0298');
        END IF;
    END LOOP;
    IF high_id > original_high THEN
        EXECUTE format('ALTER SEQUENCE system_menu_seq RESTART WITH %s', high_id + 1);
        EXECUTE format('ALTER SEQUENCE system_menu_id_seq RESTART WITH %s', high_id + 1);
    END IF;
END;
$menus$;

DO $refs$
DECLARE
    package record;
    updated_ids text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM governance_0298_menu_alias) THEN RETURN; END IF;
    IF EXISTS (SELECT 1 FROM system_menu child JOIN governance_0298_menu_alias a ON a.old_id = child.parent_id
                WHERE child.deleted = 0) THEN
        RAISE EXCEPTION '0298：待合并的术语按钮仍有子菜单，需先核对父级';
    END IF;
    -- 普通角色不会自动获得新的工单权限；仅将已有术语按钮授权映射到同一业务权限。
    UPDATE system_role_menu g SET menu_id = a.kept_id, updater = 'migration:0298', update_time = CURRENT_TIMESTAMP
      FROM governance_0298_menu_alias a WHERE g.menu_id = a.old_id AND g.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu kept WHERE kept.role_id = g.role_id
                           AND kept.tenant_id = g.tenant_id AND kept.menu_id = a.kept_id AND kept.deleted = 0);
    UPDATE system_role_menu g SET deleted = 1, updater = 'migration:0298', update_time = CURRENT_TIMESTAMP
      FROM governance_0298_menu_alias a WHERE g.menu_id = a.old_id AND g.deleted = 0;

    -- 保留套餐菜单的顺序、数字或字符串表示；已有正确编号时只移除同一权限的旧引用。
    FOR package IN SELECT id, menu_ids::jsonb AS entries FROM system_tenant_package p
                    WHERE p.deleted = 0 AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(p.menu_ids::jsonb) e(id)
                                                     JOIN governance_0298_menu_alias a ON a.old_id::text = e.id) LOOP
        SELECT '[' || string_agg(value::text, ',' ORDER BY ord) || ']' INTO updated_ids FROM (
            SELECT e.ord, CASE WHEN a.old_id IS NULL THEN e.value
                               WHEN jsonb_typeof(e.value) = 'string' THEN to_jsonb(a.kept_id::text)
                               ELSE to_jsonb(a.kept_id) END AS value,
                   a.old_id, row_number() OVER (PARTITION BY a.old_id ORDER BY e.ord) AS occurrence,
                   EXISTS (SELECT 1 FROM jsonb_array_elements_text(package.entries) k(id)
                            WHERE k.id = a.kept_id::text) AS has_kept
              FROM jsonb_array_elements(package.entries) WITH ORDINALITY e(value, ord)
              LEFT JOIN governance_0298_menu_alias a ON a.old_id::text = e.value #>> '{}'
        ) mapped WHERE old_id IS NULL OR (occurrence = 1 AND NOT has_kept);
        UPDATE system_tenant_package SET menu_ids = updated_ids, updater = 'migration:0298', update_time = CURRENT_TIMESTAMP
         WHERE id = package.id;
    END LOOP;
    UPDATE system_menu m SET deleted = 1, updater = 'migration:0298', update_time = CURRENT_TIMESTAMP
      FROM governance_0298_menu_alias a WHERE m.id = a.old_id;
END;
$refs$;
COMMIT;
