-- Migration: 0187 元数据管理、实例队列及独立定版。
-- Purpose: 拆分发布与定版，冻结业务镜像；采集实例持有租约，支持文件元数据及细粒度权限。
-- Depends on: 0186_dataforge_remote-file-management.sql；0182 已建立元数据修订号及单租户结构。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；列、索引和菜单按有效身份补齐，旧任务租约列仅首次移除。
-- Rollback: 事务失败自动回滚；提交后旧任务租约字段不可恢复，须停服务并从备份恢复；不会删除外部源数据或 infra 文件。
-- 风险：执行前停止元数据采集工作进程；旧运行中实例首次迁移标记失败。DDL 和菜单写入需短暂锁表。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE dh_governance_meta_collect_task, dh_governance_meta_collect_instance, dh_governance_meta_table, system_menu IN ACCESS EXCLUSIVE MODE;

ALTER TABLE dh_governance_meta_table ADD COLUMN IF NOT EXISTS state_revision integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_governance_meta_table.state_revision IS '发布和定版状态修订号，采集与业务修订独立';
ALTER TABLE dh_governance_meta_table ADD COLUMN IF NOT EXISTS finalized_time timestamp;
COMMENT ON COLUMN dh_governance_meta_table.finalized_time IS '确认定版时间';
ALTER TABLE dh_governance_meta_table ADD COLUMN IF NOT EXISTS finalized_by varchar(64);
COMMENT ON COLUMN dh_governance_meta_table.finalized_by IS '确认定版操作人';
ALTER TABLE dh_governance_meta_table ADD COLUMN IF NOT EXISTS finalized_snapshot jsonb;
COMMENT ON COLUMN dh_governance_meta_table.finalized_snapshot IS '所选历史技术结构与确认时业务信息的只读镜像，独立于发布开关';
ALTER TABLE dh_governance_meta_collect_task ADD COLUMN IF NOT EXISTS collect_type smallint NOT NULL DEFAULT 1;
COMMENT ON COLUMN dh_governance_meta_collect_task.collect_type IS '采集类型：1数据库、2文件';
ALTER TABLE dh_governance_meta_collect_task ADD COLUMN IF NOT EXISTS execute_strategy varchar(32) NOT NULL DEFAULT 'SERIAL_DISCARD';
COMMENT ON COLUMN dh_governance_meta_collect_task.execute_strategy IS '执行策略：SERIAL_DISCARD串行丢弃、SERIAL_WAIT串行等待、SERIAL_PRIORITY串行取最新等待、PARALLEL并行执行';
ALTER TABLE dh_governance_meta_collect_task ADD COLUMN IF NOT EXISTS file_path varchar(4096);
COMMENT ON COLUMN dh_governance_meta_collect_task.file_path IS '文件采集根目录内的逻辑路径';
ALTER TABLE dh_governance_meta_collect_task ADD COLUMN IF NOT EXISTS datasource_name varchar(200);
COMMENT ON COLUMN dh_governance_meta_collect_task.datasource_name IS '连接名称展示快照';
ALTER TABLE dh_governance_meta_collect_task ADD COLUMN IF NOT EXISTS owner_name varchar(100);
COMMENT ON COLUMN dh_governance_meta_collect_task.owner_name IS '负责人名称展示快照';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS task_name varchar(200);
COMMENT ON COLUMN dh_governance_meta_collect_instance.task_name IS '执行时任务名称';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS datasource_id bigint;
COMMENT ON COLUMN dh_governance_meta_collect_instance.datasource_id IS '执行时数据连接编号';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS datasource_name varchar(200);
COMMENT ON COLUMN dh_governance_meta_collect_instance.datasource_name IS '执行时连接名称';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS source_system_id bigint;
COMMENT ON COLUMN dh_governance_meta_collect_instance.source_system_id IS '执行时来源系统编号';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS source_system_name varchar(200);
COMMENT ON COLUMN dh_governance_meta_collect_instance.source_system_name IS '执行时来源系统名称';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS collect_type smallint NOT NULL DEFAULT 1;
COMMENT ON COLUMN dh_governance_meta_collect_instance.collect_type IS '执行时采集类型：1数据库、2文件';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS owner_user_id bigint;
COMMENT ON COLUMN dh_governance_meta_collect_instance.owner_user_id IS '执行时负责人编号';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS owner_name varchar(100);
COMMENT ON COLUMN dh_governance_meta_collect_instance.owner_name IS '执行时负责人名称';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS owner_phone varchar(32);
COMMENT ON COLUMN dh_governance_meta_collect_instance.owner_phone IS '执行时负责人电话';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS description varchar(1024);
COMMENT ON COLUMN dh_governance_meta_collect_instance.description IS '执行时任务描述';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS file_count integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_governance_meta_collect_instance.file_count IS '处理文件数量';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS execution_token varchar(64);
COMMENT ON COLUMN dh_governance_meta_collect_instance.execution_token IS '实例执行租约令牌，过期令牌禁止回写';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS lease_until timestamp;
COMMENT ON COLUMN dh_governance_meta_collect_instance.lease_until IS '实例执行租约截止时间';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_governance_meta_collect_instance.attempt_count IS '实例执行尝试次数';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS execution_user_id bigint;
COMMENT ON COLUMN dh_governance_meta_collect_instance.execution_user_id IS '手动执行操作人编号';

