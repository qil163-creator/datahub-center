-- Migration: 0205 数据板块与主题域工作台及演示数据替换。
-- Purpose: 补齐板块负责人、命名规则、所属板块和英文缩写，导入十个板块、二十个主题域及八个草稿概念设计中的三十五个业务对象与活动。
-- Depends on: 0204_modeling_biz-category-data-domains.sql 已执行，或已核验平台达到对应状态；0171、0185 归属守卫和单租户升级已生效。
-- Target: 已有 PostgreSQL 平台开发库，固定 schema 为 datahub-center；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 否；这是用户授权的一次性数据重置，重复执行会再次清空这两表及其分类关联，必须通过执行账本防止重放。
-- Rollback: 单事务失败完整回滚；提交后的数据清除不可逆，执行前备份两张域表、分类关联、逻辑模型归属、概念设计和菜单，按备份恢复；已推进的序列高水位保留。
-- 事务边界：使用归属互斥锁和表锁，锁等待十秒或语句执行一百二十秒超时即整体失败。
-- 清理范围：删除旧板块、旧主题域及其分类关联，仅解除逻辑模型的旧归属并增加修订号；不删除模型本体、版本、字段、资产或元数据。
-- 外部人员账号与手机号不进入脚本，负责人映射至有效本地管理员，联系方式读取本地系统用户；不创建外部登录账号。
-- 非空概念模型、指标模型、业务限定或跨模块主题引用会阻断重置，须按其领域流程处理后重试，禁止级联删除。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
SELECT pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
LOCK TABLE dh_modeling_data_domain,dh_modeling_subject_domain,dh_modeling_biz_category_data_domain_rel,
    dh_modeling_logic_model IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE dh_modeling_concept_model,dh_modeling_indicator_model,dh_modeling_business_condition,
    dh_governance_meta_table,dh_asset_data_asset IN SHARE MODE;

DO $preflight$
BEGIN
    IF EXISTS(SELECT 1 FROM dh_modeling_concept_model WHERE data_domain_id IS NOT NULL OR subject_domain_id IS NOT NULL)
       OR EXISTS(SELECT 1 FROM dh_modeling_indicator_model WHERE data_domain_id IS NOT NULL OR subject_domain_id IS NOT NULL)
       OR EXISTS(SELECT 1 FROM dh_modeling_business_condition WHERE data_domain_id IS NOT NULL OR subject_domain_id IS NOT NULL)
       OR EXISTS(SELECT 1 FROM dh_governance_meta_table WHERE subject_domain_id IS NOT NULL)
       OR EXISTS(SELECT 1 FROM dh_asset_data_asset WHERE subject_domain_id IS NOT NULL) THEN
        RAISE EXCEPTION '旧域仍被概念模型、指标、业务限定、元数据或资产引用，已阻断重置；请先通过所属领域处理引用';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM system_users WHERE deleted=0 AND status=0) THEN
        RAISE EXCEPTION '未找到可映射的有效本地用户，已阻断演示数据导入';
    END IF;
    RAISE NOTICE '重置前：数据板块 %，主题域 %，分类关联 %，待解除归属的逻辑模型 %',
        (SELECT count(*) FROM dh_modeling_data_domain),(SELECT count(*) FROM dh_modeling_subject_domain),
        (SELECT count(*) FROM dh_modeling_biz_category_data_domain_rel),
        (SELECT count(*) FROM dh_modeling_logic_model WHERE data_domain_id IS NOT NULL OR subject_domain_id IS NOT NULL);
END $preflight$;

UPDATE dh_modeling_logic_model SET data_domain_id=NULL,subject_domain_id=NULL,classification_status='UNCLASSIFIED',
    revision=revision+1,updater='migration:0205',update_time=CURRENT_TIMESTAMP
WHERE data_domain_id IS NOT NULL OR subject_domain_id IS NOT NULL;
DELETE FROM dh_modeling_biz_category_data_domain_rel;
DELETE FROM dh_modeling_subject_domain;
DELETE FROM dh_modeling_data_domain;

