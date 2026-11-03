-- Migration: 0195 重建市政务标准数据元类目。
-- Purpose: 清空误用的清洗规则分类，建立七个公共数据元对象根类及二十八个平台管理子类，解除旧分类引用并保留标准本体。
-- Depends on: 0194_governance_remove-standard-naming.sql 已执行，或已核验平台达到对应状态；0191 的共享分类、修订号和引用触发器已生效。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 否；一次性全量替换，检测到本迁移创建的类目即拒绝再次执行，避免覆盖后续维护的数据。
-- Rollback: 失败整笔回滚；提交后须从执行前备份恢复类目表及三个标准表的分类、修订号和审计字段，保留序列高水位。
-- 不可逆操作：物理删除全部现有类目，包括停用和逻辑删除记录；三个标准表的旧分类全部置空，历史版本快照不重写。
-- 执行前备份类目、标准数据元、标准码表和业务术语四表，并暂停相关编辑；不级联删除其他业务记录。
-- 事务内锁定四表及编号序列，锁等待超过十秒或语句执行超过两分钟则回滚。
-- 分类依据：GB/T 19488.2-2008 第 4.2 条的人员、机构、位置、时间、公文、金融、其他七类；二级为平台管理细分。
-- 国家标准信息：https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=C908E4E99C95626962B7C6648816CBFB
-- 类目主键及层级路径只用于平台管理，不作为国家标准的数据元内部标识符。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE dh_governance_standard_element, dh_governance_standard_code_set,
    dh_governance_standard_term, dh_governance_category_datameta IN SHARE ROW EXCLUSIVE MODE;

DO $preflight$
BEGIN
    IF EXISTS (SELECT 1 FROM dh_governance_category_datameta WHERE creator = 'migration:0195') THEN
        RAISE EXCEPTION '0195 标准数据元类目已初始化，禁止再次清空';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_sequence
        WHERE seqrelid = 'dh_foundation_category_datameta_seq'::regclass AND seqincrement = 1
    ) THEN
        RAISE EXCEPTION '0195 类目编号序列不存在或步长不是一，禁止重建';
    END IF;
END $preflight$;

-- 获取序列事务锁后再读取高水位；事务式重启避免回退并发已分配的编号。
ALTER SEQUENCE dh_foundation_category_datameta_seq INCREMENT BY 1;

DO $reset_categories$
DECLARE
    high_id bigint;
    new_id bigint;
    parent_id bigint;
    category_path text;
    new_ids bigint[] := ARRAY[]::bigint[];
    item record;
