-- Migration: 0295 主数据相似匹配、去重合并与黄金记录
-- Purpose: 增加主数据相似匹配规则、匹配执行记录、疑似重复组和合并来源追溯（黄金记录）四张表；
--          主数据变更申请增加“合并”操作与关联的疑似重复组；新增“相似匹配与合并”页面及查询、规则配置、
--          执行匹配、提交合并、忽略五个权限，并授予超级管理员角色。
-- Depends on: 0294_governance_quality-instance-nullable-pass-rate.sql（编号前驱）；
--             dh_governance_master_model、dh_governance_master_data_change 及主数据管理目录菜单（path=master）已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；建表、加列、索引按存在性处理，约束先删后建，菜单按组件与权限标识识别，重放不重复插入、不覆盖角色授权。
-- Rollback: 失败整笔回滚。提交后回退应用前须确认没有 operation = 'MERGE' 的变更申请（或先归档），再恢复原操作约束；
--           删除新增四张表会丢失匹配结果与合并来源追溯，须先备份。菜单可按权限标识 governance:master-match:* 逻辑删除。
-- 加列与重建约束会短时锁变更申请表；锁等待超过十秒或执行超过两分钟则回滚。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- ==================== 相似匹配规则（每个模型一条） ====================
CREATE SEQUENCE IF NOT EXISTS dh_governance_master_match_rule_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_match_rule (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_match_rule_seq'),
    model_id bigint NOT NULL,
    fields_json text NOT NULL,
    blocking_fields_json text NOT NULL DEFAULT '[]',
    threshold numeric(5, 4) NOT NULL,
    survivorship_json text NOT NULL DEFAULT '{}',
    max_rows integer NOT NULL DEFAULT 20000,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_match_rule_threshold CHECK (threshold >= 0.5 AND threshold <= 1),
    CONSTRAINT ck_master_match_rule_max_rows CHECK (max_rows BETWEEN 2 AND 100000),
    CONSTRAINT ck_master_match_rule_fields CHECK (jsonb_typeof(fields_json::jsonb) = 'array'),
    CONSTRAINT ck_master_match_rule_blocking CHECK (jsonb_typeof(blocking_fields_json::jsonb) = 'array'),
    CONSTRAINT ck_master_match_rule_survivorship CHECK (jsonb_typeof(survivorship_json::jsonb) = 'object'),
    CONSTRAINT fk_master_match_rule_model FOREIGN KEY (model_id) REFERENCES dh_governance_master_model (id)
);
ALTER SEQUENCE dh_governance_master_match_rule_seq OWNED BY dh_governance_master_match_rule.id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_match_rule_model ON dh_governance_master_match_rule (model_id) WHERE deleted = 0;
COMMENT ON TABLE dh_governance_master_match_rule IS '主数据管理-相似匹配规则';
COMMENT ON COLUMN dh_governance_master_match_rule.id IS '规则编号';
COMMENT ON COLUMN dh_governance_master_match_rule.model_id IS '主数据模型编号';
COMMENT ON COLUMN dh_governance_master_match_rule.fields_json IS '参与打分的字段、算法与权重';
COMMENT ON COLUMN dh_governance_master_match_rule.blocking_fields_json IS '分块字段：取值相同的记录才互相比较';
COMMENT ON COLUMN dh_governance_master_match_rule.threshold IS '疑似重复相似度阈值';
COMMENT ON COLUMN dh_governance_master_match_rule.survivorship_json IS '黄金记录字段取值规则';
COMMENT ON COLUMN dh_governance_master_match_rule.max_rows IS '单次匹配最大读取记录数';
COMMENT ON COLUMN dh_governance_master_match_rule.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_match_rule.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_match_rule.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_match_rule.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_match_rule.deleted IS '是否删除';

