-- 仅用于每例独立的 PostgreSQL 测试库，不是平台迁移。
ALTER TABLE dh_market_resource_apply ADD asset_id bigint, ADD app_id bigint, ADD api_id bigint,
    ADD status integer, ADD paused integer, ADD if_auth integer, ADD update_time timestamp;
CREATE TABLE dh_asset_data_asset(id bigint PRIMARY KEY, tenant_id bigint, deleted smallint DEFAULT 0,
    tech_leader_user_id bigint, business_leader_user_id bigint, owner_dept_id bigint, update_time timestamp);
INSERT INTO dh_asset_data_asset(id,tenant_id,tech_leader_user_id,owner_dept_id) VALUES(8,1,11,100);
INSERT INTO dh_market_resource_apply(id,asset_id,app_id,api_id,status,source_revision,paused,if_auth)
    VALUES(7,8,1,2,2,2,0,1);
CREATE SEQUENCE dh_market_audit_event_seq;
CREATE TABLE dh_market_audit_event (
    id bigint PRIMARY KEY DEFAULT nextval('dh_market_audit_event_seq'),
    event_id varchar(64) NOT NULL,event_type varchar(32) NOT NULL,actor_user_id bigint,dept_id bigint,
    object_type varchar(32) NOT NULL,object_id bigint,from_status varchar(32),to_status varchar(32),
    reason varchar(500),result varchar(32),occurred_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id varchar(64),tenant_id bigint NOT NULL DEFAULT 1,creator varchar(64),updater varchar(64),
    create_time timestamp(6) DEFAULT CURRENT_TIMESTAMP,update_time timestamp(6) DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0
);
