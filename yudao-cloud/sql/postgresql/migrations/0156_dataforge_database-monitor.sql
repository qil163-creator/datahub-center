-- Migration: 0156 数据库运行监控、采样与告警
-- Purpose: 增加逐租户分钟采样、数据库告警规则和可审计处置记录；复用现有数据库查询与更新权限。
-- Depends on: 0155_dataforge_remove-data-explore.sql 已执行，或平台达到对应状态；数据库、同步执行和开发执行表已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；表、序列和索引均使用存在性保护，不重置已有采样、规则和告警。
-- Rollback: 失败整笔回滚；提交后先停用 databaseMonitorCollectJob 并回退应用，再备份并删除本迁移新增的三张表及其序列。
-- 加法式变更，不删除现有数据；采样保留三十五天，告警处置记录不自动删除。数据库告警不发送外部通知。
-- 仅创建平台存储，不连接外部数据库，也不修改外部 XXL-Job 管理库；调度建议每分钟执行。
-- 历史执行表新增查询索引会在建索引期间阻止写入；建议低峰执行，十秒锁等待和两分钟语句超时失败后整笔回滚。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_database_sample_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_database_sample (
    id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_database_sample_seq'),
    database_id bigint NOT NULL,
    slot_time timestamp NOT NULL,
    collected_time timestamp,
    state varchar(16) NOT NULL DEFAULT 'COLLECTING' CHECK (state IN ('COLLECTING','COMPLETE','DISCARDED')),
    lease_token varchar(36) NOT NULL,
    lease_until timestamp NOT NULL,
    connection_status integer CHECK (connection_status IN (0,1,2)),
    collection_status varchar(16) CHECK (collection_status IN ('SUCCESS','FAILED','UNSUPPORTED')),
    collection_message varchar(200),
    response_time_ms bigint CHECK (response_time_ms >= 0),
    capacity_used varchar(32) CHECK (capacity_used ~ '^[0-9]+$'),
    creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0, tenant_id bigint NOT NULL,
    CONSTRAINT uk_database_sample_slot UNIQUE (tenant_id, database_id, slot_time)
);
CREATE INDEX IF NOT EXISTS idx_database_sample_trend ON dh_dataforge_database_sample (tenant_id,database_id,collected_time DESC) WHERE state='COMPLETE' AND deleted=0;
COMMENT ON TABLE dh_dataforge_database_sample IS '数据库分钟采样及崩溃恢复租约；无外库凭据';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_database_alarm_rule_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_database_alarm_rule (
    id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_database_alarm_rule_seq'),
    database_id bigint NOT NULL,
    name varchar(100) NOT NULL,
    description varchar(500) NOT NULL DEFAULT '',
    metric varchar(32) NOT NULL CHECK (metric IN ('CONNECTION_STATUS','RESPONSE_TIME')),
    operator varchar(8) NOT NULL CHECK (operator IN ('EQ','GT','GTE','LT','LTE')),
    threshold varchar(32) NOT NULL CHECK (threshold ~ '^[0-9]{1,12}([.][0-9]{1,6})?$'),
    level varchar(16) NOT NULL CHECK (level IN ('GENERAL','SERIOUS','URGENT')),
    status integer NOT NULL DEFAULT 0 CHECK (status IN (0,1)),
    owner varchar(100) NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0, tenant_id bigint NOT NULL,
    CONSTRAINT ck_database_alarm_threshold CHECK (
        (metric='CONNECTION_STATUS' AND operator='EQ' AND threshold::numeric=0)
        OR (metric='RESPONSE_TIME' AND threshold::numeric>0))
);
CREATE INDEX IF NOT EXISTS idx_database_alarm_rule_scope ON dh_dataforge_database_alarm_rule (tenant_id,database_id,status) WHERE deleted=0;
COMMENT ON TABLE dh_dataforge_database_alarm_rule IS '数据库连接和响应时间告警规则；负责人仅为文字说明';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_database_alarm_record_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_database_alarm_record (
    id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_database_alarm_record_seq'),
    database_id bigint NOT NULL,
    rule_id bigint NOT NULL,
    rule_name varchar(100) NOT NULL,
    description varchar(500) NOT NULL DEFAULT '',
    metric varchar(32) NOT NULL,
    operator varchar(8) NOT NULL,
    threshold varchar(32) NOT NULL,
    current_value varchar(32) NOT NULL,
    level varchar(16) NOT NULL CHECK (level IN ('GENERAL','SERIOUS','URGENT')),
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','HANDLED','IGNORED')),
    first_time timestamp NOT NULL,
    last_time timestamp NOT NULL,
    occurrences integer NOT NULL DEFAULT 1 CHECK (occurrences>0),
    handled_by varchar(64), handled_time timestamp, handle_remark varchar(500),
    creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0, tenant_id bigint NOT NULL,
    CONSTRAINT ck_database_alarm_disposition CHECK (status='PENDING' OR (handled_by IS NOT NULL AND handled_time IS NOT NULL AND length(trim(handle_remark))>0))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_database_alarm_pending ON dh_dataforge_database_alarm_record (tenant_id,database_id,rule_id) WHERE status='PENDING' AND deleted=0;
CREATE INDEX IF NOT EXISTS idx_database_alarm_record_scope ON dh_dataforge_database_alarm_record (tenant_id,database_id,last_time DESC) WHERE deleted=0;
COMMENT ON TABLE dh_dataforge_database_alarm_record IS '数据库告警规则快照、重复触发次数及人工处理审计';

CREATE INDEX IF NOT EXISTS idx_database_monitor_access_source ON dh_dataforge_access_task (tenant_id,(config_json->>'sourceDatabaseId'),create_time DESC) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_database_monitor_access_target ON dh_dataforge_access_task (tenant_id,(config_json->>'targetDatabaseId'),create_time DESC) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_database_monitor_dev_scope ON dh_dataforge_dev_record (tenant_id,database_id,create_time DESC) WHERE deleted=0;

-- 三张表的公共字段和业务字段逐项中文注释；重放仅修正说明，不更新业务数据。
DO $comments$
DECLARE
    v_table_name text;
    v_column_name text;
    label text;
BEGIN
    FOREACH v_table_name IN ARRAY ARRAY['dh_dataforge_database_sample','dh_dataforge_database_alarm_rule','dh_dataforge_database_alarm_record'] LOOP
        FOR v_column_name,label IN SELECT * FROM (VALUES
          ('id','主键编号'),('database_id','本租户数据库编号'),('tenant_id','租户编号'),
          ('creator','创建人编号'),('create_time','创建时间'),('updater','更新人编号'),('update_time','更新时间'),('deleted','逻辑删除标记'),
          ('slot_time','分钟采样槽，租户与数据库内唯一'),('collected_time','采样时间'),('state','采样状态：采集中、完成或已废弃'),
          ('lease_token','采集租约令牌，旧执行结果不能覆盖新令牌'),('lease_until','采集租约截止时间'),
          ('connection_status','连接状态：零未知、一在线、二离线'),('collection_status','采集结果：成功、失败或不支持'),('collection_message','固定脱敏采集结果说明'),
          ('response_time_ms','连接建立并完成探活查询的耗时毫秒'),('capacity_used','数据库已用字节十进制字符串，未知为空'),
          ('name','规则名称'),('description','业务说明'),('metric','告警指标：连接状态或响应时间'),('operator','阈值比较运算符'),
          ('threshold','精确阈值十进制字符串，连接断开为零，响应时间单位毫秒'),('level','告警级别：一般、严重、紧急'),
          ('status','规则零启用一停用；告警待处理、已处理或已忽略'),('owner','负责人文字说明，不隐含通知授权'),
          ('rule_id','规则编号，删除规则后保留告警历史'),('rule_name','最近触发时规则名称快照'),('current_value','最近触发采样值十进制字符串'),
          ('first_time','首次触发时间'),('last_time','最近触发时间'),('occurrences','未处置期间触发次数'),
          ('handled_by','处置人编号'),('handled_time','处置时间'),('handle_remark','处置原因')
        ) AS descriptions(column_key,column_label) LOOP
            IF EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema='datahub-center' AND c.table_name=v_table_name AND c.column_name=v_column_name) THEN
                EXECUTE format('COMMENT ON COLUMN %I.%I IS %L',v_table_name,v_column_name,label);
            END IF;
        END LOOP;
    END LOOP;
END $comments$;

COMMIT;
