-- 仅测试：当前授权表的最小旧结构，不是生产迁移或新库基线。
CREATE TABLE dh_supply_app_api (
 id bigint PRIMARY KEY, app_id bigint NOT NULL, api_id bigint NOT NULL,
 tenant_id bigint NOT NULL DEFAULT 1, deleted smallint NOT NULL DEFAULT 0,
 grant_revision integer DEFAULT 1, grant_status smallint DEFAULT 1
);
CREATE TABLE dh_supply_grant_op (
 id bigint PRIMARY KEY, source_system varchar(64) NOT NULL,
 operation_id varchar(64) NOT NULL, tenant_id bigint NOT NULL DEFAULT 1,
 deleted smallint NOT NULL DEFAULT 0
);