-- ==================== 匹配执行记录 ====================
CREATE SEQUENCE IF NOT EXISTS dh_governance_master_match_run_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_match_run (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_match_run_seq'),
    model_id bigint NOT NULL,
    rule_json text NOT NULL,
    status varchar(16) NOT NULL,
    scanned_rows integer,
    compared_pairs bigint,
    group_count integer,
    message varchar(500),
    started_at timestamp NOT NULL,
    finished_at timestamp,
    run_by bigint NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_match_run_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT fk_master_match_run_model FOREIGN KEY (model_id) REFERENCES dh_governance_master_model (id)
);
ALTER SEQUENCE dh_governance_master_match_run_seq OWNED BY dh_governance_master_match_run.id;
-- 同一模型同时只允许一次匹配在执行
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_match_run_running ON dh_governance_master_match_run (model_id)
    WHERE status = 'RUNNING' AND deleted = 0;
CREATE INDEX IF NOT EXISTS idx_master_match_run_model ON dh_governance_master_match_run (model_id, id DESC) WHERE deleted = 0;
COMMENT ON TABLE dh_governance_master_match_run IS '主数据管理-相似匹配执行记录';
COMMENT ON COLUMN dh_governance_master_match_run.id IS '执行编号';
COMMENT ON COLUMN dh_governance_master_match_run.model_id IS '主数据模型编号';
COMMENT ON COLUMN dh_governance_master_match_run.rule_json IS '执行时的规则快照';
COMMENT ON COLUMN dh_governance_master_match_run.status IS '状态：执行中、成功、失败';
COMMENT ON COLUMN dh_governance_master_match_run.scanned_rows IS '读取记录数';
COMMENT ON COLUMN dh_governance_master_match_run.compared_pairs IS '比较记录对数';
COMMENT ON COLUMN dh_governance_master_match_run.group_count IS '新增疑似重复组数';
COMMENT ON COLUMN dh_governance_master_match_run.message IS '执行说明或失败原因';
COMMENT ON COLUMN dh_governance_master_match_run.started_at IS '开始时间';
COMMENT ON COLUMN dh_governance_master_match_run.finished_at IS '结束时间';
COMMENT ON COLUMN dh_governance_master_match_run.run_by IS '执行人编号';
COMMENT ON COLUMN dh_governance_master_match_run.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_match_run.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_match_run.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_match_run.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_match_run.deleted IS '是否删除';

-- ==================== 疑似重复组 ====================
CREATE SEQUENCE IF NOT EXISTS dh_governance_master_match_group_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_match_group (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_match_group_seq'),
    run_id bigint NOT NULL,
    model_id bigint NOT NULL,
    fingerprint varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    member_count integer NOT NULL,
    max_score numeric(5, 4) NOT NULL,
    members_json text NOT NULL,
    pairs_json text NOT NULL,
    change_id bigint,
    handled_by bigint,
    handled_at timestamp,
    handle_remark varchar(500),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_match_group_status CHECK (status IN ('PENDING', 'MERGING', 'MERGED', 'IGNORED', 'STALE')),
    CONSTRAINT ck_master_match_group_members CHECK (member_count BETWEEN 2 AND 20 AND jsonb_typeof(members_json::jsonb) = 'array'),
    CONSTRAINT ck_master_match_group_pairs CHECK (jsonb_typeof(pairs_json::jsonb) = 'array'),
    CONSTRAINT fk_master_match_group_run FOREIGN KEY (run_id) REFERENCES dh_governance_master_match_run (id),
    CONSTRAINT fk_master_match_group_model FOREIGN KEY (model_id) REFERENCES dh_governance_master_model (id),
    CONSTRAINT fk_master_match_group_change FOREIGN KEY (change_id) REFERENCES dh_governance_master_data_change (id)
);
ALTER SEQUENCE dh_governance_master_match_group_seq OWNED BY dh_governance_master_match_group.id;
CREATE INDEX IF NOT EXISTS idx_master_match_group_model ON dh_governance_master_match_group (model_id, status, id DESC)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_master_match_group_fingerprint ON dh_governance_master_match_group (model_id, fingerprint)
    WHERE deleted = 0;
