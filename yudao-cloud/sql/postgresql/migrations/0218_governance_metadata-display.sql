-- Migration: 0218 元数据业务属性与可信导入展示快照。
-- Purpose: 补充独立临时表、存储类型业务属性及六类元数据可信导入展示快照，保留敏感表属性及不可变定版语义。
-- Depends on: 0217_dataforge_operations-instances.sql；平台已具备当前单租户元数据表及业务修订号。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的全量基线。
-- Repeatable: 是；按列和约束存在性补齐，重复执行不修改业务值或历史定版快照。
-- Rollback: 事务失败自动回滚；提交后删除 temporary_flag/storage_type/display_snapshot 列会不可逆丢弃业务属性和源端展示信息，回滚前须备份。
-- 加法式 DDL 获取短时表锁，等待超过十秒即失败；不回填或重写历史冻结镜像。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE dh_governance_meta_table
    ADD COLUMN IF NOT EXISTS temporary_flag smallint NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_governance_meta_table.temporary_flag IS '是否临时表：0-否，1-是；平台业务属性，采集不覆盖';
ALTER TABLE dh_governance_meta_table ADD COLUMN IF NOT EXISTS storage_type varchar(128);
COMMENT ON COLUMN dh_governance_meta_table.storage_type IS '存储类型；平台业务属性，独立于采集存储引擎';

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'dh_governance_meta_table'::regclass
          AND conname = 'ck_governance_meta_table_temporary_flag'
    ) THEN
        ALTER TABLE dh_governance_meta_table
            ADD CONSTRAINT ck_governance_meta_table_temporary_flag CHECK (temporary_flag IN (0, 1));
    END IF;
END
$migration$;

ALTER TABLE dh_governance_meta_db ADD COLUMN IF NOT EXISTS display_snapshot jsonb;
COMMENT ON COLUMN dh_governance_meta_db.display_snapshot IS '可信离线导入的只读展示快照；不参与本平台身份授权或连接执行';
ALTER TABLE dh_governance_meta_table ADD COLUMN IF NOT EXISTS display_snapshot jsonb;
COMMENT ON COLUMN dh_governance_meta_table.display_snapshot IS '可信离线导入的只读展示快照；不参与本平台身份授权或连接执行';
ALTER TABLE dh_governance_meta_column ADD COLUMN IF NOT EXISTS display_snapshot jsonb;
COMMENT ON COLUMN dh_governance_meta_column.display_snapshot IS '可信离线导入的只读展示快照；不参与本平台身份授权或连接执行';
ALTER TABLE dh_governance_meta_collect_task ADD COLUMN IF NOT EXISTS display_snapshot jsonb;
COMMENT ON COLUMN dh_governance_meta_collect_task.display_snapshot IS '可信离线导入的只读展示快照；不参与本平台身份授权或连接执行';
ALTER TABLE dh_governance_meta_collect_instance ADD COLUMN IF NOT EXISTS display_snapshot jsonb;
COMMENT ON COLUMN dh_governance_meta_collect_instance.display_snapshot IS '可信离线导入的只读展示快照；不参与本平台身份授权或连接执行';
ALTER TABLE dh_governance_meta_file ADD COLUMN IF NOT EXISTS display_snapshot jsonb;
COMMENT ON COLUMN dh_governance_meta_file.display_snapshot IS '可信离线导入的只读展示快照；不参与本平台身份授权或连接执行';

COMMIT;
