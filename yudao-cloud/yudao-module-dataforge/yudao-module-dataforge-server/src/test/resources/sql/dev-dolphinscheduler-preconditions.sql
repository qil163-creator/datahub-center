-- 仅用于独立临时 PostgreSQL 库；建立 0163 等价最小状态，不得执行于共享或业务数据库。
DO $fixture_guard$
BEGIN
    IF current_database() NOT LIKE 'dev_schedule_0164_%' THEN
        RAISE EXCEPTION '0164 夹具只允许在 dev_schedule_0164_ 前缀独立测试库运行';
    END IF;
END $fixture_guard$;
CREATE SCHEMA "datahub-center";
SET search_path TO "datahub-center", pg_catalog;
CREATE TABLE dh_dataforge_dev_task (id bigint PRIMARY KEY, current_version_id bigint, revision integer,
    scheduler varchar(32) NOT NULL DEFAULT 'XXL_JOB', schedule_status smallint, next_run_time timestamp,
    cron_expression varchar(100), tenant_id bigint, updater varchar(64), update_time timestamp,
    CONSTRAINT ck_dataforge_dev_scheduler CHECK (scheduler IN ('XXL_JOB')));
CREATE TABLE dh_dataforge_dev_task_version (id bigint PRIMARY KEY, task_id bigint, snapshot_json jsonb);
CREATE TABLE dh_dataforge_dev_record (id bigint PRIMARY KEY, task_id bigint, version_id bigint, snapshot_json jsonb);
INSERT INTO dh_dataforge_dev_task VALUES
    (1, 80, 7, 'XXL_JOB', 1, timestamp '2026-10-14 10:00:00', '0 0 12 ? * 1', 1, '既有保存', timestamp '2026-10-01'),
    (2, NULL, 3, 'XXL_JOB', 0, NULL, '0 */5 * * * ?', 1, '既有保存', timestamp '2026-10-01');
INSERT INTO dh_dataforge_dev_task_version VALUES
    (80, 1, '{"task":{"publishedVersionId":80,"scheduler":"XXL_JOB","cronExpression":"0 0 12 ? * 1"}}');
INSERT INTO dh_dataforge_dev_record VALUES
    (9, 1, 80, '{"task":{"publishedVersionId":80,"scheduler":"XXL_JOB"},"runKey":"legacy"}');
CREATE TABLE fixture_0164_before (kind text, id bigint, row_value jsonb, PRIMARY KEY (kind, id));
INSERT INTO fixture_0164_before SELECT 'task', id, to_jsonb(row_value) FROM dh_dataforge_dev_task row_value;
INSERT INTO fixture_0164_before SELECT 'version', id, to_jsonb(row_value) FROM dh_dataforge_dev_task_version row_value;
INSERT INTO fixture_0164_before SELECT 'record', id, to_jsonb(row_value) FROM dh_dataforge_dev_record row_value;
