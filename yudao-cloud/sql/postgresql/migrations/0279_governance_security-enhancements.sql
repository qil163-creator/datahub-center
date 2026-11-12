-- Migration: 0279 数据安全增强：场景豁免口径、ETL/导出场景、字段加密密钥引用、异常访问告警
-- Purpose: 1) dh_governance_mask_scene 新增 whitelist_scene（场景生效的白名单豁免口径，管理端可改配），
--             场景编码新增 ETL_PREVIEW（ETL 设计预览）、DATA_EXPORT（数据导出），并补齐两行启用的种子数据；
--          2) 新增 dh_governance_safety_access_alert（异常访问告警，按“规则+用户+窗口”唯一去重）；
--          3) 访问审计类型说明补充 EXPORT（数据导出）、SCAN（敏感识别采样，明文且不返回用户）；
--          4) 在“数据治理 > 数据安全”目录下新增“密钥管理”（governance/keyref/index，按钮
--             governance:key-ref:query/create/update）与“异常访问”（governance/accessalert/index，按钮
--             governance:access-alert:query/handle）两个页面，仅授权超级管理员。
-- Depends on: 0278_modeling_composite-indicator-query.sql（编号前驱）；0276_governance_dynamic-mask-scene.sql
--             （场景表与 ck_mask_scene_code 约束）；存在 /govern 顶级目录及其下 path=safety 的“数据安全”目录。
--             字段加密运行时需在部署环境配置 yudao.governance.key-center.master-keys 与 active-version，本脚本不涉及密钥。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；列、表、索引带存在性保护，约束先删后建，种子按 scene_code 去重，菜单按组件定位、按钮按权限去重。
-- Rollback: 加法迁移，无不可逆操作。删除两个新页面及其按钮与 system_role_menu 授权，删除
--           dh_governance_safety_access_alert 表及其序列，删除 ETL_PREVIEW/DATA_EXPORT 场景行后按 0276 的取值重建
--           ck_mask_scene_code，再删除 whitelist_scene 列。原始 SQL 不触发缓存失效，提交后需清理权限菜单相关
--           Redis 缓存或重启 system 服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 1) 场景豁免口径与新增场景
ALTER TABLE dh_governance_mask_scene ADD COLUMN IF NOT EXISTS whitelist_scene varchar(16);
COMMENT ON COLUMN dh_governance_mask_scene.whitelist_scene IS '生效的白名单豁免口径：QUERY/SHARE/OPEN/EXPORT；为空取场景默认值';
ALTER TABLE dh_governance_mask_scene DROP CONSTRAINT IF EXISTS ck_mask_scene_whitelist;
ALTER TABLE dh_governance_mask_scene ADD CONSTRAINT ck_mask_scene_whitelist
    CHECK (whitelist_scene IS NULL OR whitelist_scene IN ('QUERY', 'SHARE', 'OPEN', 'EXPORT'));
ALTER TABLE dh_governance_mask_scene DROP CONSTRAINT IF EXISTS ck_mask_scene_code;
ALTER TABLE dh_governance_mask_scene ADD CONSTRAINT ck_mask_scene_code CHECK (scene_code IN
    ('DATA_QUERY', 'ASSET_PREVIEW', 'MARKET_PREVIEW', 'DATABASE_PREVIEW', 'INDICATOR_QUERY', 'ETL_PREVIEW', 'DATA_EXPORT'));
COMMENT ON COLUMN dh_governance_mask_scene.scene_code IS '场景编码：DATA_QUERY数据查询、ASSET_PREVIEW资产预览、MARKET_PREVIEW集市预览、DATABASE_PREVIEW数据库预览、INDICATOR_QUERY指标查询、ETL_PREVIEW ETL设计预览、DATA_EXPORT数据导出';
INSERT INTO dh_governance_mask_scene(scene_code, enabled, remark, revision, creator, updater, deleted)
SELECT code, true, '', 0, 'migration:0279', 'migration:0279', 0
  FROM (VALUES ('ETL_PREVIEW'), ('DATA_EXPORT')) AS seed(code)
 WHERE NOT EXISTS (SELECT 1 FROM dh_governance_mask_scene old WHERE old.scene_code = seed.code);