COMMENT ON TABLE dh_governance_master_match_group IS '主数据管理-疑似重复组';
COMMENT ON COLUMN dh_governance_master_match_group.id IS '疑似重复组编号';
COMMENT ON COLUMN dh_governance_master_match_group.run_id IS '产生该组的匹配执行编号';
COMMENT ON COLUMN dh_governance_master_match_group.model_id IS '主数据模型编号';
COMMENT ON COLUMN dh_governance_master_match_group.fingerprint IS '成员主键集合摘要，用于识别已忽略的同一组';
COMMENT ON COLUMN dh_governance_master_match_group.status IS '状态：待处理、合并中、已合并、已忽略、已过期';
COMMENT ON COLUMN dh_governance_master_match_group.member_count IS '成员数';
COMMENT ON COLUMN dh_governance_master_match_group.max_score IS '组内最高相似度';
COMMENT ON COLUMN dh_governance_master_match_group.members_json IS '成员主键与匹配时的记录快照';
COMMENT ON COLUMN dh_governance_master_match_group.pairs_json IS '组内达到阈值的记录对及逐字段得分';
COMMENT ON COLUMN dh_governance_master_match_group.change_id IS '合并提交的主数据变更申请编号';
COMMENT ON COLUMN dh_governance_master_match_group.handled_by IS '处理人编号';
COMMENT ON COLUMN dh_governance_master_match_group.handled_at IS '处理时间';
COMMENT ON COLUMN dh_governance_master_match_group.handle_remark IS '处理说明';
COMMENT ON COLUMN dh_governance_master_match_group.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_match_group.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_match_group.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_match_group.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_match_group.deleted IS '是否删除';

-- ==================== 合并申请：操作类型与疑似重复组 ====================
ALTER TABLE dh_governance_master_data_change ADD COLUMN IF NOT EXISTS match_group_id bigint;
COMMENT ON COLUMN dh_governance_master_data_change.match_group_id IS '合并申请对应的疑似重复组编号';
ALTER TABLE dh_governance_master_data_change DROP CONSTRAINT IF EXISTS ck_master_data_change_operation;
ALTER TABLE dh_governance_master_data_change ADD CONSTRAINT ck_master_data_change_operation
    CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE', 'MERGE'));
COMMENT ON COLUMN dh_governance_master_data_change.operation IS '操作类型：新增、修改、删除、合并';

-- ==================== 合并来源追溯（黄金记录） ====================
CREATE SEQUENCE IF NOT EXISTS dh_governance_master_merge_xref_seq;
CREATE TABLE IF NOT EXISTS dh_governance_master_merge_xref (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_master_merge_xref_seq'),
    model_id bigint NOT NULL,
    change_id bigint NOT NULL,
    group_id bigint,
    golden_key varchar(1000) NOT NULL,
    source_key varchar(1000) NOT NULL,
    source_row_json text NOT NULL,
    merged_by bigint,
    merged_at timestamp NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_master_merge_xref_row CHECK (jsonb_typeof(source_row_json::jsonb) = 'object'),
    CONSTRAINT fk_master_merge_xref_model FOREIGN KEY (model_id) REFERENCES dh_governance_master_model (id),
    CONSTRAINT fk_master_merge_xref_change FOREIGN KEY (change_id) REFERENCES dh_governance_master_data_change (id),
    CONSTRAINT fk_master_merge_xref_group FOREIGN KEY (group_id) REFERENCES dh_governance_master_match_group (id)
);
ALTER SEQUENCE dh_governance_master_merge_xref_seq OWNED BY dh_governance_master_merge_xref.id;
CREATE INDEX IF NOT EXISTS idx_master_merge_xref_golden ON dh_governance_master_merge_xref (model_id, golden_key)
    WHERE deleted = 0;
