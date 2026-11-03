-- Migration: 0176 数据集市类目、门户与资产地图。
-- Purpose: 补齐数据资产集市门户所需的个人收藏、集市导航和菜单权限。
-- Depends on: 0175_dataforge_remove-connection-driver-profile.sql 已执行；资产发布目录和现有集市申请表已存在。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；结构与索引可重放，菜单按组件身份定位，个人收藏与资产记录不被覆盖。
-- Rollback: 单事务失败完整回滚；提交后回退代码前应导出新增收藏与类目数据，按部署前菜单备份恢复导航。
-- 事务边界：结构与菜单变更在同一事务内执行；不删除既有资产、申请、交付和审计记录。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_asset_market_category_seq;
CREATE TABLE IF NOT EXISTS dh_asset_market_category (
    id bigint NOT NULL DEFAULT nextval('dh_asset_market_category_seq'),
    parent_id bigint NOT NULL DEFAULT 0,
    name varchar(50) NOT NULL,
    description varchar(256),
    status smallint NOT NULL DEFAULT 0,
    sort integer NOT NULL DEFAULT 0,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT pk_asset_market_category PRIMARY KEY (id),
    CONSTRAINT ck_asset_market_category_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_asset_market_category_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_asset_market_category_parent CHECK (parent_id >= 0 AND parent_id <> id),
    CONSTRAINT ck_asset_market_category_name CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT ck_asset_market_category_sort CHECK (sort >= 0)
);
ALTER SEQUENCE dh_asset_market_category_seq OWNED BY dh_asset_market_category.id;
COMMENT ON TABLE dh_asset_market_category IS '数据资产-集市类目';
COMMENT ON COLUMN dh_asset_market_category.id IS '集市类目编号';
COMMENT ON COLUMN dh_asset_market_category.parent_id IS '父集市类目编号，顶级为零';
COMMENT ON COLUMN dh_asset_market_category.name IS '集市类目名称，最多五十个字符';
COMMENT ON COLUMN dh_asset_market_category.description IS '集市类目描述，最多二百五十六个字符';
COMMENT ON COLUMN dh_asset_market_category.status IS '状态：0-启用 1-停用';
COMMENT ON COLUMN dh_asset_market_category.sort IS '显示顺序，非负整数';
COMMENT ON COLUMN dh_asset_market_category.creator IS '创建者';
COMMENT ON COLUMN dh_asset_market_category.create_time IS '创建时间';
COMMENT ON COLUMN dh_asset_market_category.updater IS '更新者';
COMMENT ON COLUMN dh_asset_market_category.update_time IS '更新时间';
COMMENT ON COLUMN dh_asset_market_category.deleted IS '是否删除：0-未删除 1-已删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_market_category_parent_name
    ON dh_asset_market_category(parent_id, name) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_asset_market_category_parent
    ON dh_asset_market_category(parent_id, sort, id) WHERE deleted = 0;

