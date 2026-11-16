-- Migration: 0292 数据研发任务运行失败通知
-- Purpose: 1) 新建 dh_dataforge_run_alert：任务运行失败通知的持久投递记录。dataforge 的 dataforgeRunAlertJob 每分钟扫描
--             五类平台运行记录中已失败的实例——数据开发（dh_dataforge_dev_record.status='FAILED'）、数据同步
--             （dh_dataforge_access_task.run_status=4）、可视化 ETL / 数据集成平台运行（dh_dataforge_etl_record.run_status=4）、
--             受管数据集成实例（dh_dataforge_integration_record.status='FAILURE' 且 ds_project_code 非空）、作业编排
--             （dh_dataforge_job_record.status='FAILURE' 且 origin='PLATFORM'），按“运行实例 + 事件 + 渠道 + 接收人”唯一登记，
--             再由同一任务以租约认领、有界重试的方式发送。源系统迁入的历史快照（origin=SOURCE）不通知。
--             接收人为任务负责人、发布人、本次执行身份和任务创建人中启用的用户（去重）；找不到接收人时登记为死信，
--             不会静默丢弃。通知只含任务名称、实例名称和失败时间，不含错误详情与日志，详情到运维中心查看。
--          2) 在上述五张运行表上新增只覆盖失败行的部分索引，供扫描按失败时间读取。
--          3) 新增站内信模板 dataforge_run_failed（启用）；邮件模板 dataforge_run_failed_mail 与短信模板
--             dataforge_run_failed_sms 以“停用”状态登记（与 0289 一致），需管理员确认邮箱账号、短信渠道与服务商模板编号后启用。
--             邮件、短信渠道还需在 dataforge 配置 dataforge.run-alert.extra-channels 中显式开启，默认只发站内信。
--          不新增菜单与权限。
-- Depends on: 0291_modeling_model-entity-versions.sql（编号前驱）；dh_dataforge_dev_record、dh_dataforge_access_task、
--             dh_dataforge_etl_record、dh_dataforge_integration_record（含 ds_project_code）、dh_dataforge_job_record（含 origin）、
--             system_notify_template、system_mail_template、system_mail_account、system_sms_template、system_sms_channel
--             及其主键序列已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；表、序列、索引带存在性保护，约束先删后建，注释可重复执行；站内信模板按 code 判重，已存在且内容一致时跳过，
--             内容不一致或已停用时报错中止，不静默覆盖；邮件、短信模板已存在（含管理员已修改过的）时不覆盖，没有可用邮箱账号
--             或短信渠道时跳过并输出 NOTICE，配置好后重新执行即可补齐。
--             单事务执行。五个部分索引在建立期间会阻塞对应运行表的写入（锁等待上限 10 秒，超时即失败，可稍后重试）；
--             索引只包含失败行，通常很小，但运行表很大时建议在低峰执行。
-- Rollback: 应用回退后可执行
--           DROP TABLE IF EXISTS "datahub-center".dh_dataforge_run_alert; DROP SEQUENCE IF EXISTS "datahub-center".dh_dataforge_run_alert_seq;
--           DROP INDEX IF EXISTS 下述五个 idx_*_run_alert 索引；
--           DELETE FROM "datahub-center".system_notify_template WHERE code = 'dataforge_run_failed';
--           DELETE FROM "datahub-center".system_mail_template WHERE code = 'dataforge_run_failed_mail';
--           DELETE FROM "datahub-center".system_sms_template WHERE code = 'dataforge_run_failed_sms';
--           会丢失通知投递记录（已发出的站内信、邮件、短信日志保留在 system 模块）。旧版本应用不读取这些对象。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '300s';

-- 1. 任务运行失败通知
CREATE SEQUENCE IF NOT EXISTS dh_dataforge_run_alert_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_run_alert (
    id int8 NOT NULL DEFAULT nextval('dh_dataforge_run_alert_seq'::regclass),
    source_kind varchar(32) NOT NULL,
    run_id int8 NOT NULL,
    task_id int8,
    task_name varchar(200) NOT NULL DEFAULT '',
    run_name varchar(512) NOT NULL DEFAULT '',
    event_type varchar(16) NOT NULL DEFAULT 'FAILURE',
    occurred_time timestamp(6),
    receiver_user_id int8 NOT NULL DEFAULT 0,
    channel int2 NOT NULL DEFAULT 1,
    template_params jsonb NOT NULL DEFAULT '{}'::jsonb,
    status int2 NOT NULL DEFAULT 0,
    attempts int4 NOT NULL DEFAULT 0,
    available_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token varchar(64),
    lease_until timestamp(6),
    receipt_id int8,
    last_error varchar(64),
    handled_at timestamp(6),
    disposition_action varchar(16),
    disposition_reason varchar(500),
    disposition_command varchar(128),
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_dataforge_run_alert PRIMARY KEY (id)
);
ALTER SEQUENCE dh_dataforge_run_alert_seq OWNED BY dh_dataforge_run_alert.id;

