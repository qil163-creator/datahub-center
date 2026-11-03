-- Migration: 0215 离线与实时数据集成工作台。
-- Purpose: 新增任务控制面、共享类目、源执行实例快照及数据接入菜单；不创建源系统运行进程。
-- Depends on: 0214_modeling_indicator-unit-dictionary.sql；既有数据接入目录、ETL 流程、系统菜单及权限表。
-- Target: 已有 PostgreSQL 平台库；新库使用已包含本迁移的全量基线。
-- Repeatable: 是；表和索引按存在性创建，源数据和菜单仅补充缺失记录，不覆盖业务修改。
-- Rollback: 无破坏性操作；撤回需先停用菜单、备份新任务和快照，再删除本迁移新增对象。迁入图仅供预览，不迁移凭据。

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_integration_category_seq START WITH 1000;
CREATE TABLE IF NOT EXISTS dh_dataforge_integration_category (
 id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_integration_category_seq'),
 parent_id bigint NOT NULL DEFAULT 0,
 name varchar(50) NOT NULL CHECK(name ~ '[A-Za-z0-9一-龥]'),
 sort integer NOT NULL DEFAULT 0 CHECK(sort BETWEEN 0 AND 100000),
 valid_flag boolean NOT NULL DEFAULT true, description varchar(256),
 creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT current_timestamp,
 updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT current_timestamp,
 deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1))
);
COMMENT ON TABLE dh_dataforge_integration_category IS '离线与实时数据集成共享类目';
CREATE UNIQUE INDEX IF NOT EXISTS uk_integration_category_name ON dh_dataforge_integration_category(parent_id,name) WHERE deleted=0;

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_integration_task_seq START WITH 1000;
CREATE TABLE IF NOT EXISTS dh_dataforge_integration_task (
 id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_integration_task_seq'),
 revision integer NOT NULL DEFAULT 0 CHECK(revision>=0),
 mode varchar(16) NOT NULL CHECK(mode IN ('OFFLINE','REALTIME')),
 category_id bigint NOT NULL REFERENCES dh_dataforge_integration_category(id),
 name varchar(200) NOT NULL,
 owner_id bigint, owner_name varchar(100), contact_number varchar(32),
 status smallint NOT NULL DEFAULT 0 CHECK(status IN (0,1)),
 schedule_status smallint NOT NULL DEFAULT 0 CHECK(schedule_status IN (0,1)),
 engine varchar(16) NOT NULL CHECK(engine IN ('DATAX','SPARK','FLINK')),
 scheduler varchar(32) NOT NULL DEFAULT 'DOLPHIN_SCHEDULER' CHECK(scheduler='DOLPHIN_SCHEDULER'),
 cron_expression varchar(100),
 priority varchar(16) NOT NULL DEFAULT 'HIGH' CHECK(priority IN ('HIGHEST','HIGH','MEDIUM','LOW','LOWEST')),
 retry_count integer NOT NULL DEFAULT 0 CHECK(retry_count BETWEEN 0 AND 100),
 retry_interval integer NOT NULL DEFAULT 0 CHECK(retry_interval BETWEEN 0 AND 1440),
 delay_minutes integer NOT NULL DEFAULT 0 CHECK(delay_minutes BETWEEN 0 AND 1440),
 execution_strategy varchar(32) NOT NULL DEFAULT 'SERIAL_DISCARD'
  CHECK(execution_strategy IN ('PARALLEL','SERIAL_WAIT','SERIAL_DISCARD','SERIAL_PRIORITY')),
 worker_group varchar(64) NOT NULL DEFAULT 'default', description varchar(256),
 engine_config_json jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(engine_config_json)='object'),
 flow_json jsonb NOT NULL DEFAULT '{"nodes":[],"edges":[]}'::jsonb CHECK(jsonb_typeof(flow_json)='object'),
 configuration_status varchar(20) NOT NULL DEFAULT 'UNCONFIGURED' CHECK(configuration_status IN ('UNCONFIGURED','COMPLETE')),
 pipeline_id bigint REFERENCES dh_dataforge_etl_pipeline(id),
 imported_snapshot boolean NOT NULL DEFAULT false,
 last_run_status varchar(16) NOT NULL DEFAULT 'IDLE' CHECK(last_run_status IN ('RUNNING','SUCCESS','FAILURE','IDLE')),
 last_run_time timestamp, last_run_duration bigint CHECK(last_run_duration>=0), creator_name varchar(100),
 creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT current_timestamp,
 updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT current_timestamp,
 deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)),
 CHECK(mode<>'REALTIME' OR (engine='FLINK' AND schedule_status=0)),
 CHECK(schedule_status=0 OR (status=1 AND pipeline_id IS NOT NULL))
);
COMMENT ON TABLE dh_dataforge_integration_task IS '离线与实时数据集成控制面，源图快照不代表本平台执行能力';
CREATE UNIQUE INDEX IF NOT EXISTS uk_integration_task_name ON dh_dataforge_integration_task(mode,name) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_integration_task_page ON dh_dataforge_integration_task(mode,category_id,id DESC) WHERE deleted=0;

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_integration_record_seq START WITH 1000;
CREATE TABLE IF NOT EXISTS dh_dataforge_integration_record (
 id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_integration_record_seq'),
 task_id bigint NOT NULL REFERENCES dh_dataforge_integration_task(id),
 status varchar(16) NOT NULL CHECK(status IN ('RUNNING','SUCCESS','FAILURE','IDLE')),
 trigger_type varchar(16) NOT NULL DEFAULT 'IMPORTED' CHECK(trigger_type IN ('IMPORTED','MANUAL','SCHEDULE')),
 start_time timestamp, end_time timestamp, duration bigint CHECK(duration>=0),
 executor_name varchar(100), message text, current_node varchar(200), instance_name varchar(300), command_type varchar(32),
 creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT current_timestamp,
 updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT current_timestamp,
 deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1))
);
COMMENT ON TABLE dh_dataforge_integration_record IS '源系统迁入执行实例快照，不持有本平台运行槽位';
CREATE INDEX IF NOT EXISTS idx_integration_record_page ON dh_dataforge_integration_record(task_id,start_time DESC,id DESC) WHERE deleted=0;

