-- Migration: 0280 数据查询结果导出（审批制）
-- Purpose: 新增 dh_asset_data_export（导出申请：申请人、数据源与目标表、受限查询 SQL、行数上限、原因、状态、
--          BPM 流程实例、批准与下载截止时间、一次性下载记录、修订号）。审批通过后 24 小时内可下载一次，
--          下载时重新执行查询并按 DATA_EXPORT 场景动态脱敏，不保存导出文件和查询结果。
--          在“数据查询”页面（组件 asset/dataquery/index）下新增按钮权限 asset:data-export:create（申请、撤回）、
--          asset:data-export:query（本人申请列表、下载），并授权给超级管理员。
-- Depends on: 0279_governance_security-enhancements.sql（编号前驱；DATA_EXPORT 场景）；存在组件为
--             asset/dataquery/index 的“数据查询”页面菜单。运行时需在 BPM 流程管理中发布 Key 为 asset_data_export
--             的审批流程（审批节点使用默认拒绝策略），否则提交申请会被拒绝并记为 FAILED。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；序列、表、约束、索引带存在性保护，按钮按权限去重、授权按角色与菜单判重。
-- Rollback: 加法迁移，无不可逆操作。删除两个按钮及其 system_role_menu 授权，再删除 dh_asset_data_export 表及
--           dh_asset_data_export_seq 序列即可；已发起的 BPM 流程实例需在流程管理中另行取消。原始 SQL 不触发缓存
--           失效，提交后需清理权限菜单相关 Redis 缓存或重启 system 服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_asset_data_export_seq;
CREATE TABLE IF NOT EXISTS dh_asset_data_export (
    id int8 NOT NULL DEFAULT nextval('dh_asset_data_export_seq'::regclass),
    user_id int8 NOT NULL,
    datasource_id int8 NOT NULL,
    schema_name varchar(128) NOT NULL,
    table_name varchar(255) NOT NULL,
    sql_text text NOT NULL,
    row_limit int4 NOT NULL,
    reason varchar(500) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    process_instance_id varchar(64),
    result_reason varchar(500),
    approved_time timestamp(6),
    expire_time timestamp(6),
    download_time timestamp(6),
    download_rows int4,
    revision int4 NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_asset_data_export PRIMARY KEY (id),
    CONSTRAINT ck_asset_data_export_status CHECK (status IN
        ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED', 'DOWNLOADED', 'FAILED')),
    CONSTRAINT ck_asset_data_export_limit CHECK (row_limit BETWEEN 1 AND 1000),
    CONSTRAINT ck_asset_data_export_sql CHECK (length(sql_text) BETWEEN 1 AND 20000),
    CONSTRAINT ck_asset_data_export_approved CHECK (status NOT IN ('APPROVED', 'DOWNLOADED')
        OR (approved_time IS NOT NULL AND expire_time IS NOT NULL AND expire_time > approved_time)),
    CONSTRAINT ck_asset_data_export_downloaded CHECK (status <> 'DOWNLOADED' OR download_time IS NOT NULL)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_asset_data_export_process
    ON dh_asset_data_export (process_instance_id) WHERE process_instance_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_dh_asset_data_export_user ON dh_asset_data_export (user_id, id DESC);
COMMENT ON TABLE dh_asset_data_export IS '数据资产-数据查询结果导出申请（审批通过后一次性下载，不保存导出文件）';
COMMENT ON COLUMN dh_asset_data_export.id IS '编号（也是 BPM 业务标识）';
COMMENT ON COLUMN dh_asset_data_export.user_id IS '申请人编号';
COMMENT ON COLUMN dh_asset_data_export.datasource_id IS '数据源编号';
COMMENT ON COLUMN dh_asset_data_export.schema_name IS '目标 schema（申请时校验得到）';
COMMENT ON COLUMN dh_asset_data_export.table_name IS '目标表';
COMMENT ON COLUMN dh_asset_data_export.sql_text IS '受限查询 SQL（只保存在本表，不写入日志）';
COMMENT ON COLUMN dh_asset_data_export.row_limit IS '导出行数上限（1-1000）';
COMMENT ON COLUMN dh_asset_data_export.reason IS '申请原因';
COMMENT ON COLUMN dh_asset_data_export.status IS '状态：PENDING审批中、APPROVED已批准、REJECTED已驳回、CANCELED已撤回、DOWNLOADED已下载、FAILED流程未能发起';
COMMENT ON COLUMN dh_asset_data_export.process_instance_id IS 'BPM 流程实例编号';
COMMENT ON COLUMN dh_asset_data_export.result_reason IS '审批或撤回说明';
COMMENT ON COLUMN dh_asset_data_export.approved_time IS '批准时间';
COMMENT ON COLUMN dh_asset_data_export.expire_time IS '下载截止时间（批准后 24 小时）';
COMMENT ON COLUMN dh_asset_data_export.download_time IS '下载时间';
COMMENT ON COLUMN dh_asset_data_export.download_rows IS '下载的行数';
COMMENT ON COLUMN dh_asset_data_export.revision IS '乐观锁修订号';
COMMENT ON COLUMN dh_asset_data_export.creator IS '创建者';
COMMENT ON COLUMN dh_asset_data_export.create_time IS '申请时间';
COMMENT ON COLUMN dh_asset_data_export.updater IS '更新者';
COMMENT ON COLUMN dh_asset_data_export.update_time IS '更新时间';
COMMENT ON COLUMN dh_asset_data_export.deleted IS '逻辑删除：0未删除、1已删除（申请不做逻辑删除）';

LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    page_id bigint;
    button_id bigint;
    high_id bigint;
    buttons text[][] := ARRAY[
        ARRAY['asset:data-export:create', '导出申请', '20'],
        ARRAY['asset:data-export:query', '导出下载', '21']];
    granted bigint[] := ARRAY[]::bigint[];
    i int;
BEGIN
    SELECT id INTO page_id FROM system_menu
    WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'asset/dataquery/index' ORDER BY id LIMIT 1;
    IF page_id IS NULL THEN RAISE EXCEPTION '0280 缺少数据查询页面菜单（asset/dataquery/index）'; END IF;
    FOR i IN 1 .. array_length(buttons, 1) LOOP
        SELECT id INTO button_id FROM system_menu WHERE deleted = 0 AND permission = buttons[i][1] ORDER BY id LIMIT 1;
        IF button_id IS NULL THEN
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO button_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                    keep_alive, always_show, creator, updater, deleted)
            VALUES (button_id, buttons[i][2], buttons[i][1], 3, buttons[i][3]::int, page_id, '', '#', 0, true, true,
                    true, 'migration:0280', 'migration:0280', 0);
        ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = button_id AND type = 3 AND parent_id = page_id) THEN
            RAISE EXCEPTION '0280 权限冲突：% 已被其他菜单使用', buttons[i][1];
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
           'migration:0280', 'migration:0280', 0
      FROM system_role r JOIN system_menu m ON m.id = ANY (granted) AND m.deleted = 0
     WHERE r.code = 'super_admin' AND r.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