ALTER TABLE dh_modeling_data_domain
    ALTER COLUMN code TYPE varchar(64),
    ALTER COLUMN description TYPE varchar(256),
    ADD COLUMN IF NOT EXISTS icon varchar(1024),
    ADD COLUMN IF NOT EXISTS business_owner_ids varchar(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS data_owner_ids varchar(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS naming_rules jsonb NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON TABLE dh_modeling_data_domain IS '数据建模-数据板块';
COMMENT ON COLUMN dh_modeling_data_domain.name IS '数据板块名称';
COMMENT ON COLUMN dh_modeling_data_domain.code IS '板块英文名称，支持字母、数字和下划线';
COMMENT ON COLUMN dh_modeling_data_domain.description IS '数据板块业务边界与说明';
COMMENT ON COLUMN dh_modeling_data_domain.leader_user_id IS '板块架构师用户编号';
COMMENT ON COLUMN dh_modeling_data_domain.leader_phone IS '板块架构师联系方式';
COMMENT ON COLUMN dh_modeling_data_domain.icon IS '板块图标的受控文件访问地址';
COMMENT ON COLUMN dh_modeling_data_domain.business_owner_ids IS '业务负责人编号列表，逗号分隔，最多三人';
COMMENT ON COLUMN dh_modeling_data_domain.data_owner_ids IS '数据负责人编号列表，逗号分隔，最多三人';
COMMENT ON COLUMN dh_modeling_data_domain.naming_rules IS '四种逻辑表的推荐命名规则，仅存储配置不执行数据库语句';

ALTER TABLE dh_modeling_subject_domain
    ALTER COLUMN code TYPE varchar(64),
    ADD COLUMN IF NOT EXISTS data_domain_id bigint NOT NULL,
    ADD COLUMN IF NOT EXISTS abbreviation varchar(64) NOT NULL;
COMMENT ON COLUMN dh_modeling_subject_domain.data_domain_id IS '所属数据板块编号';
COMMENT ON COLUMN dh_modeling_subject_domain.code IS '板块内唯一的主题域编码';
COMMENT ON COLUMN dh_modeling_subject_domain.abbreviation IS '板块内唯一的英文缩写，用于逻辑表推荐名称';

CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_data_domain_name ON dh_modeling_data_domain(name) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_data_domain_code ON dh_modeling_data_domain(lower(code)) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_subject_sibling_name ON dh_modeling_subject_domain(data_domain_id,parent_id,name) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_subject_domain_code ON dh_modeling_subject_domain(data_domain_id,lower(code)) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uq_modeling_subject_domain_abbreviation ON dh_modeling_subject_domain(data_domain_id,lower(abbreviation)) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_modeling_subject_domain_parent ON dh_modeling_subject_domain(data_domain_id,parent_id) WHERE deleted=0;
COMMENT ON INDEX uq_modeling_data_domain_name IS '有效数据板块名称唯一';
COMMENT ON INDEX uq_modeling_data_domain_code IS '有效数据板块英文名不区分大小写唯一';
COMMENT ON INDEX uq_modeling_subject_sibling_name IS '板块内同级有效主题域名称唯一';
COMMENT ON INDEX uq_modeling_subject_domain_code IS '板块内有效主题域编码不区分大小写唯一';
COMMENT ON INDEX uq_modeling_subject_domain_abbreviation IS '板块内有效主题域英文缩写不区分大小写唯一';
COMMENT ON INDEX idx_modeling_subject_domain_parent IS '按数据板块和父主题域读取有效主题树';
DO $foreign_key$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_modeling_subject_domain'::regclass
                  AND conname='fk_modeling_subject_data_domain') THEN
        ALTER TABLE dh_modeling_subject_domain ADD CONSTRAINT fk_modeling_subject_data_domain
            FOREIGN KEY(data_domain_id) REFERENCES dh_modeling_data_domain(id);
    END IF;
END $foreign_key$;

CREATE TEMP TABLE domain_local_actor ON COMMIT DROP AS
SELECT u.id,u.mobile FROM system_users u WHERE u.deleted=0 AND u.status=0
ORDER BY CASE WHEN EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id
    WHERE ur.user_id=u.id AND ur.deleted=0 AND r.deleted=0 AND r.status=0 AND r.code='super_admin') THEN 0 ELSE 1 END,u.id
LIMIT 1;

