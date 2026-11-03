-- Migration: 0230 资产标签来源目录、定义与真实关联迁入。
-- Purpose: 复用标签领域迁入四十类目、一百六十标签、一百三十九条可确认关联，补齐来源展示、修订与数据库状态引用保护。
-- Depends on: 0219_asset_qdata-market-import.sql 的既有十四标签及四十一关联；0221_asset_qdata-map-datasets.sql 的五十四资产来源映射；现有单租户标签、类目、关联、市场审计表。不依赖并发审批工单迁移。
-- 同类同名的本地人工对象或已维护且尚未确认来源身份的旧对象会中止迁入，须人工核对，不能将真实本地创建人改成来源昵称。
-- Target: 已有 PostgreSQL 平台库；新库使用已同步本迁移的完整基线。
-- Repeatable: 是；来源编号和来源关联键存在即保留，人工编辑、软删除和新增关联不被覆盖。已有同名标签复用，不重复创建。
-- Rollback: 单事务失败完整回滚；提交后须先备份并检查引用后人工处理。旧导入中未被本地维护且来源已移除的关联软删除，恢复须按修订与来源证据人工核对；禁止自动删除用户后续操作。
-- 来源不一致：列表计数合计一百五十八、详情关联一百六十三，其中二十四条指向十一个不可见或已不存在资产；仅迁入逐资产核验的上述一百三十九条，数量始终由本地真实关系及当前维护范围计算。
BEGIN;
SET LOCAL search_path TO "datahub-center",pg_catalog;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='120s';
SELECT pg_advisory_xact_lock(1500,34);
LOCK TABLE dh_asset_category_tag,dh_asset_tag,dh_asset_tag_rel IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE dh_asset_category_tag ADD COLUMN IF NOT EXISTS source_record_id varchar(64);
COMMENT ON COLUMN dh_asset_category_tag.source_record_id IS '来源标签类目编号，仅用于追溯及幂等，非本地业务主键';
ALTER TABLE dh_asset_tag ADD COLUMN IF NOT EXISTS source_record_id varchar(64);
ALTER TABLE dh_asset_tag ADD COLUMN IF NOT EXISTS source_creator_name varchar(128);
ALTER TABLE dh_asset_tag ADD COLUMN IF NOT EXISTS source_updater_name varchar(128);
ALTER TABLE dh_asset_tag ADD COLUMN IF NOT EXISTS revision integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_asset_tag.source_record_id IS '来源标签编号，包含软删除记录，不参与本地授权';
COMMENT ON COLUMN dh_asset_tag.source_creator_name IS '来源创建昵称，仅展示，不是本地操作人';
COMMENT ON COLUMN dh_asset_tag.source_updater_name IS '来源更新昵称，仅展示，本地变更后清空';
COMMENT ON COLUMN dh_asset_tag.revision IS '标签定义和启停的并发修订号';
ALTER TABLE dh_asset_tag_rel ADD COLUMN IF NOT EXISTS source_record_key varchar(128);
ALTER TABLE dh_asset_tag_rel ADD COLUMN IF NOT EXISTS revision integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_asset_tag_rel.source_record_key IS '来源标签与资产组合键，软删除后仍保留以防迁移复活';
COMMENT ON COLUMN dh_asset_tag_rel.revision IS '资产标签关联的并发修订号';
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_category_tag_source ON dh_asset_category_tag(source_record_id) WHERE source_record_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_tag_source ON dh_asset_tag(source_record_id) WHERE source_record_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_tag_rel_source ON dh_asset_tag_rel(source_record_key) WHERE source_record_key IS NOT NULL;
-- 同名允许分属不同类目；原全局名称索引会错误折叠来源的两组合法同名标签。
DROP INDEX IF EXISTS uk_dh_asset_tag_name;
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_tag_category_name ON dh_asset_tag(COALESCE(category_id,0),btrim(name)) WHERE deleted=0;
CREATE TEMP TABLE qdata_tag_category_seed(source_id bigint PRIMARY KEY,parent_source_id bigint,name text,code text,sort integer,description text,status integer,resolved_id bigint) ON COMMIT DROP;
INSERT INTO qdata_tag_category_seed(source_id,parent_source_id,name,code,sort,description,status) VALUES
(1,0,'业务属性','QDATA_TAG_A01',0,'组织描述数据所属业务领域、管理对象和应用场景的标签。',0),
(26,7,'关键程度','QDATA_TAG_A07A01',0,'用于组织与关键程度相关的标签，支撑数据资产分类、检索和治理。',0),
(15,2,'数据主题','QDATA_TAG_A02A01',0,'用于组织与数据主题相关的标签，支撑数据资产分类、检索和治理。',0),
(34,3,'数据结构','QDATA_TAG_A03A01',0,'用于组织与数据结构相关的标签，支撑数据资产分类、检索和治理。',0),
(36,5,'标准化状态','QDATA_TAG_A05A01',0,'用于组织与标准化状态相关的标签，支撑数据资产分类、检索和治理。',0),
(33,4,'更新频率','QDATA_TAG_A04A01',0,'用于组织与更新频率相关的标签，支撑数据资产分类、检索和治理。',0),
(22,1,'业务领域','QDATA_TAG_A01A01',0,'用于组织与业务领域相关的标签，支撑数据资产分类、检索和治理。',0),
(29,6,'安全级别','QDATA_TAG_A06A01',0,'用于组织与安全级别相关的标签，支撑数据资产分类、检索和治理。',0),
(20,6,'敏感程度','QDATA_TAG_A06A02',1,'用于组织与敏感程度相关的标签，支撑数据资产分类、检索和治理。',0),
(35,7,'价值等级','QDATA_TAG_A07A02',1,'用于组织与价值等级相关的标签，支撑数据资产分类、检索和治理。',0),
(11,5,'质量状态','QDATA_TAG_A05A02',1,'用于组织与质量状态相关的标签，支撑数据资产分类、检索和治理。',0),
(2,0,'内容属性','QDATA_TAG_A02',1,'组织描述数据主题、指标内容及时空覆盖范围的标签。',0),
(30,1,'管理对象','QDATA_TAG_A01A02',1,'用于组织与管理对象相关的标签，支撑数据资产分类、检索和治理。',0),
(9,3,'存储形态','QDATA_TAG_A03A02',1,'用于组织与存储形态相关的标签，支撑数据资产分类、检索和治理。',0),
(37,2,'指标类型','QDATA_TAG_A02A02',1,'用于组织与指标类型相关的标签，支撑数据资产分类、检索和治理。',0),
(25,4,'数据新鲜度','QDATA_TAG_A04A02',1,'用于组织与数据新鲜度相关的标签，支撑数据资产分类、检索和治理。',0),
(3,0,'技术形态','QDATA_TAG_A03',2,'组织描述数据结构、存储形态、数仓层级和服务方式的标签。',0),
(28,2,'时间范围','QDATA_TAG_A02A03',2,'用于组织与时间范围相关的标签，支撑数据资产分类、检索和治理。',0),
(10,6,'共享范围','QDATA_TAG_A06A03',2,'用于组织与共享范围相关的标签，支撑数据资产分类、检索和治理。',0),
(40,7,'使用热度','QDATA_TAG_A07A03',2,'用于组织与使用热度相关的标签，支撑数据资产分类、检索和治理。',0),
(19,5,'责任归属','QDATA_TAG_A05A03',2,'用于组织与责任归属相关的标签，支撑数据资产分类、检索和治理。',0),
(17,3,'数据层级','QDATA_TAG_A03A03',2,'用于组织与数据层级相关的标签，支撑数据资产分类、检索和治理。',0),
(16,4,'历史跨度','QDATA_TAG_A04A03',2,'用于组织与历史跨度相关的标签，支撑数据资产分类、检索和治理。',0),
(39,1,'业务环节','QDATA_TAG_A01A03',2,'用于组织与业务环节相关的标签，支撑数据资产分类、检索和治理。',0),
(14,7,'复用程度','QDATA_TAG_A07A04',3,'用于组织与复用程度相关的标签，支撑数据资产分类、检索和治理。',0),
(27,3,'服务方式','QDATA_TAG_A03A04',3,'用于组织与服务方式相关的标签，支撑数据资产分类、检索和治理。',0),
(13,1,'应用场景','QDATA_TAG_A01A04',3,'用于组织与应用场景相关的标签，支撑数据资产分类、检索和治理。',0),
(23,5,'血缘状态','QDATA_TAG_A05A04',3,'用于组织与血缘状态相关的标签，支撑数据资产分类、检索和治理。',0),
(21,2,'空间范围','QDATA_TAG_A02A04',3,'用于组织与空间范围相关的标签，支撑数据资产分类、检索和治理。',0),
(38,6,'开放属性','QDATA_TAG_A06A04',3,'用于组织与开放属性相关的标签，支撑数据资产分类、检索和治理。',0),
(8,4,'生命周期状态','QDATA_TAG_A04A04',3,'用于组织与生命周期状态相关的标签，支撑数据资产分类、检索和治理。',0),
(4,0,'时效生命周期','QDATA_TAG_A04',3,'组织描述更新频率、新鲜度、历史跨度和生命周期状态的标签。',0),
(31,6,'授权方式','QDATA_TAG_A06A05',4,'用于组织与授权方式相关的标签，支撑数据资产分类、检索和治理。',0),
(24,7,'服务成效','QDATA_TAG_A07A05',4,'用于组织与服务成效相关的标签，支撑数据资产分类、检索和治理。',0),
(12,2,'内容关键词','QDATA_TAG_A02A05',4,'用于组织与内容关键词相关的标签，支撑数据资产分类、检索和治理。',0),
(5,0,'治理状态','QDATA_TAG_A05',4,'组织描述标准化、质量、责任、血缘和认证状态的标签。',0),
(18,3,'数据规模','QDATA_TAG_A03A05',4,'用于组织与数据规模相关的标签，支撑数据资产分类、检索和治理。',0),
(32,5,'认证状态','QDATA_TAG_A05A05',4,'用于组织与认证状态相关的标签，支撑数据资产分类、检索和治理。',0),
(6,0,'安全共享','QDATA_TAG_A06',5,'组织描述安全级别、敏感程度及共享开放要求的标签。',0),
(7,0,'使用价值','QDATA_TAG_A07',6,'组织描述数据重要程度、使用热度和复用价值的标签。',0);
CREATE TEMP TABLE qdata_tag_seed(source_id bigint PRIMARY KEY,name text,category_code text,description text,near_synonyms text,synonyms text,status integer,creator_name text,updater_name text,create_time timestamp,update_time timestamp,resolved_id bigint) ON COMMIT DROP;
INSERT INTO qdata_tag_seed(source_id,name,category_code,description,near_synonyms,synonyms,status,creator_name,updater_name,create_time,update_time) VALUES
(2,'灌区管理','QDATA_TAG_A01A01','用于标识业务领域为“灌区管理”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','云南文梵科技','2026-07-29 19:09:39','2026-10-22 18:15:15'),
(3,'防洪减灾','QDATA_TAG_A01A01','用于标识业务领域为“防洪减灾”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:00:21'),
(4,'农村供水','QDATA_TAG_A01A01','用于标识业务领域为“农村供水”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(5,'水土保持','QDATA_TAG_A01A01','用于标识业务领域为“水土保持”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(6,'水资源管理','QDATA_TAG_A01A01','用于标识业务领域为“水资源管理”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:00:11'),
(7,'水利工程管理','QDATA_TAG_A01A01','用于标识业务领域为“水利工程管理”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:59:55'),
(8,'生态流量监管','QDATA_TAG_A01A01','用于标识业务领域为“生态流量监管”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(9,'河湖管理','QDATA_TAG_A01A01','用于标识业务领域为“河湖管理”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(10,'智慧水利','QDATA_TAG_A01A01','用于标识业务领域为“智慧水利”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(11,'行政区划','QDATA_TAG_A01A02','用于标识管理对象为“行政区划”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(12,'流域','QDATA_TAG_A01A02','用于标识管理对象为“流域”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(14,'湖泊','QDATA_TAG_A01A02','用于标识管理对象为“湖泊”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(15,'水库','QDATA_TAG_A01A02','用于标识管理对象为“水库”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(16,'堤防','QDATA_TAG_A01A02','用于标识管理对象为“堤防”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:00:21'),
(17,'水闸','QDATA_TAG_A01A02','用于标识管理对象为“水闸”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:59:04'),
(18,'泵站','QDATA_TAG_A01A02','用于标识管理对象为“泵站”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(19,'渠道','QDATA_TAG_A01A02','用于标识管理对象为“渠道”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(20,'灌区','QDATA_TAG_A01A02','用于标识管理对象为“灌区”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(21,'水厂','QDATA_TAG_A01A02','用于标识管理对象为“水厂”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(22,'管网','QDATA_TAG_A01A02','用于标识管理对象为“管网”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(23,'监测站','QDATA_TAG_A01A02','用于标识管理对象为“监测站”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,'测站',0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(24,'监测断面','QDATA_TAG_A01A02','用于标识管理对象为“监测断面”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,'断面',0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:49:31'),
(25,'数据采集','QDATA_TAG_A01A03','用于标识业务环节为“数据采集”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(26,'监测分析','QDATA_TAG_A01A03','用于标识业务环节为“监测分析”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(27,'预测预报','QDATA_TAG_A01A03','用于标识业务环节为“预测预报”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(28,'预警发布','QDATA_TAG_A01A03','用于标识业务环节为“预警发布”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(29,'调度运行','QDATA_TAG_A01A03','用于标识业务环节为“调度运行”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(30,'巡检养护','QDATA_TAG_A01A03','用于标识业务环节为“巡检养护”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(31,'行政许可','QDATA_TAG_A01A03','用于标识业务环节为“行政许可”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(32,'评价考核','QDATA_TAG_A01A03','用于标识业务环节为“评价考核”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(33,'水情总览','QDATA_TAG_A01A04','用于标识应用场景为“水情总览”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:49:46'),
(34,'水利一张图','QDATA_TAG_A01A04','用于标识应用场景为“水利一张图”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,'水利一张图应用',0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:47:44'),
(35,'防汛指挥','QDATA_TAG_A01A04','用于标识应用场景为“防汛指挥”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(36,'水资源监管','QDATA_TAG_A01A04','用于标识应用场景为“水资源监管”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:59:55'),
(37,'灌区调度','QDATA_TAG_A01A04','用于标识应用场景为“灌区调度”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(38,'农村供水保障','QDATA_TAG_A01A04','用于标识应用场景为“农村供水保障”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(39,'工程安全监测','QDATA_TAG_A01A04','用于标识应用场景为“工程安全监测”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(40,'生态流量监管','QDATA_TAG_A01A04','用于标识应用场景为“生态流量监管”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(41,'基础主数据','QDATA_TAG_A02A01','用于标识数据主题为“基础主数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:31:17'),
(42,'监测感知','QDATA_TAG_A02A01','用于标识数据主题为“监测感知”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(43,'水资源管理','QDATA_TAG_A02A01','用于标识数据主题为“水资源管理”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:00:11'),
(44,'工程运行','QDATA_TAG_A02A01','用于标识数据主题为“工程运行”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(45,'调度管理','QDATA_TAG_A02A01','用于标识数据主题为“调度管理”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(46,'风险预警','QDATA_TAG_A02A01','用于标识数据主题为“风险预警”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(47,'统计分析','QDATA_TAG_A02A01','用于标识数据主题为“统计分析”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(48,'空间地理','QDATA_TAG_A02A01','用于标识数据主题为“空间地理”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(50,'流量指标','QDATA_TAG_A02A02','用于标识指标类型为“流量指标”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:57:47'),
(51,'雨量指标','QDATA_TAG_A02A02','用于标识指标类型为“雨量指标”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:53:46'),
(53,'水质指标','QDATA_TAG_A02A02','用于标识指标类型为“水质指标”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:59:43'),
(54,'工程安全指标','QDATA_TAG_A02A02','用于标识指标类型为“工程安全指标”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(55,'实时数据','QDATA_TAG_A02A03','用于标识时间范围为“实时数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:57:43'),
(56,'最新数据','QDATA_TAG_A02A03','用于标识时间范围为“最新数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(57,'历史数据','QDATA_TAG_A02A03','用于标识时间范围为“历史数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:01:38'),
(58,'预测数据','QDATA_TAG_A02A03','用于标识时间范围为“预测数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(59,'流域范围','QDATA_TAG_A02A04','用于标识空间范围为“流域范围”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:55:10'),
(60,'行政区域','QDATA_TAG_A02A04','用于标识空间范围为“行政区域”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(61,'水资源分区','QDATA_TAG_A02A04','用于标识空间范围为“水资源分区”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:59:43'),
(62,'河湖范围','QDATA_TAG_A02A04','用于标识空间范围为“河湖范围”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(63,'工程范围','QDATA_TAG_A02A04','用于标识空间范围为“工程范围”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(64,'洪水','QDATA_TAG_A02A05','用于标识内容关键词为“洪水”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(65,'干旱','QDATA_TAG_A02A05','用于标识内容关键词为“干旱”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(66,'供水','QDATA_TAG_A02A05','用于标识内容关键词为“供水”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(67,'灌溉','QDATA_TAG_A02A05','用于标识内容关键词为“灌溉”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(68,'地下水','QDATA_TAG_A02A05','用于标识内容关键词为“地下水”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(70,'水土流失','QDATA_TAG_A02A05','用于标识内容关键词为“水土流失”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(71,'结构化','QDATA_TAG_A03A01','用于标识数据结构为“结构化”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(72,'半结构化','QDATA_TAG_A03A01','用于标识数据结构为“半结构化”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(73,'非结构化','QDATA_TAG_A03A01','用于标识数据结构为“非结构化”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,'非结构化数据',0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(74,'时序数据','QDATA_TAG_A03A01','用于标识数据结构为“时序数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(75,'空间数据','QDATA_TAG_A03A01','用于标识数据结构为“空间数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(76,'关系数据','QDATA_TAG_A03A02','用于标识存储形态为“关系数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(77,'时序存储','QDATA_TAG_A03A02','用于标识存储形态为“时序存储”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(78,'矢量数据','QDATA_TAG_A03A02','用于标识存储形态为“矢量数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(79,'栅格数据','QDATA_TAG_A03A02','用于标识存储形态为“栅格数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(80,'文档数据','QDATA_TAG_A03A02','用于标识存储形态为“文档数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(81,'ODS层','QDATA_TAG_A03A03','用于标识数据层级为“ODS层”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(82,'DIM层','QDATA_TAG_A03A03','用于标识数据层级为“DIM层”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(83,'DWD层','QDATA_TAG_A03A03','用于标识数据层级为“DWD层”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(84,'DWS层','QDATA_TAG_A03A03','用于标识数据层级为“DWS层”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(85,'ADS层','QDATA_TAG_A03A03','用于标识数据层级为“ADS层”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(86,'数据库表','QDATA_TAG_A03A04','用于标识服务方式为“数据库表”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(87,'文件下载','QDATA_TAG_A03A04','用于标识服务方式为“文件下载”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(88,'API接口','QDATA_TAG_A03A04','用于标识服务方式为“API接口”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,'应用程序接口',0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(89,'地图服务','QDATA_TAG_A03A04','用于标识服务方式为“地图服务”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(90,'小规模','QDATA_TAG_A03A05','用于标识数据规模为“小规模”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(91,'中等规模','QDATA_TAG_A03A05','用于标识数据规模为“中等规模”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(92,'大规模','QDATA_TAG_A03A05','用于标识数据规模为“大规模”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(93,'实时更新','QDATA_TAG_A04A01','用于标识更新频率为“实时更新”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,'实时',0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 19:57:43'),
(94,'五分钟更新','QDATA_TAG_A04A01','用于标识更新频率为“五分钟更新”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(95,'小时更新','QDATA_TAG_A04A01','用于标识更新频率为“小时更新”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(96,'每日更新','QDATA_TAG_A04A01','用于标识更新频率为“每日更新”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:01:03'),
(97,'月度更新','QDATA_TAG_A04A01','用于标识更新频率为“月度更新”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:01:44'),
(99,'事件触发','QDATA_TAG_A04A01','用于标识更新频率为“事件触发”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(100,'当前有效','QDATA_TAG_A04A02','用于标识数据新鲜度为“当前有效”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(101,'更新延迟','QDATA_TAG_A04A02','用于标识数据新鲜度为“更新延迟”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(102,'已过期','QDATA_TAG_A04A02','用于标识数据新鲜度为“已过期”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(103,'最新快照','QDATA_TAG_A04A03','用于标识历史跨度为“最新快照”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(104,'一年以内','QDATA_TAG_A04A03','用于标识历史跨度为“一年以内”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(106,'十年以上','QDATA_TAG_A04A03','用于标识历史跨度为“十年以上”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(107,'规划中','QDATA_TAG_A04A04','用于标识生命周期状态为“规划中”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(108,'建设中','QDATA_TAG_A04A04','用于标识生命周期状态为“建设中”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(109,'运行中','QDATA_TAG_A04A04','用于标识生命周期状态为“运行中”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(110,'已归档','QDATA_TAG_A04A04','用于标识生命周期状态为“已归档”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(111,'已标准化','QDATA_TAG_A05A01','用于标识标准化状态为“已标准化”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(112,'部分标准化','QDATA_TAG_A05A01','用于标识标准化状态为“部分标准化”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(113,'未标准化','QDATA_TAG_A05A01','用于标识标准化状态为“未标准化”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(114,'质量良好','QDATA_TAG_A05A02','用于标识质量状态为“质量良好”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(115,'质量一般','QDATA_TAG_A05A02','用于标识质量状态为“质量一般”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(116,'质量较差','QDATA_TAG_A05A02','用于标识质量状态为“质量较差”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(117,'责任明确','QDATA_TAG_A05A03','用于标识责任归属为“责任明确”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(118,'责任待确认','QDATA_TAG_A05A03','用于标识责任归属为“责任待确认”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(119,'多部门共管','QDATA_TAG_A05A03','用于标识责任归属为“多部门共管”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(120,'血缘完整','QDATA_TAG_A05A04','用于标识血缘状态为“血缘完整”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(121,'血缘部分缺失','QDATA_TAG_A05A04','用于标识血缘状态为“血缘部分缺失”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(122,'血缘未采集','QDATA_TAG_A05A04','用于标识血缘状态为“血缘未采集”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(123,'已认证','QDATA_TAG_A05A05','用于标识认证状态为“已认证”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(124,'认证中','QDATA_TAG_A05A05','用于标识认证状态为“认证中”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(125,'未认证','QDATA_TAG_A05A05','用于标识认证状态为“未认证”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(126,'公开数据','QDATA_TAG_A06A01','用于标识安全级别为“公开数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(127,'内部数据','QDATA_TAG_A06A01','用于标识安全级别为“内部数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(128,'敏感数据','QDATA_TAG_A06A01','用于标识安全级别为“敏感数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(129,'重要数据','QDATA_TAG_A06A01','用于标识安全级别为“重要数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(130,'核心数据','QDATA_TAG_A06A01','用于标识安全级别为“核心数据”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(131,'非敏感','QDATA_TAG_A06A02','用于标识敏感程度为“非敏感”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(132,'低敏感','QDATA_TAG_A06A02','用于标识敏感程度为“低敏感”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(133,'中敏感','QDATA_TAG_A06A02','用于标识敏感程度为“中敏感”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(134,'高敏感','QDATA_TAG_A06A02','用于标识敏感程度为“高敏感”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(135,'部门内部','QDATA_TAG_A06A03','用于标识共享范围为“部门内部”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(136,'跨部门','QDATA_TAG_A06A03','用于标识共享范围为“跨部门”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(137,'跨层级','QDATA_TAG_A06A03','用于标识共享范围为“跨层级”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(138,'社会共享','QDATA_TAG_A06A03','用于标识共享范围为“社会共享”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(139,'无条件开放','QDATA_TAG_A06A04','用于标识开放属性为“无条件开放”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(140,'有条件开放','QDATA_TAG_A06A04','用于标识开放属性为“有条件开放”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(141,'不予开放','QDATA_TAG_A06A04','用于标识开放属性为“不予开放”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(142,'自动授权','QDATA_TAG_A06A05','用于标识授权方式为“自动授权”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(143,'人工审批','QDATA_TAG_A06A05','用于标识授权方式为“人工审批”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(144,'白名单授权','QDATA_TAG_A06A05','用于标识授权方式为“白名单授权”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(145,'协议授权','QDATA_TAG_A06A05','用于标识授权方式为“协议授权”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(146,'核心资产','QDATA_TAG_A07A01','用于标识关键程度为“核心资产”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(147,'重要资产','QDATA_TAG_A07A01','用于标识关键程度为“重要资产”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(148,'一般资产','QDATA_TAG_A07A01','用于标识关键程度为“一般资产”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(149,'高价值','QDATA_TAG_A07A02','用于标识价值等级为“高价值”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(150,'中价值','QDATA_TAG_A07A02','用于标识价值等级为“中价值”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(151,'低价值','QDATA_TAG_A07A02','用于标识价值等级为“低价值”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(152,'高频使用','QDATA_TAG_A07A03','用于标识使用热度为“高频使用”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(153,'中频使用','QDATA_TAG_A07A03','用于标识使用热度为“中频使用”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(154,'低频使用','QDATA_TAG_A07A03','用于标识使用热度为“低频使用”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(155,'高复用','QDATA_TAG_A07A04','用于标识复用程度为“高复用”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(156,'中复用','QDATA_TAG_A07A04','用于标识复用程度为“中复用”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(157,'低复用','QDATA_TAG_A07A04','用于标识复用程度为“低复用”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(158,'成效显著','QDATA_TAG_A07A05','用于标识服务成效为“成效显著”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(159,'成效一般','QDATA_TAG_A07A05','用于标识服务成效为“成效一般”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(160,'待评估','QDATA_TAG_A07A05','用于标识服务成效为“待评估”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-07-29 19:09:39'),
(1,'河道水情','QDATA_TAG_A01A01','用于标识业务领域为“河道水情”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,'河流水情',0,'吴同','超级管理员','2026-07-29 19:09:39','2026-10-22 18:49:28'),
(13,'河流','QDATA_TAG_A01A02','用于标识管理对象为“河流”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','超级管理员','2026-07-29 19:09:39','2026-10-22 18:49:28'),
(49,'水位指标','QDATA_TAG_A02A02','用于标识指标类型为“水位指标”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:30:13'),
(52,'水量指标','QDATA_TAG_A02A02','用于标识指标类型为“水量指标”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:30:14'),
(69,'生态流量','QDATA_TAG_A02A05','用于标识内容关键词为“生态流量”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:30:10'),
(98,'年度更新','QDATA_TAG_A04A01','用于标识更新频率为“年度更新”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:30:14'),
(105,'一至五年','QDATA_TAG_A04A03','用于标识历史跨度为“一至五年”的数据资产，支撑水利数据分类检索、治理管控和应用分析。',NULL,NULL,0,'吴同','吴同','2026-07-29 19:09:39','2026-08-04 20:30:10');
CREATE TEMP TABLE qdata_tag_rel_seed(tag_source_id bigint,asset_source_id bigint,PRIMARY KEY(tag_source_id,asset_source_id)) ON COMMIT DROP;
INSERT INTO qdata_tag_rel_seed VALUES
(3,6),
(6,7),
(6,8),
(6,9),
(6,11),
(7,8),
(7,9),
(16,6),
(16,43),
(16,47),
(16,36),
(17,11),
(24,40),
(24,42),
(33,37),
(33,39),
(34,39),
(36,8),
(41,49),
(43,7),
(43,8),
(50,16),
(50,18),
(50,20),
(50,21),
(50,22),
(50,23),
(51,26),
(51,27),
(51,29),
(51,44),
(51,46),
(51,47),
(51,48),
(53,9),
(53,11),
(55,14),
(55,15),
(55,17),
(55,28),
(55,29),
(55,30),
(55,31),
(57,1),
(57,3),
(57,12),
(57,19),
(57,20),
(57,32),
(57,34),
(59,20),
(59,22),
(59,23),
(61,9),
(61,11),
(93,14),
(93,15),
(93,17),
(96,5),
(96,23),
(97,2),
(97,12),
(97,17),
(1,1),
(1,2),
(1,5),
(1,6),
(1,7),
(1,8),
(1,9),
(1,10),
(1,11),
(1,12),
(1,14),
(1,15),
(1,16),
(1,24),
(1,26),
(1,27),
(1,28),
(1,29),
(1,30),
(1,31),
(1,32),
(1,33),
(1,34),
(1,40),
(1,42),
(1,43),
(1,44),
(1,45),
(1,46),
(1,47),
(1,48),
(1,49),
(1,36),
(1,37),
(1,38),
(1,39),
(1,41),
(1,66),
(13,24),
(13,33),
(13,66),
(49,1),
(49,2),
(49,3),
(49,5),
(49,7),
(49,8),
(49,9),
(49,10),
(49,11),
(49,28),
(49,30),
(49,45),
(49,48),
(49,36),
(49,38),
(49,39),
(49,41),
(52,7),
(52,8),
(52,9),
(52,11),
(52,28),
(52,30),
(52,38),
(52,39),
(52,41),
(69,12),
(69,14),
(69,15),
(69,16),
(69,17),
(69,18),
(69,19),
(98,10),
(98,22);
DO $seed$
DECLARE item record;target_id bigint;parent_id_value bigint;matches integer;sequence_value bigint;sequence_called boolean;next_id bigint;asset_id_value bigint;tag_id_value bigint;key_value text;
BEGIN
 FOR item IN SELECT * FROM (VALUES('dh_asset_category_tag_seq','dh_asset_category_tag'),('dh_asset_tag_seq','dh_asset_tag'),('dh_asset_tag_rel_seq','dh_asset_tag_rel')) v(sequence_name,table_name) LOOP
   EXECUTE format('SELECT last_value,is_called FROM %I',item.sequence_name) INTO sequence_value,sequence_called;
   EXECUTE format('SELECT greatest(coalesce(max(id),0)+1,%s) FROM %I',sequence_value+CASE WHEN sequence_called THEN 1 ELSE 0 END,item.table_name) INTO next_id;
   EXECUTE format('ALTER SEQUENCE %I RESTART WITH %s',item.sequence_name,next_id);
 END LOOP;
 WHILE EXISTS(SELECT 1 FROM qdata_tag_category_seed WHERE resolved_id IS NULL) LOOP
   FOR item IN SELECT s.* FROM qdata_tag_category_seed s WHERE s.resolved_id IS NULL
      AND (s.parent_source_id=0 OR EXISTS(SELECT 1 FROM qdata_tag_category_seed p WHERE p.source_id=s.parent_source_id AND p.resolved_id IS NOT NULL)) ORDER BY s.source_id LOOP
     parent_id_value:=0;
     IF item.parent_source_id<>0 THEN SELECT resolved_id INTO parent_id_value FROM qdata_tag_category_seed WHERE source_id=item.parent_source_id; END IF;
     SELECT count(*),min(id) INTO matches,target_id FROM dh_asset_category_tag WHERE source_record_id=item.source_id::text OR category_code=item.code;
     IF matches>1 THEN RAISE EXCEPTION '标签类目来源身份不唯一：%',item.source_id; END IF;
     IF matches=0 THEN
       SELECT count(*),min(id) INTO matches,target_id FROM dh_asset_category_tag WHERE parent_id=parent_id_value AND category_name=item.name AND deleted=0;
       IF matches>1 THEN RAISE EXCEPTION '同名标签类目不唯一：%',item.source_id; END IF;
     END IF;
     IF target_id IS NULL THEN
       target_id:=nextval('dh_asset_category_tag_seq');
       INSERT INTO dh_asset_category_tag(id,parent_id,category_name,category_code,sort,status,remark,creator,updater,source_record_id)
       VALUES(target_id,parent_id_value,item.name,item.code,item.sort,item.status,item.description,'migration:0230','migration:0230',item.source_id::text);
     ELSE
       IF EXISTS(SELECT 1 FROM dh_asset_category_tag WHERE id=target_id AND source_record_id IS NULL
           AND (creator NOT IN('migration:0219','migration:0221') OR updater IS DISTINCT FROM creator)) THEN
         RAISE EXCEPTION '同类同名目录属于本地人工记录或已维护，禁止认领来源，请人工核对：%',item.source_id;
       END IF;
       UPDATE dh_asset_category_tag SET source_record_id=item.source_id::text WHERE id=target_id AND source_record_id IS NULL;
     END IF;
     UPDATE qdata_tag_category_seed SET resolved_id=target_id WHERE source_id=item.source_id;
   END LOOP;
 END LOOP;
 FOR item IN SELECT * FROM qdata_tag_seed ORDER BY source_id LOOP
   SELECT count(*),min(id) INTO matches,target_id FROM dh_asset_tag WHERE source_record_id=item.source_id::text;
   IF matches>1 THEN RAISE EXCEPTION '标签来源身份不唯一：%',item.source_id; END IF;
   IF matches=0 THEN
     SELECT resolved_id INTO parent_id_value FROM qdata_tag_category_seed WHERE code=item.category_code;
     SELECT count(*),min(id) INTO matches,target_id FROM dh_asset_tag WHERE name=item.name AND source_record_id IS NULL
       AND (category_id=parent_id_value OR (category_id IS NULL AND creator IN('migration:0219','migration:0221')))
       AND (deleted=0 OR creator IN('migration:0219','migration:0221'));
     IF matches>1 THEN RAISE EXCEPTION '来源标签与本地同类同名记录身份不唯一：%',item.source_id; END IF;
     IF parent_id_value IS NULL THEN RAISE EXCEPTION '标签缺少分类：%',item.source_id; END IF;
     IF target_id IS NULL AND EXISTS(SELECT 1 FROM dh_asset_tag WHERE name=item.name AND source_record_id IS NULL
         AND creator IN('migration:0219','migration:0221') AND (updater IS DISTINCT FROM creator OR revision<>0)) THEN
       RAISE EXCEPTION '旧来源标签已有本地维护，禁止重新认领，请人工核对：%',item.source_id;
     END IF;
     IF target_id IS NOT NULL AND EXISTS(SELECT 1 FROM dh_asset_tag WHERE id=target_id
         AND (creator NOT IN('migration:0219','migration:0221') OR updater IS DISTINCT FROM creator OR revision<>0)) THEN
       RAISE EXCEPTION '同类同名标签属于本地人工记录或已维护，禁止认领来源，请人工核对：%',item.source_id;
     END IF;
     IF target_id IS NULL THEN
       target_id:=nextval('dh_asset_tag_seq');
       INSERT INTO dh_asset_tag(id,name,category_id,description,near_synonyms,synonyms,status,source_record_id,
         source_creator_name,source_updater_name,creator,updater,create_time,update_time)
       VALUES(target_id,item.name,parent_id_value,item.description,item.near_synonyms,item.synonyms,item.status,item.source_id::text,
         item.creator_name,item.updater_name,'migration:0230','migration:0230',item.create_time,item.update_time);
     ELSE
       -- 旧集市及地图子集复用原主键；仅补缺失分类与来源展示，不改用户已维护的名称、状态、说明或关系。
       UPDATE dh_asset_tag SET source_record_id=item.source_id::text,category_id=coalesce(category_id,parent_id_value),
         source_creator_name=item.creator_name,source_updater_name=item.updater_name WHERE id=target_id AND source_record_id IS NULL;
     END IF;
   END IF;
   UPDATE qdata_tag_seed SET resolved_id=target_id WHERE source_id=item.source_id;
 END LOOP;
 -- 旧导入共有二十六标签、一百三十八关联；仅清理来源已移除且从未本地维护的过时边。
 -- 实测唯一过时边为水位指标来源标签四十九与来源资产四十六；修订或操作者已变化的记录保持原样。
 UPDATE dh_asset_tag_rel r SET deleted=1,revision=r.revision+1,updater='migration:0230',update_time=CURRENT_TIMESTAMP,
   source_record_key='qdata:legacy-removed:tag:'||t.source_record_id||':asset:'||d.source_asset_id
 FROM dh_asset_tag t,dh_asset_import_dataset d
 WHERE t.id=r.tag_id AND d.asset_id=r.asset_id AND t.source_record_id IS NOT NULL AND d.deleted=0 AND r.deleted=0
   AND r.creator IN('migration:0219','migration:0221') AND r.updater=r.creator AND r.revision=0
   AND NOT EXISTS(SELECT 1 FROM qdata_tag_rel_seed s WHERE s.tag_source_id::text=t.source_record_id AND s.asset_source_id::text=d.source_asset_id);
 FOR item IN SELECT * FROM qdata_tag_rel_seed ORDER BY tag_source_id,asset_source_id LOOP
   key_value:='qdata:tag:'||item.tag_source_id||':asset:'||item.asset_source_id;
   IF EXISTS(SELECT 1 FROM dh_asset_tag_rel WHERE source_record_key=key_value) THEN CONTINUE; END IF;
   SELECT resolved_id INTO tag_id_value FROM qdata_tag_seed WHERE source_id=item.tag_source_id;
   SELECT count(*),min(d.asset_id) INTO matches,asset_id_value FROM dh_asset_import_dataset d JOIN dh_asset_data_asset a ON a.id=d.asset_id AND a.deleted=0 WHERE d.source_asset_id=item.asset_source_id::text AND d.deleted=0;
   IF matches<>1 OR tag_id_value IS NULL THEN RAISE EXCEPTION '标签关联缺少唯一真实来源资产或标签：%',key_value; END IF;
   SELECT count(*),min(id) INTO matches,target_id FROM dh_asset_tag_rel WHERE asset_id=asset_id_value AND tag_id=tag_id_value AND (deleted=0 OR creator IN('migration:0219','migration:0221'));
   IF matches>1 THEN RAISE EXCEPTION '标签关联存在歧义：%',key_value; END IF;
   IF target_id IS NULL THEN
     INSERT INTO dh_asset_tag_rel(asset_id,tag_id,source_record_key,creator,updater) VALUES(asset_id_value,tag_id_value,key_value,'migration:0230','migration:0230');
   ELSE
     UPDATE dh_asset_tag_rel SET source_record_key=key_value WHERE id=target_id AND source_record_key IS NULL;
   END IF;
 END LOOP;
 IF (SELECT count(*) FROM dh_asset_tag t JOIN qdata_tag_seed s ON t.source_record_id=s.source_id::text)<>160
   OR (SELECT count(*) FROM dh_asset_tag_rel WHERE source_record_key LIKE 'qdata:tag:%:asset:%')<>139 THEN
   RAISE EXCEPTION '来源标签或可确认关联未完整迁入';
 END IF;
END;
$seed$;

-- 数据库保护与服务层锁配合；直接 SQL 同样不能删除启用或仍有有效关联的标签。
CREATE OR REPLACE FUNCTION dh_asset_tag_protect() RETURNS trigger LANGUAGE plpgsql AS $guard$
BEGIN
 IF TG_OP='DELETE' THEN
   IF OLD.deleted=0 AND (OLD.status=0 OR EXISTS(SELECT 1 FROM dh_asset_tag_rel WHERE tag_id=OLD.id AND deleted=0)) THEN
     RAISE EXCEPTION '启用或有引用的标签禁止删除';
   END IF;
   RETURN OLD;
 END IF;
 IF NEW.deleted=0 AND (TG_OP='INSERT' OR NEW.category_id IS DISTINCT FROM OLD.category_id) THEN
   PERFORM id FROM dh_asset_category_tag WHERE id=NEW.category_id AND deleted=0 AND status=0 FOR SHARE;
   IF NOT FOUND THEN RAISE EXCEPTION '标签分类必须真实有效且启用'; END IF;
 END IF;
 IF TG_OP='INSERT' OR NEW.name IS DISTINCT FROM OLD.name THEN
   IF NEW.name IS NULL OR length(btrim(NEW.name)) NOT BETWEEN 1 AND 50 OR NEW.name<>btrim(NEW.name) THEN
     RAISE EXCEPTION '标签名称必须为一至五十个非空白字符且不能有首尾空格';
   END IF;
 END IF;
 IF NEW.status NOT IN(0,1) OR NEW.revision<0 THEN RAISE EXCEPTION '标签状态或修订号无效'; END IF;
 IF TG_OP='UPDATE' AND OLD.deleted=0 THEN
   IF NEW.deleted<>0 AND (OLD.status=0 OR EXISTS(SELECT 1 FROM dh_asset_tag_rel WHERE tag_id=OLD.id AND deleted=0)) THEN RAISE EXCEPTION '启用或有引用的标签禁止删除'; END IF;
   IF OLD.status=0 AND ROW(NEW.name,NEW.category_id,NEW.color,NEW.near_synonyms,NEW.synonyms,NEW.description,NEW.remark)
       IS DISTINCT FROM ROW(OLD.name,OLD.category_id,OLD.color,OLD.near_synonyms,OLD.synonyms,OLD.description,OLD.remark) THEN
     RAISE EXCEPTION '启用标签禁止修改业务内容';
   END IF;
 END IF;
 RETURN NEW;
END;
$guard$;
COMMENT ON FUNCTION dh_asset_tag_protect() IS '标签名称格式、启用不可改删及有效引用删除保护';
DROP TRIGGER IF EXISTS trg_asset_tag_protect ON dh_asset_tag;
CREATE TRIGGER trg_asset_tag_protect BEFORE INSERT OR UPDATE OR DELETE ON dh_asset_tag FOR EACH ROW EXECUTE FUNCTION dh_asset_tag_protect();
CREATE OR REPLACE FUNCTION dh_asset_tag_relation_protect() RETURNS trigger LANGUAGE plpgsql AS $guard$
DECLARE tag_status integer;tag_deleted integer;
BEGIN
 IF NEW.deleted=0 AND (TG_OP='INSERT' OR OLD.deleted<>0 OR NEW.tag_id<>OLD.tag_id OR NEW.asset_id<>OLD.asset_id) THEN
   -- 与领域写入保持先资产后标签的锁序，避免直接 SQL 绑定与 API 绑定互相等待。
   PERFORM id FROM dh_asset_data_asset WHERE id=NEW.asset_id AND deleted=0 FOR SHARE;
   IF NOT FOUND THEN RAISE EXCEPTION '标签只允许绑定真实有效资产'; END IF;
   SELECT status,deleted INTO tag_status,tag_deleted FROM dh_asset_tag WHERE id=NEW.tag_id FOR SHARE;
   IF tag_status IS NULL OR tag_deleted<>0 OR tag_status<>0 THEN RAISE EXCEPTION '只允许绑定真实启用标签'; END IF;
 END IF;
 IF NEW.revision<0 THEN RAISE EXCEPTION '关联修订号无效'; END IF;
 RETURN NEW;
END;
$guard$;
COMMENT ON FUNCTION dh_asset_tag_relation_protect() IS '绑定时锁定并校验启用标签及真实有效资产，解绑保留来源键防止迁入复活';
DROP TRIGGER IF EXISTS trg_asset_tag_relation_protect ON dh_asset_tag_rel;
CREATE TRIGGER trg_asset_tag_relation_protect BEFORE INSERT OR UPDATE ON dh_asset_tag_rel FOR EACH ROW EXECUTE FUNCTION dh_asset_tag_relation_protect();
CREATE OR REPLACE FUNCTION dh_asset_tag_category_protect() RETURNS trigger LANGUAGE plpgsql AS $guard$
DECLARE category_value bigint;
BEGIN
 IF TG_OP='DELETE' OR (OLD.deleted=0 AND NEW.deleted<>0) THEN
   category_value:=OLD.id;
   IF EXISTS(SELECT 1 FROM dh_asset_tag WHERE category_id=category_value AND deleted=0)
      OR EXISTS(SELECT 1 FROM dh_asset_category_tag WHERE parent_id=category_value AND deleted=0) THEN
     RAISE EXCEPTION '标签类目仍有有效标签或下级，禁止删除';
   END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$guard$;
COMMENT ON FUNCTION dh_asset_tag_category_protect() IS '标签类目删除与新建标签通过类目行锁串行核验，防止有效标签悬空';
DROP TRIGGER IF EXISTS trg_asset_tag_category_protect ON dh_asset_category_tag;
CREATE TRIGGER trg_asset_tag_category_protect BEFORE UPDATE OR DELETE ON dh_asset_category_tag FOR EACH ROW EXECUTE FUNCTION dh_asset_tag_category_protect();
DO $constraints$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='fk_asset_tag_rel_asset' AND conrelid='dh_asset_tag_rel'::regclass) THEN
   ALTER TABLE dh_asset_tag_rel ADD CONSTRAINT fk_asset_tag_rel_asset FOREIGN KEY(asset_id) REFERENCES dh_asset_data_asset(id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='fk_asset_tag_rel_tag' AND conrelid='dh_asset_tag_rel'::regclass) THEN
   ALTER TABLE dh_asset_tag_rel ADD CONSTRAINT fk_asset_tag_rel_tag FOREIGN KEY(tag_id) REFERENCES dh_asset_tag(id);
 END IF;
END;
$constraints$;
COMMIT;
