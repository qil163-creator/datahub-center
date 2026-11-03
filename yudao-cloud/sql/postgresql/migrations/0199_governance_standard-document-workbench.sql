-- Migration: 0199 标准文件分类、版本信息与关联工作台。
-- Purpose: 保留原页面为标准文件检索，新建标准文件登记入口，扩展元信息、状态字典、关联索引及类目引用保护。
-- Depends on: 0198_governance_complete-rule-engines.sql 已执行；0191 标准工作台和 0195 共享类目已就绪。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；字段、约束、索引及字典均检测存在性，触发器和函数可重建。
-- Rollback: 失败整体回滚；提交后回退应用可保留新增字段。删除字段会丢失类目、版本号和废止日期，须先备份并单独编写回滚迁移。
-- 执行影响：在事务内短暂锁定标准文件及类目表，普通索引构建会阻塞相应写入；锁等待十秒或执行两分钟超时即整体回滚。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_governance_standard_doc ADD COLUMN IF NOT EXISTS category_id bigint;
ALTER TABLE dh_governance_standard_doc ADD COLUMN IF NOT EXISTS version_no varchar(64);
ALTER TABLE dh_governance_standard_doc ADD COLUMN IF NOT EXISTS abolish_date date;
COMMENT ON COLUMN dh_governance_standard_doc.category_id IS '共享标准类目编号；历史未分类记录允许为空';
COMMENT ON COLUMN dh_governance_standard_doc.version_no IS '标准文件标注的版本号，与平台版本快照序号独立';
COMMENT ON COLUMN dh_governance_standard_doc.abolish_date IS '标准废止日期';

DO $constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_standard_doc_category'
            AND conrelid = 'dh_governance_standard_doc'::regclass) THEN
        ALTER TABLE dh_governance_standard_doc ADD CONSTRAINT fk_standard_doc_category
            FOREIGN KEY (category_id) REFERENCES dh_governance_category_datameta(id);
    END IF;
END $constraints$;

