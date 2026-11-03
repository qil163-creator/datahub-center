-- Migration: 0204 按业务语义重建业务分类与数据域关联。
-- Purpose: 清理四十八个预置业务分类的错配关联，依据分类名称、描述及现有二十二个数据域定义重新填充。
-- Depends on: 0203_governance_audit-rule-dimensions.sql 已执行；0136_modeling_biz-category-data-domain-rel.sql 关系表已恢复，平台已完成单租户升级。
-- Target: 已有 PostgreSQL 平台库，保留四十八个预置分类及二十二个启用数据域；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；按编码和名称解析实际编号，仅重建关联集合或排序存在差异的分类，重复执行不新增历史记录或推进序列。
-- Rollback: 单事务失败完整回滚；执行本迁移前必须备份关系表，提交后按备份恢复有效关联及其审计字段，保留已分配编号高水位。
-- 事务边界：短时阻塞分类、数据域及关系写入，锁等待五秒或语句执行六十秒超时即整体失败。
-- 关联使用逻辑删除保留旧记录；不修改分类定义、数据域定义、模型归属及自定义分类关系。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

LOCK TABLE dh_modeling_biz_category, dh_modeling_data_domain IN SHARE MODE;
LOCK TABLE dh_modeling_biz_category_data_domain_rel IN SHARE ROW EXCLUSIVE MODE;

-- 公共信用与公共财政的历史编码不同，必须同时核对名称，不能按英文编码猜测含义。
CREATE TEMP TABLE biz_domain_definition (
    code varchar(100) PRIMARY KEY,
    name varchar(100) NOT NULL
) ON COMMIT DROP;
INSERT INTO biz_domain_definition (code, name) VALUES
    ('POPULATION', '人口基础数据域'),
    ('LEGAL_ENTITY', '法人单位数据域'),
    ('GOV_ORG', '政务组织机构数据域'),
    ('GEO_SPACE', '时空地理数据域'),
    ('E_CERT', '电子证照数据域'),
    ('PUBLIC_FINANCE', '公共信用数据域'),
    ('MACRO_ECON', '宏观经济数据域'),
    ('PUBLIC_FINANCES', '公共财政数据域'),
    ('EMP_SOC_SEC', '就业社保数据域'),
    ('HEALTH', '医疗健康数据域'),
    ('EDUCATION', '教育数据域'),
    ('NAT_RESOURCE', '自然资源数据域'),
    ('HOUSING_BUILD', '住房建设数据域'),
    ('TRANSPORT', '交通运输数据域'),
    ('ECO_ENV', '生态环境数据域'),
    ('AGRI_RURAL', '农业农村数据域'),
    ('CITY_OPERATION', '城市运行数据域'),
    ('EMERGENCY_SAFETY', '应急安全数据域'),
    ('WEATHER_HYDRO', '气象水文数据域'),
    ('CULTURE_TOURISM', '文旅体育数据域'),
    ('PUBLIC_APPEAL', '政务热线与社会诉求数据域'),
    ('MARKET_REG', '市场监管数据域');

