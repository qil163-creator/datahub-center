-- Migration: 0200 按参考截图重建水利行业标准分类。
-- Purpose: 清空现有共享标准类目，建立十个一级、五十六个二级类目，保留标准文件、数据元、代码表和业务术语本体。
-- Depends on: 0199_governance_standard-document-workbench.sql 已执行；0191 的标准引用及修订触发器、0195 的单平台共享类目已就绪。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 否；一次性全量替换，检测到本迁移创建的类目即拒绝执行，防止覆盖后续人工维护的数据。
-- Rollback: 失败整体回滚；提交后须从执行前备份恢复类目及四个标准表的分类、修订号和审计字段，保留序列高水位。
-- 不可逆操作：物理删除全部旧类目（含停用及逻辑删除记录），四个标准表的旧类目关联置空；不可变历史版本快照不重写。
-- 执行前备份上述五表及类目序列状态，并暂停相关编辑；不会级联删除任何标准业务记录。
-- 执行影响：事务内锁定五表及类目编号序列，锁等待十秒或单语句执行两分钟超时即整体回滚。
-- 数据来源：用户提供的五张标准分类管理截图；名称、两级关系、描述和从零开始的排序逐项对应，启用状态沿用平台协议零。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE dh_governance_standard_doc, dh_governance_standard_element,
    dh_governance_standard_code_set, dh_governance_standard_term,
    dh_governance_category_datameta IN SHARE ROW EXCLUSIVE MODE;

DO $preflight$
BEGIN
    IF EXISTS (SELECT 1 FROM dh_governance_category_datameta WHERE creator = 'migration:0200') THEN
        RAISE EXCEPTION '0200 水利标准分类已初始化，禁止再次清空';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_sequence
        WHERE seqrelid = 'dh_foundation_category_datameta_seq'::regclass AND seqincrement = 1
    ) THEN
        RAISE EXCEPTION '0200 类目编号序列不存在或步长不是一，禁止重建';
    END IF;
END $preflight$;

-- 在读取高水位之前取得序列事务锁，防止并发分配编号被重置。
ALTER SEQUENCE dh_foundation_category_datameta_seq INCREMENT BY 1;

DO $replace_categories$
DECLARE
    high_id bigint;
    root_id bigint;
    child_id bigint;
    root_item record;
    child_item record;
BEGIN
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM dh_foundation_category_datameta_seq))
    INTO high_id FROM dh_governance_category_datameta;
    IF high_id > 9223372036854775807 - 66 THEN
        RAISE EXCEPTION '0200 类目编号空间不足，禁止重建';
    END IF;
    EXECUTE format('ALTER SEQUENCE dh_foundation_category_datameta_seq RESTART WITH %s', high_id + 1);

    -- 旧分类与水利分类没有可靠映射，解除关联后由使用者重新分类；包含逻辑删除记录以满足外键。
    UPDATE dh_governance_standard_doc
    SET category_id = NULL, updater = 'migration:0200', update_time = CURRENT_TIMESTAMP
    WHERE category_id IS NOT NULL;
    UPDATE dh_governance_standard_element
    SET category_id = NULL, revision = revision + 1, updater = 'migration:0200', update_time = CURRENT_TIMESTAMP
    WHERE category_id IS NOT NULL;
    UPDATE dh_governance_standard_code_set
    SET category_id = NULL, revision = revision + 1, updater = 'migration:0200', update_time = CURRENT_TIMESTAMP
    WHERE category_id IS NOT NULL;
    UPDATE dh_governance_standard_term
    SET category_id = NULL, updater = 'migration:0200', update_time = CURRENT_TIMESTAMP
    WHERE category_id IS NOT NULL;

    DELETE FROM dh_governance_category_datameta;

    -- 新编号均超过旧类目及序列高水位，历史版本中的旧编号不会误指向新的类目。
    FOR root_item IN SELECT * FROM (VALUES
        (0, '基础通用', '归集水利行业通用基础、数据治理和共享交换方面的标准文件。',
            ARRAY['术语符号与计量','分类编码与标识','数据元与指标','元数据与目录','交换与共享']),
        (1, '水文监测与预报', '归集水文站网、测验监测、资料整编和预报分析方面的标准文件。',
            ARRAY['水文站网与测站','水文测验与监测','水情编码与报送','水文资料整编','水文预报与分析']),
        (2, '水资源管理', '归集水资源调查、取用水监管、配置调度和节约用水方面的标准文件。',
            ARRAY['调查评价','取用水许可','计量监测','配置调度','节约用水','地下水管理']),
        (3, '水利工程', '归集水利工程勘测设计、建设施工、安全监测和运行维护方面的标准文件。',
            ARRAY['勘测设计','建设施工','质量验收','安全监测','运行调度','维修养护']),
        (4, '水旱灾害防御', '归集洪水、干旱、山洪灾害的监测预警、调度和应急处置方面的标准文件。',
            ARRAY['洪水监测预报','风险预警','防洪调度','山洪防治','抗旱管理','应急处置']),
        (5, '河湖与水生态', '归集河湖空间管理、水域监管、水生态保护和生态流量方面的标准文件。',
            ARRAY['河湖名录与岸线','河湖长制','水域监管','生态流量','水生态','水质评价']),
        (6, '农村水利', '归集灌溉排水、灌区建设、农村供水和农业节水方面的标准文件。',
            ARRAY['灌溉排水','灌区管理','农村供水','泵站运行','农业节水']),
        (7, '水土保持', '归集水土流失调查、方案管理、监测评价和治理工程方面的标准文件。',
            ARRAY['调查规划','方案与验收','监测评价','治理工程','遥感监管']),
        (8, '智慧水利', '归集水利信息化、数据资源、空间信息、物联网和网络安全方面的标准文件。',
            ARRAY['信息化建设','数据资源','地理信息与遥感','自动化与物联网','数据服务','网络安全']),
        (9, '水行政与综合管理', '归集水行政许可、执法监督、规划项目、统计档案和移民管理方面的标准文件。',
            ARRAY['行政许可','执法监督','规划项目','水利统计','档案管理','水库移民'])
    ) AS seed(sort, category_name, remark, children) ORDER BY sort
    LOOP
        root_id := nextval('dh_foundation_category_datameta_seq');
        INSERT INTO dh_governance_category_datameta
            (id, parent_id, category_name, category_code, sort, status, remark, creator, updater, deleted)
        VALUES (root_id, 0, root_item.category_name, '0,' || root_id, root_item.sort, 0, root_item.remark,
            'migration:0200', 'migration:0200', 0);

        FOR child_item IN SELECT name, ordinal - 1 AS sort
            FROM unnest(root_item.children) WITH ORDINALITY AS child(name, ordinal) ORDER BY ordinal
        LOOP
            child_id := nextval('dh_foundation_category_datameta_seq');
            INSERT INTO dh_governance_category_datameta
                (id, parent_id, category_name, category_code, sort, status, remark, creator, updater, deleted)
            VALUES (child_id, root_id, child_item.name, '0,' || root_id || ',' || child_id,
                child_item.sort, 0, '归集水利行业' || child_item.name || '相关标准文件，用于标准登记、检索和统一管理。',
                'migration:0200', 'migration:0200', 0);
        END LOOP;
    END LOOP;

    IF (SELECT count(*) FROM dh_governance_category_datameta) <> 66
        OR (SELECT count(*) FROM dh_governance_category_datameta WHERE parent_id = 0) <> 10 THEN
        RAISE EXCEPTION '0200 分类数量校验失败，整体回滚';
    END IF;
END $replace_categories$;

COMMIT;