ALTER TABLE dh_dataforge_run_alert DROP CONSTRAINT IF EXISTS ck_dh_dataforge_run_alert_kind;
ALTER TABLE dh_dataforge_run_alert ADD CONSTRAINT ck_dh_dataforge_run_alert_kind
    CHECK (source_kind IN ('DEVELOPMENT', 'DATA_ACCESS', 'ETL', 'INTEGRATION', 'JOB'));
ALTER TABLE dh_dataforge_run_alert DROP CONSTRAINT IF EXISTS ck_dh_dataforge_run_alert_event;
ALTER TABLE dh_dataforge_run_alert ADD CONSTRAINT ck_dh_dataforge_run_alert_event CHECK (event_type IN ('FAILURE'));
ALTER TABLE dh_dataforge_run_alert DROP CONSTRAINT IF EXISTS ck_dh_dataforge_run_alert_channel;
ALTER TABLE dh_dataforge_run_alert ADD CONSTRAINT ck_dh_dataforge_run_alert_channel CHECK (channel IN (1, 2, 3));
ALTER TABLE dh_dataforge_run_alert DROP CONSTRAINT IF EXISTS ck_dh_dataforge_run_alert_status;
ALTER TABLE dh_dataforge_run_alert ADD CONSTRAINT ck_dh_dataforge_run_alert_status CHECK (status BETWEEN 0 AND 5);
ALTER TABLE dh_dataforge_run_alert DROP CONSTRAINT IF EXISTS ck_dh_dataforge_run_alert_attempts;
ALTER TABLE dh_dataforge_run_alert ADD CONSTRAINT ck_dh_dataforge_run_alert_attempts CHECK (attempts >= 0);
ALTER TABLE dh_dataforge_run_alert DROP CONSTRAINT IF EXISTS ck_dh_dataforge_run_alert_receiver;
ALTER TABLE dh_dataforge_run_alert ADD CONSTRAINT ck_dh_dataforge_run_alert_receiver
    CHECK (receiver_user_id >= 0 AND (receiver_user_id > 0 OR status IN (3, 5)));
ALTER TABLE dh_dataforge_run_alert DROP CONSTRAINT IF EXISTS ck_dh_dataforge_run_alert_lease;
ALTER TABLE dh_dataforge_run_alert ADD CONSTRAINT ck_dh_dataforge_run_alert_lease
    CHECK (status <> 1 OR (lease_token IS NOT NULL AND lease_until IS NOT NULL));
ALTER TABLE dh_dataforge_run_alert DROP CONSTRAINT IF EXISTS ck_dh_dataforge_run_alert_params;
ALTER TABLE dh_dataforge_run_alert ADD CONSTRAINT ck_dh_dataforge_run_alert_params
    CHECK (jsonb_typeof(template_params) = 'object');

CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_dataforge_run_alert_target
    ON dh_dataforge_run_alert (source_kind, run_id, event_type, channel, receiver_user_id);
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_run_alert_due
    ON dh_dataforge_run_alert (available_at, id) WHERE deleted = 0 AND status IN (0, 1);
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_run_alert_handled
    ON dh_dataforge_run_alert (handled_at, id) WHERE deleted = 0 AND status IN (2, 5);

