-- Synthetic PostgreSQL fixture for migration 0144; use only a new codex_p3_contract_* database.
-- Run setup, then 0144, then assert-success; replay 0144 and run assert-replay.
-- Failure cases add foreign-key/custom-function before 0144 and run assert-rollback after its expected failure.
DO $fixture_guard$
BEGIN
    IF current_database() !~ '^codex_p3_contract_[a-z0-9_]+$'
       OR to_regnamespace('datahub-center') IS NOT NULL THEN
        RAISE EXCEPTION 'P3 fixture requires a new isolated codex_p3_contract_* database';
    END IF;
END $fixture_guard$;

CREATE SCHEMA "datahub-center";
SET search_path TO "datahub-center", public;

CREATE TABLE dh_modeling_logic_model (id bigint PRIMARY KEY, marker text NOT NULL);
CREATE TABLE dh_modeling_logic_model_version (id bigint PRIMARY KEY, model_id bigint NOT NULL, marker text NOT NULL);
CREATE TABLE dh_modeling_logic_model_column (id bigint PRIMARY KEY, model_id bigint NOT NULL, marker text NOT NULL);
CREATE TABLE dh_modeling_model_materialized (id bigint PRIMARY KEY, model_id bigint NOT NULL, marker text NOT NULL);
CREATE TABLE dh_modeling_model_materialized_log (id bigint PRIMARY KEY, materialized_id bigint NOT NULL, marker text NOT NULL);
INSERT INTO dh_modeling_logic_model VALUES (101, 'logical retained');
INSERT INTO dh_modeling_logic_model_version VALUES (201, 101, 'version retained');
INSERT INTO dh_modeling_logic_model_column VALUES (301, 101, 'column retained');
INSERT INTO dh_modeling_model_materialized VALUES (401, 101, 'deployment retained');
INSERT INTO dh_modeling_model_materialized_log VALUES (501, 401, 'log retained');

CREATE SEQUENCE dh_modeling_physical_model_seq START WITH 901;
CREATE SEQUENCE dh_modeling_physical_model_version_seq START WITH 1001;
CREATE TABLE dh_modeling_physical_model (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_physical_model_seq'), marker text NOT NULL
);
CREATE TABLE dh_modeling_physical_model_version (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_physical_model_version_seq'),
    model_id bigint NOT NULL REFERENCES dh_modeling_physical_model(id), marker text NOT NULL
);
ALTER SEQUENCE dh_modeling_physical_model_seq OWNED BY dh_modeling_physical_model.id;
ALTER SEQUENCE dh_modeling_physical_model_version_seq OWNED BY dh_modeling_physical_model_version.id;
CREATE FUNCTION dh_modeling_unified_version_guard() RETURNS trigger LANGUAGE plpgsql AS
$function$ BEGIN RETURN NEW; END $function$;
CREATE FUNCTION dh_modeling_unified_binding_guard() RETURNS trigger LANGUAGE plpgsql AS
$function$ BEGIN RETURN NEW; END $function$;
CREATE TRIGGER physical_version_guard BEFORE INSERT ON dh_modeling_physical_model_version
    FOR EACH ROW EXECUTE FUNCTION dh_modeling_unified_version_guard();
CREATE TRIGGER physical_binding_guard BEFORE INSERT ON dh_modeling_physical_model
    FOR EACH ROW EXECUTE FUNCTION dh_modeling_unified_binding_guard();
CREATE FUNCTION p3_logical_function() RETURNS text LANGUAGE sql AS $function$ SELECT 'logical retained'::text $function$;
INSERT INTO dh_modeling_physical_model(marker) VALUES ('physical retired');
INSERT INTO dh_modeling_physical_model_version(model_id, marker) VALUES (901, 'physical version retired');

CREATE TABLE system_menu (
    id bigint PRIMARY KEY, parent_id bigint NOT NULL DEFAULT 0, type integer NOT NULL,
    component text, component_name text, path text, permission text, deleted boolean NOT NULL DEFAULT false
);
INSERT INTO system_menu(id, parent_id, type, component, component_name, path, permission, deleted) VALUES
    (101, 0, 1, NULL, NULL, 'modeling', '', false),
    (110, 101, 2, 'modeling/model/index', 'ModelingModel', 'logical-model', 'modeling:model:query', false),
    (111, 110, 3, NULL, NULL, '', 'modeling:model:create', false),
    (120, 101, 2, '/modeling/physicalmodel/index/', NULL, 'physical-model', 'modeling:model:query', false),
    (121, 120, 3, NULL, NULL, '', 'modeling:model:create', false),
    (123, 121, 3, NULL, NULL, '', 'modeling:model:update', false),
    (130, 101, 2, 'modeling/physicalmodel/index', NULL, 'legacy-hidden', '', true),
    (131, 101, 3, NULL, NULL, '', 'modeling:physical-model:delete', false),
    (132, 101, 2, NULL, 'ModelingPhysicalModel', 'old-component-name', '', false),
    (133, 101, 2, NULL, NULL, '/modeling/physical-model/', '', false),
    (140, 101, 2, 'modeling/materialized/index', 'ModelingMaterialized', 'materialized', 'modeling:materialized:query', false),
    (141, 140, 3, NULL, NULL, '', 'modeling:materialized:publish', false);
CREATE TABLE system_role_menu (id bigint PRIMARY KEY, role_id bigint NOT NULL, menu_id bigint NOT NULL);
INSERT INTO system_role_menu VALUES
    (1, 1, 120), (2, 1, 121), (3, 1, 110), (4, 1, 111), (5, 2, 130), (6, 2, 131),
    (7, 2, 132), (8, 2, 133), (9, 2, 140), (10, 2, 141), (11, 1, 123);
CREATE TABLE system_tenant_package (
    id bigint PRIMARY KEY, menu_ids text NOT NULL, updater text NOT NULL,
    update_time timestamp NOT NULL, deleted boolean NOT NULL DEFAULT false
);
INSERT INTO system_tenant_package VALUES
    (1, '[110,120,"121",111,"140",130,131,132,133,123,141]', 'fixture', TIMESTAMP '2000-01-01', false),
    (2, '[101,110,"111",140]', 'fixture', TIMESTAMP '2000-01-01', false),
    (3, '[130,"133"]', 'fixture', TIMESTAMP '2000-01-01', true),
    (4, '[]', 'fixture', TIMESTAMP '2000-01-01', false);

CREATE TABLE public.p3_contract_snapshots (table_name text PRIMARY KEY, rows_json jsonb NOT NULL);
DO $snapshots$
DECLARE
    table_name text;
    rows_json jsonb;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'dh_modeling_logic_model', 'dh_modeling_logic_model_version', 'dh_modeling_logic_model_column',
        'dh_modeling_model_materialized', 'dh_modeling_model_materialized_log',
        'dh_modeling_physical_model', 'dh_modeling_physical_model_version',
        'system_menu', 'system_role_menu', 'system_tenant_package'
    ] LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.id), ''[]''::jsonb) FROM "datahub-center".%I t', table_name)
            INTO rows_json;
        INSERT INTO public.p3_contract_snapshots VALUES (table_name, rows_json);
    END LOOP;
END $snapshots$;