-- 数据内容来自授权会话读取的板块和主题接口，保留编号、名称、时间、状态及板块归属。
INSERT INTO dh_modeling_data_domain
    (id,name,code,description,status,create_time,update_time,leader_user_id,leader_phone,creator,updater,naming_rules)
SELECT seed.id,seed.name,seed.code,seed.description,seed.status,seed.created::timestamp,seed.created::timestamp,
    actor.id,actor.mobile,actor.id::text,actor.id::text,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'::jsonb
FROM (VALUES
    (29, '水利监测与防汛调度', 'WATER_CONSERVANCY', '面向河流、水库、水文站、雨量站、闸坝、泵站等水利对象，统一建设水雨情监测、防洪调度、工程运行和预警处置等数据模型，为防汛抗旱、水资源调度和水利运行监管提供标准化数据支撑。', 0, '2026-09-29 17:47:38'),
    (12, '电商零售', 'EC_RETAIL', '管理电商业务中的客户、商品、交易、履约等核心业务数据', 0, '2026-09-29 12:43:24'),
    (35, '能源综合管理', 'ENERGY_MANAGEMENT', '面向电、水、气、热等能源的采集、消耗、计量、分析及节能管理业务，统一规划能源数据。', 0, '2026-09-28 17:53:54'),
    (34, '工业生产制造', 'INDUSTRIAL_MFG', '面向工厂、车间、产线、设备、生产计划、工单及质量管理等业务，统一规划工业制造数据。', 0, '2026-09-28 17:53:33'),
    (33, '教育教学管理', 'EDU_MANAGEMENT', '面向学校、教师、学生、课程、教学及考试等业务，统一规划教育教学管理数据。', 0, '2026-09-28 17:53:09'),
    (32, '医疗健康服务', 'HEALTHCARE', '面向医疗机构、患者、就诊、检查、检验、药品等业务，统一规划医疗健康服务数据。', 0, '2026-09-28 17:52:28'),
    (31, '智慧园区运营', 'SMART_PARK', '向园区企业、楼宇、人员、车辆、设备、能耗及安防等业务，统一规划园区综合运营数据。', 0, '2026-09-28 17:52:04'),
    (30, '城市交通运行', 'URBAN_TRAFFIC', '面向城市道路、车辆、公交、停车及交通事件等业务，统一规划城市交通运行相关数据。', 0, '2026-09-28 17:51:41'),
    (2, '水利工程数据板块', 'ENG_ODL', '用于水库、堤防、闸泵等水利工程数据规划。', 1, '2026-09-19 18:09:03'),
    (1, '水文气象数据板块', 'HYD_MET', '用于水文监测、气象降雨等相关数据规划。', 0, '2026-09-13 18:09:03')
) seed(id,name,code,description,status,created) CROSS JOIN domain_local_actor actor;

INSERT INTO dh_modeling_subject_domain
    (id,parent_id,data_domain_id,name,code,abbreviation,description,status,create_time,update_time,leader_user_id,leader_phone,creator,updater)
SELECT seed.id,seed.parent_id,seed.data_domain_id,seed.name,seed.code,seed.abbreviation,seed.description,seed.status,
    seed.created::timestamp,seed.created::timestamp,actor.id,actor.mobile,actor.id::text,actor.id::text
