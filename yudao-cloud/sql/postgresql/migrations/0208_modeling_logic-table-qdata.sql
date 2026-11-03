-- Migration: 0208 逻辑模型五类逻辑表（贴源/维度/事实/汇总/ADS 应用表）。
-- Purpose: 按 qData 逻辑模型页面重建逻辑模型主表，新增来源系统表、三类建模字典与五个逻辑表菜单，并以 qData 演示列表数据重新初始化逻辑表及字段。
-- Depends on: 0207_modeling_business-entity-workbench.sql 已执行；数据板块 1/2/12/29~35、主题域、业务实体（22~75）与数仓分层 2/4/5/6/8 已存在。
-- Target: 已有 PostgreSQL 平台开发库，固定 schema 为 datahub-center；新库使用同步的 datahub-center.sql。
-- Repeatable: 是；重复执行会再次清空并按 qData 列表数据重建逻辑表、来源系统与本迁移创建的菜单，字典已存在则跳过。
-- Irreversible: 清空逻辑模型、字段、版本、关系、物化发布记录及部署尝试，并 DROP 重建 dh_modeling_logic_model；旧逻辑模型数据不保留（平台未上线，按需求不兼容旧数据）。
-- Rollback: 单事务失败完整回滚；成功后只能从执行前备份恢复旧逻辑模型数据。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 1. 清空依赖逻辑模型的数据。
TRUNCATE dh_modeling_logic_model_version, dh_modeling_logic_relation, dh_modeling_model_materialized_log,
    dh_modeling_model_materialized, dh_modeling_deployment_attempt, dh_modeling_logic_model_column;

-- 2. 重建逻辑模型主表：保留既有建模、发布与画布字段，新增 qData 逻辑表归属字段。
DROP TABLE dh_modeling_logic_model;
CREATE TABLE dh_modeling_logic_model (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_logic_model_seq'),
    name varchar(100) NOT NULL,
    code varchar(64),
    table_name_notation varchar(128),
    create_mode varchar(16) NOT NULL DEFAULT 'FORWARD',
    latest_version integer NOT NULL DEFAULT 1,
    status varchar(16) NOT NULL DEFAULT 'REVISING',
    description varchar(500),
    model_style varchar(20) NOT NULL DEFAULT 'RELATIONAL',
    warehouse_layer varchar(10) NOT NULL DEFAULT 'NONE',
    grain varchar(512),
    layer_id bigint,
    table_type varchar(32),
    classification_status varchar(32) DEFAULT 'UNCLASSIFIED',
    data_domain_id bigint,
    subject_domain_id bigint,
    biz_category_id bigint,
    source_system_id bigint,
    datasource_id bigint,
    db_type varchar(32),
    db_name varchar(128),
    schema_name varchar(128),
    entity_id bigint,
    obj_type varchar(8),
    business_type varchar(8),
    data_timeliness varchar(8),
    statistics_str varchar(500),
    table_case smallint NOT NULL DEFAULT 1,
    document_type varchar(32),
    document_id bigint,
    contact_user_id bigint,
    contact_number varchar(32),
    owner_name varchar(64),
    owner_phone varchar(32),
    enabled smallint NOT NULL DEFAULT 0,
    remark varchar(500),
    revision bigint NOT NULL DEFAULT 0,
    layout_revision bigint NOT NULL DEFAULT 0,
    pos_x integer,
    pos_y integer,
    creator varchar(64) DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0
);
COMMENT ON TABLE dh_modeling_logic_model IS '数据建模-逻辑模型';
COMMENT ON COLUMN dh_modeling_logic_model.name IS '逻辑表中文名称';
COMMENT ON COLUMN dh_modeling_logic_model.code IS '逻辑表英文名（不含分层前缀）';
COMMENT ON COLUMN dh_modeling_logic_model.table_name_notation IS '完整表名';
COMMENT ON COLUMN dh_modeling_logic_model.create_mode IS '创建方式：FORWARD-正向建模 REVERSE-从原表创建';
COMMENT ON COLUMN dh_modeling_logic_model.latest_version IS '最新版本号';
COMMENT ON COLUMN dh_modeling_logic_model.status IS '设计状态：REVISING-修订中 PUBLISHED-已发布 DISCARDED-已废弃';
COMMENT ON COLUMN dh_modeling_logic_model.description IS '描述';
COMMENT ON COLUMN dh_modeling_logic_model.model_style IS '建模风格：RELATIONAL-关系 DIMENSIONAL-维度 WIDE-宽表 TIME_SERIES-时序 SPATIAL-空间 EVENT_STREAM-事件流';
COMMENT ON COLUMN dh_modeling_logic_model.warehouse_layer IS '数仓分层：NONE/ODS/DWD/DIM/DWS/ADS';
COMMENT ON COLUMN dh_modeling_logic_model.grain IS '模型一行数据代表的粒度';
COMMENT ON COLUMN dh_modeling_logic_model.layer_id IS '所属数仓分层编号';
COMMENT ON COLUMN dh_modeling_logic_model.table_type IS '表类型：SOURCE贴源/DIMENSION维度/DETAIL事实/SUMMARY汇总/APPLICATION应用';
COMMENT ON COLUMN dh_modeling_logic_model.classification_status IS '归属评估结果：UNCLASSIFIED/CONFIRMED';
COMMENT ON COLUMN dh_modeling_logic_model.data_domain_id IS '所属数据板块编号';
COMMENT ON COLUMN dh_modeling_logic_model.subject_domain_id IS '所属主题域编号';
COMMENT ON COLUMN dh_modeling_logic_model.biz_category_id IS '业务分类编号';
COMMENT ON COLUMN dh_modeling_logic_model.source_system_id IS '来源系统编号（贴源逻辑表）';
COMMENT ON COLUMN dh_modeling_logic_model.datasource_id IS '来源数据连接编号（贴源逻辑表）';
COMMENT ON COLUMN dh_modeling_logic_model.db_type IS '来源数据库类型（贴源逻辑表）';
COMMENT ON COLUMN dh_modeling_logic_model.db_name IS '来源数据库名（贴源逻辑表）';
COMMENT ON COLUMN dh_modeling_logic_model.schema_name IS '来源模式名（贴源逻辑表）';
COMMENT ON COLUMN dh_modeling_logic_model.entity_id IS '关联业务实体编号';
COMMENT ON COLUMN dh_modeling_logic_model.obj_type IS '维度类型，字典 modeling_logic_obj_type';
COMMENT ON COLUMN dh_modeling_logic_model.business_type IS '业务类型，字典 modeling_logic_business_type';
COMMENT ON COLUMN dh_modeling_logic_model.data_timeliness IS '统计时效，字典 modeling_logic_data_timeliness';
COMMENT ON COLUMN dh_modeling_logic_model.statistics_str IS '统计粒度（汇总逻辑表，逗号分隔）';
COMMENT ON COLUMN dh_modeling_logic_model.table_case IS '完整表名大小写：1 大写，2 小写';
COMMENT ON COLUMN dh_modeling_logic_model.document_type IS '标准类型';
COMMENT ON COLUMN dh_modeling_logic_model.document_id IS '标准登记编号';
COMMENT ON COLUMN dh_modeling_logic_model.contact_user_id IS '责任人用户编号';
COMMENT ON COLUMN dh_modeling_logic_model.contact_number IS '联系电话';
COMMENT ON COLUMN dh_modeling_logic_model.owner_name IS '责任人姓名';
COMMENT ON COLUMN dh_modeling_logic_model.owner_phone IS '责任人电话';
COMMENT ON COLUMN dh_modeling_logic_model.enabled IS '是否启用：0 禁用，1 启用';
COMMENT ON COLUMN dh_modeling_logic_model.remark IS '备注';
COMMENT ON COLUMN dh_modeling_logic_model.revision IS '统一设计聚合乐观锁';
COMMENT ON COLUMN dh_modeling_logic_model.layout_revision IS '画布布局独立乐观锁';
COMMENT ON COLUMN dh_modeling_logic_model.pos_x IS '主题 ER 画布横坐标';
COMMENT ON COLUMN dh_modeling_logic_model.pos_y IS '主题 ER 画布纵坐标';
COMMENT ON COLUMN dh_modeling_logic_model.creator IS '创建者';
COMMENT ON COLUMN dh_modeling_logic_model.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_logic_model.updater IS '更新者';
COMMENT ON COLUMN dh_modeling_logic_model.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_logic_model.deleted IS '是否删除';
CREATE INDEX idx_dh_modeling_logic_model_type ON dh_modeling_logic_model (table_type, id DESC) WHERE deleted = 0;
CREATE INDEX idx_dh_modeling_logic_model_layer ON dh_modeling_logic_model (layer_id) WHERE deleted = 0;
CREATE INDEX idx_dh_modeling_logic_model_data_domain ON dh_modeling_logic_model (data_domain_id) WHERE deleted = 0;
CREATE INDEX idx_dh_modeling_logic_model_subject_domain ON dh_modeling_logic_model (subject_domain_id) WHERE deleted = 0;
CREATE INDEX idx_dh_modeling_logic_model_source_system ON dh_modeling_logic_model (source_system_id) WHERE deleted = 0;
CREATE INDEX idx_dh_modeling_logic_model_classification ON dh_modeling_logic_model (classification_status) WHERE deleted = 0;
CREATE INDEX idx_dh_modeling_logic_model_biz_category ON dh_modeling_logic_model (biz_category_id) WHERE deleted = 0 AND biz_category_id IS NOT NULL;
CREATE INDEX idx_logic_model_document_live ON dh_modeling_logic_model (document_id, id DESC) WHERE deleted = 0;
CREATE UNIQUE INDEX uq_modeling_logic_table_name ON dh_modeling_logic_model (lower(table_name_notation))
    WHERE deleted = 0 AND table_name_notation IS NOT NULL;