CREATE TABLE IF NOT EXISTS dh_asset_market_listing (
    asset_id bigint NOT NULL,
    market_category_id bigint,
    display_name varchar(256) NOT NULL,
    description varchar(256),
    frequency varchar(64) NOT NULL,
    delivery_types varchar(32) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT pk_asset_market_listing PRIMARY KEY (asset_id),
    CONSTRAINT ck_asset_market_listing_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_asset_market_listing_name CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_asset_market_listing_revision CHECK (revision >= 0),
    CONSTRAINT ck_asset_market_listing_frequency CHECK (btrim(frequency) <> ''),
    CONSTRAINT ck_asset_market_listing_delivery CHECK (
        delivery_types IN ('1', '2', '3', '1,2', '1,3', '2,3', '1,2,3')
    ),
    CONSTRAINT fk_asset_market_listing_asset FOREIGN KEY (asset_id)
        REFERENCES dh_asset_data_asset(id) ON DELETE CASCADE,
    CONSTRAINT fk_asset_market_listing_category FOREIGN KEY (market_category_id)
        REFERENCES dh_asset_market_category(id) ON DELETE RESTRICT
);
COMMENT ON TABLE dh_asset_market_listing IS '数据资产-集市展示信息';
COMMENT ON COLUMN dh_asset_market_listing.asset_id IS '资产编号；发布状态及版本仍以资产发布目录为准';
COMMENT ON COLUMN dh_asset_market_listing.market_category_id IS '挂载的独立集市类目编号';
COMMENT ON COLUMN dh_asset_market_listing.display_name IS '集市展示名称';
COMMENT ON COLUMN dh_asset_market_listing.description IS '集市描述，最多二百五十六个字符';
COMMENT ON COLUMN dh_asset_market_listing.frequency IS '更新频率';
COMMENT ON COLUMN dh_asset_market_listing.delivery_types IS '交付形态声明：1-数据库表 2-接口 3-文件，升序逗号分隔，不替代交付授权';
COMMENT ON COLUMN dh_asset_market_listing.revision IS '集市展示信息修订号，防止并发发布覆盖，不替代资产目录修订号';
COMMENT ON COLUMN dh_asset_market_listing.creator IS '创建者';
COMMENT ON COLUMN dh_asset_market_listing.create_time IS '创建时间';
COMMENT ON COLUMN dh_asset_market_listing.updater IS '更新者';
COMMENT ON COLUMN dh_asset_market_listing.update_time IS '更新时间';
COMMENT ON COLUMN dh_asset_market_listing.deleted IS '是否删除：0-未删除 1-已删除';
CREATE INDEX IF NOT EXISTS idx_asset_market_listing_category
    ON dh_asset_market_listing(market_category_id, asset_id) WHERE deleted = 0;