CREATE INDEX IF NOT EXISTS idx_standard_doc_category_live
    ON dh_governance_standard_doc(category_id, std_type, id DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_standard_element_document_live
    ON dh_governance_standard_element(document_id, id DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_standard_code_set_document_live
    ON dh_governance_standard_code_set(document_id, id DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_logic_model_document_live
    ON dh_modeling_logic_model(document_id, id DESC) WHERE deleted = 0;

-- 文档写入与类目逻辑删除争用相同父行锁，避免检查通过后父类目被删除。
CREATE OR REPLACE FUNCTION standard_doc_category_reference_guard() RETURNS trigger
LANGUAGE plpgsql AS $guard$
BEGIN
    IF NEW.deleted = 0 AND NEW.category_id IS NOT NULL THEN
        PERFORM 1 FROM dh_governance_category_datameta
            WHERE id = NEW.category_id AND deleted = 0 FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION '标准文件类目不存在或已删除'; END IF;
    END IF;
    RETURN NEW;
END $guard$;
DROP TRIGGER IF EXISTS standard_doc_category_reference_guard ON dh_governance_standard_doc;
CREATE TRIGGER standard_doc_category_reference_guard
    BEFORE INSERT OR UPDATE OF category_id, deleted ON dh_governance_standard_doc
    FOR EACH ROW EXECUTE FUNCTION standard_doc_category_reference_guard();

CREATE OR REPLACE FUNCTION standard_doc_category_delete_guard() RETURNS trigger
LANGUAGE plpgsql AS $guard$
BEGIN
    IF NEW.deleted <> 0 AND OLD.deleted = 0 AND EXISTS (
        SELECT 1 FROM dh_governance_standard_doc WHERE category_id = OLD.id AND deleted = 0
    ) THEN
        RAISE EXCEPTION '类目仍被标准文件引用';
    END IF;
    RETURN NEW;
END $guard$;
DROP TRIGGER IF EXISTS standard_doc_category_delete_guard ON dh_governance_category_datameta;
CREATE TRIGGER standard_doc_category_delete_guard BEFORE UPDATE OF deleted ON dh_governance_category_datameta
    FOR EACH ROW EXECUTE FUNCTION standard_doc_category_delete_guard();

-- 使用现有字典协议，保留已有状态、标签和历史数据。
LOCK TABLE system_dict_data IN SHARE ROW EXCLUSIVE MODE;
SELECT setval('system_dict_data_seq', GREATEST(
    (SELECT last_value FROM system_dict_data_seq),
    COALESCE((SELECT max(id) FROM system_dict_data), 1)
));
INSERT INTO system_dict_data (id, sort, label, value, dict_type, status, color_type, creator, updater, deleted)
SELECT nextval('system_dict_data_seq'), seed.sort, seed.label, seed.value,
    'standard_implement_status', 0, seed.color_type, 'migration:0199', 'migration:0199', 0
FROM (VALUES
    (0, '草案', 'DRAFT', 'default'),
    (5, '修订中', 'REVISING', 'orange'),
    (6, '过渡期', 'TRANSITION', 'gold')
) AS seed(sort, label, value, color_type)
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data existing
    WHERE existing.dict_type = 'standard_implement_status' AND existing.value = seed.value AND existing.deleted = 0);

-- 原页面只更名，组件、路径及原有权限不变；登记使用独立页面并共用标准文件操作权限。
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
SELECT setval('system_menu_seq', GREATEST(
    (SELECT last_value FROM system_menu_seq), (SELECT last_value FROM system_menu_id_seq),
    COALESCE((SELECT max(id) FROM system_menu), 1)
));
CREATE TEMP TABLE standard_doc_menu_mapping (source_id bigint PRIMARY KEY, target_id bigint NOT NULL) ON COMMIT DROP;
DO $menus$
DECLARE original system_menu%ROWTYPE; action system_menu%ROWTYPE; registration_id bigint; action_id bigint;
BEGIN
    SELECT * INTO STRICT original FROM system_menu WHERE component = 'governance/doc/index' AND deleted = 0;
    UPDATE system_menu SET name = '标准文件检索', updater = 'migration:0199', update_time = CURRENT_TIMESTAMP
        WHERE id = original.id AND name <> '标准文件检索';
    IF EXISTS (SELECT 1 FROM system_menu WHERE parent_id = original.parent_id AND path = 'doc-register'
            AND deleted = 0 AND component IS DISTINCT FROM 'governance/doc-register/index') THEN
        RAISE EXCEPTION '标准文件登记菜单路径被其他组件占用';
    END IF;
    SELECT id INTO registration_id FROM system_menu
        WHERE component = 'governance/doc-register/index' AND deleted = 0;
    IF registration_id IS NULL THEN
        registration_id := nextval('system_menu_seq');
        INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
            component_name, status, visible, keep_alive, always_show, creator, updater, deleted)
        VALUES (registration_id, '标准文件登记', '', 2, original.sort + 1, original.parent_id,
            'doc-register', 'carbon:document-add', 'governance/doc-register/index', 'StandardDocRegister',
            original.status, true, true, true, 'migration:0199', 'migration:0199', 0);
    END IF;
    INSERT INTO standard_doc_menu_mapping VALUES (original.id, registration_id);
    FOR action IN SELECT * FROM system_menu
        WHERE parent_id = original.id AND type = 3 AND deleted = 0 AND permission LIKE 'governance:doc:%'
    LOOP
        SELECT id INTO action_id FROM system_menu
            WHERE parent_id = registration_id AND permission = action.permission AND type = 3 AND deleted = 0;
        IF action_id IS NULL THEN
            action_id := nextval('system_menu_seq');
            INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                component_name, status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES (action_id, action.name, action.permission, 3, action.sort, registration_id, '', '', '', '',
                action.status, true, true, true, 'migration:0199', 'migration:0199', 0);
        END IF;
        INSERT INTO standard_doc_menu_mapping VALUES (action.id, action_id);
    END LOOP;
END $menus$;
SELECT setval('system_menu_id_seq', GREATEST((SELECT last_value FROM system_menu_id_seq),
    (SELECT last_value FROM system_menu_seq), (SELECT max(id) FROM system_menu)));
SELECT setval('system_role_menu_seq', GREATEST((SELECT last_value FROM system_role_menu_seq),
    COALESCE((SELECT max(id) FROM system_role_menu), 1)));

-- 仅继承原菜单和对应按钮已有的授权，不向只读角色新增写权限。
INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater, deleted)
SELECT nextval('system_role_menu_seq'), source.role_id, mapping.target_id, source.tenant_id,
    'migration:0199', 'migration:0199', 0
FROM (SELECT DISTINCT role_id, menu_id, tenant_id FROM system_role_menu WHERE deleted = 0) source
JOIN standard_doc_menu_mapping mapping ON mapping.source_id = source.menu_id
WHERE NOT EXISTS (SELECT 1 FROM system_role_menu existing WHERE existing.role_id = source.role_id
    AND existing.menu_id = mapping.target_id AND existing.tenant_id = source.tenant_id AND existing.deleted = 0);

-- 通用脚手架套餐保留原有元素及权限范围，仅追加与已有菜单对应的新入口。
WITH additions AS (
    SELECT package.id, jsonb_agg(mapping.target_id ORDER BY mapping.target_id) AS ids
    FROM system_tenant_package package
    JOIN standard_doc_menu_mapping mapping ON EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
        WHERE entry.value = mapping.source_id::text
    )
    WHERE package.deleted = 0 AND NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
        WHERE entry.value = mapping.target_id::text
    )
    GROUP BY package.id
)
UPDATE system_tenant_package package SET menu_ids = (package.menu_ids::jsonb || additions.ids)::text,
    updater = 'migration:0199', update_time = CURRENT_TIMESTAMP
FROM additions WHERE additions.id = package.id;

COMMIT;
