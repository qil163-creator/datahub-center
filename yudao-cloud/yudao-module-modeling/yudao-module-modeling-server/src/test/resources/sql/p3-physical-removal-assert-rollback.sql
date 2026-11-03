-- Run in a fresh connection after an expected 0144 failure/connection-close rollback.
DO $assert_rollback$
DECLARE snapshot record; observed jsonb;
BEGIN
    IF to_regclass('"datahub-center".dh_modeling_physical_model') IS NULL
       OR to_regclass('"datahub-center".dh_modeling_physical_model_version') IS NULL
       OR to_regclass('"datahub-center".dh_modeling_physical_model_seq') IS NULL
       OR to_regclass('"datahub-center".dh_modeling_physical_model_version_seq') IS NULL
       OR to_regprocedure('"datahub-center".dh_modeling_unified_version_guard()') IS NULL
       OR to_regprocedure('"datahub-center".dh_modeling_unified_binding_guard()') IS NULL THEN
        RAISE EXCEPTION 'P3 failed migration left retired objects partially removed';
    END IF;
    FOR snapshot IN SELECT * FROM public.p3_contract_snapshots LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.id), ''[]''::jsonb) FROM "datahub-center".%I t', snapshot.table_name)
            INTO observed;
        IF observed IS DISTINCT FROM snapshot.rows_json THEN
            RAISE EXCEPTION 'P3 failed migration did not restore all rows/grants/packages in %', snapshot.table_name;
        END IF;
    END LOOP;
    IF "datahub-center".p3_logical_function() <> 'logical retained' THEN
        RAISE EXCEPTION 'P3 failed migration changed unrelated logical function';
    END IF;
    IF to_regclass('public.p3_unknown_fk') IS NOT NULL THEN
        IF (SELECT count(*) FROM public.p3_unknown_fk WHERE id=701 AND physical_model_id=901) <> 1 THEN
            RAISE EXCEPTION 'P3 failed migration changed unknown FK data';
        END IF;
    END IF;
    IF to_regprocedure('"datahub-center".p3_unknown_function()') IS NOT NULL THEN
        IF "datahub-center".p3_unknown_function() <> 1 THEN
            RAISE EXCEPTION 'P3 failed migration changed custom function or its referenced data';
        END IF;
    END IF;
END $assert_rollback$;
