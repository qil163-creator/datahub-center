-- 仅测试：供新增匹配和确认匹配共用，在每例独立的 SharingPostgresSupport 临时数据库创建，不是平台迁移。
CREATE TABLE dh_asset_data_asset (
  id bigint PRIMARY KEY, tenant_id bigint NOT NULL DEFAULT 1, asset_code varchar, catalog_item_urn varchar,
  asset_name varchar, asset_type int, category_id bigint, meta_table_id bigint, resource_urn varchar,
  published_snapshot_id bigint, published_version varchar, datasource_id bigint, source_system_name varchar,
  db_name varchar, schema_name varchar, table_name varchar, table_type varchar, layer varchar,
  subject_domain_id bigint, subject_domain_name varchar, row_count bigint, field_count int,
  storage_size bigint, health_score numeric, description varchar, status int, publish_time timestamp,
  publisher varchar, tech_leader_user_id bigint, tech_leader_phone varchar, business_leader_user_id bigint,
  business_leader_phone varchar, owner_dept_id bigint, view_count bigint, share_attribute int,
  share_condition varchar, update_cycle varchar, business_purpose varchar, contact_name varchar,
  contact_phone varchar, remark varchar, share_attr int, share_deny_reason varchar, provider_dept_id bigint,
  source_dept_id bigint, duty_basis varchar, update_rule varchar, catalog_revision bigint,
  published_catalog_version_id bigint, tech_available int, external_code varchar,
  creator varchar, updater varchar, create_time timestamp, update_time timestamp,
  deleted smallint NOT NULL DEFAULT 0);
INSERT INTO dh_asset_data_asset(id,tenant_id,status,asset_name,asset_type,catalog_item_urn,
    meta_table_id,resource_urn,published_snapshot_id,published_version,published_catalog_version_id,
    datasource_id,db_name,schema_name,table_name,owner_dept_id,tech_leader_user_id,
    business_leader_user_id,provider_dept_id,source_dept_id,share_attr,share_attribute,
    share_condition,share_deny_reason,tech_available,catalog_revision)
  SELECT id,1,1,'test asset',1,'catalog',31,'resource',41,'v1',51,
         61,'database','public','table',71,81,82,91,92,1,1,'condition','reason',1,1
  FROM (VALUES(8),(9)) AS fixture(id);
