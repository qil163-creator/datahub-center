-- Isolated PostgreSQL regression fixture; NEVER a platform migration.
DO $$ BEGIN
  IF current_user <> 'modeling_verify' THEN RAISE EXCEPTION 'Use an isolated modeling_verify test cluster'; END IF;
END $$;
CREATE SCHEMA "datahub-center";
SET search_path TO "datahub-center",public;
CREATE TABLE dh_modeling_data_domain (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, deleted smallint DEFAULT 0, status smallint DEFAULT 0);
CREATE TABLE dh_modeling_subject_domain (LIKE dh_modeling_data_domain INCLUDING ALL);
CREATE TABLE dh_modeling_dw_layer (LIKE dh_modeling_data_domain INCLUDING ALL);
CREATE TABLE dh_modeling_business_process (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, deleted smallint DEFAULT 0,
  data_domain_id bigint, biz_category_id bigint, code varchar(64), name varchar(100));
CREATE TABLE dh_modeling_business_process_supporting_domain_rel (id bigint PRIMARY KEY, tenant_id bigint NOT NULL,
  deleted smallint DEFAULT 0, business_process_id bigint, data_domain_id bigint);
CREATE TABLE dh_modeling_subject_domain_process_rel (id bigint PRIMARY KEY, tenant_id bigint NOT NULL,
  deleted smallint DEFAULT 0, business_process_id bigint, subject_domain_id bigint);
CREATE TABLE dh_modeling_concept_model (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, deleted smallint DEFAULT 0,
  theme_id bigint, layer_id bigint, subject_domain_id bigint, business_process_id bigint);
CREATE TABLE dh_modeling_logic_model (LIKE dh_modeling_concept_model INCLUDING ALL, classification_status varchar(32));
CREATE TABLE dh_modeling_physical_model (LIKE dh_modeling_logic_model INCLUDING ALL);
CREATE TABLE dh_modeling_logic_model_version (id bigint PRIMARY KEY, model_id bigint, fields jsonb);
CREATE TABLE dh_modeling_model_check (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, deleted smallint DEFAULT 0,
  task_status varchar(16) DEFAULT 'RUNNING', start_time timestamp, end_time timestamp, update_time timestamp, error_info varchar(2000));
CREATE TABLE dh_modeling_model_check_config (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, deleted smallint DEFAULT 0);
CREATE TABLE dh_modeling_model_check_result (id bigint PRIMARY KEY, drift_type varchar(32));
CREATE TABLE dh_governance_standard_naming_policy (id bigint PRIMARY KEY, scope_type varchar(32));
CREATE TABLE system_menu (id bigint PRIMARY KEY, deleted smallint DEFAULT 0, update_time timestamp);
INSERT INTO dh_modeling_model_check(id,tenant_id,task_status) VALUES (1,1,'RUNNING');