BEGIN
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM dh_foundation_category_datameta_seq))
    INTO high_id FROM dh_governance_category_datameta;
    IF high_id > 9223372036854775807 - 35 THEN
        RAISE EXCEPTION '0195 类目编号空间不足，禁止重建';
    END IF;
    EXECUTE format('ALTER SEQUENCE dh_foundation_category_datameta_seq RESTART WITH %s', high_id + 1);

    -- 原分类没有可靠的政务语义映射，当前标准归为未分类；修订触发器使并发旧表单失效。
    UPDATE dh_governance_standard_element
    SET category_id = NULL, revision = revision + 1, updater = 'migration:0195', update_time = CURRENT_TIMESTAMP
    WHERE category_id IS NOT NULL;
    UPDATE dh_governance_standard_code_set
    SET category_id = NULL, revision = revision + 1, updater = 'migration:0195', update_time = CURRENT_TIMESTAMP
    WHERE category_id IS NOT NULL;
    UPDATE dh_governance_standard_term
    SET category_id = NULL, updater = 'migration:0195', update_time = CURRENT_TIMESTAMP
    WHERE category_id IS NOT NULL;

    DELETE FROM dh_governance_category_datameta;

    -- 父类先于子类分配新编号，全部编号高于旧类目及序列高水位，避免历史快照误认新分类。
    FOR item IN SELECT * FROM (VALUES
        (1, 0, '人员类', 1, '依据 GB/T 19488.2-2008 第4.2条设置，管理自然人相关公共数据元。'),
        (2, 1, '身份标识', 1, '平台管理子类：姓名、身份证件类型、证件号码等自然人身份标识。'),
        (3, 1, '人口特征', 2, '平台管理子类：性别、民族、国籍、婚姻状况等人口基本特征。'),
        (4, 1, '教育与专业', 3, '平台管理子类：学历、学位、所学专业、专业技术资格等信息。'),
        (5, 1, '从业与任职', 4, '平台管理子类：职业、职务、职级、就业状态等从业任职信息。'),
        (6, 1, '联系信息', 5, '平台管理子类：联系电话、电子邮箱等自然人联系信息，地址归入位置类。'),
        (7, 0, '机构类', 2, '依据 GB/T 19488.2-2008 第4.2条设置，管理法人及其他组织相关公共数据元。'),
        (8, 7, '机构标识', 1, '平台管理子类：统一社会信用代码、组织机构代码等机构身份标识。'),
        (9, 7, '机构名称', 2, '平台管理子类：机构全称、简称、曾用名等机构名称信息。'),
        (10, 7, '机构类型', 3, '平台管理子类：机构类别、组织形式、经济类型等机构分类信息。'),
        (11, 7, '行业与经营', 4, '平台管理子类：行业类别、经营范围、登记状态等机构经营属性。'),
        (12, 7, '设立与注销', 5, '平台管理子类：登记机关、设立方式、注销原因等登记管理属性，具体日期归入时间类。'),
        (13, 0, '位置类', 3, '依据 GB/T 19488.2-2008 第4.2条设置，管理行政区划、地理位置及地址相关公共数据元。'),
        (14, 13, '行政区划', 1, '平台管理子类：行政区划代码、行政区划名称、乡镇街道及村社区标识。'),
        (15, 13, '地理位置', 2, '平台管理子类：经度、纬度、空间位置标识等地理定位信息。'),
        (16, 13, '地址信息', 3, '平台管理子类：标准地址、道路名称、门楼牌号、邮政编码等地址要素。'),
        (17, 0, '时间类', 4, '依据 GB/T 19488.2-2008 第4.2条设置，管理日期、时刻及日期时间相关公共数据元。'),
        (18, 17, '日期', 1, '平台管理子类：出生日期、成立日期、办理日期、有效期起止日期等日历日期。'),
        (19, 17, '时刻', 2, '平台管理子类：受理时刻、办结时刻等不含日期的日内时间。'),
        (20, 17, '日期时间', 3, '平台管理子类：登记时间、更新时间、事件发生时间等完整日期时间。'),
        (21, 0, '公文类', 5, '依据 GB/T 19488.2-2008 第4.2条设置，管理政务公文及其流转相关公共数据元。'),
        (22, 21, '公文标识', 1, '平台管理子类：公文标识、发文字号、文种等公文识别信息。'),
        (23, 21, '标题与主题', 2, '平台管理子类：公文标题、主题词、内容摘要等主题信息。'),
        (24, 21, '行文主体', 3, '平台管理子类：发文机关、主送机关、抄送机关等行文主体信息。'),
        (25, 21, '密级与紧急程度', 4, '平台管理子类：公文密级、保密期限、紧急程度等管理属性。'),
        (26, 21, '印发与流转', 5, '平台管理子类：印发机构、份号、份数、流转状态等印发流转属性，具体日期归入时间类。'),
        (27, 0, '金融类', 6, '依据 GB/T 19488.2-2008 第4.2条设置，管理货币、金额、账户及汇率相关公共数据元。'),
        (28, 27, '币种与货币代码', 1, '平台管理子类：货币名称、货币字母代码、货币数字代码等币种信息。'),
        (29, 27, '金额与资金', 2, '平台管理子类：金额、资金数额、收支金额等货币度量信息。'),
        (30, 27, '银行账户', 3, '平台管理子类：银行账号、账户名称、开户银行等银行账户信息。'),
        (31, 27, '汇率', 4, '平台管理子类：汇率数值、汇率种类、折算比例等货币兑换信息。'),
        (32, 0, '其他类', 7, '依据 GB/T 19488.2-2008 第4.2条设置，管理前六类之外的其他公共数据元。'),
        (33, 32, '物品与商品', 1, '平台管理子类：物品名称、商品标识、规格型号等物品与商品属性。'),
        (34, 32, '计量单位', 2, '平台管理子类：计量单位名称、单位符号、计量单位代码等单位信息。'),
        (35, 32, '通用描述', 3, '平台管理子类：备注、说明等无法归入其他对象类的通用描述信息。')
    ) AS seed(seed_key, parent_key, category_name, sort, remark) ORDER BY seed_key
    LOOP
        new_id := nextval('dh_foundation_category_datameta_seq');
        new_ids[item.seed_key] := new_id;
        parent_id := CASE WHEN item.parent_key = 0 THEN 0 ELSE new_ids[item.parent_key] END;
        category_path := CASE WHEN parent_id = 0 THEN '0,' || new_id
            ELSE '0,' || parent_id || ',' || new_id END;
        INSERT INTO dh_governance_category_datameta
            (id, parent_id, category_name, category_code, sort, status, remark, creator, updater, deleted)
        VALUES (new_id, parent_id, item.category_name, category_path, item.sort, 0, item.remark,
            'migration:0195', 'migration:0195', 0);
    END LOOP;
END $reset_categories$;

COMMIT;
