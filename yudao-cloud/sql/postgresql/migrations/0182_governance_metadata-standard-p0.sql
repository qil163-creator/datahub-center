-- Migration: 0182 元数据维护并发凭据与标准版本质量模板。
-- Purpose: 防止旧表单覆盖治理属性，隔离已确认删除对象的重建身份，修复物理命名空间，按标准版本区分质量模板并统一登记长整型引用。
-- Depends on: 0181_asset_remove-monitor-pages.sql 已执行；平台已完成单租户改造，元数据和标准质量表已存在。
-- Target: 已有 PostgreSQL 平台库；全新数据库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；列和检查约束按名称保护，索引在事务内重建，菜单按组件精确更新。
-- Rollback: 事务失败完整回滚；提交后回滚应用前应先停止维护与采集，不得缩窄登记编号、清空已修正命名空间或重置并发凭据。
-- 索引重建和字段类型扩展需要短时表锁；应在应用停止写入时执行，超时则整笔回滚并择期重试。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_governance_meta_table ADD COLUMN IF NOT EXISTS biz_revision integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_governance_meta_table.biz_revision IS '表业务属性并发修订号，独立于采集结构版本';
ALTER TABLE dh_governance_meta_db ADD COLUMN IF NOT EXISTS tag_revision integer NOT NULL DEFAULT 0;
ALTER TABLE dh_governance_meta_table ADD COLUMN IF NOT EXISTS tag_revision integer NOT NULL DEFAULT 0;
ALTER TABLE dh_governance_meta_column ADD COLUMN IF NOT EXISTS tag_revision integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_governance_meta_db.tag_revision IS '数据库元数据标签集合并发修订号';
COMMENT ON COLUMN dh_governance_meta_table.tag_revision IS '表元数据标签集合并发修订号';
COMMENT ON COLUMN dh_governance_meta_column.tag_revision IS '字段元数据标签集合并发修订号';

-- 已确认删除的对象保留为历史墓碑；同名对象再次采集时必须生成新的编号和资源标识。
-- 待确认删除对象仍占自然键，允许采集恢复同一身份；仅状态三释放自然键。
DROP INDEX IF EXISTS uk_dh_governance_meta_db_natural_key;
CREATE UNIQUE INDEX uk_dh_governance_meta_db_natural_key
    ON dh_governance_meta_db (datasource_id, db_name, schema_name)
    WHERE deleted = 0 AND object_status <> 3;
DROP INDEX IF EXISTS uk_dh_governance_meta_table_natural_key;
CREATE UNIQUE INDEX uk_dh_governance_meta_table_natural_key
    ON dh_governance_meta_table (db_id, table_name)
    WHERE deleted = 0 AND object_status <> 3;
DROP INDEX IF EXISTS uk_dh_governance_meta_column_natural_key;
CREATE UNIQUE INDEX uk_dh_governance_meta_column_natural_key
    ON dh_governance_meta_column (table_id, column_name)
    WHERE deleted = 0 AND object_status <> 3;

-- 旧采集、手工登记及导入统一把关系命名空间传为库分组名称，却遗漏 schema_name。
-- 按该生产者契约恢复已保存的精确名称，保留编号、URN、标签及映射；不猜默认 schema。
-- 同一自然键若已有另一活动身份，必须停止迁移并核对，不自动合并历史对象。
DO $p0_namespaces$
BEGIN
    IF EXISTS (
        SELECT 1 FROM dh_governance_meta_db legacy
        JOIN dh_governance_meta_db current_db
          ON current_db.datasource_id = legacy.datasource_id
         AND current_db.db_name = legacy.db_name
         AND current_db.schema_name = legacy.db_name
         AND current_db.id <> legacy.id
         AND current_db.deleted = 0 AND current_db.object_status <> 3
        WHERE legacy.deleted = 0 AND legacy.object_status <> 3
          AND legacy.origin IN (1, 2, 3) AND legacy.schema_name = ''
          AND btrim(legacy.db_name) <> ''
    ) THEN
        RAISE EXCEPTION '元数据库命名空间回填发现不同活动身份冲突，请核对后重新执行0182；禁止自动合并'
            USING ERRCODE = '23505';
    END IF;
END $p0_namespaces$;
UPDATE dh_governance_meta_db
SET schema_name = db_name, updater = 'migration:0182', update_time = CURRENT_TIMESTAMP
WHERE deleted = 0 AND origin IN (1, 2, 3) AND schema_name = '' AND btrim(db_name) <> '';
COMMENT ON COLUMN dh_governance_meta_db.schema_name IS 'DB Switch关系命名空间；schema型产品保存schema，catalog型产品保存catalog，与读取表时的输入一致';

-- 标准登记主键为长整型，数据元引用必须能无损保存同一编号。
ALTER TABLE dh_governance_standard_element ALTER COLUMN document_id TYPE bigint USING document_id::bigint;
COMMENT ON COLUMN dh_governance_standard_element.document_id IS '来源标准登记编号，与标准登记长整型主键保持一致';

-- 相同约束在多个发布版本中各有不可变模板，不能用新版本刷新旧版本的模板参数。
DROP INDEX IF EXISTS uk_dh_governance_quality_rule_std_constraint;
CREATE UNIQUE INDEX uk_dh_governance_quality_rule_std_constraint
    ON dh_governance_quality_rule (standard_element_id, element_version, constraint_hash)
    WHERE deleted = 0 AND standard_element_id IS NOT NULL;
COMMENT ON COLUMN dh_governance_quality_rule.constraint_hash IS '冻结约束哈希，与标准编号和固定发布版本共同构成模板幂等键';
DO $p0_checks$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'dh_governance_quality_rule'::regclass
                   AND conname = 'ck_quality_standard_template_identity') THEN
        ALTER TABLE dh_governance_quality_rule ADD CONSTRAINT ck_quality_standard_template_identity
            CHECK (standard_element_id IS NULL OR
                   (element_version IS NOT NULL AND element_version > 0
                    AND constraint_hash IS NOT NULL AND constraint_hash ~ '^[0-9a-f]{64}$'));
    END IF;
END $p0_checks$;

-- 仅调整现有入口名称；任务、实例、最新观测和已发布快照继续各自拥有独立页面及权限。
UPDATE system_menu SET name = '最新元数据（库表）', updater = 'migration:0182', update_time = CURRENT_TIMESTAMP
WHERE deleted = 0 AND type = 2 AND trim(both '/' from component) = 'governance/metaobject/index'
  AND name IS DISTINCT FROM '最新元数据（库表）';
UPDATE system_menu SET name = '定版元数据', updater = 'migration:0182', update_time = CURRENT_TIMESTAMP
WHERE deleted = 0 AND type = 2 AND trim(both '/' from component) = 'governance/metapublished/index'
  AND name IS DISTINCT FROM '定版元数据';
COMMIT;
