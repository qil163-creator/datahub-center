-- Migration: 0277 数据探查：探查记录、字段指标、Top 值与按钮权限
-- Purpose: 新增字段级数据探查的持久化结构：dh_governance_profile_run 记录每次探查（状态 PENDING/RUNNING/SUCCESS/FAILED、
--          抽样与总行数、执行人、起止时间、失败原因及执行租约），部分唯一索引保证每张元数据表同时只有一条
--          PENDING/RUNNING 记录；dh_governance_profile_column 保存各列非空/空值率/唯一率/最值/长度/平均值；
--          dh_governance_profile_value 保存各列 Top 值及计数，落库前经动态脱敏（maskForStorage）处理，敏感列只保存不可逆掩码或计数，绝不保存原文。
--          在“数据治理 > 元数据管理 > 最新元数据”页面（组件 governance/metaobject/index）下新增按钮权限
--          governance:profile:run（发起探查）、governance:profile:query（探查结果、记录分页），并授权给超级管理员。
-- Depends on: 平台库已升级至 0276；存在组件为 governance/metaobject/index 的“最新元数据”页面菜单。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；序列、表、约束、索引带存在性保护，按钮按权限去重、授权按角色与菜单判重，单事务执行。
-- Rollback: 加法迁移，无不可逆操作。按 dh_governance_profile_value、dh_governance_profile_column、dh_governance_profile_run
--           的顺序删除表及同名 _seq 序列，删除权限为 governance:profile:run / governance:profile:query 的按钮及其
--           system_role_menu 授权即可；探查结果为派生数据，回滚后可重新探查。原始 SQL 不触发缓存失效，提交后需清理
--           权限菜单相关 Redis 缓存或重启 system 服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_governance_profile_run_seq;
CREATE SEQUENCE IF NOT EXISTS dh_governance_profile_column_seq;
CREATE SEQUENCE IF NOT EXISTS dh_governance_profile_value_seq;