ALTER TABLE dh_governance_meta_collect_instance ALTER COLUMN start_time DROP NOT NULL;
COMMENT ON COLUMN dh_governance_meta_collect_instance.status IS '实例状态：0排队、1运行、2成功、3失败、4部分成功、5已丢弃';
COMMENT ON COLUMN dh_governance_meta_table.publish_status IS '发布开关：0未发布、1已发布；与定版独立';
COMMENT ON COLUMN dh_governance_meta_table.published_version IS '当前定版结构版本号，取消发布不清除';
COMMENT ON COLUMN dh_governance_meta_table.published_snapshot_id IS '当前定版结构快照编号，资产历史引用继续有效';
DO $retire_task_lease$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
            AND table_name='dh_governance_meta_collect_task' AND column_name='execution_token') THEN
        UPDATE dh_governance_meta_collect_instance SET status=3, error_type='MIGRATION_INTERRUPTED',
            end_time=CURRENT_TIMESTAMP, remark='元数据执行机制升级，旧实例终止，请重新执行任务',
            updater='migration:0187',update_time=CURRENT_TIMESTAMP WHERE status=1 AND deleted=0;
    END IF;
END $retire_task_lease$;
ALTER TABLE dh_governance_meta_collect_task DROP COLUMN IF EXISTS execution_token;
ALTER TABLE dh_governance_meta_collect_task DROP COLUMN IF EXISTS lease_until;
ALTER TABLE dh_governance_meta_collect_task DROP COLUMN IF EXISTS running_instance_id;
CREATE INDEX IF NOT EXISTS idx_meta_instance_queue ON dh_governance_meta_collect_instance(status,id) WHERE deleted=0 AND status=0;
CREATE INDEX IF NOT EXISTS idx_meta_instance_lease ON dh_governance_meta_collect_instance(lease_until,id) WHERE deleted=0 AND status=1;
CREATE INDEX IF NOT EXISTS idx_meta_instance_task_state ON dh_governance_meta_collect_instance(task_id,status,id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_meta_table_finalized ON dh_governance_meta_table(finalized_time DESC,id) WHERE deleted=0 AND published_snapshot_id IS NOT NULL;
CREATE SEQUENCE IF NOT EXISTS dh_governance_meta_file_seq;
CREATE TABLE IF NOT EXISTS dh_governance_meta_file (
    id bigint PRIMARY KEY DEFAULT nextval('dh_governance_meta_file_seq'),
    datasource_id bigint NOT NULL, datasource_name varchar(200), source_system_id bigint, source_system_name varchar(200),
    task_id bigint, instance_id bigint, path varchar(4096) NOT NULL, parent_path varchar(4096) NOT NULL,
    name varchar(255) NOT NULL, file_type varchar(32), size bigint NOT NULL DEFAULT 0,
    source_modified_time timestamp, first_seen_time timestamp, last_seen_time timestamp,
    object_status smallint NOT NULL DEFAULT 1, business_name varchar(200), description varchar(1024),
    owner_user_id bigint, tech_leader_user_id bigint, tech_leader_phone varchar(32),
    business_leader_user_id bigint, business_leader_phone varchar(32), biz_revision integer NOT NULL DEFAULT 0,
    creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0
);
COMMENT ON TABLE dh_governance_meta_file IS '独立文件元数据登记，只保存源端文件描述，不保存文件内容';

COMMENT ON COLUMN dh_governance_meta_file.id IS '文件元数据编号';
COMMENT ON COLUMN dh_governance_meta_file.datasource_id IS '受管连接编号';
COMMENT ON COLUMN dh_governance_meta_file.datasource_name IS '连接名称快照';
COMMENT ON COLUMN dh_governance_meta_file.source_system_id IS '来源系统编号';
COMMENT ON COLUMN dh_governance_meta_file.source_system_name IS '来源系统名称快照';
COMMENT ON COLUMN dh_governance_meta_file.task_id IS '最近采集任务编号';
COMMENT ON COLUMN dh_governance_meta_file.instance_id IS '最近采集实例编号';
COMMENT ON COLUMN dh_governance_meta_file.path IS '连接根目录内的完整逻辑路径';
COMMENT ON COLUMN dh_governance_meta_file.parent_path IS '逻辑父目录';
COMMENT ON COLUMN dh_governance_meta_file.name IS '文件名称';
COMMENT ON COLUMN dh_governance_meta_file.file_type IS '文件扩展类型';
COMMENT ON COLUMN dh_governance_meta_file.size IS '源文件大小字节数';
COMMENT ON COLUMN dh_governance_meta_file.source_modified_time IS '源文件修改时间';
COMMENT ON COLUMN dh_governance_meta_file.first_seen_time IS '首次发现时间';
COMMENT ON COLUMN dh_governance_meta_file.last_seen_time IS '最近确认时间';
COMMENT ON COLUMN dh_governance_meta_file.object_status IS '对象状态：1正常、2源端已缺失';
COMMENT ON COLUMN dh_governance_meta_file.business_name IS '平台维护业务名称';
COMMENT ON COLUMN dh_governance_meta_file.description IS '平台维护描述';
COMMENT ON COLUMN dh_governance_meta_file.owner_user_id IS '负责人编号';
COMMENT ON COLUMN dh_governance_meta_file.tech_leader_user_id IS '技术负责人编号';
COMMENT ON COLUMN dh_governance_meta_file.tech_leader_phone IS '技术负责人电话';
COMMENT ON COLUMN dh_governance_meta_file.business_leader_user_id IS '业务负责人编号';
COMMENT ON COLUMN dh_governance_meta_file.business_leader_phone IS '业务负责人电话';
COMMENT ON COLUMN dh_governance_meta_file.biz_revision IS '业务属性并发修订号';
COMMENT ON COLUMN dh_governance_meta_file.creator IS '创建者';
COMMENT ON COLUMN dh_governance_meta_file.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_meta_file.updater IS '更新者';
COMMENT ON COLUMN dh_governance_meta_file.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_meta_file.deleted IS '逻辑删除：0有效、1删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_file_source_path ON dh_governance_meta_file(datasource_id,md5(path)) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_meta_file_parent_path ON dh_governance_meta_file(datasource_id,md5(parent_path)) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_meta_file_source_system ON dh_governance_meta_file(source_system_id,id) WHERE deleted=0;
DO $metadata_constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_table'::regclass AND conname='ck_meta_table_state_revision') THEN
        ALTER TABLE dh_governance_meta_table ADD CONSTRAINT ck_meta_table_state_revision CHECK (state_revision >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_collect_task'::regclass AND conname='ck_meta_task_collect_type') THEN
        ALTER TABLE dh_governance_meta_collect_task ADD CONSTRAINT ck_meta_task_collect_type CHECK (collect_type IN (1,2));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_collect_task'::regclass AND conname='ck_meta_task_execute_strategy') THEN
        ALTER TABLE dh_governance_meta_collect_task ADD CONSTRAINT ck_meta_task_execute_strategy CHECK (execute_strategy IN ('SERIAL_DISCARD','SERIAL_WAIT','SERIAL_PRIORITY','PARALLEL'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_collect_instance'::regclass AND conname='ck_meta_instance_status') THEN
        ALTER TABLE dh_governance_meta_collect_instance ADD CONSTRAINT ck_meta_instance_status CHECK (status BETWEEN 0 AND 5);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_collect_instance'::regclass AND conname='ck_meta_instance_collect_type') THEN
        ALTER TABLE dh_governance_meta_collect_instance ADD CONSTRAINT ck_meta_instance_collect_type CHECK (collect_type IN (1,2));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_collect_instance'::regclass AND conname='ck_meta_instance_attempts') THEN
        ALTER TABLE dh_governance_meta_collect_instance ADD CONSTRAINT ck_meta_instance_attempts CHECK (attempt_count >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_file'::regclass AND conname='ck_meta_file_status') THEN
        ALTER TABLE dh_governance_meta_file ADD CONSTRAINT ck_meta_file_status CHECK (object_status IN (1,2));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_file'::regclass AND conname='ck_meta_file_revision') THEN
        ALTER TABLE dh_governance_meta_file ADD CONSTRAINT ck_meta_file_revision CHECK (biz_revision >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_meta_file'::regclass AND conname='ck_meta_file_size') THEN
        ALTER TABLE dh_governance_meta_file ADD CONSTRAINT ck_meta_file_size CHECK (size >= 0);
    END IF;
END $metadata_constraints$;
DO $metadata_menus$
DECLARE root_id bigint; page_id bigint; target_id bigint; row_count integer; item record;
BEGIN
    SELECT count(*),min(id) INTO row_count,root_id FROM system_menu WHERE path='metadata' AND type=1 AND deleted=0;
    IF row_count<>1 THEN RAISE EXCEPTION '0187 元数据管理目录缺失或重复'; END IF;
    UPDATE system_menu SET name='最新元数据',updater='migration:0187',update_time=CURRENT_TIMESTAMP
        WHERE component='governance/metaobject/index' AND deleted=0;
    UPDATE system_menu SET name='元数据发布与定版',updater='migration:0187',update_time=CURRENT_TIMESTAMP
        WHERE permission='governance:meta-table:publish' AND deleted=0;
    -- 文件元数据在最新元数据页的文件页签内展示，仅补充独立操作权限。
    FOR item IN SELECT * FROM (VALUES
        ('governance/metaobject/index','文件元数据查询','governance:meta-file:query',5),
        ('governance/metaobject/index','文件元数据维护','governance:meta-file:update',6),
        ('governance/metaobject/index','文件元数据删除','governance:meta-file:delete',7),
        ('governance/metacollectinstance/index','采集实例删除','governance:meta-collect-instance:delete',1)
    ) seed(component,name,permission,sort) LOOP
        SELECT count(*),min(id) INTO row_count,page_id FROM system_menu WHERE component=item.component AND deleted=0;
        IF row_count<>1 THEN RAISE EXCEPTION '0187 元数据页面菜单缺失或重复：%',item.component; END IF;
        SELECT count(*) INTO row_count FROM system_menu WHERE permission=item.permission AND deleted=0;
        IF row_count=0 THEN
            SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO target_id FROM system_menu;
            INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
            VALUES(target_id,item.name,item.permission,3,item.sort,page_id,'',0,true,true,true,'migration:0187','migration:0187',0);
            PERFORM setval('system_menu_seq',target_id);
            PERFORM setval('system_menu_id_seq',target_id);
        ELSIF row_count<>1 OR NOT EXISTS(SELECT 1 FROM system_menu WHERE permission=item.permission AND parent_id=page_id AND type=3 AND deleted=0) THEN
            RAISE EXCEPTION '0187 元数据权限菜单归属冲突：%',item.permission;
        END IF;
    END LOOP;
END $metadata_menus$;
-- 新操作权限由管理员按角色授权，不从查询权限推导维护或删除权限。
COMMIT;
