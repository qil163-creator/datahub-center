-- Migration: 0190 数据质量规则目录规范及只读菜单。
-- Purpose: 合并重复清洗分类并校准两条稽查种子展示文案，在数据质量目录增加两个只读规则菜单；保留规则编号、策略与清洗参数。
-- Depends on: 0189_governance_quality-task-report.sql；现有数据质量父菜单 5995 和规则目录种子已就绪。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；仅校准已知旧种子值，分类以逻辑删除合并，菜单和角色授权按存在性补齐。
-- Rollback: 失败时事务回滚；提交后可停用新增菜单并撤销授权。分类合并与文案修正应从执行前备份恢复，勿删除规则或清洗参数。
-- 风险：相关目录表取得短暂写锁；重复类下发现未知有效规则或子类时中止。仅向现有启用超级管理员角色授予新增查询入口。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE dh_governance_audit_rule, dh_governance_clean_rule, dh_governance_category_clean_rule IN SHARE ROW EXCLUSIVE MODE;

-- 仅修正已知旧文案，已有人工维护内容不被覆盖。
UPDATE dh_governance_audit_rule SET name = '数值字段精度校验', updater = 'migration:0190', update_time = CURRENT_TIMESTAMP
WHERE code = '110' AND name = '数值精度校验' AND deleted = 0;
UPDATE dh_governance_audit_rule SET description = '检查字段是否为合法的时间格式（如yyyy-MM-dd）',
    updater = 'migration:0190', update_time = CURRENT_TIMESTAMP
WHERE code = '109' AND description = '检查字段值是否为合法的时间格式（如yyyy-MM-dd）' AND deleted = 0;
UPDATE dh_governance_audit_rule SET use_case = '检查字段是否为合法的时间格式（如yyyy-MM-dd）',
    updater = 'migration:0190', update_time = CURRENT_TIMESTAMP
WHERE code = '109' AND use_case = '检查字段值是否为合法的时间格式（如yyyy-MM-dd）' AND deleted = 0;

DO $quality_rule_catalog_categories$
DECLARE duplicate_id bigint; target_id bigint; target_count integer; duplicate_count integer;
BEGIN
    SELECT count(*), min(id) INTO duplicate_count, duplicate_id FROM dh_governance_category_clean_rule
    WHERE category_name = '敏词过滤类' AND parent_id = 71 AND deleted = 0;
    IF duplicate_count = 0 THEN RETURN; END IF;
    SELECT count(*), min(id) INTO target_count, target_id FROM dh_governance_category_clean_rule
    WHERE category_name = '脏词过滤类' AND parent_id = 71 AND deleted = 0;
    IF duplicate_count <> 1 OR target_count <> 1 OR NOT EXISTS (
        SELECT 1 FROM dh_governance_category_clean_rule WHERE id = 71 AND parent_id = 0 AND category_name = '有效性处理' AND deleted = 0
    ) THEN RAISE EXCEPTION '0190 清洗种子分类缺失或歧义，请先核查'; END IF;
    IF EXISTS (SELECT 1 FROM dh_governance_category_clean_rule WHERE parent_id = duplicate_id AND deleted = 0)
        OR EXISTS (SELECT 1 FROM dh_governance_clean_rule WHERE category_id = duplicate_id AND deleted = 0
            AND NOT (coalesce(code, '') = '034' AND coalesce(name, '') = '关键词脱敏'
                OR coalesce(code, '') = '035' AND coalesce(name, '') = '敏感词替换')) THEN
        RAISE EXCEPTION '0190 重复清洗分类存在未知规则或子类，拒绝自动合并';
    END IF;
    UPDATE dh_governance_clean_rule SET category_id = target_id, updater = 'migration:0190', update_time = CURRENT_TIMESTAMP
    WHERE category_id = duplicate_id AND deleted = 0 AND code IN ('034', '035');
    -- 保留原分类行供历史引用识别；有效目录只显示合并后的既有分类。
    UPDATE dh_governance_category_clean_rule SET deleted = 1, updater = 'migration:0190', update_time = CURRENT_TIMESTAMP
    WHERE id = duplicate_id AND deleted = 0;
END $quality_rule_catalog_categories$;

DO $quality_rule_catalog_menus$
DECLARE
    item record;
    page_id bigint;
    matches integer;
    menu_high bigint;
    role_high bigint;
    inserted integer;
    page_ids bigint[] := ARRAY[]::bigint[];
BEGIN
    IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 5995 AND path = 'quality' AND type = 1 AND deleted = 0 AND status = 0) THEN
        RAISE EXCEPTION '0190 数据质量父菜单 5995 缺失或不可用';
    END IF;
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
        (SELECT last_value FROM system_menu_id_seq)) INTO menu_high FROM system_menu;
    FOR item IN SELECT * FROM (VALUES
        ('稽查规则', 'audit-rule', 'governance/quality/audit-rule/index', 'GovernanceQualityAuditRule', 'governance:audit-rule:query', 'carbon:rule', 15),
        ('清洗规则', 'clean-rule', 'governance/quality/clean-rule/index', 'GovernanceQualityCleanRule', 'governance:clean-rule:query', 'carbon:filter', 16)
    ) seed(name, path, component, component_name, permission, icon, sort) LOOP
        SELECT count(*), min(id) INTO matches, page_id FROM system_menu
        WHERE deleted = 0 AND (component = item.component OR permission = item.permission OR (parent_id = 5995 AND path = item.path));
        IF matches = 0 THEN
            menu_high := menu_high + 1;
            page_id := menu_high;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES(page_id, item.name, item.permission, 2, item.sort, 5995, item.path, item.icon, item.component, item.component_name,
                0, true, true, true, 'migration:0190', 'migration:0190', 0);
        ELSIF matches <> 1 OR NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND parent_id = 5995
            AND type = 2 AND path = item.path AND component = item.component AND permission = item.permission AND deleted = 0) THEN
            RAISE EXCEPTION '0190 规则目录菜单归属或权限冲突：%', item.permission;
        END IF;
        page_ids := array_append(page_ids, page_id);
    END LOOP;
    PERFORM setval('system_menu_seq', menu_high);
    PERFORM setval('system_menu_id_seq', menu_high);

    -- 沿用平台超级管理员角色，不复制查询权限给所有用户或其他业务角色。
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO role_high FROM system_role_menu;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT role_high + row_number() OVER (ORDER BY pending.role_id, pending.tenant_id, pending.menu_id),
        pending.role_id, pending.menu_id, pending.tenant_id, 'migration:0190', 'migration:0190', 0
    FROM (SELECT role.id AS role_id, role.tenant_id, page.id AS menu_id
        FROM system_role role CROSS JOIN unnest(page_ids) page(id)
        WHERE role.code = 'super_admin' AND role.status = 0 AND role.deleted = 0
        AND NOT EXISTS (SELECT 1 FROM system_role_menu existing WHERE existing.role_id = role.id
            AND existing.tenant_id = role.tenant_id AND existing.menu_id = page.id AND existing.deleted = 0)) pending;
    GET DIAGNOSTICS inserted = ROW_COUNT;
    IF inserted > 0 THEN
        PERFORM setval('system_role_menu_seq', role_high + inserted);
    END IF;
END $quality_rule_catalog_menus$;
COMMIT;
