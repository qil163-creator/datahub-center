-- Run after a second execution of 0144: no remaining record or timestamp may change.
DO $assert_replay$
DECLARE snapshot record; observed jsonb;
BEGIN
    FOR snapshot IN SELECT * FROM public.p3_contract_after LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.id), ''[]''::jsonb) FROM "datahub-center".%I t', snapshot.table_name)
            INTO observed;
        IF observed IS DISTINCT FROM snapshot.rows_json THEN
            RAISE EXCEPTION 'P3 replay changed state in %', snapshot.table_name;
        END IF;
    END LOOP;
    IF to_regclass('"datahub-center".dh_modeling_physical_model') IS NOT NULL
       OR to_regclass('"datahub-center".dh_modeling_physical_model_version') IS NOT NULL THEN
        RAISE EXCEPTION 'P3 replay recreated retired tables';
    END IF;
END $assert_replay$;