COMMENT ON TABLE dh_dataforge_run_alert IS '数据研发-任务运行失败通知投递记录（每个运行实例、事件、渠道、接收人一条）';
COMMENT ON COLUMN dh_dataforge_run_alert.id IS '编号';
COMMENT ON COLUMN dh_dataforge_run_alert.source_kind IS '运行来源：DEVELOPMENT-数据开发 DATA_ACCESS-数据同步 ETL-可视化ETL/数据集成平台运行 INTEGRATION-受管数据集成实例 JOB-作业编排';
COMMENT ON COLUMN dh_dataforge_run_alert.run_id IS '来源运行记录编号';
COMMENT ON COLUMN dh_dataforge_run_alert.task_id IS '来源任务编号';
COMMENT ON COLUMN dh_dataforge_run_alert.task_name IS '任务名称快照';
COMMENT ON COLUMN dh_dataforge_run_alert.run_name IS '运行实例名称快照';
COMMENT ON COLUMN dh_dataforge_run_alert.event_type IS '事件类型：FAILURE-运行失败';
COMMENT ON COLUMN dh_dataforge_run_alert.occurred_time IS '失败时间（运行结束时间，缺失时取运行记录更新时间）';
COMMENT ON COLUMN dh_dataforge_run_alert.receiver_user_id IS '接收人用户编号；0 表示未找到启用的接收人（仅出现在死信或人工关闭记录）';
COMMENT ON COLUMN dh_dataforge_run_alert.channel IS '渠道：1-站内信 2-短信 3-邮件';
COMMENT ON COLUMN dh_dataforge_run_alert.template_params IS '登记时定稿的模板参数（不含错误详情与日志）';
COMMENT ON COLUMN dh_dataforge_run_alert.status IS '状态：0-待发送 1-发送中 2-已送达 3-死信（需人工处置） 4-结果未知（短信/邮件发送结果未确认，不自动重发） 5-已人工关闭';
COMMENT ON COLUMN dh_dataforge_run_alert.attempts IS '已认领发送次数';
COMMENT ON COLUMN dh_dataforge_run_alert.available_at IS '最早可再次认领时间';
COMMENT ON COLUMN dh_dataforge_run_alert.lease_token IS '发送租约令牌';
COMMENT ON COLUMN dh_dataforge_run_alert.lease_until IS '发送租约到期时间';
COMMENT ON COLUMN dh_dataforge_run_alert.receipt_id IS 'system 模块返回的站内信、短信日志或邮件日志编号';
COMMENT ON COLUMN dh_dataforge_run_alert.last_error IS '最近一次失败原因代码';
COMMENT ON COLUMN dh_dataforge_run_alert.handled_at IS '送达或人工关闭时间';
COMMENT ON COLUMN dh_dataforge_run_alert.disposition_action IS '人工处置动作：RETRY-重新发送 ACKNOWLEDGE-确认关闭';
COMMENT ON COLUMN dh_dataforge_run_alert.disposition_reason IS '人工处置原因';
COMMENT ON COLUMN dh_dataforge_run_alert.disposition_command IS '人工处置的调度执行身份（xxl-job:任务编号:日志编号），用于幂等与审计';
COMMENT ON COLUMN dh_dataforge_run_alert.creator IS '创建者';
COMMENT ON COLUMN dh_dataforge_run_alert.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_run_alert.updater IS '更新者';
COMMENT ON COLUMN dh_dataforge_run_alert.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_run_alert.deleted IS '是否删除';
COMMENT ON INDEX uk_dh_dataforge_run_alert_target IS '同一运行实例、事件、渠道、接收人只登记一次';
COMMENT ON INDEX idx_dh_dataforge_run_alert_due IS '待发送与发送中记录按可认领时间读取';
COMMENT ON INDEX idx_dh_dataforge_run_alert_handled IS '已送达与已关闭记录按处理时间清理';

-- 2. 运行表失败行部分索引（扫描条件与索引表达式保持一致：coalesce(end_time, update_time)）
CREATE INDEX IF NOT EXISTS idx_dev_record_run_alert
    ON dh_dataforge_dev_record ((coalesce(end_time, update_time))) WHERE deleted = 0 AND status = 'FAILED';
CREATE INDEX IF NOT EXISTS idx_access_task_run_alert
    ON dh_dataforge_access_task ((coalesce(end_time, update_time))) WHERE deleted = 0 AND run_status = 4;
CREATE INDEX IF NOT EXISTS idx_etl_record_run_alert
    ON dh_dataforge_etl_record ((coalesce(end_time, update_time))) WHERE deleted = 0 AND run_status = 4;
CREATE INDEX IF NOT EXISTS idx_integration_record_run_alert
    ON dh_dataforge_integration_record ((coalesce(end_time, update_time)))
    WHERE deleted = 0 AND status = 'FAILURE' AND ds_project_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_job_record_run_alert
    ON dh_dataforge_job_record ((coalesce(end_time, update_time)))
    WHERE deleted = 0 AND status = 'FAILURE' AND origin = 'PLATFORM';
COMMENT ON INDEX idx_dev_record_run_alert IS '任务失败通知扫描：失败的数据开发运行按失败时间';
COMMENT ON INDEX idx_access_task_run_alert IS '任务失败通知扫描：失败的数据同步运行按失败时间';
COMMENT ON INDEX idx_etl_record_run_alert IS '任务失败通知扫描：失败的 ETL 运行按失败时间';
COMMENT ON INDEX idx_integration_record_run_alert IS '任务失败通知扫描：失败的受管数据集成实例按失败时间';
COMMENT ON INDEX idx_job_record_run_alert IS '任务失败通知扫描：失败的平台作业运行按失败时间';

