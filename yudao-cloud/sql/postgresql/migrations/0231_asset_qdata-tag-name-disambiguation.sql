-- Migration: 0231 来源重名标签增加类目后缀。
-- Purpose: 为来源四十和四十三的标签补充类目名称，保留一百六十个标签及一百三十九条来源关联。
-- Depends on: 0230_asset_qdata-tags.sql 已完整提交，来源标签和类目身份均已建立。
-- Target: 已完成标签来源导入的 PostgreSQL 平台库；新库使用包含本迁移的完整基线。
-- Repeatable: 是；目标名称已存在于同一来源记录时不更新，不推进修订号；人工改名、移动类目或删除时失败并回滚。
-- Rollback: 全部操作处于单一事务，失败完整回滚；提交后如需恢复名称，须以新编号迁移核对后续修订与引用，不能覆盖人工修改。
-- 修正依据：用户选择以类目后缀区分来源重名，来源编号、分类和关联保持不变；不关闭数据库保护触发器。
BEGIN;
SET LOCAL search_path TO "datahub-center",pg_catalog;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
SELECT pg_advisory_xact_lock(1500,34);
LOCK TABLE dh_asset_category_tag,dh_asset_tag,dh_asset_tag_rel IN SHARE ROW EXCLUSIVE MODE;

DO $migration$
DECLARE
    item record;
    tag_record record;
    affected integer;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('40','生态流量监管','生态流量监管（应用场景）','QDATA_TAG_A01A04'),
        ('43','水资源管理','水资源管理（数据主题）','QDATA_TAG_A02A01')
    ) AS source_tags(source_id,original_name,target_name,category_code) LOOP
        SELECT t.id,t.name,t.status,t.revision,t.deleted,t.updater,c.category_code
        INTO tag_record
        FROM dh_asset_tag t
        JOIN dh_asset_category_tag c ON c.id=t.category_id AND c.deleted=0
        WHERE t.source_record_id=item.source_id
        FOR UPDATE OF t;
        IF NOT FOUND THEN
            RAISE EXCEPTION '来源标签或类目缺失：%',item.source_id;
        END IF;
        IF tag_record.deleted<>0 OR tag_record.category_code<>item.category_code THEN
            RAISE EXCEPTION '来源标签已删除或类目已改变，禁止覆盖：%',item.source_id;
        END IF;
        IF tag_record.name=item.target_name THEN
            CONTINUE;
        END IF;
        IF tag_record.name<>item.original_name OR tag_record.revision<>0
            OR tag_record.updater<>'migration:0230' THEN
            RAISE EXCEPTION '来源标签已被维护，禁止覆盖：%',item.source_id;
        END IF;
        IF EXISTS(SELECT 1 FROM dh_asset_tag WHERE name=item.target_name AND deleted=0 AND id<>tag_record.id) THEN
            RAISE EXCEPTION '来源标签的目标名称已占用：%',item.source_id;
        END IF;

        -- 先只调整启停状态，再修改名称并恢复原状态，遵守启用标签的内容保护；中间状态不对事务外可见。
        IF tag_record.status=0 THEN
            UPDATE dh_asset_tag
            SET status=1,revision=revision+1,updater='migration:0231',update_time=CURRENT_TIMESTAMP
            WHERE id=tag_record.id AND revision=tag_record.revision;
            GET DIAGNOSTICS affected=ROW_COUNT;
            IF affected<>1 THEN RAISE EXCEPTION '来源标签修订冲突：%',item.source_id; END IF;
            tag_record.revision:=tag_record.revision+1;
        END IF;
        UPDATE dh_asset_tag
        SET name=item.target_name,status=tag_record.status,revision=revision+1,
            source_updater_name=NULL,updater='migration:0231',update_time=CURRENT_TIMESTAMP
        WHERE id=tag_record.id AND revision=tag_record.revision;
        GET DIAGNOSTICS affected=ROW_COUNT;
        IF affected<>1 THEN RAISE EXCEPTION '来源标签修订冲突：%',item.source_id; END IF;
    END LOOP;

    IF (SELECT count(*) FROM dh_asset_tag WHERE source_record_id IN ('40','43') AND deleted=0
        AND ((source_record_id='40' AND name='生态流量监管（应用场景）')
          OR (source_record_id='43' AND name='水资源管理（数据主题）')))<>2 THEN
        RAISE EXCEPTION '来源重名标签修正不完整';
    END IF;
END;
$migration$;
COMMIT;