CREATE SEQUENCE IF NOT EXISTS dh_asset_market_favorite_seq;
CREATE TABLE IF NOT EXISTS dh_asset_market_favorite (
    id bigint NOT NULL DEFAULT nextval('dh_asset_market_favorite_seq'),
    user_id bigint NOT NULL,
    asset_id bigint NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT pk_asset_market_favorite PRIMARY KEY (id),
    CONSTRAINT ck_asset_market_favorite_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT fk_asset_market_favorite_asset FOREIGN KEY (asset_id)
        REFERENCES dh_asset_data_asset(id) ON DELETE CASCADE
);
ALTER SEQUENCE dh_asset_market_favorite_seq OWNED BY dh_asset_market_favorite.id;
COMMENT ON TABLE dh_asset_market_favorite IS '数据资产-个人集市收藏';
COMMENT ON COLUMN dh_asset_market_favorite.id IS '收藏编号';
COMMENT ON COLUMN dh_asset_market_favorite.user_id IS '收藏用户编号，由当前登录身份提供';
COMMENT ON COLUMN dh_asset_market_favorite.asset_id IS '已发布资产编号';
COMMENT ON COLUMN dh_asset_market_favorite.creator IS '创建者';
COMMENT ON COLUMN dh_asset_market_favorite.create_time IS '收藏时间';
COMMENT ON COLUMN dh_asset_market_favorite.updater IS '更新者';
COMMENT ON COLUMN dh_asset_market_favorite.update_time IS '更新时间';
COMMENT ON COLUMN dh_asset_market_favorite.deleted IS '是否删除：0-未删除 1-已删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_market_favorite_user_asset
    ON dh_asset_market_favorite(user_id, asset_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_asset_market_favorite_user
    ON dh_asset_market_favorite(user_id, create_time DESC, id DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_asset_market_favorite_asset
    ON dh_asset_market_favorite(asset_id) WHERE deleted = 0;

-- 页面按组件身份定位，不把旧消费页面的角色授权直接改成草稿管理权限。
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    asset_root bigint;
    market_root bigint;
    matches integer;
    page_id bigint;
    mart_id bigint;
    category_id bigint;
    button_id bigint;
    old_pages bigint[];
    market_sources bigint[];
    manage_sources bigint[];
    category_sources bigint[];
    source_ids bigint[];
    item record;
BEGIN
    SELECT count(*), min(id) INTO matches, asset_root FROM system_menu
    WHERE deleted = 0 AND type = 1 AND trim(both '/' from path) = 'asset' AND parent_id = 0;
    IF matches <> 1 THEN
        RAISE EXCEPTION '数据资产根目录必须唯一，禁止猜测菜单归属';
    END IF;
    SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO old_pages FROM system_menu
    WHERE type = 2 AND trim(both '/' from component) IN ('market/overview/index', 'market/resource/index');
    SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO market_sources FROM system_menu
    WHERE deleted = 0 AND status = 0 AND permission = 'asset:market:query';
    SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO manage_sources FROM system_menu
    WHERE deleted = 0 AND status = 0 AND permission = 'asset:data-asset:query';
    SELECT coalesce(array_agg(id), ARRAY[]::bigint[]) INTO category_sources FROM system_menu
    WHERE deleted = 0 AND status = 0 AND permission = 'asset:category-data-asset:query';

    SELECT count(*), min(id) INTO matches, market_root FROM system_menu
    WHERE deleted = 0 AND type = 1 AND trim(both '/' from path) = 'market'
      AND parent_id IN (0, asset_root);
    IF matches > 1 THEN RAISE EXCEPTION '数据集市目录身份冲突'; END IF;
    IF matches = 0 THEN
        SELECT coalesce(max(id), 0) + 1 INTO market_root FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon,
                                status, visible, keep_alive, always_show, creator, updater, deleted)
        VALUES(market_root, '数据集市', '', 1, 8, asset_root, 'market', 'carbon:store',
               0, true, true, true, 'migration:0176', 'migration:0176', 0);
    ELSE
        UPDATE system_menu SET parent_id = asset_root, path = 'market', name = '数据集市',
            updater = 'migration:0176', update_time = CURRENT_TIMESTAMP WHERE id = market_root;
    END IF;

    FOR item IN SELECT * FROM (VALUES
        ('数据集市', 'mart', 'asset/market/index', 'AssetMarketMart', 'carbon:store', market_root, 1,
         'asset:market:query', market_sources),
        ('资产地图', 'map', 'asset/market/map/index', 'AssetMarketMap', 'carbon:map', market_root, 2,
         'asset:data-asset:query', manage_sources),
        ('集市类目', 'market-category', 'asset/marketcategory/index', 'AssetMarketCategory', 'carbon:categories', asset_root, 9,
         'asset:market-category:query', category_sources)
    ) t(label, path, component, component_name, icon, parent_id, sort, permission, source_ids) LOOP
        SELECT count(*), min(id) INTO matches, page_id FROM system_menu
        WHERE trim(both '/' from component) = item.component;
        IF matches > 1 THEN RAISE EXCEPTION '页面组件身份不唯一：%', item.component; END IF;
        IF matches = 0 THEN
            IF EXISTS(SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = item.parent_id AND path = item.path) THEN
                RAISE EXCEPTION '页面路径已被其他功能占用：%', item.path;
            END IF;
            SELECT coalesce(max(id), 0) + 1 INTO page_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component,
                                    component_name, status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES(page_id, item.label, item.permission, 2, item.sort, item.parent_id, item.path, item.icon,
                   item.component, item.component_name, 0, true, true, true, 'migration:0176', 'migration:0176', 0);
        ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id = page_id AND deleted = 0 AND type = 2
                         AND parent_id = item.parent_id AND permission = item.permission AND path = item.path) THEN
            RAISE EXCEPTION '页面归属或权限身份冲突：%', item.component;
        END IF;
        INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
        SELECT (SELECT coalesce(max(id), 0) FROM system_role_menu)
                   + row_number() OVER(ORDER BY pending.role_id, pending.tenant_id),
               pending.role_id, page_id, pending.tenant_id, 'migration:0176', 'migration:0176', 0
        FROM (SELECT DISTINCT old_grant.role_id, old_grant.tenant_id
            FROM system_role_menu old_grant WHERE old_grant.deleted = 0 AND old_grant.menu_id = ANY(item.source_ids)
              AND NOT EXISTS(SELECT 1 FROM system_role_menu current_grant WHERE current_grant.deleted = 0
                  AND current_grant.role_id = old_grant.role_id AND current_grant.tenant_id = old_grant.tenant_id
                  AND current_grant.menu_id = page_id)) pending;
        IF item.component = 'asset/market/index' THEN mart_id = page_id; END IF;
        IF item.component = 'asset/marketcategory/index' THEN category_id = page_id; END IF;
    END LOOP;

    -- 新目录的空权限只承载导航；不能因此授予资产管理或类目维护权限。
    FOREACH page_id IN ARRAY ARRAY[asset_root, market_root] LOOP
        source_ids = market_sources || manage_sources;
        IF page_id = asset_root THEN source_ids = source_ids || category_sources; END IF;
        INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
        SELECT (SELECT coalesce(max(id), 0) FROM system_role_menu)
                   + row_number() OVER(ORDER BY pending.role_id, pending.tenant_id),
               pending.role_id, page_id, pending.tenant_id, 'migration:0176', 'migration:0176', 0
        FROM (SELECT DISTINCT old_grant.role_id, old_grant.tenant_id
            FROM system_role_menu old_grant WHERE old_grant.deleted = 0
              AND old_grant.menu_id = ANY(source_ids)
              AND NOT EXISTS(SELECT 1 FROM system_role_menu current_grant WHERE current_grant.deleted = 0
                  AND current_grant.role_id = old_grant.role_id AND current_grant.tenant_id = old_grant.tenant_id
                  AND current_grant.menu_id = page_id)) pending;
    END LOOP;

    FOR item IN SELECT * FROM (VALUES
        ('收藏资产', 'asset:market:favorite', mart_id, market_sources),
        ('新增集市类目', 'asset:market-category:create', category_id, ARRAY(SELECT id FROM system_menu
            WHERE deleted = 0 AND status = 0 AND permission = 'asset:category-data-asset:create')),
        ('修改集市类目', 'asset:market-category:update', category_id, ARRAY(SELECT id FROM system_menu
            WHERE deleted = 0 AND status = 0 AND permission = 'asset:category-data-asset:update')),
        ('删除集市类目', 'asset:market-category:delete', category_id, ARRAY(SELECT id FROM system_menu
            WHERE deleted = 0 AND status = 0 AND permission = 'asset:category-data-asset:delete'))
    ) t(label, permission, parent_id, source_ids) LOOP
        SELECT count(*), min(id) INTO matches, button_id FROM system_menu WHERE permission = item.permission;
        IF matches > 1 THEN RAISE EXCEPTION '按钮权限重复：%', item.permission; END IF;
        IF matches = 0 THEN
            SELECT coalesce(max(id), 0) + 1 INTO button_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path,
                                    status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES(button_id, item.label, item.permission, 3, 0, item.parent_id, '',
                   0, true, true, true, 'migration:0176', 'migration:0176', 0);
        ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id = button_id AND deleted = 0
                         AND parent_id = item.parent_id AND type = 3) THEN
            RAISE EXCEPTION '按钮归属冲突：%', item.permission;
        END IF;
        INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
        SELECT (SELECT coalesce(max(id), 0) FROM system_role_menu)
                   + row_number() OVER(ORDER BY pending.role_id, pending.tenant_id),
               pending.role_id, button_id, pending.tenant_id, 'migration:0176', 'migration:0176', 0
        FROM (SELECT DISTINCT old_grant.role_id, old_grant.tenant_id
            FROM system_role_menu old_grant WHERE old_grant.deleted = 0 AND old_grant.menu_id = ANY(item.source_ids)
              AND NOT EXISTS(SELECT 1 FROM system_role_menu current_grant WHERE current_grant.deleted = 0
                  AND current_grant.role_id = old_grant.role_id AND current_grant.tenant_id = old_grant.tenant_id
                  AND current_grant.menu_id = button_id)) pending;
    END LOOP;
    UPDATE system_menu SET parent_id = mart_id, updater = 'migration:0176', update_time = CURRENT_TIMESTAMP
    WHERE parent_id = ANY(old_pages) AND type = 3 AND deleted = 0;
    UPDATE system_menu SET deleted = 1, status = 1, visible = false,
        updater = 'migration:0176', update_time = CURRENT_TIMESTAMP WHERE id = ANY(old_pages) AND deleted = 0;

    IF to_regclass('system_menu_id_seq') IS NOT NULL THEN
        PERFORM setval('system_menu_id_seq', greatest((SELECT last_value FROM system_menu_id_seq), (SELECT max(id) FROM system_menu)));
    END IF;
    IF to_regclass('system_menu_seq') IS NOT NULL THEN
        PERFORM setval('system_menu_seq', greatest((SELECT last_value FROM system_menu_seq), (SELECT max(id) FROM system_menu)));
    END IF;
END $menus$;

COMMIT;
