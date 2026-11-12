-- Migration: 0276 动态脱敏场景开关、菜单与权限
-- Purpose: 新增 dh_governance_mask_scene（每个动态脱敏场景一行：DATA_QUERY 数据查询、ASSET_PREVIEW 资产预览、
--          MARKET_PREVIEW 集市预览、DATABASE_PREVIEW 数据库预览、INDICATOR_QUERY 指标查询），初始全部启用；
--          场景行缺失时服务端按启用处理（失败关闭）。在“数据治理 > 数据安全”目录下新增“动态脱敏”页面
--          （组件 governance/dynamicmask/index）及按钮权限 governance:dynamic-mask:query（场景列表、策略试算）、
--          governance:dynamic-mask:update（启停场景），并授权给超级管理员。
-- Depends on: 平台库已升级至 0275；存在 /govern 顶级目录及其下 path=safety 的“数据安全”目录。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；序列、表、约束带存在性保护，场景种子按 scene_code 去重（不覆盖已调整的开关），
--             页面按组件路径定位、按钮按权限去重、授权按角色与菜单判重。
-- Rollback: 加法迁移，无不可逆操作。删除组件为 governance/dynamicmask/index 的页面、权限为
--           governance:dynamic-mask:query / governance:dynamic-mask:update 的按钮及其 system_role_menu 授权，
--           再删除 dh_governance_mask_scene 表及 dh_governance_mask_scene_seq 序列；删除后服务端按“全部场景启用”处理。
--           原始 SQL 不触发缓存失效，提交后需清理权限菜单相关 Redis 缓存或重启 system 服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_governance_mask_scene_seq;

CREATE TABLE IF NOT EXISTS dh_governance_mask_scene (
    id int8 NOT NULL DEFAULT nextval('dh_governance_mask_scene_seq'::regclass),
    scene_code varchar(32) NOT NULL,
    enabled bool NOT NULL DEFAULT true,
    remark varchar(500) NOT NULL DEFAULT '',
    revision int4 NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_mask_scene PRIMARY KEY (id),
    CONSTRAINT uk_dh_governance_mask_scene_code UNIQUE (scene_code),
    CONSTRAINT ck_mask_scene_code CHECK (scene_code IN
        ('DATA_QUERY', 'ASSET_PREVIEW', 'MARKET_PREVIEW', 'DATABASE_PREVIEW', 'INDICATOR_QUERY')),
    CONSTRAINT ck_mask_scene_revision CHECK (revision >= 0),
    CONSTRAINT ck_mask_scene_disable_remark CHECK (enabled OR length(trim(remark)) > 0)
);

COMMENT ON TABLE dh_governance_mask_scene IS '数据安全-动态脱敏场景开关（每个场景一行；缺失按启用处理）';
COMMENT ON COLUMN dh_governance_mask_scene.id IS '编号';
COMMENT ON COLUMN dh_governance_mask_scene.scene_code IS '场景编码：DATA_QUERY数据查询、ASSET_PREVIEW资产预览、MARKET_PREVIEW集市预览、DATABASE_PREVIEW数据库预览、INDICATOR_QUERY指标查询';
COMMENT ON COLUMN dh_governance_mask_scene.enabled IS '是否启用动态脱敏；停用时该场景返回原值';
COMMENT ON COLUMN dh_governance_mask_scene.remark IS '备注；停用时必须填写原因';
COMMENT ON COLUMN dh_governance_mask_scene.revision IS '乐观锁修订号，每次启停加一';
COMMENT ON COLUMN dh_governance_mask_scene.creator IS '创建者';
COMMENT ON COLUMN dh_governance_mask_scene.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_mask_scene.updater IS '更新者';
COMMENT ON COLUMN dh_governance_mask_scene.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_mask_scene.deleted IS '逻辑删除：0未删除、1已删除（场景行不做逻辑删除）';