-- 3. 来源系统：贴源逻辑表左侧类目与筛选条件（重复执行时重建）。
DROP TABLE IF EXISTS dh_modeling_source_system;
DROP SEQUENCE IF EXISTS dh_modeling_source_system_seq;
CREATE SEQUENCE dh_modeling_source_system_seq;
CREATE TABLE dh_modeling_source_system (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_source_system_seq'),
    name varchar(100) NOT NULL CHECK (btrim(name) <> ''),
    type varchar(8),
    sort integer NOT NULL DEFAULT 0,
    description varchar(500),
    status smallint NOT NULL DEFAULT 0,
    remark varchar(500),
    creator varchar(64) DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0
);
COMMENT ON TABLE dh_modeling_source_system IS '数据建模-来源系统';
COMMENT ON COLUMN dh_modeling_source_system.name IS '来源系统名称';
COMMENT ON COLUMN dh_modeling_source_system.type IS '系统类型';
COMMENT ON COLUMN dh_modeling_source_system.sort IS '排序';
COMMENT ON COLUMN dh_modeling_source_system.description IS '描述';
COMMENT ON COLUMN dh_modeling_source_system.status IS '状态：0 启用，1 停用';
COMMENT ON COLUMN dh_modeling_source_system.remark IS '备注';
CREATE UNIQUE INDEX uq_modeling_source_system_name ON dh_modeling_source_system (name) WHERE deleted = 0;
INSERT INTO dh_modeling_source_system (id, name, type, sort, description, status, remark, create_time) VALUES
(1,'水利数据仓库-ODS贴源层','2',1,'汇聚业务源数据，保留原始结构，仅进行必要的格式转换和加载审计。',0,'层级编码：ODS；资产表前缀：ODS_','2026-08-04 22:12:45'),
(2,'水利数据仓库-DWD明细层','3',2,'对贴源数据进行清洗、标准化和去重，沉淀统一粒度的明细数据。',0,'层级编码：DWD；资产表前缀：DWD_','2026-08-04 22:12:46'),
(3,'水利数据仓库-DIM公共维度层','3',3,'维护测站、河流和河段等公共维度，统一编码、名称和属性口径。',0,'层级编码：DIM；资产表前缀：DIM_','2026-08-04 22:12:47'),
(4,'水利数据仓库-DWS汇总层','3',4,'按水情主题和时间周期形成统计、汇总及趋势分析数据。',0,'层级编码：DWS；资产表前缀：DWS_','2026-08-04 22:12:48'),
(5,'水利数据仓库-ADS应用层','4',5,'面向地图、报表、接口和专题分析提供应用数据集。',0,'层级编码：ADS；资产表前缀：ADS_','2026-08-04 22:12:49'),
(6,'内部文件管理系统','1',6,'面向内部文件统一管理，按原始资料、标准成果、质量报告、规则脚本和归档文件分类存储。',0,NULL,'2026-08-05 10:21:08');
SELECT setval('dh_modeling_source_system_seq', (SELECT max(id) FROM dh_modeling_source_system));

-- 4. 逻辑表与字段初始化（来源：qData 逻辑模型列表）。
INSERT INTO dh_modeling_logic_model (id, name, code, table_name_notation, create_mode, model_style, warehouse_layer,
    layer_id, table_type, classification_status, data_domain_id, subject_domain_id, table_case, contact_user_id,
    contact_number, owner_name, owner_phone, enabled, description, source_system_id, db_type, db_name, schema_name,
    entity_id, obj_type, business_type, data_timeliness, statistics_str, create_time, update_time) VALUES
