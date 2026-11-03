-- 仅用于本次独立临时 PostgreSQL，禁止在已有平台数据库执行。
-- 使用 psql 变量 migration_file 指向 0111 增量文件。
\set ON_ERROR_STOP on
CREATE SCHEMA "datahub-center";
SET search_path TO "datahub-center", public;
CREATE TABLE dh_dataforge_etl_node_record (
    id bigint PRIMARY KEY,
    record_id bigint NOT NULL,
    pipeline_id bigint NOT NULL,
    node_no varchar(64) NOT NULL,
    read_count bigint NOT NULL DEFAULT 0,
    write_count bigint NOT NULL DEFAULT 0
);
INSERT INTO dh_dataforge_etl_node_record (id, record_id, pipeline_id, node_no) VALUES (1, 1, 1, 'historical');
\i :migration_file
\i :migration_file

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM dh_dataforge_etl_node_record WHERE id = 1
               AND (port_counts_json IS NOT NULL OR file_output_state IS NOT NULL)) THEN
        RAISE EXCEPTION '旧记录被错误回填';
    END IF;
    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = 'dh_dataforge_etl_node_record'::regclass
        AND conname IN ('ck_etl_node_port_counts_object', 'ck_etl_node_file_output_state')) <> 2 THEN
        RAISE EXCEPTION '回放后约束数量不一致';
    END IF;
    INSERT INTO dh_dataforge_etl_node_record VALUES (2, 1, 1, 'branch', 3, 2,
        '{"pass":1,"reject":1}', 'PUBLISHED');
    INSERT INTO dh_dataforge_etl_node_record VALUES (3, 1, 1, 'file', 0, 0, '{}', 'PUBLISHED_CLEANUP_PENDING');
    IF (SELECT (port_counts_json::jsonb ->> 'reject')::bigint FROM dh_dataforge_etl_node_record WHERE id = 2) <> 1 THEN
        RAISE EXCEPTION '端口统计读取不一致';
    END IF;
    BEGIN
        UPDATE dh_dataforge_etl_node_record SET port_counts_json = '[]' WHERE id = 2;
        RAISE EXCEPTION '错误地允许数组端口计数';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE dh_dataforge_etl_node_record SET file_output_state = 'INVALID' WHERE id = 2;
        RAISE EXCEPTION '错误地允许未知文件状态';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END $$;
SELECT '迁移首次执行与回放、旧记录空值、端口读写、文件状态、非法值拒绝：通过' AS 验证结果;