CREATE TEMP TABLE biz_domain_assignment (
    category_code varchar(100) PRIMARY KEY,
    category_name varchar(100) NOT NULL,
    domain_codes text[] NOT NULL
) ON COMMIT DROP;
-- 主业务域优先，后续域仅补充描述明确涉及的主体、空间、证照或交叉业务支撑。
INSERT INTO biz_domain_assignment (category_code, category_name, domain_codes) VALUES
    -- 政务协同使用组织机构；审批制证使用电子证照；资源交易覆盖采购、土地和工程。
    ('BC_GOV', '政务运行与政务服务', ARRAY['GOV_ORG','E_CERT','PUBLIC_FINANCES','NAT_RESOURCE','HOUSING_BUILD']),
    ('BC_GOV_APPROVAL', '行政审批服务', ARRAY['E_CERT','POPULATION','LEGAL_ENTITY','GOV_ORG']),
    ('BC_GOV_COLLAB', '政务协同办公', ARRAY['GOV_ORG']),
    ('BC_GOV_TRANSACTION', '公共资源交易', ARRAY['PUBLIC_FINANCES','NAT_RESOURCE','HOUSING_BUILD','LEGAL_ENTITY']),
    -- 宏观监测与产业运行对应经济指标及企业主体，投资项目补充工程和土地资源。
    ('BC_ECON', '经济发展', ARRAY['MACRO_ECON','LEGAL_ENTITY','HOUSING_BUILD','NAT_RESOURCE']),
    ('BC_ECON_MONITOR', '宏观经济监测', ARRAY['MACRO_ECON']),
    ('BC_ECON_INDUSTRY', '产业运行管理', ARRAY['MACRO_ECON','LEGAL_ENTITY']),
    ('BC_ECON_PROJECT', '投资项目管理', ARRAY['HOUSING_BUILD','NAT_RESOURCE','LEGAL_ENTITY']),
    -- 市场准入、检查和产品安全归市场监管，处罚信用及投诉举报使用对应交叉域。
    ('BC_MARKET', '市场监督管理', ARRAY['MARKET_REG','LEGAL_ENTITY','PUBLIC_FINANCE']),
    ('BC_MARKET_ACCESS', '市场准入管理', ARRAY['MARKET_REG','LEGAL_ENTITY','E_CERT']),
    ('BC_MARKET_INSPECT', '综合监管执法', ARRAY['MARKET_REG','PUBLIC_FINANCE','PUBLIC_APPEAL','LEGAL_ENTITY']),
    ('BC_MARKET_QUALITY', '质量与食品药品安全', ARRAY['MARKET_REG','HEALTH']),
    -- 网格涉及人员、房屋、空间和事件；热线与调解直接使用社会诉求域。
    ('BC_SOCIAL', '社会治理', ARRAY['PUBLIC_APPEAL','CITY_OPERATION','POPULATION','GEO_SPACE','HOUSING_BUILD']),
    ('BC_SOCIAL_GRID', '基层网格治理', ARRAY['PUBLIC_APPEAL','CITY_OPERATION','POPULATION','GEO_SPACE','HOUSING_BUILD']),
    ('BC_SOCIAL_HOTLINE', '12345热线服务', ARRAY['PUBLIC_APPEAL']),
    ('BC_SOCIAL_MEDIATION', '信访与矛盾调解', ARRAY['PUBLIC_APPEAL']),
    -- 现有域未单设民政救助和医保，人口、社保待遇及医疗健康仅表达已有数据支撑，不新增专域。
    ('BC_LIVELIHOOD', '民生保障', ARRAY['EMP_SOC_SEC','POPULATION','HEALTH']),
    ('BC_LIVE_EMPLOYMENT', '就业与社会保障', ARRAY['EMP_SOC_SEC','POPULATION']),
    ('BC_LIVE_ASSISTANCE', '社会救助与养老服务', ARRAY['POPULATION','EMP_SOC_SEC','HEALTH']),
    ('BC_LIVE_MEDICAL_INS', '医疗保障', ARRAY['EMP_SOC_SEC','HEALTH','POPULATION']),
    ('BC_EDU_HEALTH', '教育卫生', ARRAY['EDUCATION','HEALTH']),
    ('BC_EDUCATION', '教育管理', ARRAY['EDUCATION','POPULATION']),
    ('BC_PUBLIC_HEALTH', '公共卫生', ARRAY['HEALTH','POPULATION']),
    ('BC_MEDICAL_SERVICE', '医疗服务管理', ARRAY['HEALTH']),
    ('BC_URBAN', '城乡建设与自然资源', ARRAY['NAT_RESOURCE','HOUSING_BUILD','CITY_OPERATION','GEO_SPACE']),
    ('BC_URBAN_PLANNING', '国土空间规划', ARRAY['NAT_RESOURCE','GEO_SPACE']),
    ('BC_URBAN_CONSTRUCTION', '工程建设与房地产', ARRAY['HOUSING_BUILD','LEGAL_ENTITY','E_CERT']),
    ('BC_URBAN_MUNICIPAL', '市政设施管理', ARRAY['CITY_OPERATION','GEO_SPACE']),
    ('BC_TRANSPORT', '交通运输与出行', ARRAY['TRANSPORT','CITY_OPERATION','GEO_SPACE']),
    ('BC_TRANS_PUBLIC', '公共交通管理', ARRAY['TRANSPORT','GEO_SPACE']),
    ('BC_TRANS_ROAD', '道路运输管理', ARRAY['TRANSPORT','LEGAL_ENTITY','E_CERT']),
    ('BC_TRANS_OPERATION', '交通运行与停车治理', ARRAY['TRANSPORT','CITY_OPERATION','GEO_SPACE']),
    -- 污染防治以生态环境为主，水环境补充气象水文，生活垃圾补充城市运行。
    ('BC_ECO', '生态环境保护', ARRAY['ECO_ENV','NAT_RESOURCE','WEATHER_HYDRO','GEO_SPACE','CITY_OPERATION']),
    ('BC_ECO_AIR_WATER', '大气与水环境治理', ARRAY['ECO_ENV','WEATHER_HYDRO','GEO_SPACE']),
    ('BC_ECO_WASTE', '土壤与固体废物治理', ARRAY['ECO_ENV','CITY_OPERATION']),
    ('BC_ECO_PROTECTION', '生态保护与修复', ARRAY['ECO_ENV','NAT_RESOURCE','GEO_SPACE']),
    ('BC_AGRI', '农业农村与乡村振兴', ARRAY['AGRI_RURAL','MARKET_REG','WEATHER_HYDRO','NAT_RESOURCE','GEO_SPACE']),
    ('BC_AGRI_PRODUCTION', '农业生产管理', ARRAY['AGRI_RURAL','WEATHER_HYDRO','NAT_RESOURCE']),
    ('BC_AGRI_QUALITY', '农产品质量安全', ARRAY['AGRI_RURAL','MARKET_REG']),
    ('BC_AGRI_RURAL', '乡村建设与农村治理', ARRAY['AGRI_RURAL','GEO_SPACE']),
    ('BC_CULTURE', '文化旅游与体育', ARRAY['CULTURE_TOURISM','MARKET_REG']),
    ('BC_CULTURE_PUBLIC', '公共文化服务', ARRAY['CULTURE_TOURISM']),
    ('BC_TOURISM', '旅游服务与市场监管', ARRAY['CULTURE_TOURISM','MARKET_REG','LEGAL_ENTITY']),
    ('BC_SPORTS', '全民健身与体育赛事', ARRAY['CULTURE_TOURISM']),
    ('BC_EMERGENCY', '应急管理与公共安全', ARRAY['EMERGENCY_SAFETY','WEATHER_HYDRO','NAT_RESOURCE','GEO_SPACE']),
    ('BC_SAFE_PRODUCTION', '安全生产监管', ARRAY['EMERGENCY_SAFETY','LEGAL_ENTITY']),
    ('BC_DISASTER', '自然灾害防御', ARRAY['EMERGENCY_SAFETY','WEATHER_HYDRO','NAT_RESOURCE','GEO_SPACE']),
    ('BC_EMERGENCY_RESPONSE', '应急处置与消防救援', ARRAY['EMERGENCY_SAFETY','GEO_SPACE']);