DO $comments$
DECLARE item record; table_name text;
BEGIN
 FOR item IN SELECT * FROM (VALUES
 ('dh_dataforge_integration_category','id','类目编号'),('dh_dataforge_integration_category','parent_id','父类目编号，零表示根'),
 ('dh_dataforge_integration_category','name','类目名称'),('dh_dataforge_integration_category','sort','排序'),
 ('dh_dataforge_integration_category','valid_flag','类目是否有效'),('dh_dataforge_integration_category','description','类目说明'),
 ('dh_dataforge_integration_task','id','任务编号'),('dh_dataforge_integration_task','revision','并发修订号'),
 ('dh_dataforge_integration_task','mode','开发模式：离线或实时'),('dh_dataforge_integration_task','category_id','共享类目编号'),
 ('dh_dataforge_integration_task','name','任务名称'),('dh_dataforge_integration_task','owner_id','本平台负责人编号，源快照可为空'),
 ('dh_dataforge_integration_task','owner_name','负责人名称快照'),('dh_dataforge_integration_task','contact_number','负责人联系电话'),
 ('dh_dataforge_integration_task','status','任务状态：零未上线，一已上线'),('dh_dataforge_integration_task','schedule_status','本平台调度状态：零关闭，一开启'),
 ('dh_dataforge_integration_task','engine','选定执行引擎'),('dh_dataforge_integration_task','scheduler','选定调度系统'),
 ('dh_dataforge_integration_task','cron_expression','调度周期表达式'),('dh_dataforge_integration_task','priority','任务优先级'),
 ('dh_dataforge_integration_task','retry_count','失败重试次数'),('dh_dataforge_integration_task','retry_interval','失败重试间隔分钟'),
 ('dh_dataforge_integration_task','delay_minutes','延迟执行分钟'),('dh_dataforge_integration_task','execution_strategy','执行策略'),
 ('dh_dataforge_integration_task','worker_group','工作节点分组'),('dh_dataforge_integration_task','description','任务描述'),
 ('dh_dataforge_integration_task','engine_config_json','引擎资源配置，不存凭据'),('dh_dataforge_integration_task','flow_json','只读流程图快照，不存凭据'),
 ('dh_dataforge_integration_task','configuration_status','源流程配置状态'),('dh_dataforge_integration_task','pipeline_id','本平台受管可执行流程编号，仅受信任配置可绑定'),
 ('dh_dataforge_integration_task','imported_snapshot','是否含源系统快照'),('dh_dataforge_integration_task','last_run_status','最近执行状态摘要，迁入时为历史快照'),
 ('dh_dataforge_integration_task','last_run_time','最近执行开始时间快照'),('dh_dataforge_integration_task','last_run_duration','最近执行耗时秒'),
 ('dh_dataforge_integration_task','creator_name','创建人名称快照'),('dh_dataforge_integration_record','id','迁入实例编号'),
 ('dh_dataforge_integration_record','task_id','集成任务编号'),('dh_dataforge_integration_record','status','源实例状态'),
 ('dh_dataforge_integration_record','trigger_type','实例来源或触发方式'),('dh_dataforge_integration_record','start_time','源实例开始时间'),
 ('dh_dataforge_integration_record','end_time','源实例结束时间'),('dh_dataforge_integration_record','duration','源实例耗时秒'),
 ('dh_dataforge_integration_record','executor_name','源执行人名称'),('dh_dataforge_integration_record','message','脱敏执行说明'),
 ('dh_dataforge_integration_record','current_node','源实例当前节点摘要'),
 ('dh_dataforge_integration_record','instance_name','源实例完整名称'),('dh_dataforge_integration_record','command_type','源实例控制命令类型码')
 ) descriptions(table_name,column_name,description) LOOP
  EXECUTE format('COMMENT ON COLUMN %I.%I IS %L',item.table_name,item.column_name,item.description);
 END LOOP;
 FOREACH table_name IN ARRAY ARRAY['dh_dataforge_integration_category','dh_dataforge_integration_task','dh_dataforge_integration_record'] LOOP
  EXECUTE format('COMMENT ON COLUMN %I.creator IS %L',table_name,'创建人');
  EXECUTE format('COMMENT ON COLUMN %I.create_time IS %L',table_name,'创建时间');
  EXECUTE format('COMMENT ON COLUMN %I.updater IS %L',table_name,'更新人');
  EXECUTE format('COMMENT ON COLUMN %I.update_time IS %L',table_name,'更新时间');
  EXECUTE format('COMMENT ON COLUMN %I.deleted IS %L',table_name,'逻辑删除标记');
 END LOOP;
END $comments$;

