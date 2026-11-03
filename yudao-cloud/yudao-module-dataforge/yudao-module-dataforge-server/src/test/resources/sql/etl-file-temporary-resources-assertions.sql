-- 仅用于新建的独立测试数据库；禁止在已有平台或业务数据库执行。
-- psql变量 prior_migration 指向0115，migration_file 指向0116。
\set ON_ERROR_STOP on
CREATE SCHEMA "datahub-center";
SET search_path TO "datahub-center", public;
\i :prior_migration
INSERT INTO dh_dataforge_etl_file_output
    (id,tenant_id,record_id,pipeline_id,node_no,owner_id,run_token,operation_key,storage_key,storage_identity,file_name)
VALUES (1,7,11,12,'historical',99,'fixture','00000000-0000-0000-0000-000000000001',
        'exports','00000000-0000-0000-0000-000000000002','historical.xlsx');
\i :migration_file
\i :migration_file

DO $$
BEGIN
    IF (SELECT temporary_resources_json FROM dh_dataforge_etl_file_output WHERE id=1) <> '[]'::jsonb THEN
        RAISE EXCEPTION '历史运行被补造资源身份证据';
    END IF;
    IF (SELECT count(*) FROM pg_constraint WHERE conrelid='dh_dataforge_etl_file_output'::regclass
        AND conname='dh_etl_file_resources_budget_ck') <> 1 THEN
        RAISE EXCEPTION '重放后资源预算约束数量不一致';
    END IF;
    UPDATE dh_dataforge_etl_file_output SET temporary_resources_json =
        '[{"name":"excel-sheet-1","state":"INTENT","fileKey":null},{"name":"excel-template-1","state":"CREATED","fileKey":"fixture"}]'
        WHERE id=1;
    BEGIN
        UPDATE dh_dataforge_etl_file_output SET temporary_resources_json='{}' WHERE id=1;
        RAISE EXCEPTION '错误地允许非数组资源清单';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE dh_dataforge_etl_file_output SET temporary_resources_json='[1,2,3,4,5]' WHERE id=1;
        RAISE EXCEPTION '错误地允许超过四项的资源清单';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE dh_dataforge_etl_file_output SET temporary_resources_json=jsonb_build_array(repeat('测',6000)) WHERE id=1;
        RAISE EXCEPTION '错误地允许UTF8编码后超过16KiB的资源清单';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    UPDATE dh_dataforge_etl_file_output SET temporary_resources_json=NULL WHERE id=1;
    UPDATE dh_dataforge_etl_file_output SET temporary_resources_json='[]' WHERE id=1;
END $$;
SELECT '0116首执行、重复执行、历史无证据、JSON数组/数量/UTF8字节约束：通过' AS 验证结果;