(44,'客户基本信息表','ST_RSVR_R','ods_st_rsvr_raaaa','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,2,NULL,NULL,NULL,NULL,1,'记录业务系统中的客户基础信息，包括客户名称、联系方式、注册渠道、客户状态及注册时间等原始数据。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-21 15:19:19','2026-09-23 13:53:25'),
(45,'会员基本信息表','ST_RIVER_R','ods_ST_RIVER_R','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,NULL,NULL,NULL,NULL,0,'记录业务系统中的客户基础信息，包括客户名称、联系方式、注册渠道、客户状态及注册时间等原始数据。记录业务系统中的客户基础信息，包括客户名称、联系方式、注册渠道、客户状态及注册时间等原始数据。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-21 15:19:20','2026-09-21 15:19:33'),
(46,'水库水情表','ST_RSVR_R','ods_ST_RSVR_R','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,NULL,NULL,NULL,NULL,1,'1',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-21 15:26:04','2026-09-23 11:34:08'),
(51,'客户维度逻辑表','CUSTOMER','DIM_CUSTOMER','FORWARD','DIMENSIONAL','DIM',4,'DIMENSION','CONFIRMED',12,76,1,1,'15888888888','超级管理员','15888888888',0,'客户维度逻辑表',NULL,NULL,NULL,NULL,22,'1',NULL,'3',NULL,'2026-09-21 18:05:18','2026-09-24 14:28:56'),
(53,'下单事件事实表','ORDER_CREATE','DWD_ORDER_CREATE','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',12,78,1,1,'15888888888','超级管理员','15888888888',1,'下单事件事实表',NULL,NULL,NULL,NULL,29,NULL,'1','1',NULL,'2026-09-21 18:37:55','2026-09-23 14:59:17'),
(59,'客户','customer','dws_customer','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',12,76,2,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,NULL,NULL,'1','1','1','客户','2026-09-22 15:21:52','2026-09-29 10:46:12'),
(60,'河流基本信息表','WR_RV_B','ods1_wr_rv_baaa','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,2,1,'15888888888','超级管理员','15888888888',0,'记录河流基础属性原始数据，包括河流编码、河流名称、所属水系、河流等级、起止位置及相关基础属性信息。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-23 11:47:32','2026-09-29 13:56:11'),
(62,'实时告警信息表','SL_WARN_R','ods_sl_warn_r1111','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,2,1,'15888888888','超级管理员','15888888888',0,'记录监测业务产生的实时告警原始数据，包括告警对象、告警指标、告警级别、告警值、告警时间及处理状态等信息',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-23 11:47:33','2026-09-29 13:56:04'),
(63,'测站基本属性','ST_STBPRP_B','ODS_ST_STBPRP_B','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,1,'15888888888','超级管理员','15888888888',0,'记录水文监测站点的基础属性原始数据，包括测站编码、测站名称、测站类型、所属区域、经纬度及站点状态等信息。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-23 11:55:03','2026-09-29 13:55:52'),
(64,'降水量表','ST_PPTN_R','ODS_ST_PPTN_R','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,1,'15888888888','超级管理员','15888888888',0,'记录各监测站点采集的降水原始数据，包括测站编码、降水量、降水时段、采集时间及数据状态等信息。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-23 11:57:04','2026-09-29 13:55:35'),
(70,'数据源表','DA_DATASOURCE','ODS_DA_DATASOURCE','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,1,'15888888888','超级管理员','15888888888',1,'记录平台接入的数据源基础配置信息，包括数据源名称、数据源类型、连接配置、所属系统及使用状态等信息。',4,'DM8','QDATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-23 17:33:45','2026-09-29 13:55:27'),
(71,'水库日水情','ST_SRVR_DAYS','ODS_ST_SRVR_DAYS','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,1,'15888888888','超级管理员','15888888888',1,'记录水库每日水情原始数据，包括日水位、蓄水量、入库水量、出库水量及统计日期等信息。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-23 17:37:15','2026-09-29 13:55:20'),
(72,'水情日信息表','ST_RIVSER_DAY','ODS_ST_RIVSER_DAY','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,1,'15888888888','超级管理员','15888888888',1,'记录水情监测业务形成的日尺度原始数据，包括每日水位、流量及相关水情统计信息。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-23 17:37:17','2026-09-29 13:55:13'),
(74,'水库水情表','ODS_RESMON_RES_ST_RSVR_R','ODS_ODS_RESMON_RES_ST_RSVR_R','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,NULL,'15365128327','吴同','15365128327',1,'记录水库实时水情原始数据，包括水库水位、蓄水量、入库流量、出库流量及采集时间等信息。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-24 10:45:56','2026-09-29 13:55:06'),
(75,'河道水情表','ST_RIVER_R_TMP','ODS_ST_RIVER_R_TMP','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,1,'15888888888','超级管理员','15888888888',1,'记录河道测站实时水情原始数据，包括河道水位、流量、测报时间及相关水情状态等信息。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-24 11:30:04','2026-09-29 13:54:51'),
(76,'实时告警信息表','SL_WARN_R_TMP','ODS_SL_WARN_R_TMP','REVERSE','RELATIONAL','ODS',2,'SOURCE','CONFIRMED',NULL,NULL,1,1,'15888888888','超级管理员','15888888888',0,'记录水利业务系统产生的实时告警原始信息，包括告警对象、告警类型、告警级别、告警内容、发生时间及处理状态等数据。',4,'DM8','DATA',NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-24 11:30:05','2026-09-29 13:54:38'),
(81,'会员维度逻辑表','MEMBER','DIM_MEMBER','FORWARD','DIMENSIONAL','DIM',4,'DIMENSION','CONFIRMED',12,76,1,NULL,'15699126319','吴同','15365128327',0,'会员维度逻辑表描述',NULL,NULL,NULL,NULL,23,'1',NULL,'1',NULL,'2026-09-28 11:13:20','2026-09-28 11:13:20'),
(82,'商品类目层级维度表','PRODUCT_CATEGORY','DIM_PRODUCT_CATEGORY','FORWARD','DIMENSIONAL','DIM',4,'DIMENSION','CONFIRMED',12,77,1,NULL,'15365128327','吴同','15365128327',0,'商品类目层级维度表',NULL,NULL,NULL,NULL,24,'2',NULL,'1',NULL,'2026-09-28 11:16:36','2026-09-28 11:16:36'),
(83,'商品维度表','PRODUCT','DIM_PRODUCT','FORWARD','DIMENSIONAL','DIM',4,'DIMENSION','CONFIRMED',12,77,1,NULL,'15699126319','吴同','15365128327',0,'商品维度表',NULL,NULL,NULL,NULL,25,'1',NULL,'1',NULL,'2026-09-28 11:18:54','2026-09-28 11:18:54'),
(84,'支付事件事实表','TRADE_ORDER_PAYMENT_DI','DWD_TRADE_ORDER_PAYMENT_DI','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',12,78,1,1,'15888888888','超级管理员','15888888888',0,'支付事件事实表',NULL,NULL,NULL,NULL,30,NULL,'1','1',NULL,'2026-09-28 11:22:11','2026-09-28 11:22:11'),
(85,'退款事实表','TRADE_ORDER_REFUND_DI','DWD_TRADE_ORDER_REFUND_DI','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',12,78,1,1,'15888888888','超级管理员','15888888888',0,'退款事实表',NULL,NULL,NULL,NULL,31,NULL,'1','1',NULL,'2026-09-28 11:24:56','2026-09-28 11:24:56'),
(86,'订单履约流程事实表','FULFILLMENT_ORDER_FULFILLMENT_PROCESS_DA','DWD_FULFILLMENT_ORDER_FULFILLMENT_PROCESS_DA','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',12,79,1,NULL,'15699126319','吴同','15365128327',0,'订单履约流程事实表',NULL,NULL,NULL,NULL,34,NULL,'2','1',NULL,'2026-09-28 11:30:32','2026-09-28 11:30:32'),
(87,'客户日交易汇总表','customer2','dws_customer2','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',12,76,2,NULL,'15365128327','吴同','15365128327',0,'按客户粒度每日汇总下单、支付及退款相关指标，用于客户交易行为、支付情况及退款情况分析。',NULL,NULL,NULL,NULL,NULL,'1','1','1','客户','2026-09-28 14:34:16','2026-09-29 10:46:10'),
(88,'订单日资金汇总表','order_item','dws_order_item','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',12,78,2,NULL,'15699126319','吴同','15365128327',0,'按订单粒度每日汇总支付及退款相关指标，用于分析订单资金收付和退款情况。',NULL,NULL,NULL,NULL,NULL,'1','1','1','订单明细','2026-09-28 14:36:25','2026-09-29 10:46:08'),
(89,'商品日退款汇总表','product','dws_product','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',12,77,2,NULL,'15699126319','吴同','15365128327',0,'按商品粒度每日汇总退款业务指标，用于分析不同商品的退款数量和退款金额情况。',NULL,NULL,NULL,NULL,NULL,'1','1','1','商品','2026-09-28 14:38:16','2026-09-29 10:46:06'),
(90,'客户交易分析应用表','customer_trade_analysis','ads_customer_trade_analysis','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',12,76,2,NULL,'15699126319','吴同','15365128327',0,'面向客户交易分析场景，汇总每日及近期下单、支付、退款等核心交易指标，用户客户价值分析、客户行为分析及运营看板展示。',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-28 14:45:11','2026-09-28 14:45:11'),
(91,'客户近30日交易排行表','customer_trade_rank_30d','ads_customer_trade_rank_30d','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',12,78,2,NULL,'15365128327','吴同','15365128327',0,'面向客户交易排行榜应用场景，统计客户近30日支付金额、订单数量及退款金额，并形成客户交易排名结果。',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-28 14:55:31','2026-09-28 14:55:31'),
(92,'订单资金分析应用表','order_fund_analysis','ads_order_fund_analysis','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',12,78,2,NULL,'15699126319','吴同','15365128327',0,'面向订单资金分析场景，整合订单支付及退款指标，用于分析订单资金收入、退款情况及资金净额',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-28 14:59:42','2026-09-28 14:59:42'),
(93,'商品退款分析应用表','product_refund_analysis','ads_product_refund_analysis','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',12,78,2,NULL,'15365128327','吴同','15365128327',0,'面向商品退款分析场景，汇总商品当日及近期退款指标，用于商品质量分析、退款异常识别及商品运营优化。',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-28 15:02:20','2026-09-28 18:20:13'),
(94,'水文站维度表','HYDRO_STATION','DIM_HYDRO_STATION','FORWARD','DIMENSIONAL','DIM',4,'DIMENSION','CONFIRMED',29,113,1,NULL,'15365128327','吴同','15365128327',1,'水文站维度表',NULL,NULL,NULL,NULL,56,'1',NULL,'1',NULL,'2026-09-28 18:34:43','2026-09-28 18:34:43'),
(95,'水库维度表','RESERVOIR','DIM_RESERVOIR','FORWARD','DIMENSIONAL','DIM',4,'DIMENSION','CONFIRMED',29,114,1,1,'15888888888','超级管理员','15888888888',1,'水库维度表',NULL,NULL,NULL,NULL,61,'1',NULL,'1',NULL,'2026-09-28 18:47:05','2026-09-28 18:47:05'),
(96,'水位监测事实表','HYDROLOGY_WATER_LEVEL_COLLECT_DI','DWD_HYDROLOGY_WATER_LEVEL_COLLECT_DI','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',29,113,1,NULL,'15365128327','吴同','15365128327',0,'水位监测事实表',NULL,NULL,NULL,NULL,58,NULL,'1','1',NULL,'2026-09-28 19:04:08','2026-09-28 19:04:08'),
(97,'流量监测事实表','HYDROLOGY_FLOW_COLLECT_DI','DWD_HYDROLOGY_FLOW_COLLECT_DI','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',29,113,1,NULL,'15365128327','吴同','15365128327',0,'流量监测事实表',NULL,NULL,NULL,NULL,59,NULL,'1','1',NULL,'2026-09-28 19:05:55','2026-09-28 19:05:55'),
(98,'泵站运行事实表','RIVERLAKE_PUMP_OPERATION_DI','DWD_RIVERLAKE_PUMP_OPERATION_DI','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',29,115,1,1,'15888888888','超级管理员','15888888888',0,'泵站运行事实表',NULL,NULL,NULL,NULL,68,NULL,'1','1',NULL,'2026-09-28 19:08:32','2026-09-28 19:08:32'),
(99,'闸门运行事实表','RIVERLAKE_GATE_OPERATION_DI','DWD_RIVERLAKE_GATE_OPERATION_DI','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',29,115,1,NULL,'15699126319','吴同','15365128327',1,'闸门运行事实表',NULL,NULL,NULL,NULL,67,NULL,'1','1',NULL,'2026-09-28 19:10:48','2026-09-28 19:10:48'),
(100,'洪水预警事实表','FLOODCONTROL_FLOOD_WARNING_TRIGGER_DI','DWD_FLOODCONTROL_FLOOD_WARNING_TRIGGER_DI','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',29,117,1,NULL,'15699126319','吴同','15365128327',1,'洪水预警事实表',NULL,NULL,NULL,NULL,71,NULL,'1','1',NULL,'2026-09-28 19:12:32','2026-09-28 19:12:32'),
(101,'降雨监测事实表','HYDROLOGY_RAIN_COLLECT_DI','DWD_HYDROLOGY_RAIN_COLLECT_DI','FORWARD','RELATIONAL','DWD',5,'DETAIL','CONFIRMED',29,113,1,NULL,'15699126319','吴同','15365128327',0,'降雨监测事实表',NULL,NULL,NULL,NULL,60,NULL,'1','1',NULL,'2026-09-28 19:14:49','2026-09-29 13:49:20'),
(102,'预警等级维度表','FLOOD_WARNING','DIM_FLOOD_WARNING','FORWARD','DIMENSIONAL','DIM',4,'DIMENSION','CONFIRMED',29,117,1,NULL,'15699126319','吴同','15365128327',1,'预警等级维度表',NULL,NULL,NULL,NULL,70,'1',NULL,'1',NULL,'2026-09-28 19:19:03','2026-09-28 19:19:03'),
(103,'行政区划维度表','FLOOD_CONTROL_AREA','DIM_FLOOD_CONTROL_AREA','FORWARD','DIMENSIONAL','DIM',4,'DIMENSION','CONFIRMED',29,117,1,NULL,'15365128327','吴同','15365128327',0,'行政区划维度表',NULL,NULL,NULL,NULL,69,'2',NULL,'1',NULL,'2026-09-28 19:21:22','2026-10-20 09:33:17'),
(104,'会员月交易汇总表','member','dws_member','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',12,76,2,NULL,'15365128327','吴同','15365128327',0,'会员月交易汇总表',NULL,NULL,NULL,NULL,NULL,'1','1','1','会员','2026-09-29 09:23:24','2026-09-29 10:46:03'),
(105,'水文站日水情汇总表','hydro_station','dws_hydro_station','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',29,113,2,NULL,'15365128327','吴同','15365128327',0,'水文站日水情汇总表',NULL,NULL,NULL,NULL,NULL,'1','1','1','水文站','2026-09-29 09:47:05','2026-09-29 10:46:00'),
(106,'雨量站日降雨汇总表','rain_station','dws_rain_station','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',29,113,2,NULL,'15365128327','吴同','15365128327',0,'雨量站日降雨汇总表',NULL,NULL,NULL,NULL,NULL,'1','1','1','雨量站','2026-09-29 09:47:58','2026-09-29 10:45:56'),
(108,'水库日运行汇总表','reservoir','dws_reservoir','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',29,114,2,1,'15888888888','超级管理员','15888888888',0,'水库日运行汇总表',NULL,NULL,NULL,NULL,NULL,'1','1','1','水库','2026-09-29 09:58:51','2026-09-29 10:45:48'),
(110,'日活跃会员占比汇总','customer2_member','dws_customer2_member','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',12,76,2,1,'15888888888','超级管理员','15888888888',1,'日活跃会员占比汇总',NULL,NULL,NULL,NULL,NULL,'1','1','1','会员,客户','2026-09-29 10:11:10','2026-09-29 10:44:42'),
(112,'水雨情监测应用表','WATER_RAIN_MONITOR','ADS_WATER_RAIN_MONITOR','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',29,113,1,NULL,'15365128327','吴同','15365128327',0,'水雨情监测大屏',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-29 10:14:00','2026-09-29 10:14:00'),
(113,'水库运行监控应用表','RESERVOIR_OPERATION','ADS_RESERVOIR_OPERATION','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',29,114,1,NULL,'15699126319','吴同','15365128327',0,'水库运行监控应用表',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-29 10:15:12','2026-09-29 10:15:12'),
(114,'防汛预警应用表','FLOOD_WARNING_MONITOR','ADS_FLOOD_WARNING_MONITOR','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',29,117,1,NULL,'15365128327','吴同','15365128327',0,'防汛预警应用表',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-29 10:16:11','2026-09-29 10:16:11'),
(115,'水利综合运行驾驶舱','WATER_CONSERVANCY_OVERVIEW','ADS_WATER_CONSERVANCY_OVERVIEW','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',29,113,1,NULL,'15365128327','吴同','15365128327',0,'水利综合运行驾驶舱',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-29 10:17:32','2026-09-29 10:17:32'),
(118,'防汛预警处置明细应用表','FLOOD_WARNING_DETAIL','ADS_FLOOD_WARNING_DETAIL','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',29,117,1,NULL,'15699126319','吴同','15365128327',1,'防汛预警处置明细应用表',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-29 10:25:59','2026-09-29 10:25:59'),
(119,'商品退款趋势分析应用表','PRODUCT_REFUND_TREND_ANALYSIS','ADS_PRODUCT_REFUND_TREND_ANALYSIS','FORWARD','RELATIONAL','ADS',8,'APPLICATION','CONFIRMED',12,77,1,NULL,'15365128327','吴同','15365128327',1,'商品退款趋势分析应用表',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-09-29 10:29:30','2026-09-29 10:29:58'),
(122,'防汛责任区日预警汇总表','warning_publish','dws_warning_publish','FORWARD','RELATIONAL','DWS',6,'SUMMARY','CONFIRMED',29,117,2,NULL,'15699126319','吴同','15365128327',1,'防汛责任区日预警汇总表',NULL,NULL,NULL,NULL,NULL,'1','1','1','预警发布','2026-09-29 10:45:29','2026-09-29 10:45:29');
UPDATE dh_modeling_logic_model SET creator = '1', updater = '1';
SELECT setval('dh_modeling_logic_model_seq', (SELECT max(id) FROM dh_modeling_logic_model));

INSERT INTO dh_modeling_logic_model_column (model_id, field_uid, field_name, cn_name, data_type, data_length,
    data_scale, default_value, pk_flag, nullable_flag, sort, comment, creator, updater)
SELECT v.model_id, md5('qdata-logic-column-' || v.model_id || '-' || v.field_name), v.field_name, v.cn_name, v.data_type,
    v.data_length, v.data_scale, v.default_value, v.pk_flag, v.nullable_flag, v.sort, v.comment, '1', '1'
FROM (VALUES
(44,'ST_TYPE','水库类型','VARCHAR2',100,0,NULL,0,0,13,NULL),
(44,'COLUMN1','测试采集同步资产','VARCHAR2',100,0,NULL,0,0,12,NULL),
(44,'MSQMT','测流方法','CHAR',1,0,NULL,0,0,11,NULL),
(44,'INQDR','入流时段长','NUMBER',22,2,NULL,0,0,10,NULL),
(44,'RWPTN','库水水势','CHAR',1,0,NULL,0,0,9,NULL),
(44,'RWCHRCD','库水特征码','CHAR',1,0,NULL,0,0,8,NULL),
(44,'OTQ','出库流量','NUMBER',22,3,NULL,0,0,7,NULL),
(44,'BLRZ','库下水位','NUMBER',22,3,NULL,0,0,6,NULL),
(44,'W','蓄水量','NUMBER',22,3,NULL,0,0,5,NULL),
(44,'INQ','入库流量','NUMBER',22,3,NULL,0,0,4,NULL),
(44,'RZ','库水位','NUMBER',22,3,NULL,0,0,3,NULL),
(44,'TM','时间','DATETIME',8,6,NULL,1,1,2,NULL),
(44,'ST_CODE','测站编码','VARCHAR2',18,0,NULL,1,1,1,NULL),
(45,'ADD_C2','添加字段2','VARCHAR2',100,0,NULL,0,0,14,NULL),
(45,'ADD_C1','字段1','VARCHAR2',128,0,NULL,0,0,13,NULL),
(45,'MSVMT','测速方法','CHAR',1,0,NULL,0,0,12,NULL),
(45,'MSAMT','测积方法','CHAR',1,0,NULL,0,0,11,NULL),
(45,'MSQMT','测流方法','CHAR',1,0,NULL,0,0,10,NULL),
(45,'WPTN','水势','CHAR',1,0,NULL,0,0,9,NULL),
(45,'FLWCHRCD','河水特征码','CHAR',1,0,NULL,0,0,8,NULL),
(45,'XSMXV','断面最大流速','NUMBER',22,3,NULL,0,0,7,NULL),
(45,'XSAVV','断面平均流速','NUMBER',22,3,NULL,0,0,6,NULL),
(45,'XSA','断面过水面积','NUMBER',22,3,NULL,0,0,5,NULL),
(45,'Q','流量','NUMBER',22,3,NULL,0,0,4,NULL),
(45,'Z','水位','NUMBER',22,3,NULL,0,0,3,NULL),
(45,'TM','时间','DATETIME',8,6,NULL,1,1,2,NULL),
(45,'ST_CODE','测站编码','VARCHAR2',18,0,NULL,1,1,1,NULL),
(46,'ST_TYPE','水库类型','VARCHAR2',100,0,NULL,0,0,13,NULL),
(46,'COLUMN1','测试采集同步资产','VARCHAR2',100,0,NULL,0,0,12,NULL),
(46,'MSQMT','测流方法','CHAR',1,0,NULL,0,0,11,NULL),
(46,'INQDR','入流时段长','NUMBER',22,2,NULL,0,0,10,NULL),
(46,'RWPTN','库水水势','CHAR',1,0,NULL,0,0,9,NULL),
(46,'RWCHRCD','库水特征码','CHAR',1,0,NULL,0,0,8,NULL),
(46,'OTQ','出库流量','NUMBER',22,3,NULL,0,0,7,NULL),
(46,'BLRZ','库下水位','NUMBER',22,3,NULL,0,0,6,NULL),
(46,'W','蓄水量','NUMBER',22,3,NULL,0,0,5,NULL),
(46,'INQ','入库流量','NUMBER',22,3,NULL,0,0,4,NULL),
(46,'RZ','库水位','NUMBER',22,3,NULL,0,0,3,NULL),
(46,'TM','时间','DATETIME',8,6,NULL,1,1,2,NULL),
(46,'ST_CODE','测站编码','VARCHAR2',18,0,NULL,1,1,1,NULL),
(51,'customer_name','客户名称','VARCHAR',NULL,NULL,NULL,0,0,2,NULL),
(51,'customer_id','客户ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(53,'discount_amount','优惠金额','DECIMAL',NULL,NULL,NULL,0,0,7,NULL),
(53,'order_amount','实付订单金额','DECIMAL',11,1,'1',0,0,2,NULL),
(53,'customer_id','下单客户','VARCHAR',11,1,'11',0,1,3,NULL),
(53,'pay_status','支付状态','CHAR',NULL,NULL,NULL,0,0,10,NULL),
(53,'valid_order','订单状态','CHAR',NULL,NULL,NULL,0,0,9,NULL),
(53,'order_channel','下单渠道','CHAR',NULL,NULL,NULL,0,0,8,NULL),
(53,'original_amount','商品原价','NUMERIC',NULL,NULL,NULL,0,0,6,NULL),
(53,'product_count','商品数量','INTEGER',11,1,NULL,0,1,5,NULL),
(53,'order_time','下单时间','DATETIME',11,1,'11',0,0,4,NULL),
(53,'order_id','订单ID','NUMBER',11,1,'1',1,1,1,NULL),
(59,'CUSTOMER_id','客户编码','VARCHAR2',32,NULL,NULL,1,1,2,'在平台产生注册、浏览、购买等行为的客户主体'),
(59,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(60,'RVSREL','河源高程','VARCHAR2',1024,0,NULL,0,0,6,NULL),
(60,'LVBSLV','水准基面','VARCHAR2',1024,0,NULL,0,0,5,NULL),
(60,'USER_ID','用水户编码','VARCHAR2',1024,0,NULL,0,0,4,NULL),
(60,'RVPL','河源位置','VARCHAR2',1024,0,NULL,0,0,3,NULL),
(60,'RVNM','河流名称','VARCHAR2',1024,0,NULL,1,1,2,NULL),
(60,'RVCD','河流代码','VARCHAR2',1024,0,NULL,1,1,1,NULL),
(60,'RVTRPL','河口位置','VARCHAR2',1024,0,NULL,0,0,7,NULL),
(62,'SENDDT',NULL,'DATETIME',8,0,NULL,1,1,20,NULL),
(62,'OBJNAME','告警对象名称','VARCHAR2',50,0,NULL,0,0,19,NULL),
(62,'MONITORDATA','检测值','VARCHAR2',50,0,NULL,0,0,18,NULL),
(62,'WARNDATA','告警阈值','VARCHAR2',50,0,NULL,0,0,17,NULL),
(62,'HAPPENTIME','告警发生时间','VARCHAR2',36,0,NULL,0,0,16,NULL),
(62,'OBJCODE','告警水利对象编码','VARCHAR2',50,0,NULL,1,1,15,NULL),
(62,'ACTIVE','是否为处置','VARCHAR2',2,0,NULL,0,0,14,NULL),
(62,'ADDVCD','事件所在行政区划编码','VARCHAR2',50,0,NULL,0,0,13,NULL),
(62,'EVENTFLAG','事件标记','VARCHAR2',50,0,NULL,0,0,12,NULL),
(62,'AREADESC','涉及行政区划编码集合','VARCHAR2',50,0,NULL,0,0,11,NULL),
(62,'Y','经度','VARCHAR2',50,0,NULL,0,0,10,NULL),
(62,'X','纬度','VARCHAR2',50,0,NULL,0,0,9,NULL),
(62,'SEVERITY','告警级别','VARCHAR2',50,0,NULL,1,1,8,NULL),
(62,'SENDER','发布单位','VARCHAR2',50,0,NULL,0,0,7,NULL),
(62,'SENDTIME','告警信息生成发布时间','VARCHAR2',36,0,NULL,0,0,6,NULL),
(62,'IDENTIFIER','事件戳','VARCHAR2',100,0,NULL,0,0,5,NULL),
(62,'HEADLINE','标题','VARCHAR2',300,0,NULL,0,0,4,NULL),
(62,'EVENTTYPE','告警事件类型','VARCHAR2',64,0,NULL,1,1,3,NULL),
(62,'TIP','内容详情','VARCHAR2',4000,0,NULL,0,0,2,NULL),
(62,'ID','序号','NUMBER',8,0,NULL,0,1,1,NULL),
(63,'INDEXS',NULL,'NUMBER',4,0,'0',0,0,33,NULL),
(63,'MARKER_OFFSET','弹层偏移量','VARCHAR2',30,0,NULL,0,0,32,NULL),
(63,'ECOLOGY',NULL,'CHAR',1,0,NULL,0,0,31,NULL),
(63,'ROTATE','偏移角度','VARCHAR2',50,0,NULL,0,0,30,NULL),
(63,'OFFSET','偏移量','VARCHAR2',50,0,NULL,0,0,29,NULL),
(63,'STCD_OLD',NULL,'VARCHAR2',10,0,NULL,0,0,28,NULL),
(63,'MODITIME','时间戳','DATETIME',8,6,NULL,0,0,27,NULL),
(63,'COMMENTS','备注','VARCHAR2',200,0,NULL,0,0,26,NULL),
(63,'USFL','启用标志','CHAR',1,0,NULL,0,0,25,NULL),
(63,'PHCD','拼音码','CHAR',6,0,NULL,0,0,24,NULL),
(63,'DRNA','集水面积','NUMBER',22,0,NULL,0,0,23,NULL),
(63,'DSTRVM','至河口距离','NUMBER',22,1,NULL,0,0,22,NULL),
(63,'STAZT','测站方位','NUMBER',22,0,NULL,0,0,21,NULL),
(63,'STBK','测站岸别','CHAR',1,0,NULL,0,0,20,NULL),
(63,'LOCALITY','交换管理单位','CHAR',10,0,NULL,0,0,19,NULL),
(63,'ADMAUTH','信息管理单位','VARCHAR2',50,0,NULL,0,0,18,NULL),
(63,'ATCUNIT','隶属行业单位','VARCHAR2',50,0,NULL,0,0,17,NULL),
(63,'BGFRYM','始报年月','CHAR',6,0,NULL,0,0,16,NULL),
(63,'ESSTYM','建站年月','CHAR',6,0,NULL,0,0,15,NULL),
(63,'FRGRD','报汛等级','CHAR',1,0,NULL,0,0,14,NULL),
(63,'STTP','站类','CHAR',2,0,NULL,0,0,13,NULL),
(63,'DTPR','基面修正值','NUMBER',22,3,NULL,0,0,12,NULL),
(63,'DTMEL','基面高程','NUMBER',22,3,NULL,0,0,11,NULL),
(63,'DTMNM','基面名称','CHAR',16,0,NULL,0,0,10,NULL),
(63,'ADDVCD','行政区划码','CHAR',6,0,NULL,0,0,9,NULL),
(63,'STLC','站址','CHAR',50,0,NULL,0,0,8,NULL),
(63,'LTTD','纬度','NUMBER',22,6,NULL,0,0,7,NULL),
(63,'LGTD','经度','NUMBER',22,6,NULL,0,0,6,NULL),
(63,'BSNM','流域名称','CHAR',30,0,NULL,0,0,5,NULL),
(63,'HNNM','水系名称','CHAR',30,0,NULL,0,0,4,NULL),
(63,'RVNM','河流名称','CHAR',30,0,NULL,0,0,3,NULL),
(63,'STNM','测站名称','CHAR',30,0,NULL,0,0,2,NULL),
(63,'ST_CODE','测站编码','VARCHAR2',18,0,NULL,1,1,1,NULL),
(64,'WTH','天气状况','CHAR',1,0,NULL,0,0,7,NULL),
(64,'DYP','日降水量','NUMBER',22,1,NULL,0,0,6,NULL),
(64,'PDR','降水历时','NUMBER',22,2,NULL,0,0,5,NULL),
(64,'INTV','时段长','NUMBER',22,2,NULL,0,0,4,NULL),
(64,'DRP','时段降水量','NUMBER',22,1,NULL,0,0,3,NULL),
(64,'IDTM','标志时间','DATETIME',8,6,NULL,1,1,2,NULL),
(64,'ST_CODE','测站编码','VARCHAR2',18,0,NULL,1,1,1,NULL),
(70,'DEL_FLAG',NULL,'VARCHAR2',1,0,'''0''',0,1,19,NULL),
(70,'REMARK','备注','VARCHAR2',512,0,NULL,0,0,18,NULL),
(70,'UPDATE_TIME','更新时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,17,NULL),
(70,'UPDATER_ID','更新人id','NUMBER',8,0,NULL,0,0,16,NULL),
(70,'UPDATE_BY','更新人','VARCHAR2',32,0,NULL,0,0,15,NULL),
(70,'CREATE_TIME','创建时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,14,NULL),
(70,'CREATOR_ID','创建人id','NUMBER',8,0,NULL,0,0,13,NULL),
(70,'CREATE_BY','创建人','VARCHAR2',32,0,NULL,0,0,12,NULL),
(70,'VALID_FLAG','是否有效;0：无效，1：有效','VARCHAR2',1,0,'1',0,1,11,NULL),
(70,'DESCRIPTION','描述','VARCHAR2',512,0,NULL,0,0,10,NULL),
(70,'DATA_SIZE','同步数据量大小（预留）','NUMBER',4,0,'0',0,0,9,NULL),
(70,'SYNC_COUNT','同步记录数（预留）','NUMBER',4,0,'0',0,0,8,NULL),
(70,'LIST_COUNT','数据库表数（预留）','NUMBER',4,0,'0',0,0,7,NULL),
(70,'PORT','端口号','NUMBER',4,0,NULL,0,0,6,NULL),
(70,'IP','IP','VARCHAR2',32,0,NULL,0,0,5,NULL),
(70,'DATASOURCE_CONFIG','数据源配置(json字符串)','VARCHAR2',3072,0,NULL,0,0,4,NULL),
(70,'DATASOURCE_TYPE','数据源类型','VARCHAR2',32,0,NULL,0,1,3,NULL),
(70,'DATASOURCE_NAME','数据源名称','VARCHAR2',128,0,NULL,0,1,2,NULL),
(70,'ID','ID','NUMBER',8,0,NULL,1,1,1,NULL),
(71,'ST_FWQ','日水量','DOUBLE',8,0,NULL,0,0,14,NULL),
(71,'ST_INQ','日流量','DOUBLE',8,0,'0.',0,0,13,NULL),
(71,'ST_CODE','水库编码','VARCHAR2',100,0,NULL,0,1,12,NULL),
(71,'REMARK','备注','VARCHAR2',512,0,NULL,0,0,11,NULL),
(71,'UPDATE_TIME','更新时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,10,NULL),
(71,'UPDATE_ID','更新人ID','NUMBER',8,0,NULL,0,0,9,NULL),
(71,'UPDATE_BY','更新人','VARCHAR2',64,0,NULL,0,0,8,NULL),
(71,'CREATOR_ID','创建人ID','NUMBER',8,0,NULL,0,0,7,NULL),
(71,'CREATE_TIME','创建时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,6,NULL),
(71,'CREATE_BY','创建人','VARCHAR2',64,0,NULL,0,0,5,NULL),
(71,'DEL_FLAG','删除标志','VARCHAR2',10,0,'''0''',0,1,4,NULL),
(71,'VALID_FLAG','是否有效','VARCHAR2',10,0,'''1''',0,1,3,NULL),
(71,'SOURCE','来源','VARCHAR2',10,0,NULL,0,0,2,NULL),
(71,'MDM_ID','主数据ID','NUMBER',8,0,NULL,1,1,1,NULL),
(72,'SOURECES','来源','VARCHAR2',200,0,NULL,0,0,18,NULL),
(72,'ZQSL','总取水量','NUMBER',10,2,'0.',0,0,17,NULL),
(72,'QSL','取水量','NUMBER',8,0,'0',0,0,16,NULL),
(72,'DRL','流量','DOUBLE',8,0,'0.',0,0,15,NULL),
(72,'RSVR','所属河流','VARCHAR2',100,0,NULL,0,0,14,NULL),
(72,'ADDR','水库位置','VARCHAR2',100,0,NULL,0,0,13,NULL),
(72,'BSCODE','水库编码','VARCHAR2',100,0,NULL,0,1,12,NULL),
(72,'REMARK','备注','VARCHAR2',512,0,NULL,0,0,11,NULL),
(72,'UPDATE_TIME','更新时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,10,NULL),
(72,'UPDATE_ID','更新人ID','NUMBER',8,0,NULL,0,0,9,NULL),
(72,'UPDATE_BY','更新人','VARCHAR2',64,0,NULL,0,0,8,NULL),
(72,'CREATOR_ID','创建人ID','NUMBER',8,0,NULL,0,0,7,NULL),
(72,'CREATE_TIME','创建时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,6,NULL),
(72,'CREATE_BY','创建人','VARCHAR2',64,0,NULL,0,0,5,NULL),
(72,'DEL_FLAG','删除标志','VARCHAR2',10,0,'''0''',0,1,4,NULL),
(72,'VALID_FLAG','是否有效','VARCHAR2',10,0,'''1''',0,1,3,NULL),
(72,'SOURCE','来源','VARCHAR2',10,0,NULL,0,0,2,NULL),
(72,'MDM_ID','主数据ID','NUMBER',8,0,NULL,1,1,1,NULL),
(74,'ST_TYPE','水库类型','VARCHAR2',100,0,NULL,0,0,13,NULL),
(74,'COLUMN1','测试采集同步资产','VARCHAR2',100,0,NULL,0,0,12,NULL),
(74,'MSQMT','测流方法','CHAR',1,0,NULL,0,0,11,NULL),
(74,'INQDR','入流时段长','NUMBER',22,2,NULL,0,0,10,NULL),
(74,'RWPTN','库水水势','CHAR',1,0,NULL,0,0,9,NULL),
(74,'RWCHRCD','库水特征码','CHAR',1,0,NULL,0,0,8,NULL),
(74,'OTQ','出库流量','NUMBER',22,3,NULL,0,0,7,NULL),
(74,'BLRZ','库下水位','NUMBER',22,3,NULL,0,0,6,NULL),
(74,'W','蓄水量','NUMBER',22,3,NULL,0,0,5,NULL),
(74,'INQ','入库流量','NUMBER',22,3,NULL,0,0,4,NULL),
(74,'RZ','库水位','NUMBER',22,3,NULL,0,0,3,NULL),
(74,'TM','时间','DATETIME',8,6,NULL,1,1,2,NULL),
(74,'ST_CODE','测站编码','VARCHAR2',18,0,NULL,1,1,1,NULL),
(75,'ADD_C2','添加字段2','VARCHAR2',100,0,NULL,0,1,25,NULL),
(75,'ADD_C1','字段1','VARCHAR2',128,0,NULL,0,1,24,NULL),
(75,'MSVMT','测速方法','CHAR',1,0,NULL,0,1,23,NULL),
(75,'MSAMT','测积方法','CHAR',1,0,NULL,0,1,22,NULL),
(75,'MSQMT','测流方法','CHAR',1,0,NULL,0,1,21,NULL),
(75,'WPTN','水势','CHAR',1,0,NULL,0,1,20,NULL),
(75,'FLWCHRCD','河水特征码','CHAR',1,0,NULL,0,1,19,NULL),
(75,'XSMXV','断面最大流速','NUMBER',22,3,NULL,0,1,18,NULL),
(75,'XSAVV','断面平均流速','NUMBER',22,3,NULL,0,1,17,NULL),
(75,'XSA','断面过水面积','NUMBER',22,3,NULL,0,1,16,NULL),
(75,'Q','流量','NUMBER',22,3,NULL,0,1,15,NULL),
(75,'Z','水位','NUMBER',22,3,NULL,0,1,14,NULL),
(75,'TM','时间','DATETIME',8,6,NULL,1,1,13,NULL),
(75,'ST_CODE','测站编码','VARCHAR2',18,0,NULL,1,1,12,NULL),
(75,'REMARK','备注','VARCHAR2',512,0,NULL,0,0,11,NULL),
(75,'UPDATE_TIME','更新时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,10,NULL),
(75,'UPDATE_ID','更新人ID','NUMBER',8,0,NULL,0,0,9,NULL),
(75,'UPDATE_BY','更新人','VARCHAR2',64,0,NULL,0,0,8,NULL),
(75,'CREATOR_ID','创建人ID','NUMBER',8,0,NULL,0,0,7,NULL),
(75,'CREATE_TIME','创建时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,6,NULL),
(75,'CREATE_BY','创建人','VARCHAR2',64,0,NULL,0,0,5,NULL),
(75,'DEL_FLAG','删除标志','VARCHAR2',10,0,'''0''',0,1,4,NULL),
(75,'VALID_FLAG','是否有效','VARCHAR2',10,0,'''1''',0,1,3,NULL),
(75,'SOURCE','来源','VARCHAR2',10,0,NULL,0,0,2,NULL),
(75,'MDM_ID','主数据ID','NUMBER',8,0,NULL,1,1,1,NULL),
(76,'SENDDT','SENDDT','DATETIME',8,6,NULL,0,0,31,NULL),
(76,'OBJNAME','告警对象名称','VARCHAR2',50,0,NULL,0,1,30,NULL),
(76,'MONITORDATA','检测值','VARCHAR2',50,0,NULL,0,1,29,NULL),
(76,'WARNDATA','告警阈值','VARCHAR2',50,0,NULL,0,1,28,NULL),
(76,'HAPPENTIME','告警发生时间','VARCHAR2',36,0,NULL,0,1,27,NULL),
(76,'OBJCODE','告警水利对象编码','VARCHAR2',50,0,NULL,0,0,26,NULL),
(76,'ACTIVE','是否为处置','VARCHAR2',2,0,NULL,0,1,25,NULL),
(76,'ADDVCD','事件所在行政区划编码','VARCHAR2',50,0,NULL,0,1,24,NULL),
(76,'EVENTFLAG','事件标记','VARCHAR2',50,0,NULL,0,1,23,NULL),
(76,'AREADESC','涉及行政区划编码集合','VARCHAR2',50,0,NULL,0,1,22,NULL),
(76,'Y','经度','VARCHAR2',50,0,NULL,0,1,21,NULL),
(76,'X','纬度','VARCHAR2',50,0,NULL,0,1,20,NULL),
(76,'SEVERITY','告警级别','VARCHAR2',50,0,NULL,0,0,19,NULL),
(76,'SENDER','发布单位','VARCHAR2',50,0,NULL,0,1,18,NULL),
(76,'SENDTIME','告警信息生成发布时间','VARCHAR2',36,0,NULL,0,1,17,NULL),
(76,'IDENTIFIER','事件戳','VARCHAR2',100,0,NULL,0,1,16,NULL),
(76,'HEADLINE','标题','VARCHAR2',300,0,NULL,0,1,15,NULL),
(76,'EVENTTYPE','告警事件类型','VARCHAR2',64,0,NULL,0,0,14,NULL),
(76,'TIP','内容详情','VARCHAR2',4000,0,NULL,0,1,13,NULL),
(76,'ID','序号','NUMBER',8,0,NULL,1,1,12,NULL),
(76,'REMARK','备注','VARCHAR2',512,0,NULL,0,0,11,NULL),
(76,'UPDATE_TIME','更新时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,10,NULL),
(76,'UPDATE_ID','更新人ID','NUMBER',8,0,NULL,0,0,9,NULL),
(76,'UPDATE_BY','更新人','VARCHAR2',64,0,NULL,0,0,8,NULL),
(76,'CREATOR_ID','创建人ID','NUMBER',8,0,NULL,0,0,7,NULL),
(76,'CREATE_TIME','创建时间','DATETIME',8,6,'CURRENT_TIMESTAMP',0,1,6,NULL),
(76,'CREATE_BY','创建人','VARCHAR2',64,0,NULL,0,0,5,NULL),
(76,'DEL_FLAG','删除标志','VARCHAR2',10,0,'''0''',0,1,4,NULL),
(76,'VALID_FLAG','是否有效','VARCHAR2',10,0,'''1''',0,1,3,NULL),
(76,'SOURCE','来源','VARCHAR2',10,0,NULL,0,0,2,NULL),
(76,'MDM_ID','主数据ID','NUMBER',8,0,NULL,1,1,1,NULL),
(81,'join_time','入会时间','DATETIME',10,NULL,NULL,0,0,6,NULL),
(81,'member_status','会员状态','CHAR',1,NULL,NULL,0,0,5,NULL),
(81,'member_points','当前积分','DECIMAL',19,2,NULL,0,0,4,NULL),
(81,'member_level','会员等级','VARCHAR',10,NULL,NULL,0,0,3,NULL),
(81,'customer_id','客户ID','BIGINT',19,NULL,NULL,0,0,2,NULL),
(81,'member_id','会员ID','NUMBER',19,NULL,NULL,1,1,1,NULL),
(82,'leaf_flag','是否叶子节点','CHAR',1,NULL,NULL,0,0,6,NULL),
(82,'category_path','完整路径','VARCHAR',128,NULL,NULL,0,0,5,NULL),
(82,'category_level','层级','INTEGER',10,NULL,NULL,0,0,4,NULL),
(82,'parent_category_id','父类目ID','BIGINT',19,NULL,NULL,0,0,3,NULL),
(82,'category_name','类目名称','VARCHAR',128,NULL,NULL,0,0,2,NULL),
(82,'product_category_id','商品类目ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(83,'shelf_time','上架时间','DATETIME',10,NULL,NULL,0,0,7,NULL),
(83,'product_status','商品状态','CHAR',1,NULL,NULL,0,0,6,NULL),
(83,'sale_price','销售价格','VARCHAR',128,NULL,NULL,0,0,5,NULL),
(83,'brand_name','品牌','VARCHAR',128,NULL,NULL,0,0,4,NULL),
(83,'category_id','商品类目','INTEGER',10,NULL,NULL,0,0,3,NULL),
(83,'product_name','商品名称','VARCHAR',128,NULL,NULL,0,0,2,NULL),
(83,'product_id','商品ID','NUMBER',19,NULL,NULL,1,1,1,NULL),
(84,'pay_status','支付状态','CHAR',1,NULL,NULL,0,0,7,NULL),
(84,'pay_amount','支付金额','DECIMAL',20,2,NULL,0,0,6,NULL),
(84,'pay_channel','支付渠道','VARCHAR',10,NULL,NULL,0,0,5,NULL),
(84,'pay_time','支付时间','DATETIME',10,NULL,NULL,0,0,4,NULL),
(84,'customer_id','客户','BIGINT',10,NULL,NULL,0,0,3,NULL),
(84,'order_id','订单','BIGINT',10,NULL,NULL,0,0,2,NULL),
(84,'order_payment_id','订单支付ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(85,'refund_status','状态','CHAR',1,NULL,NULL,0,0,9,NULL),
(85,'refund_reason','退款原因','VARCHAR',256,NULL,NULL,0,0,8,NULL),
(85,'refund_amount','退款金额','DECIMAL',20,NULL,NULL,0,0,7,NULL),
(85,'refund_time','退款时间','DATETIME',20,NULL,NULL,0,0,6,NULL),
(85,'product_id','商品','BIGINT',20,NULL,NULL,0,0,5,NULL),
(85,'customer_id','客户','BIGINT',20,NULL,NULL,0,0,4,NULL),
(85,'order_item_id','订单明细ID','BIGINT',20,NULL,NULL,0,0,3,NULL),
(85,'order_id','订单ID','BIGINT',20,NULL,NULL,0,0,2,NULL),
(85,'order_refund_id','订单退款ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(86,'final_status','最终状态','CHAR',1,NULL,NULL,0,0,11,NULL),
(86,'fulfillment_duration','总履约耗时','BIGINT',20,NULL,NULL,0,0,10,NULL),
(86,'ship_duration','发货耗时','BIGINT',20,NULL,NULL,0,0,9,NULL),
(86,'pay_duration','支付耗时','BIGINT',20,NULL,NULL,0,0,8,NULL),
(86,'order_amount','订单金额','DECIMAL',20,2,NULL,0,0,7,NULL),
(86,'receive_time','签收时间','DATETIME',20,NULL,NULL,0,0,6,NULL),
(86,'ship_time','发货时间','DATETIME',20,NULL,NULL,0,0,5,NULL),
(86,'pay_time','支付时间','DATETIME',20,NULL,NULL,0,0,4,NULL),
(86,'order_time','下单时间','DATETIME',20,NULL,NULL,0,0,3,NULL),
(86,'customer_id','下单客户','BIGINT',20,NULL,NULL,0,0,2,NULL),
(86,'order_fulfillment_process_id','订单履约流程ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(87,'customer2_id','客户2编码','VARCHAR2',32,NULL,NULL,1,1,2,NULL),
(87,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(88,'order_item_id','订单明细编码','VARCHAR2',32,NULL,NULL,1,1,2,NULL),
(88,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(89,'product_id','商品编码','VARCHAR2',32,NULL,NULL,1,1,2,'平台实际销售的商品'),
(89,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(90,'day_success_pay_cnt','单日成功支付次数','INTEGER',NULL,NULL,NULL,0,0,5,NULL),
(90,'day_app_order_cnt','当日APP订单数','INTEGER',NULL,NULL,NULL,0,0,4,NULL),
(90,'member_level','会员等级','VARCHAR',10,NULL,NULL,0,0,3,NULL),
(90,'customer_id','客户ID','INTEGER',20,NULL,NULL,0,0,2,NULL),
(90,'ds','统计日期','DATETIME',NULL,NULL,NULL,0,0,1,NULL),
(91,'pay_rank','支付金额排名','INTEGER',20,NULL,NULL,0,0,7,NULL),
(91,'refund_amount_30d','近30日退款金额','DECIMAL',20,2,NULL,0,0,6,NULL),
(91,'pay_amount_30d','近30日支付金额','DECIMAL',20,2,NULL,0,0,5,NULL),
(91,'order_cnt_30d','近30日订单数','INTEGER',20,NULL,NULL,0,0,4,NULL),
(91,'member_level','会员等级','VARCHAR',10,NULL,NULL,0,0,3,NULL),
(91,'customer_id','客户ID','BIGINT',0,NULL,NULL,0,0,2,NULL),
(91,'ds','统计日期','DATETIME',NULL,NULL,NULL,0,0,1,NULL),
(92,'refund_rate','退款金额占比','VARCHAR',10,NULL,NULL,0,0,9,NULL),
(92,'net_amount','订单净收入金额','DECIMAL',20,2,NULL,0,0,8,NULL),
(92,'success_refund_amount','成功退款金额','DECIMAL',20,2,NULL,0,0,7,NULL),
(92,'success_refund_cnt','成功退款笔数','INTEGER',20,NULL,NULL,0,0,6,NULL),
(92,'success_pay_amount','成功支付金额','DECIMAL',20,2,NULL,0,0,5,NULL),
(92,'success_pay_cnt','成功支付流水数','INTEGER',20,NULL,NULL,0,0,4,NULL),
(92,'customer_id','客户ID','BIGINT',20,NULL,NULL,0,0,3,NULL),
(92,'order_id','订单ID','BIGINT',20,NULL,NULL,0,0,2,NULL),
(92,'ds','统计日期','DATETIME',NULL,NULL,NULL,0,0,1,NULL),
(93,'asaaa','afsdfasdfas','VARCHAR',NULL,NULL,NULL,0,0,9,NULL),
(93,'refund_amount_30d','近30日成功退款金额','DECIMAL',20,2,NULL,0,0,8,NULL),
(93,'refund_amount_7d','近7日成功退款金额','DECIMAL',20,2,NULL,0,0,7,NULL),
(93,'refund_amount_day','当日成功退款金额','DECIMAL',20,2,NULL,0,0,6,NULL),
(93,'refund_cnt_day','当日成功退款笔数','INTEGER',20,NULL,NULL,0,0,5,NULL),
(93,'brand_name','品牌名称','VARCHAR',128,NULL,NULL,0,0,4,NULL),
(93,'category_id','商品类目ID','BIGINT',20,NULL,NULL,0,0,3,NULL),
(93,'product_id','商品ID','BIGINT',20,NULL,NULL,0,0,2,NULL),
(93,'ds','统计日期','DATETIME',NULL,NULL,NULL,0,0,1,NULL),
(94,'build_date','建站日期','DATETIME',NULL,NULL,NULL,0,0,12,NULL),
(94,'enable_status','启用状态','CHAR',1,NULL,NULL,0,0,11,NULL),
(94,'guarantee_level','保证水位','DECIMAL',NULL,NULL,NULL,0,0,10,NULL),
(94,'warning_level','警戒水位','NUMERIC',NULL,NULL,NULL,0,0,9,NULL),
(94,'station_type','站点类型','VARCHAR',128,NULL,NULL,0,0,8,NULL),
(94,'latitude','纬度','VARCHAR',128,NULL,NULL,0,0,7,NULL),
(94,'longitude','经度','VARCHAR',128,NULL,NULL,0,0,6,NULL),
(94,'region_id','行政区划ID','INTEGER',NULL,NULL,NULL,0,0,5,NULL),
(94,'river_id','所属河流ID','BIGINT',NULL,NULL,NULL,0,0,4,NULL),
(94,'station_name','水文站名称','VARCHAR',NULL,NULL,NULL,0,0,3,NULL),
(94,'station_code','水文站编码','VARCHAR',128,NULL,NULL,0,0,2,NULL),
(94,'hydro_station_id','水文站ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(95,'dead_level','死水位','VARCHAR',64,NULL,NULL,0,0,10,NULL),
(95,'flood_high_level','防洪高水位','VARCHAR',64,NULL,NULL,0,0,9,NULL),
(95,'flood_limit_level','汛限水位','VARCHAR',64,NULL,NULL,0,0,8,NULL),
(95,'normal_level','正常蓄水位','VARCHAR',64,NULL,NULL,0,0,7,NULL),
(95,'total_capacity','总库容','VARCHAR',64,NULL,NULL,0,0,6,NULL),
(95,'river_id','所属河流ID','BIGINT',NULL,NULL,NULL,0,0,5,NULL),
(95,'reservoir_level','水库等级','INTEGER',NULL,NULL,NULL,0,0,4,NULL),
(95,'reservoir_name','水库名称','VARCHAR',128,NULL,NULL,0,0,3,NULL),
(95,'reservoir_code','水库编码','VARCHAR',128,NULL,NULL,0,0,2,NULL),
(95,'reservoir_id','水库ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(95,'region_id','所属区域ID','VARCHAR',10,NULL,NULL,0,0,11,NULL),
(95,'enable_status','启用状态','CHAR',1,NULL,NULL,0,0,12,NULL),
(96,'data_date','数据日期','DATETIME',NULL,NULL,NULL,0,0,13,NULL),
(96,'quality_flag','数据质量标识','VARCHAR',64,NULL,NULL,0,0,12,NULL),
(96,'over_guarantee_flag','超保证标识','VARCHAR',64,NULL,NULL,0,0,11,NULL),
(96,'over_warning_flag','超警戒标识','VARCHAR',64,NULL,NULL,0,0,10,NULL),
(96,'over_guarantee_height','超保证高度','VARCHAR',64,NULL,NULL,0,0,9,NULL),
(96,'over_warning_height','超警戒高度','VARCHAR',64,NULL,NULL,0,0,8,NULL),
(96,'guarantee_level','保证水位','VARCHAR',64,NULL,NULL,0,0,7,NULL),
(96,'warning_level','警戒水位','VARCHAR',64,NULL,NULL,0,0,6,NULL),
(96,'water_level','当前水位','VARCHAR',64,NULL,NULL,0,0,5,NULL),
(96,'monitor_time','监测时间','DATETIME',NULL,NULL,NULL,0,0,4,NULL),
(96,'river_id','河流ID','BIGINT',NULL,NULL,NULL,0,0,3,NULL),
(96,'station_id','水文站ID','INTEGER',NULL,NULL,NULL,0,0,2,NULL),
(96,'water_level_collect_id','水位采集ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(97,'flow_collect_id','流量采集ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(98,'data_date','数据日期','DATETIME',NULL,NULL,NULL,0,0,8,NULL),
(98,'current_power','当前功率','VARCHAR',64,NULL,NULL,0,0,7,NULL),
(98,'instant_flow','瞬时流量','VARCHAR',64,NULL,NULL,0,0,6,NULL),
(98,'running_pump_cnt','运行泵机数','VARCHAR',64,NULL,NULL,0,0,5,NULL),
(98,'operation_type','操作类型','VARCHAR',64,NULL,NULL,0,0,4,NULL),
(98,'operation_time','操作时间','VARCHAR',NULL,NULL,NULL,0,0,3,NULL),
(98,'pump_station_id','泵站ID','BIGINT',NULL,NULL,NULL,0,0,2,NULL),
(98,'pump_operation_id','泵站启停ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(99,'operation_time','操作时间','VARCHAR',NULL,NULL,NULL,0,0,3,NULL),
(99,'gate_id','闸门ID','VARCHAR',NULL,NULL,NULL,0,0,2,NULL),
(99,'gate_operation_id','闸门启闭ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(100,'trigger_time','触发时间','VARCHAR',NULL,NULL,NULL,0,0,4,NULL),
(100,'river_id','河流ID','VARCHAR',NULL,NULL,NULL,0,0,3,NULL),
(100,'area_id','责任区ID','VARCHAR',NULL,NULL,NULL,0,0,2,NULL),
(100,'flood_warning_trigger_id','洪水预警触发ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(101,'qq','qq','VARCHAR',13,NULL,NULL,0,0,9,NULL),
(101,'data_date','数据日期','DATETIME',NULL,NULL,NULL,0,0,8,NULL),
(101,'rain_intensity','降雨强度','VARCHAR',64,NULL,NULL,0,0,7,NULL),
(101,'rain_24h','24小时降雨量','VARCHAR',64,NULL,NULL,0,0,6,NULL),
(101,'rain_3h','3小时降雨量','VARCHAR',64,NULL,NULL,0,0,5,NULL),
(101,'rain_1h','1小时降雨量','VARCHAR',64,NULL,NULL,0,0,4,NULL),
(101,'monitor_time','监测时间','DATETIME',NULL,NULL,NULL,0,0,3,NULL),
(101,'rain_station_id','雨量站ID','BIGINT',NULL,NULL,NULL,0,0,2,NULL),
(101,'rain_collect_id','降雨采集ID','NUMBER',19,0,NULL,1,1,1,NULL),
(102,'enable_status','启用状态','VARCHAR',10,NULL,NULL,0,0,7,NULL),
(102,'level_desc','等级说明','VARCHAR',128,NULL,NULL,0,0,6,NULL),
(102,'risk_level','风险程度','VARCHAR',128,NULL,NULL,0,0,5,NULL),
(102,'level_order','等级序号','VARCHAR',64,NULL,NULL,0,0,4,NULL),
(102,'warning_level_name','预警等级名称','VARCHAR',128,NULL,NULL,0,0,3,NULL),
(102,'warning_level_code','预警等级编码','VARCHAR',64,NULL,NULL,0,0,2,NULL),
(102,'flood_warning_id','洪水预警ID','NUMBER',NULL,NULL,NULL,1,1,1,NULL),
(103,'city_name','市级名称','VARCHAR',64,NULL,NULL,0,0,2,NULL),
(103,'parent_region_id','父级区域ID','VARCHAR',64,NULL,NULL,0,0,3,NULL),
(103,'region_level','区域层级','VARCHAR',64,NULL,NULL,0,0,4,NULL),
(103,'region_name','区域名称','VARCHAR',64,NULL,NULL,0,0,5,NULL),
(103,'region_code','区域编码','VARCHAR',64,NULL,NULL,0,0,6,NULL),
(103,'flood_control_area_id','防汛责任区ID','NUMBER',19,0,NULL,1,1,1,NULL),
(104,'member_id','会员编码','VARCHAR2',32,NULL,NULL,1,1,2,NULL),
(104,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(105,'hydro_station_id','水文站编码','VARCHAR2',32,NULL,NULL,1,1,2,'水位、流量等水文监测站点'),
(105,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(106,'rain_station_id','雨量站编码','VARCHAR2',32,NULL,NULL,1,1,2,'降雨监测站点'),
(106,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(108,'reservoir_id','水库编码','VARCHAR2',32,NULL,NULL,1,1,2,NULL),
(108,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(110,'customer2_id','客户2编码','VARCHAR2',32,NULL,NULL,1,1,3,NULL),
(110,'member_id','会员编码','VARCHAR2',32,NULL,NULL,1,1,2,NULL),
(110,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期'),
(112,'region_id','区域ID','VARCHAR',255,NULL,NULL,0,0,2,NULL),
(112,'stat_date','统计日期','DATETIME',NULL,NULL,NULL,0,0,1,NULL),
(113,'max_water_level','当日最高水位','VARCHAR',255,NULL,NULL,0,0,3,NULL),
(113,'reservoir_id','水库ID','VARCHAR',255,NULL,NULL,0,0,2,NULL),
(113,'stat_date','统计日期','DATETIME',NULL,NULL,NULL,0,0,1,NULL),
(114,'active_warning_cnt','当前有效预警次数','VARCHAR',255,NULL,NULL,0,0,4,NULL),
(114,'warning_cnt','洪水预警次数','VARCHAR',255,NULL,NULL,0,0,3,NULL),
(114,'area_name','责任区名称','VARCHAR',255,NULL,NULL,0,0,2,NULL),
(114,'area_id','责任区ID','VARCHAR',255,NULL,NULL,0,0,1,NULL),
(115,'over_warning_station_cnt','超警戒站点数','VARCHAR',255,NULL,NULL,0,0,6,NULL),
(115,'max_water_level','区域最高水位','VARCHAR',255,NULL,NULL,0,0,5,NULL),
(115,'reservoir_cnt','水库数量','VARCHAR',255,NULL,NULL,0,0,4,NULL),
(115,'rain_station_cnt','雨量站数量','VARCHAR',255,NULL,NULL,0,0,3,NULL),
(115,'hydro_station_cnt','水文站数量','VARCHAR',255,NULL,NULL,0,0,2,NULL),
(115,'region_id','区域ID','VARCHAR',255,NULL,NULL,0,0,1,NULL),
(118,'trigger_time','触发时间','VARCHAR',128,NULL,NULL,0,0,8,NULL),
(118,'area_name','责任区名称','VARCHAR',128,NULL,NULL,0,0,7,NULL),
(118,'reservoir_name','水库名称','VARCHAR',128,NULL,NULL,0,0,6,NULL),
(118,'river_name','河流名称','VARCHAR',128,NULL,NULL,0,0,5,NULL),
(118,'target_type','预警对象类型','INTEGER',NULL,NULL,NULL,0,0,4,NULL),
(118,'warning_level','预警等级','VARCHAR',128,NULL,NULL,0,0,3,NULL),
(118,'warning_no','预警编号','VARCHAR',128,NULL,NULL,0,0,2,NULL),
(118,'warning_id','预警ID','BIGINT',NULL,NULL,NULL,0,0,1,NULL),
(119,'refund_amount_30d','近30日成功退款金额','VARCHAR',64,NULL,NULL,0,0,9,NULL),
(119,'refund_amount_7d','近7日成功退款金额','VARCHAR',64,NULL,NULL,0,0,8,NULL),
(119,'refund_cnt_7d','近7日成功退款笔数','VARCHAR',64,NULL,NULL,0,0,7,NULL),
(119,'day_refund_amount','当日成功退款金额','VARCHAR',64,NULL,NULL,0,0,6,NULL),
(119,'day_refund_cnt','当日成功退款笔数','INTEGER',NULL,NULL,NULL,0,0,5,NULL),
(119,'brand_name','品牌名称','VARCHAR',128,NULL,NULL,0,0,4,NULL),
(119,'category_id','商品类目ID','BIGINT',NULL,NULL,NULL,0,0,3,NULL),
(119,'product_name','商品名称','VARCHAR',128,NULL,NULL,0,0,2,NULL),
(119,'product_id','商品ID','BIGINT',NULL,NULL,NULL,0,0,1,NULL),
(122,'warning_publish_id','预警发布编码','VARCHAR2',32,NULL,NULL,1,1,2,'正式发布预警'),
(122,'ds','统计日期','DATE',NULL,NULL,NULL,1,1,1,'汇总数据所属业务日期')
) AS v(model_id, field_name, cn_name, data_type, data_length, data_scale, default_value, pk_flag, nullable_flag, sort, comment);

-- 每个逻辑表建立修订中的版本 1，字段快照与字段表一致，供详情与修改复用既有建模流程。
INSERT INTO dh_modeling_logic_model_version (model_id, version, status, fields, creator, updater)
SELECT m.id, 1, 'REVISING', coalesce((
        SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
            'fieldUid', c.field_uid, 'fieldName', c.field_name, 'cnName', c.cn_name, 'dataType', c.data_type,
            'dataLength', c.data_length, 'dataScale', c.data_scale, 'defaultValue', c.default_value,
            'sort', c.sort, 'comment', c.comment, 'pkFlag', c.pk_flag = 1, 'nullableFlag', c.nullable_flag <> 0))
            ORDER BY c.sort, c.id)
        FROM dh_modeling_logic_model_column c WHERE c.model_id = m.id AND c.deleted = 0), '[]'::jsonb), '1', '1'
FROM dh_modeling_logic_model m;

-- 5. 字典：维度类型、业务类型、统计时效。
CREATE TEMP TABLE logic_dict_seed (dict_type text, dict_name text, sort integer, label text, value text, color_type text) ON COMMIT DROP;
INSERT INTO logic_dict_seed VALUES
    ('modeling_logic_obj_type', '逻辑表维度类型', 1, '普通对象', '1', 'primary'),
    ('modeling_logic_obj_type', '逻辑表维度类型', 2, '层级对象', '2', 'primary'),
    ('modeling_logic_business_type', '逻辑表业务类型', 1, '业务事件', '1', 'primary'),
    ('modeling_logic_business_type', '逻辑表业务类型', 2, '业务流程', '2', 'primary'),
    ('modeling_logic_data_timeliness', '逻辑表统计时效', 1, '天（T+1）', '1', 'success'),
    ('modeling_logic_data_timeliness', '逻辑表统计时效', 2, '小时（T+h）', '2', 'success'),
    ('modeling_logic_data_timeliness', '逻辑表统计时效', 3, '分钟（T+h）', '3', 'success');
SELECT setval('system_dict_type_seq', greatest((SELECT last_value FROM system_dict_type_seq), (SELECT max(id) FROM system_dict_type)));
SELECT setval('system_dict_data_seq', greatest((SELECT last_value FROM system_dict_data_seq), (SELECT max(id) FROM system_dict_data)));
INSERT INTO system_dict_type (id, name, type, status, creator, updater)
SELECT nextval('system_dict_type_seq'), s.dict_name, s.dict_type, 0, 'migration:0208', 'migration:0208'
FROM (SELECT DISTINCT dict_type, dict_name FROM logic_dict_seed) s
WHERE NOT EXISTS (SELECT 1 FROM system_dict_type t WHERE t.type = s.dict_type AND t.deleted = 0)
ORDER BY s.dict_type;
INSERT INTO system_dict_data (id, sort, label, value, dict_type, status, color_type, creator, updater)
SELECT nextval('system_dict_data_seq'), s.sort, s.label, s.value, s.dict_type, 0, s.color_type, 'migration:0208', 'migration:0208'
FROM logic_dict_seed s
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data d WHERE d.dict_type = s.dict_type AND d.value = s.value AND d.deleted = 0)
ORDER BY s.dict_type, s.sort;

-- 6. 菜单：逻辑模型下新增五个逻辑表页面，原“创建模型”页面隐藏（保留其权限供详情、ER 图等辅助页面使用）。
DO $menus$
DECLARE
    page record;
    action record;
    page_id bigint;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6216 AND deleted = 0) THEN
        RAISE EXCEPTION '缺少逻辑模型目录菜单 6216';
    END IF;
    -- 重复执行：先移除本迁移此前创建的菜单及角色授权。
    DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM system_menu WHERE creator = 'migration:0208');
    DELETE FROM system_menu WHERE creator = 'migration:0208';
    UPDATE system_menu SET visible = false, updater = 'migration:0208', update_time = CURRENT_TIMESTAMP WHERE id = 6722;
    PERFORM setval('system_menu_id_seq', greatest((SELECT last_value FROM system_menu_id_seq), (SELECT max(id) FROM system_menu)));
    FOR page IN SELECT * FROM (VALUES
            (1, '贴源逻辑表', 'source-table', 'modeling/logictable/source/index', 'ModelingLogicSourceTable'),
            (2, '维度逻辑表', 'dimension-table', 'modeling/logictable/dimension/index', 'ModelingLogicDimensionTable'),
            (3, '事实逻辑表', 'fact-table', 'modeling/logictable/fact/index', 'ModelingLogicFactTable'),
            (4, '汇总逻辑表', 'summary-table', 'modeling/logictable/summary/index', 'ModelingLogicSummaryTable'),
            (5, 'ADS应用表', 'application-table', 'modeling/logictable/application/index', 'ModelingLogicApplicationTable')
        ) AS p(position, label, path, component, component_name)
    LOOP
        INSERT INTO system_menu (name, permission, type, sort, parent_id, path, icon, component, component_name, status,
            visible, keep_alive, always_show, creator, updater, deleted)
        VALUES (page.label, 'modeling:logic-table:query', 2, page.position, 6216, page.path, '', page.component,
            page.component_name, 0, true, true, true, 'migration:0208', 'migration:0208', 0)
        RETURNING id INTO page_id;
        FOR action IN SELECT * FROM (VALUES
                (1, '逻辑表查询', 'query'), (2, '逻辑表新增', 'create'), (3, '逻辑表修改', 'update'), (4, '逻辑表删除', 'delete')
            ) AS a(position, label, code)
        LOOP
            INSERT INTO system_menu (name, permission, type, sort, parent_id, path, status, visible, keep_alive,
                always_show, creator, updater, deleted)
            VALUES (action.label, 'modeling:logic-table:' || action.code, 3, action.position, page_id, '', 0, true, true,
                true, 'migration:0208', 'migration:0208', 0);
        END LOOP;
    END LOOP;
    -- 已授权“创建模型”的角色同步获得五个逻辑表页面及按钮。
    INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, deleted, tenant_id)
    SELECT nextval('system_role_menu_seq'), r.role_id, m.id, 'migration:0208', 'migration:0208', 0, r.tenant_id
    FROM (SELECT DISTINCT role_id, tenant_id FROM system_role_menu WHERE menu_id = 6722 AND deleted = 0) r
    CROSS JOIN system_menu m
    WHERE m.creator = 'migration:0208' AND m.deleted = 0
      AND NOT EXISTS (SELECT 1 FROM system_role_menu x WHERE x.role_id = r.role_id AND x.menu_id = m.id AND x.deleted = 0);
    PERFORM setval('system_menu_seq', greatest((SELECT last_value FROM system_menu_seq), (SELECT max(id) FROM system_menu)));
END $menus$;

DO $verify$
BEGIN
    IF (SELECT count(*) FROM dh_modeling_logic_model) <> 51
        OR (SELECT count(*) FROM dh_modeling_logic_model_column) <> 443
        OR (SELECT count(*) FROM dh_modeling_logic_model_version) <> 51
        OR (SELECT count(*) FROM dh_modeling_source_system) <> 6 THEN
        RAISE EXCEPTION '逻辑表初始化数量不匹配';
    END IF;
    IF (SELECT count(*) FROM system_menu WHERE creator = 'migration:0208' AND type = 2 AND deleted = 0) <> 5 THEN
        RAISE EXCEPTION '逻辑表菜单数量不匹配';
    END IF;
END $verify$;
COMMIT;