CREATE TABLE IF NOT EXISTS dh_governance_profile_run (
    id int8 NOT NULL DEFAULT nextval('dh_governance_profile_run_seq'::regclass),
    meta_table_id int8 NOT NULL,
    datasource_id int8,
    schema_name varchar(128),
    table_name varchar(255) NOT NULL DEFAULT '',
    status varchar(16) NOT NULL,
    sample_limit int4 NOT NULL,
    sample_rows int8,
    total_rows int8,
    column_count int4 NOT NULL DEFAULT 0,
    executor_user_id int8 NOT NULL,
    start_time timestamp(6),
    end_time timestamp(6),
    fail_reason varchar(500),
    lease_owner varchar(64),
    lease_until timestamp(6),
    attempt_count int4 NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_profile_run PRIMARY KEY (id),
    CONSTRAINT ck_profile_run_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_profile_run_counts CHECK (sample_limit > 0 AND column_count >= 0 AND attempt_count >= 0
        AND (sample_rows IS NULL OR sample_rows >= 0) AND (total_rows IS NULL OR total_rows >= 0)),
    CONSTRAINT ck_profile_run_lease CHECK (status <> 'RUNNING' OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
);

COMMENT ON TABLE dh_governance_profile_run IS '数据探查-探查记录（每次对一张元数据表发起的字段级统计探查）';
COMMENT ON COLUMN dh_governance_profile_run.id IS '探查记录编号';
COMMENT ON COLUMN dh_governance_profile_run.meta_table_id IS '元数据表编号（dh_governance_meta_table.id）';
COMMENT ON COLUMN dh_governance_profile_run.datasource_id IS '发起时的数据源编号快照';
COMMENT ON COLUMN dh_governance_profile_run.schema_name IS '发起时的物理 schema 快照';
COMMENT ON COLUMN dh_governance_profile_run.table_name IS '发起时的物理表名快照';
COMMENT ON COLUMN dh_governance_profile_run.status IS '状态：PENDING等待执行、RUNNING执行中、SUCCESS成功、FAILED失败';
COMMENT ON COLUMN dh_governance_profile_run.sample_limit IS '抽样行数上限';
COMMENT ON COLUMN dh_governance_profile_run.sample_rows IS '实际抽样行数';
COMMENT ON COLUMN dh_governance_profile_run.total_rows IS '全表总行数；统计超时或失败时为空';
COMMENT ON COLUMN dh_governance_profile_run.column_count IS '实际探查的字段数';
COMMENT ON COLUMN dh_governance_profile_run.executor_user_id IS '执行人（发起探查的用户编号）';
COMMENT ON COLUMN dh_governance_profile_run.start_time IS '首次开始执行时间';
COMMENT ON COLUMN dh_governance_profile_run.end_time IS '结束时间';
COMMENT ON COLUMN dh_governance_profile_run.fail_reason IS '失败原因（不含 SQL 文本或连接信息）';
COMMENT ON COLUMN dh_governance_profile_run.lease_owner IS '当前执行租约持有者令牌';
COMMENT ON COLUMN dh_governance_profile_run.lease_until IS '执行租约到期时间；过期后可被重新领取';
COMMENT ON COLUMN dh_governance_profile_run.attempt_count IS '已领取执行次数';
COMMENT ON COLUMN dh_governance_profile_run.creator IS '创建者';
COMMENT ON COLUMN dh_governance_profile_run.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_profile_run.updater IS '更新者';
COMMENT ON COLUMN dh_governance_profile_run.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_profile_run.deleted IS '逻辑删除：0未删除、1已删除';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_governance_profile_run_active
    ON dh_governance_profile_run (meta_table_id) WHERE status IN ('PENDING', 'RUNNING') AND deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dh_governance_profile_run_table
    ON dh_governance_profile_run (meta_table_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_dh_governance_profile_run_due
    ON dh_governance_profile_run (status, lease_until, id) WHERE status IN ('PENDING', 'RUNNING') AND deleted = 0;

CREATE TABLE IF NOT EXISTS dh_governance_profile_column (
    id int8 NOT NULL DEFAULT nextval('dh_governance_profile_column_seq'::regclass),
    run_id int8 NOT NULL,
    meta_column_id int8,
    column_name varchar(255) NOT NULL,
    data_type varchar(128),
    column_kind varchar(16) NOT NULL,
    ordinal_position int4,
    non_null_count int8 NOT NULL,
    null_count int8 NOT NULL,
    null_rate numeric(7, 6) NOT NULL,
    distinct_count int8,
    unique_rate numeric(7, 6),
    min_value varchar(256),
    max_value varchar(256),
    value_masked int2 NOT NULL DEFAULT 0,
    mask_reason varchar(32),
    avg_length numeric(18, 4),
    min_length int8,
    max_length int8,
    avg_value numeric,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_profile_column PRIMARY KEY (id),
    CONSTRAINT uk_dh_governance_profile_column UNIQUE (run_id, column_name),
    CONSTRAINT fk_profile_column_run FOREIGN KEY (run_id) REFERENCES dh_governance_profile_run (id),
    CONSTRAINT ck_profile_column_kind CHECK (column_kind IN ('TEXT', 'NUMERIC', 'TEMPORAL', 'BOOLEAN', 'OTHER')),
    CONSTRAINT ck_profile_column_rates CHECK (null_rate BETWEEN 0 AND 1 AND (unique_rate IS NULL OR unique_rate BETWEEN 0 AND 1)),
    CONSTRAINT ck_profile_column_counts CHECK (non_null_count >= 0 AND null_count >= 0
        AND (distinct_count IS NULL OR distinct_count >= 0)),
    CONSTRAINT ck_profile_column_masked CHECK (value_masked IN (0, 1))
);

COMMENT ON TABLE dh_governance_profile_column IS '数据探查-字段指标（每次探查每个字段一行，统计基于抽样）';
COMMENT ON COLUMN dh_governance_profile_column.id IS '字段指标编号';
COMMENT ON COLUMN dh_governance_profile_column.run_id IS '探查记录编号';
COMMENT ON COLUMN dh_governance_profile_column.meta_column_id IS '元数据字段编号（dh_governance_meta_column.id）';
COMMENT ON COLUMN dh_governance_profile_column.column_name IS '字段名';
COMMENT ON COLUMN dh_governance_profile_column.data_type IS '探查时的字段类型';
COMMENT ON COLUMN dh_governance_profile_column.column_kind IS '统计类别：TEXT文本、NUMERIC数值、TEMPORAL日期时间、BOOLEAN布尔、OTHER其他（只统计非空）';
COMMENT ON COLUMN dh_governance_profile_column.ordinal_position IS '字段序号';
COMMENT ON COLUMN dh_governance_profile_column.non_null_count IS '非空数';
COMMENT ON COLUMN dh_governance_profile_column.null_count IS '空值数';
COMMENT ON COLUMN dh_governance_profile_column.null_rate IS '空值率（0~1）';
COMMENT ON COLUMN dh_governance_profile_column.distinct_count IS '唯一值数；OTHER 类别不统计';
COMMENT ON COLUMN dh_governance_profile_column.unique_rate IS '唯一率（唯一值数/非空数，0~1）';
COMMENT ON COLUMN dh_governance_profile_column.min_value IS '最小值文本（截断至 256 字符）；已脱敏时为空或为脱敏后文本，不保存原文';
COMMENT ON COLUMN dh_governance_profile_column.max_value IS '最大值文本（截断至 256 字符）；已脱敏时为空或为脱敏后文本，不保存原文';
COMMENT ON COLUMN dh_governance_profile_column.value_masked IS '取值已脱敏：0否、1是（最值与 Top 值只保存不可逆掩码或为空，不保存原文）';
COMMENT ON COLUMN dh_governance_profile_column.mask_reason IS '脱敏原因：RULE命中分类规则、UNCLASSIFIED敏感未分类、UNTRACEABLE来源不可追溯、UNVERIFIED未验证、METADATA元数据敏感标注、MASK_FAILED脱敏服务失败（失败关闭，不保存取值）';
COMMENT ON COLUMN dh_governance_profile_column.avg_length IS '文本平均长度（字符数）';
COMMENT ON COLUMN dh_governance_profile_column.min_length IS '文本最小长度（字符数）';
COMMENT ON COLUMN dh_governance_profile_column.max_length IS '文本最大长度（字符数）';
COMMENT ON COLUMN dh_governance_profile_column.avg_value IS '数值平均值';
COMMENT ON COLUMN dh_governance_profile_column.creator IS '创建者';
COMMENT ON COLUMN dh_governance_profile_column.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_profile_column.updater IS '更新者';
COMMENT ON COLUMN dh_governance_profile_column.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_profile_column.deleted IS '逻辑删除：0未删除、1已删除';

CREATE TABLE IF NOT EXISTS dh_governance_profile_value (
    id int8 NOT NULL DEFAULT nextval('dh_governance_profile_value_seq'::regclass),
    run_id int8 NOT NULL,
    profile_column_id int8 NOT NULL,
    rank_no int4 NOT NULL,
    value_text varchar(256),
    value_count int8 NOT NULL,
    value_masked int2 NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_profile_value PRIMARY KEY (id),
    CONSTRAINT uk_dh_governance_profile_value UNIQUE (profile_column_id, rank_no),
    CONSTRAINT fk_profile_value_run FOREIGN KEY (run_id) REFERENCES dh_governance_profile_run (id),
    CONSTRAINT fk_profile_value_column FOREIGN KEY (profile_column_id) REFERENCES dh_governance_profile_column (id),
    CONSTRAINT ck_profile_value_rank CHECK (rank_no >= 1 AND value_count >= 0),
    CONSTRAINT ck_profile_value_masked CHECK (value_masked IN (0, 1) AND (value_masked = 1 OR value_text IS NOT NULL))
);

COMMENT ON TABLE dh_governance_profile_value IS '数据探查-Top 值分布（每个字段按出现次数倒序的前 N 个取值）';
COMMENT ON COLUMN dh_governance_profile_value.id IS 'Top 值编号';
COMMENT ON COLUMN dh_governance_profile_value.run_id IS '探查记录编号';
COMMENT ON COLUMN dh_governance_profile_value.profile_column_id IS '字段指标编号';
COMMENT ON COLUMN dh_governance_profile_value.rank_no IS '排名（从 1 开始）';
COMMENT ON COLUMN dh_governance_profile_value.value_text IS '取值文本（截断至 256 字符）；已脱敏时为空或为脱敏后文本，不保存原文';
COMMENT ON COLUMN dh_governance_profile_value.value_count IS '抽样内出现次数';
COMMENT ON COLUMN dh_governance_profile_value.value_masked IS '已脱敏：0否、1是（文本为不可逆掩码或为空；脱敏失败时只保存计数）';
COMMENT ON COLUMN dh_governance_profile_value.creator IS '创建者';
COMMENT ON COLUMN dh_governance_profile_value.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_profile_value.updater IS '更新者';
COMMENT ON COLUMN dh_governance_profile_value.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_profile_value.deleted IS '逻辑删除：0未删除、1已删除';

CREATE INDEX IF NOT EXISTS idx_dh_governance_profile_column_run ON dh_governance_profile_column (run_id);
CREATE INDEX IF NOT EXISTS idx_dh_governance_profile_value_run ON dh_governance_profile_value (run_id);

-- ========== 按钮权限：挂在“最新元数据”页面下，仅授权超级管理员 ==========
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE
    page_id bigint;
    run_id bigint;
    query_id bigint;
    high_id bigint;
BEGIN
    SELECT id INTO page_id FROM system_menu
    WHERE deleted = 0 AND type = 2 AND trim(both '/' from coalesce(component, '')) = 'governance/metaobject/index'
    ORDER BY id LIMIT 1;
    IF page_id IS NULL THEN RAISE EXCEPTION '0277 缺少最新元数据页面菜单 governance/metaobject/index'; END IF;

    SELECT id INTO query_id FROM system_menu WHERE deleted = 0 AND permission = 'governance:profile:query'
    ORDER BY id LIMIT 1;
    IF query_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO query_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                keep_alive, always_show, creator, updater, deleted)
        VALUES (query_id, '数据探查查询', 'governance:profile:query', 3, 20, page_id, '', '#', 0, true, true, true,
                'migration:0277', 'migration:0277', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = query_id AND type = 3 AND parent_id = page_id) THEN
        RAISE EXCEPTION '0277 权限冲突：governance:profile:query 已被其他菜单使用';
    END IF;

    SELECT id INTO run_id FROM system_menu WHERE deleted = 0 AND permission = 'governance:profile:run'
    ORDER BY id LIMIT 1;
    IF run_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                        (SELECT last_value FROM system_menu_id_seq)) + 1 INTO run_id FROM system_menu;
        INSERT INTO system_menu(id, name, permission, type, sort, parent_id, path, icon, status, visible,
                                keep_alive, always_show, creator, updater, deleted)
        VALUES (run_id, '数据探查发起', 'governance:profile:run', 3, 21, page_id, '', '#', 0, true, true, true,
                'migration:0277', 'migration:0277', 0);
    ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = run_id AND type = 3 AND parent_id = page_id) THEN
        RAISE EXCEPTION '0277 权限冲突：governance:profile:run 已被其他菜单使用';
    END IF;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                    (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);

    -- 与 0274 一致，仅授权超级管理员；沿用角色自身的脚手架租户归属。
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY r.id, r.tenant_id, m.id), r.id, m.id, r.tenant_id,
           'migration:0277', 'migration:0277', 0
      FROM system_role r JOIN system_menu m ON m.id IN (query_id, run_id) AND m.deleted = 0
     WHERE r.code = 'super_admin' AND r.deleted = 0
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old WHERE old.role_id = r.id AND old.tenant_id = r.tenant_id
                                                              AND old.menu_id = m.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;
COMMIT;