FROM (VALUES
    (117, 0, 29, '防汛预警', 'FLOOD_CONTROL', 'FLOODCONTROL', '管理洪水预警、预警发布、响应及处置过程', 0, '2026-09-29 17:57:28'),
    (115, 0, 29, '河湖管理', 'RIVER_LAKE', 'RIVERLAKE', '管理河流、湖泊、河段及水情分析', 0, '2026-09-29 17:57:28'),
    (114, 0, 29, '水库调度', 'RESERVOIR', 'RESERVOIR', '管理水库水位、库容、入库出库及调度过程', 0, '2026-09-29 17:56:44'),
    (113, 0, 29, '水文监测', 'HYDROLOGY', 'HYDROLOGY', '管理水位、流量、降雨、蒸发等水文监测业务', 0, '2026-09-29 17:56:16'),
    (79, 0, 12, '履约域', 'FULFILLMENT', 'FULFILLMENT', '发货、签收及订单履约过程', 0, '2026-09-29 17:02:03'),
    (78, 0, 12, '交易域', 'TRADE', 'TRADE', '订单、订单明细、下单、支付、退款', 0, '2026-09-29 17:01:49'),
    (77, 0, 12, '商品域', 'PRODUCT', 'PRODUCT', '商品、商品分类', 0, '2026-09-29 17:01:27'),
    (76, 0, 12, '客户域', 'CUSTOMER', 'CUSTOMER', '客户、会员及客户状态', 0, '2026-09-29 17:00:07'),
    (124, 0, 33, '学生管理', 'STUDENT_MGMT', 'STUDENTMGMT', '管理学生基本信息、学籍、入学、转学及毕业业务', 1, '2026-09-28 10:52:10'),
    (123, 0, 33, '学校基础管理', 'SCHOOL_BASE', 'SCHOOLBASE', '管理学校、校区、院系、专业、班级等组织基础数据', 1, '2026-09-28 10:52:10'),
    (122, 0, 34, '生产执行', 'PRODUCTION_EXEC', 'PRODUCTIONEXEC', '管理生产工单、生产批次、工序执行及生产完工业务', 1, '2026-09-28 10:52:10'),
    (121, 0, 34, '生产计划', 'PRODUCTION_PLAN', 'PRODUCTIONPLAN', '管理生产计划、生产任务及计划执行情况', 1, '2026-09-28 10:52:10'),
    (130, 0, 30, '车辆运行', 'VEHICLE_OPERATION', 'VEHICLEOPERATI', '管理车辆运行、轨迹、速度及交通流数据', 1, '2026-09-28 10:52:10'),
    (125, 0, 32, '患者管理', 'PATIENT_MGMT', 'PATIENTMGMT', '管理患者基本信息、健康档案及就医身份信息', 1, '2026-09-28 10:52:10'),
    (120, 0, 35, '电力能源管理', 'ELECTRIC_POWER', 'ELECTRICPOWER', '管理用电量、负荷、电压、电流、功率及电力运行数据', 1, '2026-09-28 10:52:10'),
    (129, 0, 30, '道路基础管理', 'ROAD_BASE', 'ROADBASE', '管理道路、路段、路口及道路设施基础数据', 0, '2026-09-28 10:52:10'),
    (128, 0, 31, '企业管理', 'ENTERPRISE_MGMT', 'ENTERPRISEMGMT', '管理入驻企业、企业档案、租赁及经营信息', 1, '2026-09-28 10:52:10'),
    (119, 0, 35, '能源基础管理', 'ENERGY_BASE', 'ENERGYBASE', '管理能源类型、计量点、能源设备及能源组织等基础数据', 1, '2026-09-28 10:52:10'),
    (127, 0, 31, '园区基础管理', 'PARK_BASE', 'PARKBASE', '管理园区、楼宇、楼层、房间及空间资源', 1, '2026-09-28 10:52:10'),
    (126, 0, 32, '门诊管理', 'OUTPATIENT', 'OUTPATIENT', '管理挂号、就诊、诊断、处方等门诊业务', 0, '2026-09-28 10:52:10')
) seed(id,parent_id,data_domain_id,name,code,abbreviation,description,status,created) CROSS JOIN domain_local_actor actor;

-- 每个有业务实体的主题导入一个草稿概念设计，实体与活动以结构化内容参与统计，不保存展示用虚构计数。
INSERT INTO dh_modeling_concept_model
    (name,code,data_domain_id,subject_domain_id,description,status,design,creator,updater,create_time,update_time)
SELECT seed.name,seed.code,seed.data_domain_id,seed.subject_domain_id,
    '根据授权读取的 QData 业务规划导入业务对象和业务活动，实体属性及关联由后续详情功能继续维护。',
    'DRAFT',seed.design::jsonb,actor.id::text,actor.id::text,seed.created::timestamp,seed.created::timestamp
