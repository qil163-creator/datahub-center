-- Migration: 0247 主数据推送类目及基础管理入口。
-- Purpose: 新增独立推送类目树和任务归属，在基础管理下增加类目维护菜单；历史任务保持未分类。
-- Depends on: 0246_dataforge_push-categories.sql；0188_governance_master-push.sql 创建的主数据推送表。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；结构和菜单按身份检查并复用，不恢复后续删除的业务类目或重置任务归属。
-- Rollback: 执行前备份菜单及授权；回滚须先恢复原版代码并移除本次菜单授权与菜单，备份新增类目及任务归属后才能删除新增列、表和序列。
-- 事务边界：单事务；创建索引和新增列会短时持锁，锁等待超过十秒失败。序列在失败回滚时可能前移。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_governance_master_push_category_seq;
COMMENT ON SEQUENCE dh_governance_master_push_category_seq IS '主数据推送类目编号序列';

CREATE TABLE IF NOT EXISTS dh_governance_master_push_category (
    id bigint NOT NULL DEFAULT nextval('dh_governance_master_push_category_seq'::regclass),
    parent_id bigint NOT NULL DEFAULT 0,
    name varchar(128) NOT NULL,
    sort integer NOT NULL DEFAULT 0,
    status smallint NOT NULL DEFAULT 0,
    description varchar(500),
    creator varchar(64) DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_governance_master_push_category_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_governance_master_push_category_parent CHECK (parent_id >= 0)
);
COMMENT ON TABLE dh_governance_master_push_category IS '主数据推送任务类目';
COMMENT ON COLUMN dh_governance_master_push_category.id IS '类目编号';
COMMENT ON COLUMN dh_governance_master_push_category.parent_id IS '父类目编号，0 表示根类目';
COMMENT ON COLUMN dh_governance_master_push_category.name IS '类目名称';
COMMENT ON COLUMN dh_governance_master_push_category.sort IS '同级排序';
COMMENT ON COLUMN dh_governance_master_push_category.status IS '状态：0 启用，1 停用';
COMMENT ON COLUMN dh_governance_master_push_category.description IS '类目描述';
COMMENT ON COLUMN dh_governance_master_push_category.creator IS '创建人';
COMMENT ON COLUMN dh_governance_master_push_category.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_push_category.updater IS '更新人';
COMMENT ON COLUMN dh_governance_master_push_category.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_push_category.deleted IS '逻辑删除标识：0 未删除，1 已删除';

CREATE UNIQUE INDEX IF NOT EXISTS uk_governance_master_push_category_parent_name
    ON dh_governance_master_push_category (parent_id, name) WHERE deleted = 0;
COMMENT ON INDEX uk_governance_master_push_category_parent_name IS '有效推送类目同级名称唯一';

ALTER TABLE dh_governance_master_distribute ADD COLUMN IF NOT EXISTS category_id bigint;
COMMENT ON COLUMN dh_governance_master_distribute.category_id IS '推送类目编号，空表示未分类';
CREATE INDEX IF NOT EXISTS idx_governance_master_distribute_category
    ON dh_governance_master_distribute (category_id, id) WHERE deleted = 0;
COMMENT ON INDEX idx_governance_master_distribute_category IS '按类目查询有效推送任务';

-- 菜单与角色关联需要互斥分配编号，不改变其他角色的授权范围。
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    basic_id bigint;
    category_id bigint;
    page_id bigint;
    action_id bigint;
    high_id bigint;
    matches integer;
    grant_ids bigint[];
    action record;
BEGIN
    SELECT count(*), min(id) INTO matches, basic_id FROM system_menu
    WHERE deleted = 0 AND type = 1 AND parent_id = 0
      AND trim(both '/' from coalesce(path, '')) = 'basic';
    IF matches <> 1 THEN RAISE EXCEPTION '0247 需要唯一的基础管理菜单'; END IF;
    SELECT count(*), min(id) INTO matches, category_id FROM system_menu
    WHERE deleted = 0 AND type = 1 AND parent_id = basic_id
      AND trim(both '/' from coalesce(path, '')) = 'category';
    IF matches <> 1 THEN RAISE EXCEPTION '0247 需要唯一的类目管理菜单'; END IF;

    SELECT count(*), min(id) INTO matches, page_id FROM system_menu
    WHERE trim(both '/' from coalesce(component, '')) = 'governance/masterpushcategory/index';
    IF matches > 1 OR EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND
       (deleted <> 0 OR type <> 2 OR parent_id <> category_id OR path <> 'master-push-category'
        OR permission <> 'governance:master-push-category:query'))
       OR EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = category_id
                  AND path = 'master-push-category' AND id IS DISTINCT FROM page_id)
       OR EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0
                  AND component_name = 'GovernanceMasterPushCategory' AND id IS DISTINCT FROM page_id)
    THEN RAISE EXCEPTION '0247 主数据推送类目菜单身份冲突'; END IF;
    IF matches = 0 THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
        INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon,
                                 component, component_name, status, visible, keep_alive, always_show,
                                 creator, updater, deleted)
        VALUES (page_id, '主数据推送类目', 'governance:master-push-category:query', 2, 100, category_id,
                'master-push-category', 'lucide:folder-tree', 'governance/masterpushcategory/index',
                'GovernanceMasterPushCategory', 0, true, true, true, 'migration:0247', 'migration:0247', 0);
    END IF;
    grant_ids := ARRAY[basic_id, category_id, page_id];

    FOR action IN SELECT * FROM (VALUES
        ('create', '新增类目', 1), ('update', '修改及切换状态', 2), ('delete', '删除及批量删除', 3)
    ) AS permissions(code, label, sort) LOOP
        SELECT count(*), min(id) INTO matches, action_id FROM system_menu
        WHERE permission = 'governance:master-push-category:' || action.code;
        IF matches > 1 OR EXISTS (SELECT 1 FROM system_menu WHERE id = action_id
             AND (deleted <> 0 OR type <> 3 OR parent_id <> page_id)) THEN
            RAISE EXCEPTION '0247 主数据推送类目按钮身份冲突：%', action.code;
        END IF;
        IF matches = 0 THEN
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO action_id FROM system_menu;
            INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, status,
                                     visible, keep_alive, always_show, creator, updater, deleted)
            VALUES (action_id, action.label, 'governance:master-push-category:' || action.code, 3, action.sort,
                    page_id, '', 0, true, true, true, 'migration:0247', 'migration:0247', 0);
        END IF;
        grant_ids := array_append(grant_ids, action_id);
    END LOOP;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                    (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id, true);
    PERFORM setval('system_menu_id_seq', high_id, true);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
      INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY role.id, role.tenant_id, menu.id),
           role.id, menu.id, role.tenant_id, 'migration:0247', 'migration:0247', 0
    FROM system_role role CROSS JOIN system_menu menu
    WHERE role.code = 'super_admin' AND role.status = 0 AND role.deleted = 0
      AND menu.id = ANY(grant_ids) AND menu.deleted = 0
      AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.deleted = 0
          AND old.role_id = role.id AND old.tenant_id = role.tenant_id AND old.menu_id = menu.id);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
      INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id, true);
END $menus$;

COMMIT;