-- 2) 异常访问告警
CREATE SEQUENCE IF NOT EXISTS dh_governance_safety_access_alert_seq;
CREATE TABLE IF NOT EXISTS dh_governance_safety_access_alert (
    id int8 NOT NULL DEFAULT nextval('dh_governance_safety_access_alert_seq'::regclass),
    dedupe_key varchar(128) NOT NULL,
    rule_code varchar(32) NOT NULL,
    user_id int8 NOT NULL,
    user_name varchar(64) NOT NULL DEFAULT '',
    window_start timestamp(6) NOT NULL,
    window_end timestamp(6) NOT NULL,
    metric_value int4 NOT NULL,
    threshold int4 NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    handler_id int8,
    handle_time timestamp(6),
    handle_remark varchar(500),
    revision int4 NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_safety_access_alert PRIMARY KEY (id),
    CONSTRAINT uk_dh_governance_safety_access_alert_dedupe UNIQUE (dedupe_key),
    CONSTRAINT ck_access_alert_rule CHECK (rule_code IN ('VOLUME', 'PLAINTEXT', 'OFF_HOURS', 'TABLE_SPREAD')),
    CONSTRAINT ck_access_alert_status CHECK (status IN ('OPEN', 'CONFIRMED', 'IGNORED')),
    CONSTRAINT ck_access_alert_window CHECK (window_end > window_start),
    CONSTRAINT ck_access_alert_handled CHECK (status = 'OPEN' OR (handler_id IS NOT NULL AND handle_time IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_dh_governance_safety_access_alert_status
    ON dh_governance_safety_access_alert (status, window_start DESC);
CREATE INDEX IF NOT EXISTS idx_dh_governance_safety_access_alert_user
    ON dh_governance_safety_access_alert (user_id, window_start DESC);
COMMENT ON TABLE dh_governance_safety_access_alert IS '数据安全-异常访问告警（只记统计值与规则，不记访问到的数据）';
COMMENT ON COLUMN dh_governance_safety_access_alert.id IS '编号';
COMMENT ON COLUMN dh_governance_safety_access_alert.dedupe_key IS '去重键：规则:用户:窗口开始，任务重跑不重复告警';
COMMENT ON COLUMN dh_governance_safety_access_alert.rule_code IS '规则：VOLUME敏感访问过多、PLAINTEXT豁免明文访问过多、OFF_HOURS非工作时段访问、TABLE_SPREAD访问表过多';
COMMENT ON COLUMN dh_governance_safety_access_alert.user_id IS '访问用户编号';
COMMENT ON COLUMN dh_governance_safety_access_alert.user_name IS '访问用户名称快照';
COMMENT ON COLUMN dh_governance_safety_access_alert.window_start IS '统计窗口开始';
COMMENT ON COLUMN dh_governance_safety_access_alert.window_end IS '统计窗口结束（不含）';
COMMENT ON COLUMN dh_governance_safety_access_alert.metric_value IS '统计值';
COMMENT ON COLUMN dh_governance_safety_access_alert.threshold IS '触发阈值';
COMMENT ON COLUMN dh_governance_safety_access_alert.status IS '状态：OPEN待处理、CONFIRMED已确认风险、IGNORED已忽略';
COMMENT ON COLUMN dh_governance_safety_access_alert.handler_id IS '处理人编号';
COMMENT ON COLUMN dh_governance_safety_access_alert.handle_time IS '处理时间';
COMMENT ON COLUMN dh_governance_safety_access_alert.handle_remark IS '处理说明';
COMMENT ON COLUMN dh_governance_safety_access_alert.revision IS '乐观锁修订号';
COMMENT ON COLUMN dh_governance_safety_access_alert.creator IS '创建者';
COMMENT ON COLUMN dh_governance_safety_access_alert.create_time IS '识别时间';
COMMENT ON COLUMN dh_governance_safety_access_alert.updater IS '更新者';
COMMENT ON COLUMN dh_governance_safety_access_alert.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_safety_access_alert.deleted IS '逻辑删除：0未删除、1已删除（告警不做逻辑删除）';

-- 3) 审计类型说明
COMMENT ON COLUMN dh_governance_safety_access_log.access_type IS '访问类型：API数据服务接口、STATIC_TASK静态脱敏任务、QUERY数据查询、DEV数据开发调试、EXPLORE数据探查/预览、ACCESS数据接入/集市、EXPORT数据导出、SCAN敏感识别采样（明文、不返回用户）';
COMMENT ON COLUMN dh_governance_safety_access_log.masked_flag IS '是否已脱敏：1已脱敏、0明文（白名单豁免或敏感识别采样）';
COMMENT ON COLUMN dh_governance_safety_key_ref.external_key_id IS '外部密钥服务中的密钥标识；KEY_CENTER 为随机派生盐，创建后不可修改';

-- 4) 菜单与权限
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    govern_id bigint;
    dir_id bigint;
    page_id bigint;
    button_id bigint;
    high_id bigint;
    pages text[][] := ARRAY[
        ARRAY['governance/keyref/index', 'key-ref', '密钥管理', 'carbon:password', 'SafetyKeyRef', '9'],
        ARRAY['governance/accessalert/index', 'access-alert', '异常访问', 'carbon:warning-alt', 'SafetyAccessAlert', '10']];
    buttons text[][] := ARRAY[
        ARRAY['governance/keyref/index', 'governance:key-ref:query', '密钥引用查询', '1'],
        ARRAY['governance/keyref/index', 'governance:key-ref:create', '密钥引用新建', '2'],
        ARRAY['governance/keyref/index', 'governance:key-ref:update', '密钥引用启停', '3'],
        ARRAY['governance/accessalert/index', 'governance:access-alert:query', '异常访问查询', '1'],
        ARRAY['governance/accessalert/index', 'governance:access-alert:handle', '异常访问处理', '2']];
    granted bigint[] := ARRAY[]::bigint[];
    i int;
BEGIN
    SELECT id INTO govern_id FROM system_menu
    WHERE deleted = 0 AND parent_id = 0 AND type = 1 AND trim(both '/' from coalesce(path, '')) = 'govern'
    ORDER BY id LIMIT 1;
    IF govern_id IS NULL THEN RAISE EXCEPTION '0279 缺少数据治理顶级目录 /govern'; END IF;
    SELECT id INTO dir_id FROM system_menu
    WHERE deleted = 0 AND parent_id = govern_id AND type = 1 AND trim(both '/' from coalesce(path, '')) = 'safety'
    ORDER BY id LIMIT 1;
    IF dir_id IS NULL THEN RAISE EXCEPTION '0279 缺少数据安全目录 /govern/safety'; END IF;

    FOR i IN 1 .. array_length(pages, 1) LOOP
        SELECT id INTO page_id FROM system_menu
        WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = pages[i][1] ORDER BY id LIMIT 1;
        IF page_id IS NULL THEN
            IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = dir_id
                                                   AND trim(both '/' from coalesce(path, '')) = pages[i][2]) THEN
                RAISE EXCEPTION '0279 菜单路径冲突：/govern/safety/% 已被其他菜单占用', pages[i][2];
            END IF;
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                                    status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES (page_id, pages[i][3], '', 2, pages[i][6]::int, dir_id, pages[i][2], pages[i][4], pages[i][1],
                    pages[i][5], 0, true, true, true, 'migration:0279', 'migration:0279', 0);
        ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND parent_id = dir_id AND type = 2) THEN
            RAISE EXCEPTION '0279 菜单组件身份冲突：% 已挂在其他位置', pages[i][1];
        END IF;
        granted := granted || page_id;
    END LOOP;

    FOR i IN 1 .. array_length(buttons, 1) LOOP
        SELECT id INTO page_id FROM system_menu
        WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = buttons[i][1] ORDER BY id LIMIT 1;
        SELECT id INTO button_id FROM system_menu WHERE deleted = 0 AND permission = buttons[i][2] ORDER BY id LIMIT 1;
        IF button_id IS NULL THEN
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO button_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                    keep_alive, always_show, creator, updater, deleted)
            VALUES (button_id, buttons[i][3], buttons[i][2], 3, buttons[i][4]::int, page_id, '', '#', 0, true, true,
                    true, 'migration:0279', 'migration:0279', 0);
        ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = button_id AND type = 3 AND parent_id = page_id) THEN
            RAISE EXCEPTION '0279 权限冲突：% 已被其他菜单使用', buttons[i][2];
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
           'migration:0279', 'migration:0279', 0
      FROM system_role r JOIN system_menu m ON m.id = ANY (granted) AND m.deleted = 0
     WHERE r.code = 'super_admin' AND r.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