INSERT INTO dh_governance_mask_scene(scene_code, enabled, remark, revision, creator, updater, deleted)
SELECT code, true, '', 0, 'migration:0276', 'migration:0276', 0
  FROM (VALUES ('DATA_QUERY'), ('ASSET_PREVIEW'), ('MARKET_PREVIEW'), ('DATABASE_PREVIEW'), ('INDICATOR_QUERY'))
       AS seed(code)
 WHERE NOT EXISTS (SELECT 1 FROM dh_governance_mask_scene old WHERE old.scene_code = seed.code);

LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    govern_id bigint;
    dir_id bigint;
    page_id bigint;
    query_id bigint;
    update_id bigint;
    high_id bigint;
BEGIN
    SELECT id INTO govern_id FROM system_menu
    WHERE deleted = 0 AND parent_id = 0 AND type = 1 AND trim(both '/' from coalesce(path, '')) = 'govern'
    ORDER BY id LIMIT 1;
    IF govern_id IS NULL THEN RAISE EXCEPTION '0276 缺少数据治理顶级目录 /govern'; END IF;
    SELECT id INTO dir_id FROM system_menu
    WHERE deleted = 0 AND parent_id = govern_id AND type = 1 AND trim(both '/' from coalesce(path, '')) = 'safety'
    ORDER BY id LIMIT 1;
    IF dir_id IS NULL THEN RAISE EXCEPTION '0276 缺少数据安全目录 /govern/safety'; END IF;

    SELECT id INTO page_id FROM system_menu
    WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'governance/dynamicmask/index'
    ORDER BY id LIMIT 1;
    IF page_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = dir_id
                                               AND trim(both '/' from coalesce(path, '')) = 'dynamic-mask') THEN
            RAISE EXCEPTION '0276 菜单路径冲突：/govern/safety/dynamic-mask 已被其他菜单占用';
        END IF;
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                                status, visible, keep_alive, always_show, creator, updater, deleted)
        VALUES (page_id, '动态脱敏', '', 2, 8, dir_id, 'dynamic-mask', 'carbon:view-off',
                'governance/dynamicmask/index', 'SafetyDynamicMask', 0, true, true, true,
                'migration:0276', 'migration:0276', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND parent_id = dir_id AND type = 2
                                                 AND trim(both '/' from coalesce(path, '')) = 'dynamic-mask') THEN
        RAISE EXCEPTION '0276 菜单组件身份冲突：governance/dynamicmask/index 已挂在其他位置';
    END IF;

    SELECT id INTO query_id FROM system_menu
    WHERE deleted = 0 AND permission = 'governance:dynamic-mask:query' ORDER BY id LIMIT 1;
    IF query_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO query_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                keep_alive, always_show, creator, updater, deleted)
        VALUES (query_id, '动态脱敏查询', 'governance:dynamic-mask:query', 3, 1, page_id, '', '#', 0, true, true, true,
                'migration:0276', 'migration:0276', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = query_id AND type = 3 AND parent_id = page_id) THEN
        RAISE EXCEPTION '0276 权限冲突：governance:dynamic-mask:query 已被其他菜单使用';
    END IF;

    SELECT id INTO update_id FROM system_menu
    WHERE deleted = 0 AND permission = 'governance:dynamic-mask:update' ORDER BY id LIMIT 1;
    IF update_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO update_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                keep_alive, always_show, creator, updater, deleted)
        VALUES (update_id, '动态脱敏启停', 'governance:dynamic-mask:update', 3, 2, page_id, '', '#', 0, true, true, true,
                'migration:0276', 'migration:0276', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = update_id AND type = 3 AND parent_id = page_id) THEN
        RAISE EXCEPTION '0276 权限冲突：governance:dynamic-mask:update 已被其他菜单使用';
    END IF;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                    (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);

    -- 与 0274 一致，仅授权超级管理员；沿用角色自身的脚手架租户归属。
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY r.id, r.tenant_id, m.id), r.id, m.id, r.tenant_id,
           'migration:0276', 'migration:0276', 0
      FROM system_role r JOIN system_menu m ON m.id IN (page_id, query_id, update_id) AND m.deleted = 0
     WHERE r.code = 'super_admin' AND r.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
