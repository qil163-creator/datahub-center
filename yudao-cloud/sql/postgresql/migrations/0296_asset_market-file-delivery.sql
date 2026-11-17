-- Migration: 0296
-- Purpose: 将资产交付文件不可变绑定至已发布目录版本，记录申请的实际下载次数。
-- Depends on: 资产目录版本、市场申请和受管文件基础设施已部署。
-- Target: PostgreSQL 数据中台单租户业务库。
-- Repeatable: 可重复执行；不为已有申请回填文件凭据。
-- Rollback: 停止应用写入后备份新增表；删除将丢失交付身份及消费记录。
BEGIN;
CREATE TABLE IF NOT EXISTS dh_asset_market_file_binding (
    catalog_version_id bigint PRIMARY KEY REFERENCES dh_asset_catalog_version(id),
    asset_id bigint NOT NULL REFERENCES dh_asset_data_asset(id),
    operation_key varchar(128) NOT NULL UNIQUE,
    file_name varchar(180) NOT NULL,
    file_size bigint NOT NULL CHECK (file_size BETWEEN 1 AND 20971520),
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    managed_file_id bigint UNIQUE,
    uploader_id bigint NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE dh_asset_market_file_binding IS '市场资产私有交付文件，一份目录版本仅可绑定同一内容';
COMMENT ON COLUMN dh_asset_market_file_binding.catalog_version_id IS '不可变目录版本编号';
COMMENT ON COLUMN dh_asset_market_file_binding.asset_id IS '文件所属资产编号';
COMMENT ON COLUMN dh_asset_market_file_binding.operation_key IS '受管上传幂等操作编号';
COMMENT ON COLUMN dh_asset_market_file_binding.file_name IS '审核及下载展示名称';
COMMENT ON COLUMN dh_asset_market_file_binding.file_size IS '已绑定文件的字节数';
COMMENT ON COLUMN dh_asset_market_file_binding.sha256 IS '已绑定文件内容摘要';
COMMENT ON COLUMN dh_asset_market_file_binding.managed_file_id IS '基础设施受管文件编号，上传待确认时为空';
COMMENT ON COLUMN dh_asset_market_file_binding.uploader_id IS '上传时的提供方用户编号';
COMMENT ON COLUMN dh_asset_market_file_binding.create_time IS '初次登记时间';
CREATE TABLE IF NOT EXISTS dh_asset_market_file_usage (
    apply_id bigint PRIMARY KEY REFERENCES dh_market_resource_apply(id),
    download_count bigint NOT NULL CHECK (download_count > 0),
    last_used_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE dh_asset_market_file_usage IS '市场文件按申请独立统计的下载使用记录';
COMMENT ON COLUMN dh_asset_market_file_usage.apply_id IS '审核通过的申请编号';
COMMENT ON COLUMN dh_asset_market_file_usage.download_count IS '已消费的下载次数';
COMMENT ON COLUMN dh_asset_market_file_usage.last_used_at IS '最近一次下载时间';
COMMIT;
