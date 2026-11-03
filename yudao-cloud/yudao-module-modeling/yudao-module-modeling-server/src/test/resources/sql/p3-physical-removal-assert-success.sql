-- Assert the successful migration using only synthetic rows from p3-physical-removal-setup.sql.
DO $assert_success$
DECLARE
    snapshot record;
    observed jsonb;
BEGIN
    IF to_regclass('"datahub-center".dh_modeling_physical_model') IS NOT NULL
       OR to_regclass('"datahub-center".dh_modeling_physical_model_version') IS NOT NULL
       OR to_regclass('"datahub-center".dh_modeling_physical_model_seq') IS NOT NULL
       OR to_regclass('"datahub-center".dh_modeling_physical_model_version_seq') IS NOT NULL
       OR to_regprocedure('"datahub-center".dh_modeling_unified_version_guard()') IS NOT NULL
       OR to_regprocedure('"datahub-center".dh_modeling_unified_binding_guard()') IS NOT NULL THEN
        RAISE EXCEPTION 'P3 retired tables, sequences or dedicated functions survived';
    END IF;
    IF "datahub-center".p3_logical_function() <> 'logical retained' THEN
        RAISE EXCEPTION 'P3 unrelated logical function changed';
    END IF;
    FOR snapshot IN SELECT * FROM public.p3_contract_snapshots
        WHERE table_name IN ('dh_modeling_logic_model', 'dh_modeling_logic_model_version', 'dh_modeling_logic_model_column',
                            'dh_modeling_model_materialized', 'dh_modeling_model_materialized_log') LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.id), ''[]''::jsonb) FROM "datahub-center".%I t', snapshot.table_name)
            INTO observed;
        IF observed IS DISTINCT FROM snapshot.rows_json THEN
            RAISE EXCEPTION 'P3 retained model/deployment rows changed in %', snapshot.table_name;
        END IF;
    END LOOP;
    SELECT jsonb_agg(id ORDER BY id) INTO observed FROM "datahub-center".system_menu;
    IF observed IS DISTINCT FROM '[101,110,111,140,141]'::jsonb THEN
        RAISE EXCEPTION 'P3 retired menu cleanup or shared permission preservation failed';
    END IF;
    SELECT jsonb_agg(id ORDER BY id) INTO observed FROM "datahub-center".system_role_menu;
    IF observed IS DISTINCT FROM '[3,4,9,10]'::jsonb THEN
        RAISE EXCEPTION 'P3 role-menu cleanup or retained grants failed';
    END IF;
    IF (SELECT menu_ids::jsonb FROM "datahub-center".system_tenant_package WHERE id=1)
            IS DISTINCT FROM '[110,111,"140",141]'::jsonb
       OR (SELECT menu_ids::jsonb FROM "datahub-center".system_tenant_package WHERE id=3) IS DISTINCT FROM '[]'::jsonb
       OR EXISTS (SELECT 1 FROM "datahub-center".system_tenant_package WHERE id IN (1,3) AND updater<>'migration:0144') THEN
        RAISE EXCEPTION 'P3 package cleanup lost remaining value types/order or missed soft-deleted entries';
    END IF;
    SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) INTO observed FROM "datahub-center".system_tenant_package t WHERE id IN (2,4);
    IF observed IS DISTINCT FROM (
        SELECT jsonb_agg(entry.value ORDER BY entry.value->>'id')
        FROM public.p3_contract_snapshots saved, jsonb_array_elements(saved.rows_json) entry(value)
        WHERE saved.table_name='system_tenant_package' AND entry.value->>'id' IN ('2','4')
    ) THEN
        RAISE EXCEPTION 'P3 unrelated tenant packages changed';
    END IF;
END $assert_success$;

-- Preserve the complete post-migration state, including timestamps, for the replay assertion.
CREATE TABLE public.p3_contract_after (table_name text PRIMARY KEY, rows_json jsonb NOT NULL);
DO $after_snapshot$
DECLARE snapshot record; observed jsonb;
BEGIN
    FOR snapshot IN SELECT * FROM public.p3_contract_snapshots
        WHERE table_name NOT IN ('dh_modeling_physical_model', 'dh_modeling_physical_model_version') LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.id), ''[]''::jsonb) FROM "datahub-center".%I t', snapshot.table_name)
            INTO observed;
        INSERT INTO public.p3_contract_after VALUES (snapshot.table_name, observed);
    END LOOP;
END $after_snapshot$;