-- 已核实的 qData 类目和业务任务快照；仅保留界面可见资源参数和已脱敏的图结构。
INSERT INTO dh_dataforge_integration_category(id,parent_id,name,sort,valid_flag,description,creator,updater) VALUES
 (6,1,'基础对象原始数据',0,true,'同步河流、河段、断面、行政区划、测站、上下游关系等基础对象原始表到ODS。','migration:0215','migration:0215'),
 (1,0,'数据同步_第三方库到ODS',0,true,'用于管理从第三方业务库、接口及文件等数据源采集并同步至ODS层的数据集成任务，保留源端原始数据和采集过程信息。','migration:0215','migration:0215'),
 (20,4,'日旬月年统计汇总',0,true,'按日、旬、月、年或窗口周期汇总水位、流量、雨量、水势变化等统计结果。','migration:0215','migration:0215'),
 (11,2,'基础对象维度',0,true,'清洗基础对象ODS数据，形成行政区划、河流、河段、断面、测站等公共维表。','migration:0215','migration:0215'),
 (25,5,'河道水情总览',0,true,'面向河道水情总览页面组织核心指标、趋势、告警和统计卡片数据。','migration:0215','migration:0215'),
 (14,3,'实时监测明细',0,true,'对实时水位、流量、雨量等数据进行清洗、去重、维度关联，形成实时监测明细。','migration:0215','migration:0215'),
 (26,5,'日旬月年统计看板',1,true,'面向日旬月年统计分析看板组织水位、流量、雨量等统计展示数据。','migration:0215','migration:0215'),
 (12,2,'规则阈值参考数据',1,true,'清洗阈值、规则、参数配置类数据，形成可复用参考维表。','migration:0215','migration:0215'),
 (2,0,'数据清洗_ODS到DIM',1,true,'用于管理ODS层数据经清洗、去重、标准化和主数据关联后写入DIM层的数据集成任务，形成统一公共维度数据。','migration:0215','migration:0215'),
 (21,4,'防洪告警汇总',1,true,'汇总防洪告警次数、等级、断面分布、处置状态等统计结果。','migration:0215','migration:0215'),
 (15,3,'历史时序明细',1,true,'对历史水位、流量、雨量及多年同期数据进行标准化加工，形成历史时序明细。','migration:0215','migration:0215'),
 (7,1,'实时监测原始数据',1,true,'同步或接入实时水位、流量、雨量、在线状态、采集状态等实时监测原始数据到ODS。','migration:0215','migration:0215'),
 (3,0,'明细加工_ODS_DIM到DWD',2,true,'用于管理基于ODS原始数据和DIM维度数据进行关联、转换及明细加工并写入DWD层的数据集成任务。','migration:0215','migration:0215'),
 (27,5,'防洪告警看板',2,true,'面向防洪告警看板组织告警事件、告警等级和处置状态展示数据。','migration:0215','migration:0215'),
 (13,2,'生态基流参考数据',2,true,'清洗生态基流控制断面、目标流量、监管标准等参考数据。','migration:0215','migration:0215'),
 (22,4,'生态基流监管汇总',2,true,'汇总生态基流达标率、断面达标情况、连续不达标等监管统计结果。','migration:0215','migration:0215'),
 (8,1,'历史时序原始数据',2,true,'同步历史水位、历史流量、历史雨量等时序原始数据到ODS。','migration:0215','migration:0215'),
 (16,3,'来水下泄明细',2,true,'加工来水、下泄、上下游断面对比等过程明细数据。','migration:0215','migration:0215'),
 (28,5,'生态基流监管看板',3,true,'面向生态基流监管看板组织达标率、断面状态和连续不达标展示数据。','migration:0215','migration:0215'),
 (23,4,'来水下泄分析汇总',3,true,'汇总来水量、下泄流量、上下游变化趋势等分析结果。','migration:0215','migration:0215'),
 (9,1,'规则阈值原始数据',3,true,'同步防洪告警阈值、生态基流阈值、异常识别规则等原始规则数据到ODS。','migration:0215','migration:0215'),
 (17,3,'防洪告警事件明细',3,true,'加工超警、超保、流量突增、水位上涨速率等防洪告警事件明细。','migration:0215','migration:0215'),
 (4,0,'汇总统计_DWD_DIM到DWS',3,true,'用于管理基于DWD明细数据和DIM维度数据进行指标计算、分组汇总及统计分析并写入DWS层的数据集成任务。','migration:0215','migration:0215'),
 (29,5,'来水下泄断面监控看板',4,true,'面向来水下泄断面监控页面组织断面对比和趋势分析数据。','migration:0215','migration:0215'),
 (24,4,'异常识别汇总',4,true,'汇总异常数据类型、异常次数、影响范围、修复状态等统计结果。','migration:0215','migration:0215'),
 (10,1,'来水下泄原始数据',4,true,'同步来水、下泄、上下游断面监测相关原始数据到ODS。','migration:0215','migration:0215'),
 (5,0,'应用服务_DWD_DWS到ADS',4,true,'用于管理面向看板、报表、接口及专题应用，将DWD和DWS数据加工并写入ADS层的数据集成任务。','migration:0215','migration:0215'),
 (18,3,'生态基流监管明细',4,true,'加工生态基流达标、不达标、连续不达标等监管明细。','migration:0215','migration:0215'),
 (30,5,'异常数据识别工作台',5,true,'面向异常识别工作台组织异常记录、影响对象和处理状态数据。','migration:0215','migration:0215'),
 (19,3,'异常识别明细',5,true,'加工测站离线、数据缺测、采集中断、数据突变等异常识别明细。','migration:0215','migration:0215'),
 (31,5,'河道水情一张图',6,true,'面向河道水情一张图组织断面、测站、空间坐标和实时指标展示数据。','migration:0215','migration:0215'),
 (32,5,'水情简报数据服务',7,true,'面向水情简报生成、查询和接口服务组织简报指标和文本数据。','migration:0215','migration:0215'),
 (33,5,'告警推送数据服务',8,true,'面向告警消息推送、接口调用和通知服务组织推送数据。','migration:0215','migration:0215'),
 (34,5,'统计报表数据服务',9,true,'面向统计报表查询、导出和接口服务组织报表结果数据。','migration:0215','migration:0215')
