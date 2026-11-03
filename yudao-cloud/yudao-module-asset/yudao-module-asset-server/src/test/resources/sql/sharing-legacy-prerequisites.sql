-- 仅测试：当前交付相关表的最小旧结构，不是生产迁移或新库基线。
CREATE TABLE dh_asset_grant_source (
 id bigint PRIMARY KEY, apply_id bigint, revision bigint DEFAULT 1,
 app_id bigint, api_id bigint, revoked integer DEFAULT 0, scope_json text,
 tenant_id bigint NOT NULL DEFAULT 1, deleted smallint NOT NULL DEFAULT 0
);
CREATE TABLE dh_market_resource_apply (
 id bigint PRIMARY KEY, revision integer DEFAULT 1,
 tenant_id bigint NOT NULL DEFAULT 1, deleted smallint NOT NULL DEFAULT 0
);
-- 与现网基线一致由序列生成主键（生产 INPUT 主键模式依赖此默认值）；用例显式主键均小于起始值。
CREATE SEQUENCE dh_market_delivery_operation_seq START WITH 1000000;
CREATE TABLE dh_market_delivery_operation (
 id bigint PRIMARY KEY DEFAULT nextval('dh_market_delivery_operation_seq'), operation_id varchar(64) NOT NULL, apply_id bigint,
 source_grant_id varchar(64), op_type varchar(16), app_id bigint, api_id bigint,
 expected_revision integer, status varchar(16) NOT NULL, attempt_count integer DEFAULT 0,
 next_retry_time timestamp(6), lease_until timestamp(6), last_error varchar(500),
 result_ref varchar(128), creator varchar(64), updater varchar(64),
 create_time timestamp(6) DEFAULT CURRENT_TIMESTAMP, update_time timestamp(6) DEFAULT CURRENT_TIMESTAMP,
 tenant_id bigint NOT NULL DEFAULT 1, deleted smallint NOT NULL DEFAULT 0
);