-- 编码重名、名称漂移、缺失或禁用必须在清理旧关联前拒绝，避免不完整填充。
DO $preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM biz_domain_assignment seed
        LEFT JOIN dh_modeling_biz_category category ON category.code = seed.category_code AND category.deleted = 0
        GROUP BY seed.category_code, seed.category_name
        HAVING count(category.id) <> 1 OR min(category.name) IS DISTINCT FROM seed.category_name
    ) THEN
        RAISE EXCEPTION '预置业务分类缺失、编码重复或名称已变更，请重新核对业务分类语义';
    END IF;
    IF EXISTS (
        SELECT 1 FROM biz_domain_definition seed
        LEFT JOIN dh_modeling_data_domain domain ON domain.code = seed.code AND domain.deleted = 0
        GROUP BY seed.code, seed.name
        HAVING count(domain.id) <> 1 OR min(domain.name) IS DISTINCT FROM seed.name OR min(domain.status) IS DISTINCT FROM 0
    ) THEN
        RAISE EXCEPTION '数据域缺失、禁用、编码重复或名称已变更，请重新核对数据域语义';
    END IF;
    IF EXISTS (
        SELECT 1 FROM biz_domain_assignment seed CROSS JOIN LATERAL unnest(seed.domain_codes) item(code)
        LEFT JOIN biz_domain_definition definition ON definition.code = item.code
        WHERE definition.code IS NULL
    ) THEN
        RAISE EXCEPTION '业务分类映射包含未定义的数据域编码';
    END IF;
END $preflight$;

CREATE TEMP TABLE biz_domain_desired ON COMMIT DROP AS
SELECT category.id AS biz_category_id, domain.id AS data_domain_id, (item.ordinality - 1)::integer AS sort_order
FROM biz_domain_assignment seed
JOIN dh_modeling_biz_category category ON category.code = seed.category_code AND category.deleted = 0
CROSS JOIN LATERAL unnest(seed.domain_codes) WITH ORDINALITY item(code, ordinality)
JOIN dh_modeling_data_domain domain ON domain.code = item.code AND domain.deleted = 0 AND domain.status = 0;
ALTER TABLE biz_domain_desired ADD PRIMARY KEY (biz_category_id, data_domain_id);

