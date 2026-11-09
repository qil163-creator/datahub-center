-- Migration: 0270 资产运营：使用热度、价值评估与盘点报告
-- Purpose: 新增资产运营指标日快照 dh_asset_operation_metric（每资产每天一行：近 30 天浏览/申请/使用/收藏/授权/
--          开放网关调用/问题反馈，热度、广度、质量、完整度、价值分与等级、运营建议）；在“数据资产”下新增“资产运营”
--          目录及“资产使用与价值”“资产盘点报告”两个页面，权限 asset:operation:query/refresh/export。
-- Depends on: 平台库已升级至 0269；存在 dh_asset_usage_log、dh_market_resource_apply 与“数据资产”根菜单（/asset）。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；表与索引带存在性保护，菜单按路径/组件定位、按权限去重。
-- Rollback: 加法迁移。移除 creator='migration:0270' 的菜单及其角色授权；快照表可删除（可由任务完整重建）。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_asset_operation_metric_seq;

CREATE TABLE IF NOT EXISTS dh_asset_operation_metric (
    id int8 NOT NULL DEFAULT nextval('dh_asset_operation_metric_seq'::regclass),
    stat_date date NOT NULL,
    window_days int4 NOT NULL DEFAULT 30,
    asset_id int8 NOT NULL,
    asset_name varchar(256),
    asset_code varchar(128),
    asset_type int2,
    asset_status int2,
    category_id int8,
    layer varchar(64),
    source_system_name varchar(256),
    owner_dept_id int8,
    browse_count int8 NOT NULL DEFAULT 0,
    browse_user_count int8 NOT NULL DEFAULT 0,
    apply_count int8 NOT NULL DEFAULT 0,
    usage_count int8 NOT NULL DEFAULT 0,
    usage_user_count int8 NOT NULL DEFAULT 0,
    favorite_count int8 NOT NULL DEFAULT 0,
    active_grant_count int8 NOT NULL DEFAULT 0,
    grant_dept_count int8 NOT NULL DEFAULT 0,
    api_call_count int8 NOT NULL DEFAULT 0,
    api_success_count int8 NOT NULL DEFAULT 0,
    api_app_count int8 NOT NULL DEFAULT 0,
    feedback_count int8 NOT NULL DEFAULT 0,
    last_used_time timestamp(6),
    idle_days int4 NOT NULL DEFAULT 0,
    heat_score numeric(4,1) NOT NULL DEFAULT 0,
    breadth_score numeric(4,1) NOT NULL DEFAULT 0,
    quality_score numeric(4,1),
    completeness_score numeric(4,1) NOT NULL DEFAULT 0,
    value_score numeric(4,1) NOT NULL DEFAULT 0,
    value_level varchar(16) NOT NULL,
    suggestion varchar(32) NOT NULL,
    api_stats_available bool NOT NULL DEFAULT true,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_asset_operation_metric PRIMARY KEY (id),
    CONSTRAINT ck_asset_operation_metric_level CHECK (value_level IN ('HIGH', 'MEDIUM', 'LOW', 'IDLE')),
    CONSTRAINT ck_asset_operation_metric_suggestion CHECK (suggestion IN
        ('KEEP', 'IMPROVE_QUALITY', 'COMPLETE_METADATA', 'PROMOTE', 'RETIRE_REVIEW')),
    CONSTRAINT ck_asset_operation_metric_scores CHECK (heat_score BETWEEN 0 AND 100 AND breadth_score BETWEEN 0 AND 100
        AND completeness_score BETWEEN 0 AND 100 AND value_score BETWEEN 0 AND 100
        AND (quality_score IS NULL OR quality_score BETWEEN 0 AND 100))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_operation_metric_day
    ON dh_asset_operation_metric (stat_date, asset_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_asset_operation_metric_asset
    ON dh_asset_operation_metric (asset_id, stat_date) WHERE deleted = 0;

COMMENT ON TABLE dh_asset_operation_metric IS '数据资产-运营指标日快照（使用热度、价值评估、运营建议）';
COMMENT ON COLUMN dh_asset_operation_metric.id IS '快照编号';
COMMENT ON COLUMN dh_asset_operation_metric.stat_date IS '统计日期；同日重算整体替换';
COMMENT ON COLUMN dh_asset_operation_metric.window_days IS '统计窗口天数（截至统计日含当天）';
COMMENT ON COLUMN dh_asset_operation_metric.asset_id IS '资产编号';
COMMENT ON COLUMN dh_asset_operation_metric.asset_name IS '资产名称快照';
COMMENT ON COLUMN dh_asset_operation_metric.asset_code IS '资产编码快照';
COMMENT ON COLUMN dh_asset_operation_metric.asset_type IS '资产类型：1-库表 2-接口 3-文件';
COMMENT ON COLUMN dh_asset_operation_metric.asset_status IS '资产状态：0-未发布 1-已发布 2-已下线';
COMMENT ON COLUMN dh_asset_operation_metric.category_id IS '资产类目';
COMMENT ON COLUMN dh_asset_operation_metric.layer IS '数仓分层';
COMMENT ON COLUMN dh_asset_operation_metric.source_system_name IS '来源系统';
COMMENT ON COLUMN dh_asset_operation_metric.owner_dept_id IS '归属部门';
COMMENT ON COLUMN dh_asset_operation_metric.browse_count IS '窗口内浏览次数';
COMMENT ON COLUMN dh_asset_operation_metric.browse_user_count IS '窗口内浏览人数';
COMMENT ON COLUMN dh_asset_operation_metric.apply_count IS '窗口内申请次数';
COMMENT ON COLUMN dh_asset_operation_metric.usage_count IS '窗口内使用（数据预览/查询）次数';
COMMENT ON COLUMN dh_asset_operation_metric.usage_user_count IS '窗口内使用人数';
COMMENT ON COLUMN dh_asset_operation_metric.favorite_count IS '累计收藏数';
COMMENT ON COLUMN dh_asset_operation_metric.active_grant_count IS '当前有效授权数（已通过且未过期）';
COMMENT ON COLUMN dh_asset_operation_metric.grant_dept_count IS '当前有效授权覆盖部门数';
COMMENT ON COLUMN dh_asset_operation_metric.api_call_count IS '窗口内开放网关真实调用次数（APP_GATEWAY，不含测试）';
COMMENT ON COLUMN dh_asset_operation_metric.api_success_count IS '窗口内开放网关成功次数';
COMMENT ON COLUMN dh_asset_operation_metric.api_app_count IS '窗口内调用应用数（按接口累加）';
COMMENT ON COLUMN dh_asset_operation_metric.feedback_count IS '窗口内质量问题反馈（核查类评论）数';
COMMENT ON COLUMN dh_asset_operation_metric.last_used_time IS '最近一次真实使用时间：申请、预览/查询或网关调用（浏览不计）';
COMMENT ON COLUMN dh_asset_operation_metric.idle_days IS '闲置天数：距最近使用；从未使用按发布时间、再按创建时间计';
COMMENT ON COLUMN dh_asset_operation_metric.heat_score IS '热度分 0-100：min(100, 25×log10(1+活跃度))';
COMMENT ON COLUMN dh_asset_operation_metric.breadth_score IS '使用广度分 0-100：授权部门、有效授权与调用应用';
COMMENT ON COLUMN dh_asset_operation_metric.quality_score IS '质量分 0-100（资产健康分），未评估为空';
COMMENT ON COLUMN dh_asset_operation_metric.completeness_score IS '元数据完整度分 0-100（8 项编目信息满足比例）';
COMMENT ON COLUMN dh_asset_operation_metric.value_score IS '综合价值分 0-100：热度35%+广度25%+质量20%+完整度20%，质量缺失时按比例分摊';
COMMENT ON COLUMN dh_asset_operation_metric.value_level IS '价值等级：HIGH≥70、MEDIUM≥40、LOW、IDLE（已发布、闲置≥90天且无有效授权）';
COMMENT ON COLUMN dh_asset_operation_metric.suggestion IS '运营建议：KEEP保持、IMPROVE_QUALITY优先治理、COMPLETE_METADATA补全编目、PROMOTE加强推广、RETIRE_REVIEW评估下线';
COMMENT ON COLUMN dh_asset_operation_metric.api_stats_available IS '是否取得开放网关调用统计；数据服务不可用时为 false，接口调用按 0 计';
COMMENT ON COLUMN dh_asset_operation_metric.creator IS '创建者';
COMMENT ON COLUMN dh_asset_operation_metric.create_time IS '创建时间';
COMMENT ON COLUMN dh_asset_operation_metric.updater IS '更新者';
COMMENT ON COLUMN dh_asset_operation_metric.update_time IS '更新时间';
COMMENT ON COLUMN dh_asset_operation_metric.deleted IS '逻辑删除：0未删除、1已删除';

LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE root_id bigint; dir_id bigint; page_id bigint; usage_id bigint; new_id bigint; high_id bigint; entry record;
BEGIN
    SELECT id INTO root_id FROM system_menu WHERE deleted = 0 AND parent_id = 0 AND trim(both '/' from path) = 'asset';
    IF root_id IS NULL THEN RAISE EXCEPTION '0270 数据资产父菜单缺失'; END IF;
    SELECT id INTO dir_id FROM system_menu WHERE deleted = 0 AND parent_id = root_id AND path = 'operation';
    IF dir_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO dir_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                keep_alive, always_show, creator, updater, deleted)
        VALUES (dir_id, '资产运营', '', 1, 7, root_id, 'operation', 'carbon:chart-evaluation', 0, true, true, true,
                'migration:0270', 'migration:0270', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = dir_id AND type = 1) THEN
        RAISE EXCEPTION '0270 菜单路径冲突：asset/operation';
    END IF;
    FOR entry IN SELECT * FROM (VALUES
        ('资产使用与价值', 'usage', 'asset/operation/usage/index', 'AssetOperationUsage', 1, 'carbon:chart-line-data'),
        ('资产盘点报告', 'inventory', 'asset/operation/inventory/index', 'AssetOperationInventory', 2, 'carbon:report')
    ) e(label, path, component, component_name, sort, icon) LOOP
        SELECT id INTO page_id FROM system_menu WHERE component = entry.component AND deleted = 0;
        IF page_id IS NULL THEN
            IF EXISTS (SELECT 1 FROM system_menu WHERE parent_id = dir_id AND path = entry.path AND deleted = 0) THEN
                RAISE EXCEPTION '0270 菜单路径冲突：%', entry.path;
            END IF;
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                                    status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES (page_id, entry.label, 'asset:operation:query', 2, entry.sort, dir_id, entry.path, entry.icon,
                    entry.component, entry.component_name, 0, true, true, true, 'migration:0270', 'migration:0270', 0);
        ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND parent_id = dir_id AND path = entry.path
                                                     AND permission = 'asset:operation:query') THEN
            RAISE EXCEPTION '0270 菜单组件身份冲突：%', entry.component;
        END IF;
        IF entry.path = 'usage' THEN usage_id := page_id; END IF;
    END LOOP;
    FOR entry IN SELECT * FROM (VALUES ('刷新运营指标', 'refresh', 1), ('导出盘点清单', 'export', 2))
                 e(label, code, sort) LOOP
        IF NOT EXISTS (SELECT 1 FROM system_menu WHERE permission = 'asset:operation:' || entry.code AND deleted = 0) THEN
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO new_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                    keep_alive, always_show, creator, updater, deleted)
            VALUES (new_id, entry.label, 'asset:operation:' || entry.code, 3, entry.sort, usage_id, '', '#', 0, true,
                    true, true, 'migration:0270', 'migration:0270', 0);
        END IF;
    END LOOP;
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                    (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY r.id, r.tenant_id, m.id), r.id, m.id, r.tenant_id,
           'migration:0270', 'migration:0270', 0
      FROM system_role r CROSS JOIN system_menu m
     WHERE r.code = 'super_admin' AND r.deleted = 0 AND m.deleted = 0 AND m.creator = 'migration:0270'
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
