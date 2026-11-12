-- Migration: 0282 指标看板
-- Purpose: 新增 dh_modeling_indicator_board（指标看板：名称、说明、创建人、组件查询定义 JSON、修订号）。看板只保存查询
--          定义，不保存结果；展示时逐个组件调用指标查询，结果按查看者身份动态脱敏。在“指标模型”目录下新增
--          “指标看板”页面（组件 modeling/indicator-model/board/index）及按钮权限 modeling:indicator-board:query/create/
--          update/delete，仅授权超级管理员。口径变更影响分析接口复用 modeling:indicator-model:query，不新增权限。
-- Depends on: 0281_supply_indicator-api.sql（编号前驱）；0278_modeling_composite-indicator-query.sql（指标查询服务）；
--             存在 component_name = ModelingIndicatorModelRoot、path = indicator-model 的“指标模型”目录。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；序列、表、约束、索引带存在性保护，菜单按组件定位、按钮按权限去重、授权按角色与菜单判重。
-- Rollback: 加法迁移，无不可逆操作。删除“指标看板”页面、四个按钮及其 system_role_menu 授权，再删除
--           dh_modeling_indicator_board 表及 dh_modeling_indicator_board_seq 序列即可；看板只含查询定义，不涉及业务数据。
--           原始 SQL 不触发缓存失效，提交后需清理权限菜单相关 Redis 缓存或重启 system 服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_modeling_indicator_board_seq;
CREATE TABLE IF NOT EXISTS dh_modeling_indicator_board (
    id int8 NOT NULL DEFAULT nextval('dh_modeling_indicator_board_seq'::regclass),
    name varchar(100) NOT NULL,
    description varchar(500),
    owner_user_id int8 NOT NULL,
    widgets_json text NOT NULL,
    revision int4 NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_modeling_indicator_board PRIMARY KEY (id),
    CONSTRAINT ck_modeling_indicator_board_name CHECK (length(trim(name)) > 0),
    CONSTRAINT ck_modeling_indicator_board_widgets CHECK (length(widgets_json) BETWEEN 2 AND 200000)
);
CREATE INDEX IF NOT EXISTS idx_dh_modeling_indicator_board_owner
    ON dh_modeling_indicator_board (owner_user_id) WHERE deleted = 0;
COMMENT ON TABLE dh_modeling_indicator_board IS '指标看板（只保存组件查询定义，不保存查询结果）';
COMMENT ON COLUMN dh_modeling_indicator_board.id IS '编号';
COMMENT ON COLUMN dh_modeling_indicator_board.name IS '看板名称';
COMMENT ON COLUMN dh_modeling_indicator_board.description IS '说明';
COMMENT ON COLUMN dh_modeling_indicator_board.owner_user_id IS '创建人编号；只有创建人可修改、删除';
COMMENT ON COLUMN dh_modeling_indicator_board.widgets_json IS '组件 JSON 数组（≤12）：[{key, title, chartType LINE/BAR/TABLE/STAT, query 指标查询请求}]';
COMMENT ON COLUMN dh_modeling_indicator_board.revision IS '乐观锁修订号';
COMMENT ON COLUMN dh_modeling_indicator_board.creator IS '创建者';
COMMENT ON COLUMN dh_modeling_indicator_board.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_indicator_board.updater IS '更新者';
COMMENT ON COLUMN dh_modeling_indicator_board.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_indicator_board.deleted IS '逻辑删除：0未删除、1已删除';

LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    metric_parent_id bigint;
    matches integer;
    page_id bigint;
    button_id bigint;
    high_id bigint;
    granted bigint[] := ARRAY[]::bigint[];
    item record;
BEGIN
    SELECT count(*), min(id) INTO matches, metric_parent_id FROM system_menu
    WHERE deleted = 0 AND type = 1 AND component_name = 'ModelingIndicatorModelRoot'
      AND trim(both '/' from coalesce(path, '')) = 'indicator-model';
    IF matches <> 1 THEN
        RAISE EXCEPTION '0282 指标模型目录 ModelingIndicatorModelRoot 缺失或不唯一';
    END IF;

    SELECT count(*), min(id) INTO matches, page_id FROM system_menu
    WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'modeling/indicator-model/board/index';
    IF matches > 1 THEN
        RAISE EXCEPTION '0282 菜单组件身份重复：modeling/indicator-model/board/index';
    END IF;
    IF matches = 0 THEN
        IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = metric_parent_id
                                               AND trim(both '/' from coalesce(path, '')) = 'board') THEN
            RAISE EXCEPTION '0282 菜单路径冲突：指标模型/board 已被其他菜单占用';
        END IF;
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                                status, visible, keep_alive, always_show, creator, updater, deleted)
        VALUES (page_id, '指标看板', '', 2, 60, metric_parent_id, 'board', 'carbon:dashboard',
                'modeling/indicator-model/board/index', 'ModelingIndicatorBoard', 0, true, true, true,
                'migration:0282', 'migration:0282', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND parent_id = metric_parent_id AND type = 2) THEN
        RAISE EXCEPTION '0282 菜单组件身份冲突：modeling/indicator-model/board/index 已挂在其他位置';
    END IF;
    granted := granted || page_id;

    FOR item IN SELECT * FROM (VALUES
        ('modeling:indicator-board:query', '看板查询', 1), ('modeling:indicator-board:create', '看板新建', 2),
        ('modeling:indicator-board:update', '看板修改', 3), ('modeling:indicator-board:delete', '看板删除', 4)
    ) t(permission, label, sort) LOOP
        SELECT id INTO button_id FROM system_menu WHERE deleted = 0 AND permission = item.permission ORDER BY id LIMIT 1;
        IF button_id IS NULL THEN
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO button_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                    keep_alive, always_show, creator, updater, deleted)
            VALUES (button_id, item.label, item.permission, 3, item.sort, page_id, '', '#', 0, true, true, true,
                    'migration:0282', 'migration:0282', 0);
        ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = button_id AND type = 3 AND parent_id = page_id) THEN
            RAISE EXCEPTION '0282 权限冲突：% 已被其他菜单使用', item.permission;
        END IF;
        granted := granted || button_id;
    END LOOP;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                    (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY r.id, r.tenant_id, m.id), r.id, m.id, r.tenant_id,
           'migration:0282', 'migration:0282', 0
      FROM system_role r JOIN system_menu m ON m.id = ANY (granted) AND m.deleted = 0
     WHERE r.code = 'super_admin' AND r.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