ON CONFLICT(id) DO NOTHING;
INSERT INTO dh_dataforge_integration_task(id,revision,mode,category_id,name,owner_id,owner_name,contact_number,status,schedule_status,engine,scheduler,cron_expression,priority,retry_count,retry_interval,delay_minutes,execution_strategy,worker_group,description,engine_config_json,flow_json,configuration_status,imported_snapshot,last_run_status,last_run_time,last_run_duration,creator_name,creator,updater,create_time,update_time) VALUES
 (50,0,'OFFLINE',14,'河道水情_ODS到ADS_雨量_Z组合转换_全量_10分钟.',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_RTW_ST_PPTN_R，检查降雨量是否为负数，补充站点信息后，对测站名称进行值映射、增加常量、数据范围写入到 ADS_RH_RTW_ST_PPTN_R','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"187627824861536","label":"表输出组件","type":"91","x":620,"y":410},{"id":"187627794164064","label":"表输入组件","type":"1","x":210,"y":190},{"id":"187627813783904","label":"值映射","type":"47","x":340,"y":270},{"id":"187627819151712","label":"增加常量","type":"48","x":430,"y":310},{"id":"187627822707040","label":"数值范围","type":"49","x":520,"y":370}],"edges":[{"source":"187627794164064","target":"187627813783904"},{"source":"187627813783904","target":"187627819151712"},{"source":"187627819151712","target":"187627822707040"},{"source":"187627822707040","target":"187627824861536"}]}','COMPLETE',true,'RUNNING','2026-10-22 19:02:07',8087,'吴同','migration:0215','migration:0215','2026-10-22 17:17:47.751000','2026-10-22 19:01:54.405000'),
 (49,0,'OFFLINE',14,'河道水情_ODS到DWD/DWS_雨量_条件分流_全量_10分钟.',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_RTW_ST_PPTN_R，检查降雨量是否为负数，补充站点信息后写入大于100写入 DWD_RH_RTW_ST_PPTN_R。小于100写入 DWS_RH_RTW_ST_PPTN_R','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"187627209392480","label":"表输入组件","type":"1","x":190,"y":180},{"id":"187627209397600","label":">100表输出组件","type":"91","x":510,"y":250},{"id":"187627587340640","label":"条件分发","type":"CONDITION_DISTRIBUTE","x":290,"y":280},{"id":"187627609738592","label":"<100表输出组件","type":"91","x":430,"y":410}],"edges":[{"source":"187627209392480","target":"187627587340640"},{"source":"187627587340640","target":"187627209397600"},{"source":"187627587340640","target":"187627609738592"}]}','COMPLETE',true,'RUNNING','2026-10-22 18:58:15',8319,'吴同','migration:0215','migration:0215','2026-10-22 17:08:16.684000','2026-10-22 18:58:02.656000'),
 (48,0,'OFFLINE',14,'河道水情_ODS到DWD_雨量_清洗关联_全量_10分钟.',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_RTW_ST_PPTN_R，检查降雨量是否为负数，补充站点信息后写入 DWD_RH_RTW_ST_PPTN_R。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"187627141565792","label":"表输入组件","type":"1","x":190,"y":130},{"id":"187627141567840","label":"转换组件","type":"31","x":300,"y":250},{"id":"187627141570912","label":"表输出组件","type":"91","x":530,"y":330}],"edges":[{"source":"187627141565792","target":"187627141567840"},{"source":"187627141567840","target":"187627141570912"}]}','COMPLETE',true,'RUNNING','2026-10-22 19:11:02',7552,'吴同','migration:0215','migration:0215','2026-10-22 17:07:10.448000','2026-10-22 18:55:14.358000'),
 (47,0,'OFFLINE',6,'河道水情_基础库到ODS_水位站_全量_每日_copy_20261022141750206',NULL,'吴同','15365128327',0,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'SERIAL_WAIT','default','读取 ST_Z_STBPRP_B 中的水位站编码、名称、位置、高程和断面归属，写入 ODS_RH_BO_ST_Z_STBPRP_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"187616737520992","label":"表输出组件","type":"91","x":380,"y":250},{"id":"187616737518944","label":"表输入组件","type":"1","x":230,"y":230}],"edges":[{"source":"187616737518944","target":"187616737520992"}]}','COMPLETE',true,'IDLE','2026-10-22 14:52:35',3016,'吴同','migration:0215','migration:0215','2026-10-22 14:17:50.242000','2026-10-22 17:07:44.852000'),
 (18,0,'OFFLINE',6,'河道水情_基础库到ODS_水位站_全量_每日',NULL,'吴同','15365128327',0,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'SERIAL_WAIT','default','读取 ST_Z_STBPRP_B 中的水位站编码、名称、位置、高程和断面归属，写入 ODS_RH_BO_ST_Z_STBPRP_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180629980610112","label":"表输入组件","type":"1","x":230,"y":230},{"id":"180630021398080","label":"表输出组件","type":"91","x":380,"y":250}],"edges":[{"source":"180629980610112","target":"180630021398080"}]}','COMPLETE',true,'SUCCESS','2026-08-06 21:01:49',8,'吴同','migration:0215','migration:0215','2026-08-04 14:27:07.618000','2026-10-22 08:22:56.717000'),
 (17,0,'OFFLINE',8,'河道水情_历史库到ODS_历史水位_增量_每小时',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'SERIAL_WAIT','default','读取 ST_Z_HIS_R 中的测站、监测时间、水位、警戒水位和上涨速率，写入 ODS_RH_MON_ST_Z_HIS_R，保留原始水位记录。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180634814063168","label":"表输入组件","type":"1","x":220,"y":130},{"id":"180634825788992","label":"表输出组件","type":"91","x":440,"y":190}],"edges":[{"source":"180634814063168","target":"180634825788992"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:46:19',18,'吴同','migration:0215','migration:0215','2026-08-04 14:26:31.339000','2026-08-04 16:50:26.816000'),
 (16,0,'OFFLINE',11,'河道水情_ODS到DIM_水位站维表_去重标准化_每日',NULL,'吴同','15365128327',0,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_BO_ST_Z_STBPRP_B ，统一站名、高程和断面归属，写入 DIM_RH_BO_ST_Z_STBPRP_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180644232485440","label":"表输入组件","type":"1","x":400,"y":200},{"id":"180644247765568","label":"表输出组件","type":"91","x":560,"y":310}],"edges":[{"source":"180644232485440","target":"180644247765568"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:46:24',15,'吴同','migration:0215','migration:0215','2026-08-04 14:25:55.982000','2026-10-22 11:37:33.387000'),
 (15,0,'OFFLINE',15,'河道水情_ODS到DWD_历史水位明细_增量清洗_每小时',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_MON_ST_Z_HIS_R，检查监测时间和水位范围，写入 DWD_RH_MON_ST_Z_HIS_R。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180634940345920","label":"表输入组件","type":"1","x":280,"y":110},{"id":"180634974291520","label":"转换组件","type":"31","x":400,"y":180},{"id":"180635019662912","label":"表输出组件","type":"91","x":490,"y":260}],"edges":[{"source":"180634940345920","target":"180634974291520"},{"source":"180634974291520","target":"180635019662912"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:46:25',18,'吴同','migration:0215','migration:0215','2026-08-04 14:25:20.894000','2026-08-04 16:42:11.670000'),
 (14,0,'OFFLINE',20,'河道水情_DWD到DWS_日水位统计_日口径汇总_每日',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 DWD_RH_MON_ST_Z_HIS_R，按测站和日期计算平均、最高、最低水位及最大上涨速率，结果写入 DWS_RH_HIST_ST_Z_DAY_S。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180638398249536","label":"表输入组件","type":"1","x":230,"y":160},{"id":"180638458847808","label":"表输出组件","type":"91","x":370,"y":220}],"edges":[{"source":"180638398249536","target":"180638458847808"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:57:07',16,'吴同','migration:0215','migration:0215','2026-08-04 14:24:42.284000','2026-08-04 18:41:47.350000'),
 (13,0,'OFFLINE',25,'河道水情_DWS到ADS_河道水情综合态势_增量_10分钟',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 * ?','HIGH',0,0,0,'PARALLEL','default','读取 DWD_RH_STAT_ST_RIVER_R，组合断面名称、水位、警戒水位，写入 ADS_RH_APP_APP_RV_WTROV_A，供水情总览页面使用。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180643343962688","label":"表输入组件","type":"1","x":270,"y":190},{"id":"180643366276672","label":"表输出组件","type":"91","x":400,"y":270}],"edges":[{"source":"180643343962688","target":"180643366276672"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:46:43',10,'吴同','migration:0215','migration:0215','2026-08-04 14:23:59.448000','2026-08-04 18:39:11.549000'),
 (12,0,'OFFLINE',8,'河道水情_历史库到ODS_历史流量明细_增量_每小时',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ST_Q_HIS_R 中的测站、监测时间和流量值，写入 ODS_RH_HIST_ST_Q_HIS_R，保留原始流量记录。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180638664062528","label":"表输入组件","type":"1","x":340,"y":130},{"id":"180638682468928","label":"表输出组件","type":"91","x":480,"y":220}],"edges":[{"source":"180638664062528","target":"180638682468928"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:46:55',9,'吴同','migration:0215','migration:0215','2026-08-04 14:22:52.194000','2026-08-04 17:23:17.440000'),
 (11,0,'OFFLINE',6,'河道水情_基础库到ODS_雨量站维表_全量_每日',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ST_PP_STBPRP_B 中的雨量站编码、名称、坐标和所属区域，写入 ODS_RH_BO_ST_PP_STBPRP_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180639866575424","label":"表输入组件","type":"1","x":340,"y":180},{"id":"180639896659520","label":"表输出组件","type":"91","x":510,"y":270}],"edges":[{"source":"180639866575424","target":"180639896659520"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:47:05',7,'吴同','migration:0215','migration:0215','2026-08-04 14:22:18.390000','2026-08-04 17:42:43.619000'),
 (10,0,'OFFLINE',7,'河道水情_监测库到ODS_雨量明细_定时任务_10分钟',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ST_PPTN_R 中的雨量站、监测时间和最新降雨量，写入 ODS_RH_RTW_ST_PPTN_R，保留原始监测记录。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180639954870848","label":"表输入组件","type":"1","x":130,"y":110},{"id":"180639964181056","label":"表输出组件","type":"91","x":220,"y":250}],"edges":[{"source":"180639954870848","target":"180639964181056"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:57:22',6,'吴同','migration:0215','migration:0215','2026-08-04 14:21:39.241000','2026-08-04 17:43:59.650000'),
 (9,0,'OFFLINE',14,'河道水情_ODS到DWD_雨量_清洗关联_全量_10分钟',NULL,'吴同','15365128327',0,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_RTW_ST_PPTN_R，检查降雨量是否为负数，补充站点信息后写入 DWD_RH_RTW_ST_PPTN_R。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640027438656","label":"表输入组件","type":"1","x":190,"y":130},{"id":"180640061972032","label":"转换组件","type":"31","x":300,"y":250},{"id":"180640116290112","label":"表输出组件","type":"91","x":530,"y":330}],"edges":[{"source":"180640027438656","target":"180640061972032"},{"source":"180640061972032","target":"180640116290112"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:47:10',8,'吴同','migration:0215','migration:0215','2026-08-04 14:20:55.912000','2026-10-22 17:07:07.647000'),
 (8,0,'OFFLINE',6,'河道水情_基础库到ODS_水文站维表_全量_每日',NULL,'吴同','15365128327',0,0,'FLINK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ST_STBPRP_B 中的水文站编码、名称、站类、监测项目和断面归属，写入 ODS_RH_BO_ST_STBPRP_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640232255040","label":"表输入组件","type":"1","x":180,"y":120},{"id":"180640239028800","label":"表输出组件","type":"91","x":410,"y":230}],"edges":[{"source":"180640232255040","target":"180640239028800"}]}','COMPLETE',true,'IDLE',NULL,NULL,'吴同','migration:0215','migration:0215','2026-08-04 14:20:01.774000','2026-08-04 17:48:36.672000'),
 (6,0,'OFFLINE',11,'河道水情_ODS到DIM_河流维表_去重标准化_每日',NULL,'吴同','15365128327',0,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_BO_ST_RV_B，检查河流编码和名称，写入 DIM_RH_BO_ST_RV_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640372906560","label":"表输出组件","type":"91","x":570,"y":370},{"id":"180640318418496","label":"表输入组件","type":"1","x":350,"y":270}],"edges":[{"source":"180640318418496","target":"180640372906560"}]}','COMPLETE',true,'FAILURE','2026-08-06 20:47:44',8,'吴同','migration:0215','migration:0215','2026-08-04 14:18:43.504000','2026-08-06 20:48:02.657000'),
 (5,0,'OFFLINE',11,'河道水情_ODS到DIM_河段维表_去重标准化_每日',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_BO_ST_RCH_B ，补齐所属河流和上下游断面关系，写入 DIM_RH_BO_ST_RCH_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640530184768","label":"表输入组件","type":"1","x":440,"y":200},{"id":"180640554352192","label":"表输出组件","type":"91","x":600,"y":320}],"edges":[{"source":"180640530184768","target":"180640554352192"}]}','COMPLETE',true,'FAILURE','2026-08-06 21:11:51',9,'吴同','migration:0215','migration:0215','2026-08-04 14:17:59.833000','2026-08-04 17:53:22.924000'),
 (4,0,'OFFLINE',11,'河道水情_ODS到DIM_断面维表_去重标准化_每日',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_BO_ST_SEC_B，并关联河流、河段维表，统一坐标、高程和警戒水位，写入 DIM_RH_BO_ST_SEC_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640620423744","label":"表输入组件","type":"1","x":300,"y":150},{"id":"180640628469312","label":"表输出组件","type":"91","x":610,"y":320}],"edges":[{"source":"180640620423744","target":"180640628469312"}]}','COMPLETE',true,'FAILURE','2026-08-06 21:12:34',9,'吴同','migration:0215','migration:0215','2026-08-04 14:17:29.089000','2026-08-04 17:54:37.214000'),
 (3,0,'OFFLINE',11,'河道水情_ODS到DIM_水文站维表_去重标准化_每日',NULL,'吴同','15365128327',1,0,'SPARK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_BO_ST_STBPRP_B ，统一站点编码、站类、监测项目和位置，写入 DIM_RH_BO_ST_STBPRP_B。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640656332352","label":"表输入组件","type":"1","x":180,"y":150},{"id":"180640673126976","label":"表输出组件","type":"91","x":310,"y":280}],"edges":[{"source":"180640656332352","target":"180640673126976"}]}','COMPLETE',true,'SUCCESS','2026-08-06 20:57:45',8,'吴同','migration:0215','migration:0215','2026-08-04 14:16:57.074000','2026-08-04 17:55:24.070000'),
 (1,0,'OFFLINE',15,'河道水情_ODS到DWD_历史流量明细_增量清洗_每小时',NULL,'吴同','15365128327',1,0,'FLINK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_HIST_ST_Q_HIS_R，检查流量是否为负数，补充流量站和断面信息后写入 DWD_RH_HIST_ST_Q_HIS_R。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640748603968","label":"表输入组件","type":"1","x":260,"y":130},{"id":"180640755755584","label":"表输出组件","type":"91","x":390,"y":250}],"edges":[{"source":"180640748603968","target":"180640755755584"}]}','COMPLETE',true,'IDLE',NULL,NULL,'吴同','migration:0215','migration:0215','2026-08-04 14:15:08.537000','2026-10-22 14:17:52.280000')
ON CONFLICT(id) DO NOTHING;
-- 已核实的实时集成任务快照。
INSERT INTO dh_dataforge_integration_task(id,revision,mode,category_id,name,owner_id,owner_name,contact_number,status,schedule_status,engine,scheduler,cron_expression,priority,retry_count,retry_interval,delay_minutes,execution_strategy,worker_group,description,engine_config_json,flow_json,configuration_status,imported_snapshot,last_run_status,last_run_time,last_run_duration,creator_name,creator,updater,create_time,update_time) VALUES
 (7,0,'REALTIME',7,'河道水情_监测库到ODS_实时水位明细_实时',NULL,'吴同','15365128327',0,0,'FLINK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ST_RIVER_R 中的测站、监测时间、水位、警戒水位和上涨速率，写入 ODS_RH_STAT_ST_RIVER_R，保留原始监测记录。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640285250112","label":"表输入组件","type":"1","x":220,"y":170},{"id":"180640293073472","label":"表输出组件","type":"91","x":330,"y":240}],"edges":[{"source":"180640285250112","target":"180640293073472"}]}','COMPLETE',true,'IDLE',NULL,NULL,'吴同','migration:0215','migration:0215','2026-08-04 14:19:22.715000','2026-08-04 17:49:03.348000'),
 (2,0,'REALTIME',14,'河道水情_ODS到DWD_实时水位明细_清洗关联_实时',NULL,'吴同','15365128327',0,0,'FLINK','DOLPHIN_SCHEDULER','0 0 0 1 1 ?','HIGH',0,0,0,'PARALLEL','default','读取 ODS_RH_STAT_ST_RIVER_R，检查水位和监测时间，补充河流、河段和断面信息后写入 DWD_RH_STAT_ST_RIVER_R。','{"jvmInitialMemory":1024,"jvmMaximumMemory":2048,"channel":1,"byteSpeed":0,"recordSpeed":0,"errorLimit":0,"driverCores":1,"driverMemory":"512m","executorCount":1,"executorMemory":"512m","executorCores":1,"yarnQueue":"","jobManagerMemory":"1G","taskManagerMemory":"2G","slots":1,"taskManagerCount":2,"parallelism":1}','{"nodes":[{"id":"180640711413312","label":"表输入组件","type":"1","x":240,"y":150},{"id":"180640726257216","label":"表输出组件","type":"91","x":460,"y":240}],"edges":[{"source":"180640711413312","target":"180640726257216"}]}','COMPLETE',true,'IDLE',NULL,NULL,'吴同','migration:0215','migration:0215','2026-08-04 14:15:58.640000','2026-08-04 17:56:08.609000')
ON CONFLICT(id) DO NOTHING;
SELECT setval('dh_dataforge_integration_category_seq',greatest((SELECT last_value FROM dh_dataforge_integration_category_seq),coalesce((SELECT max(id) FROM dh_dataforge_integration_category),1)));
SELECT setval('dh_dataforge_integration_task_seq',greatest((SELECT last_value FROM dh_dataforge_integration_task_seq),coalesce((SELECT max(id) FROM dh_dataforge_integration_task),1)));


-- 全部已核实的源系统执行实例历史快照。
INSERT INTO dh_dataforge_integration_record(id,task_id,status,trigger_type,start_time,end_time,duration,executor_name,message,current_node,instance_name,command_type,creator,updater) VALUES
 (1269,50,'RUNNING','IMPORTED','2026-10-22 19:02:07',NULL,8267,'吴同','源系统历史快照；不代表本平台存在运行进程',NULL,'河道水情_ODS到ADS_雨量_Z组合转换_全量_10分钟.-4-20261022190207206','0','migration:0215','migration:0215'),
 (1268,49,'RUNNING','IMPORTED','2026-10-22 18:58:15',NULL,8500,'吴同','源系统历史快照；不代表本平台存在运行进程',NULL,'河道水情_ODS到DWD/DWS_雨量_条件分流_全量_10分钟.-5-20261022185815601','0','migration:0215','migration:0215'),
 (1271,48,'RUNNING','IMPORTED','2026-10-22 19:11:02',NULL,7732,'吴同','源系统历史快照；不代表本平台存在运行进程',NULL,'河道水情_ODS到DWD_雨量_清洗关联_全量_10分钟.-3-20261022191102720','0','migration:0215','migration:0215'),
 (1267,48,'IDLE','IMPORTED','2026-10-22 17:07:49','2026-10-22 18:55:26',6457,'吴同','源系统历史快照；不代表本平台存在运行进程',NULL,'河道水情_ODS到DWD_雨量_清洗关联_全量_10分钟.-2-20261022170749315','9','migration:0215','migration:0215'),
 (1247,47,'IDLE','IMPORTED','2026-10-22 14:52:35','2026-10-22 15:42:52',3017,'吴同','源系统历史快照；不代表本平台存在运行进程',NULL,'河道水情_基础库到ODS_水位站_全量_每日_copy_20261022141750206-2-20261022145235547','9','migration:0215','migration:0215'),
 (180829090983840,18,'SUCCESS','IMPORTED','2026-08-06 21:01:48','2026-08-06 21:01:58',10,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_水位站_全量_每日-4-20260806210149163','0','migration:0215','migration:0215'),
 (180828987542560,18,'SUCCESS','IMPORTED','2026-08-06 21:00:07','2026-08-06 21:00:17',10,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_水位站_全量_每日-4-20260806210008147','0','migration:0215','migration:0215'),
 (180828790549248,18,'SUCCESS','IMPORTED','2026-08-06 20:56:55','2026-08-06 20:57:05',10,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_水位站_全量_每日-4-20260806205655770','0','migration:0215','migration:0215'),
 (180828134271424,18,'SUCCESS','IMPORTED','2026-08-06 20:46:14','2026-08-06 20:46:27',13,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_水位站_全量_每日-4-20260806204614874','0','migration:0215','migration:0215'),
 (180732146972000,18,'SUCCESS','IMPORTED','2026-08-05 18:43:53','2026-08-05 18:45:30',97,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_水位站_全量_每日-4-20260805184357277','0','migration:0215','migration:0215'),
 (180653379000928,18,'SUCCESS','IMPORTED','2026-08-04 21:21:52','2026-08-04 21:22:34',42,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_水位站_全量_每日-4-20260804212155431','0','migration:0215','migration:0215'),
 (180634455840000,18,'SUCCESS','IMPORTED','2026-08-04 16:13:51','2026-08-04 16:15:22',91,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_水位站维表_全量_每日-3-20260804161355781','0','migration:0215','migration:0215'),
 (180828138342976,17,'SUCCESS','IMPORTED','2026-08-06 20:46:17','2026-08-06 20:46:38',21,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_历史库到ODS_历史水位_增量_每小时-1-20260806204618850','0','migration:0215','migration:0215'),
 (180732152368224,17,'SUCCESS','IMPORTED','2026-08-05 18:43:59','2026-08-05 18:45:02',63,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_历史库到ODS_历史水位_增量_每小时-1-20260805184402547','0','migration:0215','migration:0215'),
 (180653379012224,17,'SUCCESS','IMPORTED','2026-08-04 21:21:52','2026-08-04 21:23:20',88,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_历史库到ODS_历史水位_增量_每小时-1-20260804212155441','0','migration:0215','migration:0215'),
 (180634892677312,17,'SUCCESS','IMPORTED','2026-08-04 16:20:59','2026-08-04 16:22:24',85,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_历史库到ODS_历史水位_增量_每小时-1-20260804162102380','0','migration:0215','migration:0215'),
 (180828143077664,16,'SUCCESS','IMPORTED','2026-08-06 20:46:22','2026-08-06 20:46:40',18,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_水位站维表_去重标准化_每日-12-20260806204623474','0','migration:0215','migration:0215'),
 (180732707736000,16,'SUCCESS','IMPORTED','2026-08-05 18:53:02','2026-08-05 18:54:20',78,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_水位站维表_去重标准化_每日-12-20260805185304898','0','migration:0215','migration:0215'),
 (180655924056416,16,'SUCCESS','IMPORTED','2026-08-04 22:03:17','2026-08-04 22:04:34',77,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_水位站维表_去重标准化_每日-12-20260804220320836','0','migration:0215','migration:0215'),
 (180828144655136,15,'SUCCESS','IMPORTED','2026-08-06 20:46:24','2026-08-06 20:46:44',20,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_历史水位明细_增量清洗_每小时-3-20260806204625014','0','migration:0215','migration:0215'),
 (180732465720672,15,'SUCCESS','IMPORTED','2026-08-05 18:49:04','2026-08-05 18:51:04',120,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_历史水位明细_增量清洗_每小时-3-20260805184908555','0','migration:0215','migration:0215'),
 (180653525968992,15,'SUCCESS','IMPORTED','2026-08-04 21:24:15','2026-08-04 21:25:09',54,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_历史水位明细_增量清洗_每小时-3-20260804212418955','0','migration:0215','migration:0215'),
 (180636244087904,15,'SUCCESS','IMPORTED','2026-08-04 16:42:59','2026-08-04 16:44:26',87,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_历史水位明细_增量清洗_每小时-3-20260804164302118','0','migration:0215','migration:0215'),
 (180635913626528,15,'SUCCESS','IMPORTED','2026-08-04 16:37:36','2026-08-04 16:39:07',91,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_历史水位明细_增量清洗_每小时-2-20260804163739401','0','migration:0215','migration:0215'),
 (180635696217920,15,'SUCCESS','IMPORTED','2026-08-04 16:34:04','2026-08-04 16:35:27',83,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_历史水位明细_增量清洗_每小时-1-20260804163407087','0','migration:0215','migration:0215'),
 (180828802092064,14,'SUCCESS','IMPORTED','2026-08-06 20:57:06','2026-08-06 20:57:24',18,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWD到DWS_日水位统计_日口径汇总_每日-8-20260806205707043','0','migration:0215','migration:0215'),
 (180828152475584,14,'SUCCESS','IMPORTED','2026-08-06 20:46:31','2026-08-06 20:46:50',19,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWD到DWS_日水位统计_日口径汇总_每日-8-20260806204632651','0','migration:0215','migration:0215'),
 (180732715957504,14,'SUCCESS','IMPORTED','2026-08-05 18:53:10','2026-08-05 18:54:13',63,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWD到DWS_日水位统计_日口径汇总_每日-8-20260805185312927','0','migration:0215','migration:0215'),
 (180653625275712,14,'SUCCESS','IMPORTED','2026-08-04 21:25:53','2026-08-04 21:27:35',102,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWD到DWS_日水位统计_日口径汇总_每日-8-20260804212555933','0','migration:0215','migration:0215'),
 (180643952638912,14,'SUCCESS','IMPORTED','2026-08-04 18:48:27','2026-08-04 18:50:45',138,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWD到DWS_日水位统计_日口径汇总_每日-8-20260804184829998','0','migration:0215','migration:0215'),
 (180643589517120,14,'SUCCESS','IMPORTED','2026-08-04 18:42:32','2026-08-04 18:43:02',30,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWD到DWS_日水位统计_日口径汇总_每日-8-20260804184235388','0','migration:0215','migration:0215'),
 (180828163325472,13,'SUCCESS','IMPORTED','2026-08-06 20:46:42','2026-08-06 20:46:54',12,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWS到ADS_河道水情综合态势_增量_10分钟-1-20260806204643247','0','migration:0215','migration:0215'),
 (180733273618976,13,'SUCCESS','IMPORTED','2026-08-05 19:02:14','2026-08-05 19:03:39',85,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWS到ADS_河道水情综合态势_增量_10分钟-1-20260805190217518','0','migration:0215','migration:0215'),
 (180643432345632,13,'SUCCESS','IMPORTED','2026-08-04 18:39:59','2026-08-04 18:40:36',37,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_DWS到ADS_河道水情综合态势_增量_10分钟-1-20260804184001900','0','migration:0215','migration:0215'),
 (180828176013376,12,'SUCCESS','IMPORTED','2026-08-06 20:46:55','2026-08-06 20:47:05',10,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_历史库到ODS_历史流量明细_增量_每小时-1-20260806204655638','0','migration:0215','migration:0215'),
 (180734941784448,12,'SUCCESS','IMPORTED','2026-08-05 19:29:24','2026-08-05 19:30:43',79,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_历史库到ODS_历史流量明细_增量_每小时-1-20260805192926586','0','migration:0215','migration:0215'),
 (180638766873344,12,'SUCCESS','IMPORTED','2026-08-04 17:24:02','2026-08-04 17:25:22',80,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_历史库到ODS_历史流量明细_增量_每小时-1-20260804172405775','0','migration:0215','migration:0215'),
 (180828185599040,11,'SUCCESS','IMPORTED','2026-08-06 20:47:04','2026-08-06 20:47:13',9,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_雨量站维表_全量_每日-1-20260806204705000','0','migration:0215','migration:0215'),
 (180734797282592,11,'SUCCESS','IMPORTED','2026-08-05 19:27:03','2026-08-05 19:28:10',67,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_雨量站维表_全量_每日-1-20260805192705471','0','migration:0215','migration:0215'),
 (180639963656000,11,'SUCCESS','IMPORTED','2026-08-04 17:43:31','2026-08-04 17:44:49',78,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_基础库到ODS_雨量站维表_全量_每日-1-20260804174334507','0','migration:0215','migration:0215'),
 (180828817622144,10,'SUCCESS','IMPORTED','2026-08-06 20:57:21','2026-08-06 20:57:29',8,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_监测库到ODS_雨量明细_定时任务_10分钟-1-20260806205722209','0','migration:0215','migration:0215'),
 (180828188917568,10,'SUCCESS','IMPORTED','2026-08-06 20:47:07','2026-08-06 20:47:20',13,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_监测库到ODS_雨量明细_定时任务_10分钟-1-20260806204708240','0','migration:0215','migration:0215'),
 (180734876660416,10,'SUCCESS','IMPORTED','2026-08-05 19:28:20','2026-08-05 19:29:34',74,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_监测库到ODS_雨量明细_定时任务_10分钟-1-20260805192822989','0','migration:0215','migration:0215'),
 (180640182706240,10,'SUCCESS','IMPORTED','2026-08-04 17:47:05','2026-08-04 17:48:27',82,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_监测库到ODS_雨量明细_定时任务_10分钟-1-20260804174708424','0','migration:0215','migration:0215'),
 (180828190953728,9,'SUCCESS','IMPORTED','2026-08-06 20:47:09','2026-08-06 20:47:19',10,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_雨量_清洗关联_全量_10分钟-4-20260806204710228','0','migration:0215','migration:0215'),
 (180734807399776,9,'SUCCESS','IMPORTED','2026-08-05 19:27:13','2026-08-05 19:27:44',31,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_雨量_清洗关联_全量_10分钟-4-20260805192715352','0','migration:0215','migration:0215'),
 (180640641835936,9,'SUCCESS','IMPORTED','2026-08-04 17:54:32','2026-08-04 17:55:22',50,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DWD_雨量_清洗关联_全量_10分钟-4-20260804175436793','0','migration:0215','migration:0215'),
 (180828225404608,6,'FAILURE','IMPORTED','2026-08-06 20:47:43','2026-08-06 20:47:53',10,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_河流维表_去重标准化_每日-3-20260806204743871','0','migration:0215','migration:0215'),
 (180640537941088,6,'SUCCESS','IMPORTED','2026-08-04 17:52:52','2026-08-04 17:54:04',72,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_河流维表_去重标准化_每日-2-20260804175255334','0','migration:0215','migration:0215'),
 (180829707866400,5,'FAILURE','IMPORTED','2026-08-06 21:11:51','2026-08-06 21:12:01',10,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_河段维表_去重标准化_每日-1-20260806211151588','0','migration:0215','migration:0215'),
 (180828227551104,5,'FAILURE','IMPORTED','2026-08-06 20:47:45','2026-08-06 20:47:58',13,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_河段维表_去重标准化_每日-1-20260806204745968','0','migration:0215','migration:0215'),
 (180640631146592,5,'SUCCESS','IMPORTED','2026-08-04 17:54:21','2026-08-04 17:55:55',94,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_河段维表_去重标准化_每日-1-20260804175426354','0','migration:0215','migration:0215'),
 (180829751976928,4,'FAILURE','IMPORTED','2026-08-06 21:12:34','2026-08-06 21:12:44',10,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_断面维表_去重标准化_每日-1-20260806211234665','0','migration:0215','migration:0215'),
 (180828281719584,4,'FAILURE','IMPORTED','2026-08-06 20:48:38','2026-08-06 20:48:46',8,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_断面维表_去重标准化_每日-1-20260806204838866','0','migration:0215','migration:0215'),
 (180640703308992,4,'SUCCESS','IMPORTED','2026-08-04 17:55:32','2026-08-04 17:56:11',39,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_断面维表_去重标准化_每日-1-20260804175536825','0','migration:0215','migration:0215'),
 (180828841586528,3,'SUCCESS','IMPORTED','2026-08-06 20:57:45','2026-08-06 20:57:54',9,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_水文站维表_去重标准化_每日-1-20260806205745611','0','migration:0215','migration:0215'),
 (180828231566624,3,'SUCCESS','IMPORTED','2026-08-06 20:47:48','2026-08-06 20:47:56',8,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_水文站维表_去重标准化_每日-1-20260806204749889','0','migration:0215','migration:0215'),
 (180734923026784,3,'SUCCESS','IMPORTED','2026-08-05 19:29:05','2026-08-05 19:29:35',30,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_水文站维表_去重标准化_每日-1-20260805192908268','0','migration:0215','migration:0215'),
 (180640747487296,3,'SUCCESS','IMPORTED','2026-08-04 17:56:16','2026-08-04 17:57:34',78,'吴同','源系统历史快照；不代表本平台存在运行进程','表输入组件','河道水情_ODS到DIM_水文站维表_去重标准化_每日-1-20260804175619968','0','migration:0215','migration:0215')
ON CONFLICT(id) DO NOTHING;
SELECT setval('dh_dataforge_integration_record_seq',greatest((SELECT last_value FROM dh_dataforge_integration_record_seq),coalesce((SELECT max(id) FROM dh_dataforge_integration_record),1)));

LOCK TABLE system_menu,system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE page_id bigint; new_id bigint; high_id bigint; entry record; action record;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=5987 AND path='integrate' AND deleted=0) THEN
  RAISE EXCEPTION '0215 数据接入父菜单缺失';
 END IF;
 FOR entry IN SELECT * FROM (VALUES
  ('离线开发','offline-development','dataforge/integration/offline/index','DataforgeOfflineDevelopment','carbon:flow-data',2),
  ('实时开发','realtime-development','dataforge/integration/realtime/index','DataforgeRealtimeDevelopment','carbon:flow-stream',3)
 ) pages(label,path,component,component_name,icon,sort) LOOP
  SELECT id INTO page_id FROM system_menu WHERE component=entry.component AND deleted=0 LIMIT 1;
  IF page_id IS NULL THEN
   SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO page_id FROM system_menu;
   INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
   VALUES(page_id,entry.label,'dataforge:integration:query',2,entry.sort,5987,entry.path,entry.icon,entry.component,entry.component_name,0,true,true,true,'migration:0215','migration:0215',0);
  END IF;
  FOR action IN SELECT * FROM (VALUES('create','新增和克隆',1),('update','修改及上下线调度',2),('delete','删除任务',3),('run','运行及停止',4)) permissions(code,label,sort) LOOP
   IF NOT EXISTS(SELECT 1 FROM system_menu WHERE parent_id=page_id AND permission='dataforge:integration:'||action.code AND deleted=0) THEN
    SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO new_id FROM system_menu;
    INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
    VALUES(new_id,action.label,'dataforge:integration:'||action.code,3,action.sort,page_id,'',0,true,true,true,'migration:0215','migration:0215',0);
   END IF;
  END LOOP;
 END LOOP;
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
 PERFORM setval('system_menu_seq',high_id); PERFORM setval('system_menu_id_seq',high_id);
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater,deleted)
 SELECT high_id+row_number() OVER(ORDER BY r.id,r.tenant_id,m.id),r.id,m.id,r.tenant_id,'migration:0215','migration:0215',0
 FROM system_role r CROSS JOIN system_menu m
 WHERE r.code='super_admin' AND r.deleted=0 AND m.deleted=0 AND
 (m.component IN ('dataforge/integration/offline/index','dataforge/integration/realtime/index') OR m.permission LIKE 'dataforge:integration:%')
 AND NOT EXISTS(SELECT 1 FROM system_role_menu old WHERE old.role_id=r.id AND old.tenant_id=r.tenant_id AND old.menu_id=m.id AND old.deleted=0);
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 PERFORM setval('system_role_menu_seq',high_id);
END $menus$;
COMMIT;
