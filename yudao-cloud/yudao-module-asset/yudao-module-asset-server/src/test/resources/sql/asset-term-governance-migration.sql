-- 尚未使用的资产术语功能与最小平台权限夹具，仅用于隔离 PostgreSQL 测试。
CREATE SCHEMA "datahub-center";
SET search_path TO "datahub-center", pg_catalog;
CREATE SEQUENCE dh_asset_term_seq;
CREATE SEQUENCE dh_asset_category_term_seq;
CREATE TABLE dh_asset_term (id bigint PRIMARY KEY DEFAULT nextval('dh_asset_term_seq'), deleted smallint DEFAULT 0);
CREATE TABLE dh_asset_category_term (id bigint PRIMARY KEY DEFAULT nextval('dh_asset_category_term_seq'), deleted smallint DEFAULT 0);
CREATE TABLE dh_governance_standard_term (id bigint PRIMARY KEY, name varchar(255), tenant_id bigint);
INSERT INTO dh_governance_standard_term VALUES (11, '客户编号', 1), (12, '订单金额', 2);
CREATE SEQUENCE dh_asset_term_column_rel_seq;
CREATE TABLE dh_asset_term_column_rel (
    id bigint PRIMARY KEY DEFAULT nextval('dh_asset_term_column_rel_seq'), term_id bigint NOT NULL,
    asset_id bigint NOT NULL, meta_column_id bigint NOT NULL, column_name varchar(200), remark varchar(512),
    creator varchar(64) DEFAULT '', create_time timestamp DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0, tenant_id bigint NOT NULL DEFAULT 1
);
CREATE INDEX idx_dh_asset_term_column_rel_term ON dh_asset_term_column_rel (tenant_id, term_id, deleted);
CREATE UNIQUE INDEX uk_dh_asset_term_column_rel_column ON dh_asset_term_column_rel (tenant_id, asset_id, meta_column_id) WHERE deleted = 0;
CREATE TABLE system_menu (id bigint PRIMARY KEY, parent_id bigint, permission varchar(100), type smallint,
    component varchar(255), component_name varchar(100));
INSERT INTO system_menu VALUES
    (1, 0, 'asset:data-asset:query', 2, 'asset/dataasset/index', 'AssetDataAsset'),
    (2, 1, 'asset:data-asset:term', 3, NULL, NULL),
    (3, 0, 'asset:term:query', 2, 'asset/term/index', 'AssetTerm'),
    (4, 3, 'asset:term:create', 3, NULL, NULL),
    (5, 3, 'asset:category-term:query', 3, NULL, NULL),
    (6, 4, '', 3, NULL, NULL),
    (7, 0, 'governance:standard-term:query', 2, 'governance/standard/term/index', 'GovernanceStandardTerm');
CREATE TABLE system_role_menu (id bigint PRIMARY KEY, role_id bigint, menu_id bigint);
INSERT INTO system_role_menu VALUES (1,1,1),(2,1,2),(3,1,3),(4,1,4),(5,1,5),(6,1,6),(7,2,7);
CREATE TABLE system_tenant_package (id bigint PRIMARY KEY, menu_ids text, updater varchar(64), update_time timestamp);
INSERT INTO system_tenant_package VALUES
    (1, '[1,"2",3,"4",5,6,"7",1]', 'original', '2026-01-01'),
    (2, '[3,4,5,6]', 'original', '2026-01-01'),
    (3, '[1,2,7]', 'original', '2026-01-01');