CREATE TEMP TABLE biz_domain_rebuild ON COMMIT DROP AS
SELECT category.id
FROM dh_modeling_biz_category category
JOIN biz_domain_assignment seed ON seed.category_code = category.code
WHERE category.deleted = 0 AND (
    EXISTS (
        SELECT data_domain_id, sort_order FROM dh_modeling_biz_category_data_domain_rel
        WHERE biz_category_id = category.id AND deleted = 0
        EXCEPT
        SELECT data_domain_id, sort_order FROM biz_domain_desired WHERE biz_category_id = category.id
    ) OR EXISTS (
        SELECT data_domain_id, sort_order FROM biz_domain_desired WHERE biz_category_id = category.id
        EXCEPT
        SELECT data_domain_id, sort_order FROM dh_modeling_biz_category_data_domain_rel
        WHERE biz_category_id = category.id AND deleted = 0
    )
);

-- 事务性重启序列兼容历史手工编号，保留高水位；无差异时不触碰序列。
DO $reserve_ids$
DECLARE
    required_count bigint;
    sequence_value bigint;
    sequence_called boolean;
    sequence_increment bigint;
    sequence_max bigint;
    sequence_cycles boolean;
    first_id numeric;
BEGIN
    SELECT count(*) INTO required_count FROM biz_domain_desired desired
    JOIN biz_domain_rebuild rebuild ON rebuild.id = desired.biz_category_id;
    IF required_count = 0 THEN RETURN; END IF;
    SELECT seqincrement, seqmax, seqcycle INTO sequence_increment, sequence_max, sequence_cycles
    FROM pg_sequence WHERE seqrelid = 'dh_modeling_biz_category_data_domain_rel_seq'::regclass;
    IF sequence_increment <> 1 OR sequence_cycles THEN
        RAISE EXCEPTION '业务分类数据域关系序列必须使用步长一且禁止循环';
    END IF;
    -- 先锁定序列配置，再读取高水位，避免与并发预分配编号交错。
    ALTER SEQUENCE dh_modeling_biz_category_data_domain_rel_seq NO CYCLE;
    SELECT last_value, is_called INTO sequence_value, sequence_called FROM dh_modeling_biz_category_data_domain_rel_seq;
    SELECT greatest(coalesce(max(id), 0)::numeric + 1,
                    sequence_value::numeric + CASE WHEN sequence_called THEN 1 ELSE 0 END)
    INTO first_id FROM dh_modeling_biz_category_data_domain_rel;
    IF first_id + required_count - 1 > sequence_max THEN
        RAISE EXCEPTION '业务分类数据域关系编号空间不足';
    END IF;
    EXECUTE format('ALTER SEQUENCE dh_modeling_biz_category_data_domain_rel_seq RESTART WITH %s', first_id);
END $reserve_ids$;

UPDATE dh_modeling_biz_category_data_domain_rel relation
SET deleted = 1, updater = 'migration:0204', update_time = CURRENT_TIMESTAMP
FROM biz_domain_rebuild rebuild
WHERE relation.biz_category_id = rebuild.id AND relation.deleted = 0;

INSERT INTO dh_modeling_biz_category_data_domain_rel
    (biz_category_id, data_domain_id, sort_order, creator, updater, deleted)
SELECT desired.biz_category_id, desired.data_domain_id, desired.sort_order, 'migration:0204', 'migration:0204', 0
FROM biz_domain_desired desired
JOIN biz_domain_rebuild rebuild ON rebuild.id = desired.biz_category_id
ORDER BY desired.biz_category_id, desired.sort_order;

DO $verify_assignments$
BEGIN
    IF EXISTS (
        SELECT desired.biz_category_id, desired.data_domain_id, desired.sort_order
        FROM biz_domain_desired desired
        FULL JOIN (
            SELECT relation.biz_category_id, relation.data_domain_id, relation.sort_order
            FROM dh_modeling_biz_category_data_domain_rel relation
            JOIN dh_modeling_biz_category category ON category.id = relation.biz_category_id AND category.deleted = 0
            JOIN biz_domain_assignment seed ON seed.category_code = category.code
            WHERE relation.deleted = 0
        ) actual USING (biz_category_id, data_domain_id)
        WHERE desired.biz_category_id IS NULL OR actual.biz_category_id IS NULL
           OR desired.sort_order IS DISTINCT FROM actual.sort_order
    ) THEN
        RAISE EXCEPTION '业务分类数据域关联重建校验失败，事务已回滚';
    END IF;
END $verify_assignments$;

COMMIT;