FROM (VALUES
    (29,117,'防汛预警业务规划','QDATA_FLOOD_CONTROL','2026-09-29 17:57:28','{"entities":[{"key":"qdata_69","code":"FLOOD_CONTROL_AREA","name":"防汛责任区","description":"省、市、区县、责任片区","attributes":[],"entityType":"OBJECT"},{"key":"qdata_70","code":"FLOOD_WARNING","name":"洪水预警","description":"一条可持续、可解除的预警业务对象","attributes":[],"entityType":"OBJECT"},{"key":"qdata_71","code":"FLOOD_WARNING_TRIGGER","name":"洪水预警触发","description":"达到规则后产生预警","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_72","code":"WARNING_PUBLISH","name":"预警发布","description":"正式发布预警","attributes":[],"entityType":"OBJECT"},{"key":"qdata_73","code":"FLOOD_RESPONSE_START","name":"防汛响应启动","description":"启动防汛响应","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_74","code":"FLOOD_RESPONSE_END","name":"防汛响应结束","description":"结束防汛响应","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_75","code":"FLOOD_WARNING_PROCESS","name":"洪水预警处置流程","description":"从预警触发到响应结束的全过程","attributes":[],"entityType":"ACTIVITY"}],"relations":[]}'),
    (29,115,'河湖管理业务规划','QDATA_RIVER_LAKE','2026-09-29 17:57:28','{"entities":[{"key":"qdata_63","code":"RIVER_OVER_WARNING","name":"河道超警戒","description":"河道水位超过警戒水位","attributes":[],"entityType":"OBJECT"},{"key":"qdata_64","code":"WATER_PROJECT","name":"水利工程","description":"水库、闸门、泵站、堤防等工程统一对象","attributes":[],"entityType":"OBJECT"},{"key":"qdata_65","code":"SLUICE_GATE","name":"闸门","description":"水闸运行对象","attributes":[],"entityType":"OBJECT"},{"key":"qdata_66","code":"PUMP_STATION","name":"泵站","description":"排涝、调水、提水工程对象","attributes":[],"entityType":"OBJECT"},{"key":"qdata_67","code":"GATE_OPERATION","name":"闸门启闭","description":"一次闸门开启/关闭操作","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_68","code":"PUMP_OPERATION","name":"泵站启停","description":"一次泵站启动/停止操作","attributes":[],"entityType":"ACTIVITY"}],"relations":[]}'),
    (29,114,'水库调度业务规划','QDATA_RESERVOIR','2026-09-29 17:56:44','{"entities":[{"key":"qdata_61","code":"RESERVOIR","name":"水库","description":"","attributes":[],"entityType":"OBJECT"},{"key":"qdata_62","code":"RESERVOIR_OVER_LIMIT","name":"水库超汛限","description":"水库水位超过汛限水位","attributes":[],"entityType":"ACTIVITY"}],"relations":[]}'),
    (29,113,'水文监测业务规划','QDATA_HYDROLOGY','2026-09-29 17:56:16','{"entities":[{"key":"qdata_56","code":"HYDRO_STATION","name":"水文站","description":"水位、流量等水文监测站点","attributes":[],"entityType":"OBJECT"},{"key":"qdata_57","code":"RAIN_STATION","name":"雨量站","description":"降雨监测站点","attributes":[],"entityType":"OBJECT"},{"key":"qdata_58","code":"WATER_LEVEL_COLLECT","name":"水位采集","description":"一次水位采集事件","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_59","code":"FLOW_COLLECT","name":"流量采集","description":"一次流量采集事件","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_60","code":"RAIN_COLLECT","name":"降雨采集","description":"","attributes":[],"entityType":"ACTIVITY"}],"relations":[]}'),
    (12,79,'履约域业务规划','QDATA_FULFILLMENT','2026-09-29 17:02:03','{"entities":[{"key":"qdata_32","code":"ORDER_SHIP","name":"订单发货","description":"","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_33","code":"ORDER_RECEIVE","name":"订单签收","description":"","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_34","code":"ORDER_FULFILLMENT_PROCESS","name":"订单履约流程","description":"从客户下单到完成订单履约的完整业务过程ORDER_FULFILLMENT_PROCESS","attributes":[],"entityType":"ACTIVITY"}],"relations":[]}'),
    (12,78,'交易域业务规划','QDATA_TRADE','2026-09-29 17:01:49','{"entities":[{"key":"qdata_26","code":"ORDER","name":"订单","description":"客户发起购买后形成的交易订单","attributes":[],"entityType":"OBJECT"},{"key":"qdata_27","code":"ORDER_ITEM","name":"订单明细","description":"","attributes":[],"entityType":"OBJECT"},{"key":"qdata_29","code":"ORDER_CREATE","name":"客户下单","description":"客户提交商品订单形成订单的业务行为","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_30","code":"ORDER_PAYMENT","name":"订单支付","description":"客户针对订单完成支付","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_31","code":"ORDER_REFUND","name":"订单退款","description":"客户针对订单或者订单商品发起退款","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_48","code":"jyst","name":"交易实体","description":"","attributes":[],"entityType":"OBJECT"}],"relations":[]}'),
    (12,77,'商品域业务规划','QDATA_PRODUCT','2026-09-29 17:01:27','{"entities":[{"key":"qdata_24","code":"PRODUCT_CATEGORY","name":"商品类目","description":"商品所属分类体系","attributes":[],"entityType":"OBJECT"},{"key":"qdata_25","code":"PRODUCT","name":"商品","description":"平台实际销售的商品","attributes":[],"entityType":"OBJECT"}],"relations":[]}'),
    (12,76,'客户域业务规划','QDATA_CUSTOMER','2026-09-29 17:00:07','{"entities":[{"key":"qdata_22","code":"CUSTOMER","name":"客户","description":"在平台产生注册、浏览、购买等行为的客户主体","attributes":[],"entityType":"OBJECT"},{"key":"qdata_23","code":"MEMBER","name":"会员","description":"","attributes":[],"entityType":"OBJECT"},{"key":"qdata_28","code":"CUSTOMER_REGISTER","name":"客户注册","description":"客户完成平台注册的业务事件","attributes":[],"entityType":"ACTIVITY"},{"key":"qdata_49","code":"customer2","name":"客户","description":"","attributes":[],"entityType":"OBJECT"}],"relations":[]}')
) seed(data_domain_id,subject_domain_id,name,code,created,design) CROSS JOIN domain_local_actor actor;