-- 3. 通知模板
DO $run_alert_templates$
DECLARE
    template_sequence regclass;
    new_id int8;
    matches int;
    v_content constant varchar := '{kindName}任务「{taskName}」运行失败（实例：{runName}，失败时间：{failTime}）。请到运维中心查看日志并处理。';
    v_params constant varchar := '["kindName","taskName","runName","failTime"]';
    v_channel_id int8;
    v_channel_code varchar(63);
    v_account_id int8;
BEGIN
    template_sequence := COALESCE(pg_get_serial_sequence('"datahub-center".system_notify_template', 'id')::regclass,
                                  to_regclass('"datahub-center".system_notify_template_seq'));
    IF template_sequence IS NULL THEN
        RAISE EXCEPTION '0292 缺少站内信模板主键序列';
    END IF;

    -- 站内信模板：启用
    SELECT count(*) INTO matches FROM system_notify_template WHERE code = 'dataforge_run_failed' AND deleted = 0;
    IF matches > 0 THEN
        IF matches <> 1 OR NOT EXISTS (SELECT 1 FROM system_notify_template
                                       WHERE code = 'dataforge_run_failed' AND deleted = 0 AND status = 0
                                         AND content = v_content AND params = v_params) THEN
            RAISE EXCEPTION '0292 站内信模板 dataforge_run_failed 已存在但内容不同或已停用，请人工核查，禁止静默覆盖';
        END IF;
    ELSE
        LOOP
            new_id := nextval(template_sequence);
            EXIT WHEN NOT EXISTS (SELECT 1 FROM system_notify_template WHERE id = new_id);
        END LOOP;
        INSERT INTO system_notify_template
            (id, name, code, nickname, content, type, params, status, remark, creator, updater, deleted)
        VALUES (new_id, '数据研发-任务运行失败', 'dataforge_run_failed', '数据研发', v_content, 2, v_params, 0,
                '数据开发、数据同步、ETL、数据集成、作业编排运行失败通知；接收人为负责人、发布人、执行身份与创建人', '1', '1', 0);
    END IF;

    -- 邮件模板：绑定编号最小的可用邮箱账号，停用状态
    IF NOT EXISTS (SELECT 1 FROM system_mail_template WHERE code = 'dataforge_run_failed_mail' AND deleted = 0) THEN
        SELECT id INTO v_account_id FROM system_mail_account WHERE deleted = 0 ORDER BY id LIMIT 1;
        IF v_account_id IS NULL THEN
            RAISE NOTICE '[0292] 没有可用的邮箱账号，跳过 dataforge_run_failed_mail；配置账号后重新执行本迁移';
        ELSE
            INSERT INTO system_mail_template (id, name, code, account_id, nickname, title, content, params, status,
                                              remark, creator, create_time, updater, update_time, deleted)
            VALUES (nextval('system_mail_template_seq'), '【数据研发】任务运行失败', 'dataforge_run_failed_mail', v_account_id,
                    '数据研发', '【任务失败】{kindName}「{taskName}」',
                    '<p>{kindName}任务「{taskName}」运行失败。</p><p>实例：{runName}</p><p>失败时间：{failTime}</p><p>请登录系统，在“运维中心”查看日志并处理。</p>',
                    v_params, 1, '任务运行失败邮件通知；请确认发送账号后启用，并在 dataforge.run-alert.extra-channels 中开启 MAIL',
                    '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, 0);
        END IF;
    END IF;

    -- 短信模板：绑定编号最小的可用渠道，停用状态，服务商模板编号待管理员填写
    IF NOT EXISTS (SELECT 1 FROM system_sms_template WHERE code = 'dataforge_run_failed_sms' AND deleted = 0) THEN
        SELECT id, code INTO v_channel_id, v_channel_code FROM system_sms_channel WHERE deleted = 0 ORDER BY id LIMIT 1;
        IF v_channel_id IS NULL THEN
            RAISE NOTICE '[0292] 没有可用的短信渠道，跳过 dataforge_run_failed_sms；配置渠道后重新执行本迁移';
        ELSE
            INSERT INTO system_sms_template (id, type, status, code, name, content, params, remark,
                                             api_template_id, channel_id, channel_code,
                                             creator, create_time, updater, update_time, deleted)
            VALUES (nextval('system_sms_template_seq'), 2, 1, 'dataforge_run_failed_sms', '【数据研发】任务运行失败',
                    '{kindName}任务「{taskName}」于{failTime}运行失败，请登录平台运维中心处理。', v_params,
                    '任务运行失败短信通知；请选择实际渠道并填写服务商模板编号后启用，并在 dataforge.run-alert.extra-channels 中开启 SMS',
                    'TODO', v_channel_id, v_channel_code, '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, 0);
        END IF;
    END IF;
END;
$run_alert_templates$;

COMMIT;