COMMENT ON TABLE dh_governance_master_merge_xref IS '主数据管理-合并来源追溯';
COMMENT ON COLUMN dh_governance_master_merge_xref.id IS '追溯记录编号';
COMMENT ON COLUMN dh_governance_master_merge_xref.model_id IS '主数据模型编号';
COMMENT ON COLUMN dh_governance_master_merge_xref.change_id IS '合并变更申请编号';
COMMENT ON COLUMN dh_governance_master_merge_xref.group_id IS '疑似重复组编号';
COMMENT ON COLUMN dh_governance_master_merge_xref.golden_key IS '黄金记录主键（与变更日志数据标识一致）';
COMMENT ON COLUMN dh_governance_master_merge_xref.source_key IS '被合并记录主键';
COMMENT ON COLUMN dh_governance_master_merge_xref.source_row_json IS '被合并记录合并前的完整内容';
COMMENT ON COLUMN dh_governance_master_merge_xref.merged_by IS '执行合并的用户编号';
COMMENT ON COLUMN dh_governance_master_merge_xref.merged_at IS '合并生效时间';
COMMENT ON COLUMN dh_governance_master_merge_xref.creator IS '创建者';
COMMENT ON COLUMN dh_governance_master_merge_xref.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_master_merge_xref.updater IS '更新者';
COMMENT ON COLUMN dh_governance_master_merge_xref.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_master_merge_xref.deleted IS '是否删除';

-- ==================== 菜单与权限 ====================
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    master_parent_id bigint;
    matches integer;
    page_id bigint;
    button_id bigint;
    high_id bigint;
    granted bigint[] := ARRAY[]::bigint[];
    item record;
BEGIN
    SELECT count(*), min(m.id) INTO matches, master_parent_id
      FROM system_menu m JOIN system_menu p ON p.id = m.parent_id AND p.deleted = 0
     WHERE m.deleted = 0 AND m.type = 1 AND trim(both '/' from coalesce(m.path, '')) = 'master'
       AND trim(both '/' from coalesce(p.path, '')) = 'govern';
    IF matches <> 1 THEN
        RAISE EXCEPTION '0295 主数据管理目录（数据治理/master）缺失或不唯一';
    END IF;

    SELECT count(*), min(id) INTO matches, page_id FROM system_menu
    WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'governance/mastermatch/index';
    IF matches > 1 THEN
        RAISE EXCEPTION '0295 菜单组件身份重复：governance/mastermatch/index';
    END IF;
    IF matches = 0 THEN
        IF EXISTS (SELECT 1 FROM system_menu WHERE deleted = 0 AND parent_id = master_parent_id
                                               AND trim(both '/' from coalesce(path, '')) = 'mastermatch') THEN
            RAISE EXCEPTION '0295 菜单路径冲突：主数据管理/mastermatch 已被其他菜单占用';
        END IF;
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                                status, visible, keep_alive, always_show, creator, updater, deleted)
        VALUES (page_id, '相似匹配与合并', '', 2, 8, master_parent_id, 'mastermatch', 'carbon:data-refinery',
                'governance/mastermatch/index', 'GovernanceMasterMatch', 0, true, true, true,
                'migration:0295', 'migration:0295', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = page_id AND parent_id = master_parent_id AND type = 2) THEN
        RAISE EXCEPTION '0295 菜单组件身份冲突：governance/mastermatch/index 已挂在其他位置';
    END IF;
    granted := granted || page_id;

    FOR item IN SELECT * FROM (VALUES
        ('governance:master-match:query', '匹配结果查询', 1), ('governance:master-match:rule', '匹配规则配置', 2),
        ('governance:master-match:run', '执行匹配', 3), ('governance:master-match:merge', '提交合并', 4),
        ('governance:master-match:ignore', '忽略疑似重复', 5)
    ) t(permission, label, sort) LOOP
        SELECT id INTO button_id FROM system_menu WHERE deleted = 0 AND permission = item.permission ORDER BY id LIMIT 1;
        IF button_id IS NULL THEN
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO button_id FROM system_menu;
            INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                    keep_alive, always_show, creator, updater, deleted)
            VALUES (button_id, item.label, item.permission, 3, item.sort, page_id, '', '#', 0, true, true, true,
                    'migration:0295', 'migration:0295', 0);
        ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = button_id AND type = 3 AND parent_id = page_id) THEN
            RAISE EXCEPTION '0295 权限冲突：% 已被其他菜单使用', item.permission;
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
           'migration:0295', 'migration:0295', 0
      FROM system_role r JOIN system_menu m ON m.id = ANY (granted) AND m.deleted = 0
     WHERE r.code = 'super_admin' AND r.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