-- 保留每个板块已保存的四类规则差异，仅导入本次向导支持的逻辑表类型。
UPDATE dh_modeling_data_domain domain SET naming_rules=seed.rules::jsonb
FROM (VALUES
    (29,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (12,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (35,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (34,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (33,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (32,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (31,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (30,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (2,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]'),
    (1,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"fgfasdf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false}]')
) seed(id,rules) WHERE domain.id=seed.id;

-- 仅调整现有导航和按钮标题，保留路由、组件、权限码及角色授权。
UPDATE system_menu SET name=CASE permission
    WHEN 'modeling:data-domain:query' THEN '数据板块'
    WHEN 'modeling:data-domain:create' THEN '数据板块新增'
    WHEN 'modeling:data-domain:update' THEN '数据板块修改'
    WHEN 'modeling:data-domain:delete' THEN '数据板块删除' END,
    updater='migration:0205',update_time=CURRENT_TIMESTAMP
WHERE permission IN('modeling:data-domain:query','modeling:data-domain:create','modeling:data-domain:update','modeling:data-domain:delete')
  AND deleted=0;

SELECT setval('dh_modeling_data_domain_seq',greatest((SELECT last_value FROM dh_modeling_data_domain_seq),(SELECT max(id) FROM dh_modeling_data_domain)),true);
SELECT setval('dh_modeling_subject_domain_seq',greatest((SELECT last_value FROM dh_modeling_subject_domain_seq),(SELECT max(id) FROM dh_modeling_subject_domain)),true);
DO $verify$
BEGIN
    IF (SELECT count(*) FROM dh_modeling_data_domain)<>10 OR (SELECT count(*) FROM dh_modeling_subject_domain)<>20
       OR (SELECT count(*) FROM dh_modeling_concept_model)<>8
       OR (SELECT sum(jsonb_array_length(design->'entities')) FROM dh_modeling_concept_model)<>35 THEN
        RAISE EXCEPTION '板块或主题域导入数量不符，已整体回滚';
    END IF;
    RAISE NOTICE '重置后：数据板块 10，主题域 20，草稿概念设计 8，业务对象及活动 35；旧分类关联已清空，原逻辑模型本体与不可变历史保持原样';
END $verify$;
COMMIT;
