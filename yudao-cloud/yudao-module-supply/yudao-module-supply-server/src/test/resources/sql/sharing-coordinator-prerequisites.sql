-- 仅测试夹具：补齐实际旧表中协调器使用的列，不属于平台增量迁移。
CREATE SEQUENCE dh_supply_app_api_seq START 100;
ALTER TABLE dh_supply_app_api ALTER COLUMN id SET DEFAULT nextval('dh_supply_app_api_seq');
ALTER TABLE dh_supply_app_api ADD COLUMN forever_flag smallint NOT NULL DEFAULT 1,
 ADD COLUMN source_type varchar(32), ADD COLUMN update_time timestamp DEFAULT CURRENT_TIMESTAMP;
CREATE SEQUENCE dh_supply_grant_op_seq START 100;
ALTER TABLE dh_supply_grant_op ALTER COLUMN id SET DEFAULT nextval('dh_supply_grant_op_seq');
ALTER TABLE dh_supply_grant_op ADD COLUMN payload_hash varchar(64) NOT NULL,
 ADD COLUMN aggregate_key varchar(128) NOT NULL, ADD COLUMN target_status smallint NOT NULL,
 ADD COLUMN result_status varchar(16) NOT NULL, ADD COLUMN grant_id bigint,
 ADD COLUMN error_code varchar(64);
CREATE TABLE dh_supply_app (
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL, deleted smallint NOT NULL DEFAULT 0,
 app_status smallint NOT NULL DEFAULT 1
);
CREATE TABLE dh_supply_api (
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL, deleted smallint NOT NULL DEFAULT 0,
 status smallint NOT NULL DEFAULT 1, active_release_id bigint,
 contract_hash varchar(64), version varchar(20), database_id bigint,
 table_name varchar(200), config_type varchar(20), api_type varchar(20)
);
INSERT INTO dh_supply_app(id,tenant_id) VALUES(1,1),(2,1),(3,2);
INSERT INTO dh_supply_api(id,tenant_id,active_release_id,contract_hash,version,database_id,table_name,config_type,api_type)
 VALUES(10,1,100,'approved-hash','v1',9,'people','TABLE','QUERY'),
       (20,1,200,'approved-hash','v1',9,'people','TABLE','QUERY');

