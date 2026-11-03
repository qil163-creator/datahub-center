-- 仅用于独立 UUID 测试库；不是平台初始化或升级脚本。
ALTER TABLE dh_market_check_process ADD tenant_id bigint NOT NULL DEFAULT 1;
ALTER TABLE dh_asset_grant_source
    ADD source_type integer, ADD asset_id bigint, ADD start_time timestamp, ADD end_time timestamp,
    ADD creator text, ADD updater text, ADD create_time timestamp DEFAULT CURRENT_TIMESTAMP,
    ADD update_time timestamp DEFAULT CURRENT_TIMESTAMP;
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
INSERT INTO dh_market_resource_apply
    (id,tenant_id,asset_id,apply_user_id,status,if_auth,paused,revision,source_revision,
     app_id,api_id,snapshot,delivery_status)
VALUES (7,1,5,9,1,0,0,1,0,2,3,
    '{"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
      "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"],
      "approvedContractHash":"contract-1","apiVersion":"v1"}',0);
