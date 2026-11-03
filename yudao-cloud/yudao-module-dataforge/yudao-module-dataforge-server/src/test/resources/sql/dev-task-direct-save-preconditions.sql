-- 仅用于独立临时 PostgreSQL 库；建立 0162 等价最小前置状态，不得执行于共享或业务数据库。
DO $fixture_guard$
BEGIN
    IF current_database() NOT LIKE 'dev_task_0163_%' THEN
        RAISE EXCEPTION '0163 夹具只允许在 dev_task_0163_ 前缀的独立测试库运行';
    END IF;
END $fixture_guard$;
CREATE SCHEMA "datahub-center";
SET search_path TO "datahub-center", pg_catalog;
CREATE TABLE dh_dataforge_dev_task (id bigint PRIMARY KEY, revision integer NOT NULL, published_version_id bigint,
    content text, latest_version integer, status smallint, schedule_status smallint, tenant_id bigint);
CREATE TABLE dh_dataforge_dev_task_version (id bigint PRIMARY KEY, task_id bigint, version integer, snapshot_json jsonb, tenant_id bigint);
CREATE TABLE dh_dataforge_dev_record (id bigint PRIMARY KEY, task_id bigint, version_id bigint, snapshot_json jsonb, tenant_id bigint);
CREATE TABLE system_menu (id bigint PRIMARY KEY, name varchar(50), permission varchar(100), type smallint,
    parent_id bigint, status smallint DEFAULT 0, deleted smallint DEFAULT 0, updater varchar(64), update_time timestamp);
CREATE TABLE system_role_menu (id bigint PRIMARY KEY, role_id bigint, menu_id bigint, tenant_id bigint,
    creator varchar(64), create_time timestamp, updater varchar(64), update_time timestamp, deleted smallint DEFAULT 0);
CREATE TABLE system_tenant_package (id bigint PRIMARY KEY, menu_ids varchar(4096), status smallint DEFAULT 0,
    deleted smallint DEFAULT 0, updater varchar(64), update_time timestamp);

INSERT INTO dh_dataforge_dev_task VALUES
    (1, 7, 80, '未发布的较新定义', 2, 1, 1, 1), (2, 3, NULL, '只有旧保存历史', 1, 0, 0, 1),
    (3, 1, 82, '其他租户定义', 1, 1, 0, 2);
INSERT INTO dh_dataforge_dev_task_version VALUES
    (80, 1, 1, '{"task":{"publishedVersionId":80,"revision":6,"content":"旧授权定义"},"dependencies":[]}', 1),
    (81, 1, 2, '{"task":{"content":"旧存档未发布定义"}}', 1),
    (82, 3, 1, '{"task":{"publishedVersionId":82,"content":"其他租户定义"}}', 2);
INSERT INTO dh_dataforge_dev_record VALUES
    (9, 1, 80, '{"task":{"publishedVersionId":80,"content":"旧授权定义"}}', 1),
    (10, 3, 82, '{"task":{"publishedVersionId":82,"content":"其他租户定义"}}', 2);
INSERT INTO system_menu (id, name, permission, type, parent_id) VALUES
    (9007199254740993, '保存', 'dataforge:dev-task:save', 3, 700),
    (9007199254740995, '发布', 'dataforge:dev-task:publish', 3, 700),
    (9007199254740997, '其他', 'dataforge:dev-task:run', 3, 700);
INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, create_time) VALUES
    (1, 101, 9007199254740995, 1, '既有授权', timestamp '2026-01-01'),
    (2, 101, 9007199254740995, 1, '历史重复授权', timestamp '2026-01-01'),
    (3, 102, 9007199254740995, 1, '既有授权', timestamp '2026-01-01'),
    (4, 102, 9007199254740993, 1, '既有保存授权', timestamp '2026-01-01'),
    (5, 101, 9007199254740995, 2, '其他租户授权', timestamp '2026-01-01'),
    (6, 103, 9007199254740997, 1, '原运行授权', timestamp '2026-01-01');
INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, deleted) VALUES
    (7, 101, 9007199254740993, 1, 1), (8, 101, 9007199254740993, 1, 1);
INSERT INTO system_tenant_package (id, menu_ids, status, deleted) VALUES
    (1, '[101,"9007199254740995",202,9007199254740995]', 0, 0),
    (2, '[9007199254740995,"9007199254740993",303]', 0, 0),
    (3, '[9007199254740995,404]', 1, 0),
    (4, '[505,"9007199254740995"]', 0, 1),
    (5, '[9007199254740997,606]', 0, 0);
CREATE TABLE fixture_0163_before (kind text, id bigint, row_value jsonb, PRIMARY KEY (kind, id));
INSERT INTO fixture_0163_before SELECT 'task', id, to_jsonb(row_value) FROM dh_dataforge_dev_task row_value;
INSERT INTO fixture_0163_before SELECT 'version', id, to_jsonb(row_value) FROM dh_dataforge_dev_task_version row_value;
INSERT INTO fixture_0163_before SELECT 'record', id, to_jsonb(row_value) FROM dh_dataforge_dev_record row_value;
