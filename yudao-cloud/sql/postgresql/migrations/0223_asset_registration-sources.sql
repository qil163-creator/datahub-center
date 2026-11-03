-- Migration: 0223_asset_registration-sources.sql
-- Purpose: 保存资产注册业务配置及真实 API/文件来源事实，不伪造物理表和技术交付能力。
-- Depends on: 0221_asset_qdata-map-datasets.sql；资产目录及技术对象关联基础结构已存在。
-- Target: 已有 PostgreSQL 数据库；新库由完整基线包含。
-- Repeatable: 是；新增列、表和索引均带存在性保护。
-- Rollback: 回滚应用后可删除新表及 registration_config_json 列，会丢失新增注册配置与来源记录；原资产目录保留。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
ALTER TABLE dh_asset_data_asset ADD COLUMN IF NOT EXISTS registration_config_json text;
COMMENT ON COLUMN dh_asset_data_asset.registration_config_json IS '资产注册业务配置白名单；不替代物理表元数据及授权依据';

CREATE TABLE IF NOT EXISTS dh_asset_registration_source (
    asset_id bigint PRIMARY KEY REFERENCES dh_asset_data_asset(id),
    source_kind integer NOT NULL CHECK (source_kind IN (2, 3)),
    source_id bigint NOT NULL CHECK (source_id > 0),
    source_version varchar(128),
    source_name varchar(512) NOT NULL,
    file_type varchar(128),
    source_path varchar(2048),
    storage_size bigint,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0
);
COMMENT ON TABLE dh_asset_registration_source IS '资产注册的真实非表来源事实；不等同于可用交付合同';
COMMENT ON COLUMN dh_asset_registration_source.asset_id IS '资产编号，一项资产对应一个注册来源';
COMMENT ON COLUMN dh_asset_registration_source.source_kind IS '真实来源类型：2已发布 API，3当前有效文件元数据';
COMMENT ON COLUMN dh_asset_registration_source.source_id IS '提供方真实对象编号';
COMMENT ON COLUMN dh_asset_registration_source.source_version IS '注册时提供方发布编号或文件业务修订号';
COMMENT ON COLUMN dh_asset_registration_source.source_name IS '注册时提供方真实对象名称';
COMMENT ON COLUMN dh_asset_registration_source.file_type IS '文件提供方声明的文件类型；API 为空';
COMMENT ON COLUMN dh_asset_registration_source.source_path IS '提供方文件路径或 API 相对路径，不存连接凭据';
COMMENT ON COLUMN dh_asset_registration_source.storage_size IS '提供方文件大小，单位字节';
COMMENT ON COLUMN dh_asset_registration_source.creator IS '创建人';
COMMENT ON COLUMN dh_asset_registration_source.create_time IS '创建时间';
COMMENT ON COLUMN dh_asset_registration_source.updater IS '更新人';
COMMENT ON COLUMN dh_asset_registration_source.update_time IS '更新时间';
COMMENT ON COLUMN dh_asset_registration_source.deleted IS '逻辑删除：0未删除，1已删除';
CREATE INDEX IF NOT EXISTS idx_asset_registration_source_object ON dh_asset_registration_source(source_kind, source_id) WHERE deleted=0;
COMMIT;
