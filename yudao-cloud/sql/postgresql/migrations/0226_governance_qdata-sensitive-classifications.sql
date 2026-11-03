-- Migration: 0226 qData 敏感清单分类定义迁入。
-- Purpose: 迁入实测的三十六个目录和五十六个分类，复用治理现有四级分级，不创建未经取得配置的脱敏执行规则。
-- Depends on: 当前单租户治理基线，已存在 category_data/safety_category/safety_level 三表及两个序列，L1 至 L4 各有唯一启用定义；不依赖并发任务的 0225。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；按来源目录编码和分类缩写核验身份，已有定义与人工编辑保持原样，来源身份冲突或已删除时失败。
-- Rollback: 单事务失败完整回滚；提交后须核验清单及治理引用再逐条删除迁入定义，禁止自动删除；两序列事务性前移不复用旧编号。
-- 来源：2026-10-22 经授权只读取得的分类树与分类详情；仅迁入定义文字、分级和状态，不迁入账号、联系方式、连接配置或规则执行参数。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
SELECT pg_advisory_xact_lock(1500, 32);
LOCK TABLE dh_governance_category_data, dh_governance_safety_category, dh_governance_safety_level IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE qdata_sensitive_directory_seed (
 source_id bigint PRIMARY KEY, parent_source_id bigint, source_code text, name text, sort integer, resolved_id bigint
) ON COMMIT DROP;
INSERT INTO qdata_sensitive_directory_seed (source_id,parent_source_id,source_code,name,sort) VALUES
(1,0,'A01','公开信息',0),
(10,1,'A01A01','水情公开',1),
(11,1,'A01A02','基础名录',2),
(12,1,'A01A03','政策标准',3),
(2,0,'A02','人员与组织',4),
(13,2,'A02A01','身份标识',5),
(14,2,'A02A02','任职组织',6),
(15,2,'A02A03','生物特征',7),
(3,0,'A03','联系与财务',8),
(16,3,'A03A01','联系通信',9),
(17,3,'A03A02','地址位置',10),
(18,3,'A03A03','金融结算',11),
(4,0,'A04','工程与运行',12),
(19,4,'A04A01','工程标识',13),
(20,4,'A04A02','安全运行',14),
(21,4,'A04A03','调度控制',15),
(5,0,'A05','监测与感知',16),
(22,5,'A05A01','测站设备',17),
(23,5,'A05A02','通信网络',18),
(24,5,'A05A03','视频图像',19),
(6,0,'A06','水资源与河湖',20),
(25,6,'A06A01','取用水监管',21),
(26,6,'A06A02','河湖生态',22),
(27,6,'A06A03','配置调度',23),
(7,0,'A07','防洪与应急',24),
(28,7,'A07A01','责任体系',25),
(29,7,'A07A02','应急资源',26),
(30,7,'A07A03','灾情处置',27),
(8,0,'A08','空间与档案',28),
(31,8,'A08A01','空间成果',29),
(32,8,'A08A02','规划档案',30),
(33,8,'A08A03','电子文件',31),
(9,0,'A09','系统与安全',32),
(34,9,'A09A01','身份认证',33),
(35,9,'A09A02','接口连接',34),
(36,9,'A09A03','日志审计',35);
CREATE TEMP TABLE qdata_sensitive_category_seed (
 source_id bigint PRIMARY KEY, directory_source_id bigint, short_name text, name text, level_name text,
 priority text, description text, status smallint, sort integer
) ON COMMIT DROP;
INSERT INTO qdata_sensitive_category_seed VALUES
(1,10,'PUB_RAIN_WATER','公开实时雨水情','L1','LOW','经审核可向社会公开的降雨、水位、流量和汛旱情摘要，不包含测站内部配置、精确敏感位置及责任人联系方式。',0,0),
(2,11,'PUB_RIVER_LAKE','公开河湖名录','L1','LOW','依法公开的河流、湖泊、水库等名称、类型、行政区划和基本概况，不包含敏感边界、问题线索及内部监管过程。',0,1),
(3,11,'PUB_PROJECT_PROFILE','公开水利工程概况','L1','LOW','依法公开的水库、水闸、泵站、堤防、渠道等工程名称、类型、建设状态和概况，不包含控制参数及安全隐患。',0,2),
(4,12,'PUB_POLICY_STANDARD','公开政策标准','L1','LOW','已正式发布的水利政策、法规、技术标准、办事指南和公开解读材料。',0,3),
(5,13,'PERSON_NAME','自然人姓名','L3','HIGH','水利业务办理、工程管理、防汛值守、执法检查和应急处置中能够识别自然人的姓名。',0,4),
(6,13,'ID_CARD','身份证号码','L4','HIGHEST','公民身份号码及与其等价的身份证件唯一号码，能够直接识别自然人身份。',0,5),
(7,13,'OTHER_CERT_NO','其他证件号码','L4','HIGHEST','护照、港澳台通行证、军官证等非居民身份证件号码。',0,6),
(8,13,'EMPLOYEE_NO','人员工号','L2','MEDIUM','水利单位工作人员、值班人员、巡查人员和工程管理人员的内部工号或人员编码。',0,7),
(9,13,'USER_ACCOUNT','用户账号','L3','HIGH','用于登录水利业务系统、监测平台和运维平台的用户名称或账号标识。',0,8),
(10,14,'ORG_POSITION','组织岗位信息','L2','MEDIUM','人员所属单位、部门、岗位、职责分工及内部组织隶属关系。',0,9),
(11,15,'PERSON_BIOMETRIC','人员照片与生物特征','L4','HIGHEST','人员照片、人脸、指纹、手写签名图像等具有身份识别能力的数据或文件地址。',0,10),
(12,16,'MOBILE_PHONE','手机号码','L3','HIGH','自然人用于业务联系、预警通知、值班调度和应急通讯的11位手机号码。',0,11),
(13,16,'FIXED_TELEPHONE','固定电话','L2','MEDIUM','单位、部门或个人使用的固定电话号码，包括带区号、分机号等形式。',0,12),
(14,16,'EMAIL_ADDRESS','电子邮箱','L3','HIGH','用于水利业务联系、系统通知和文件传递的个人或内部电子邮箱。',0,13),
(15,17,'CONTACT_ADDRESS','联系地址','L3','HIGH','能够定位自然人或联系人居住、通信、送达地点的详细地址信息。',0,14),
(16,16,'EMERGENCY_CONTACT','应急通讯信息','L4','HIGHEST','防汛值班、应急联系人和突发事件通知使用的手机号码等通讯信息。',0,15),
(17,18,'BANK_SETTLEMENT','银行账户与结算信息','L4','HIGHEST','银行账号、支付账号、结算凭证号及与个人或单位资金收付相关的信息。',0,16),
(18,19,'WATER_PROJECT_CODE','工程编码','L2','MEDIUM','水库、水闸、泵站、堤防、渠道等水利工程的统一编码或内部管理编码。',0,17),
(19,19,'PROJECT_EXACT_LOCATION','工程精确位置','L3','HIGH','水利工程具体地址、桩号、坐标描述和可定位关键设施的精确信息。',0,18),
(20,19,'PROJECT_KEY_PARAMETER','关键技术参数','L3','HIGH','反映水利工程结构能力、控制能力、设计边界和关键部位特征的技术参数。',0,19),
(21,20,'SAFETY_HAZARD','安全鉴定与隐患信息','L4','HIGHEST','工程安全鉴定结论、风险隐患、异常部位、整改措施和复核销号等未公开信息。',0,20),
(22,21,'PROJECT_DISPATCH_COMMAND','工程调度指令','L4','HIGHEST','水库、水闸、泵站、渠道等工程的调度命令、执行时段、目标值和反馈信息。',0,21),
(23,21,'GATE_PUMP_CONTROL','闸泵控制参数','L4','HIGHEST','闸门开度、泵组启停、远程控制口令、控制策略和设备执行参数。',0,22),
(24,22,'STATION_CODE','测站编码','L2','MEDIUM','水文站、雨量站、水位站、水质站等监测站点的统一编码或内部编码。',0,23),
(25,22,'DEVICE_IDENTIFIER','设备唯一标识','L2','MEDIUM','传感器、遥测终端、摄像机和通信设备的序列号、设备号或唯一标识。',0,24),
(26,22,'STATION_EXACT_LOCATION','测站精确位置','L3','HIGH','监测站点的详细地址、经纬度、安装位置和进入路线等精确信息。',0,25),
(27,23,'TELEMETRY_PARAMETER','遥测通信参数','L4','HIGHEST','遥测信道、中心站地址、通信卡号、协议参数、端口和设备接入配置。',0,26),
(28,24,'VIDEO_ACCESS','视频访问地址与图像','L4','HIGHEST','视频流地址、摄像机访问地址、鉴权参数、实时图像和抓拍文件路径。',0,27),
(29,23,'DEVICE_NETWORK_ADDRESS','设备网络地址','L3','HIGH','监测设备、网关、摄像机和服务器使用的IP地址、MAC地址及网络端口信息。',0,28),
(30,25,'WATER_USER_IDENTITY','取用水户身份','L3','HIGH','取用水单位或个人的名称、联系人、证件标识及其与取水口、许可事项的对应关系。',0,29),
(31,25,'UNIFIED_CREDIT_CODE','统一社会信用代码','L3','HIGH','取用水单位、工程管理单位和涉水企业的18位统一社会信用代码。',0,30),
(32,25,'WATER_PERMIT_NO','取水许可证号','L3','HIGH','取水许可决定、电子许可证和监管业务中使用的许可证唯一编号。',0,31),
(33,25,'WATER_METERING_DETAIL','取用水计量明细','L3','HIGH','具体取用水户、取水口、计量设施对应的逐时、逐日或逐月取用水量和计量记录。',0,32),
(34,27,'WATER_DISPATCH_PLAN','水资源调度方案','L4','HIGHEST','水资源配置、供水保障、生态补水和应急调水的未公开方案、指令及执行安排。',0,33),
(35,26,'RIVER_POLLUTION_CLUE','河湖问题与污染线索','L4','HIGHEST','河湖问题举报、暗访记录、水质异常、污染源定位、责任主体和未公开处置线索。',0,34),
(36,28,'FLOOD_RESPONSIBLE_CONTACT','防汛责任人通讯信息','L4','HIGHEST','各级防汛抗旱责任人、值班负责人和工程责任人的内部手机号码。',0,35),
(37,28,'RESCUE_TEAM_CONTACT','抢险队伍通讯信息','L4','HIGHEST','抢险队伍负责人、专家和应急联络人员的内部手机号码及通讯信息。',0,36),
(38,29,'WAREHOUSE_EXACT_LOCATION','物资仓库精确位置','L4','HIGHEST','防汛物资仓库、储备点和关键装备存放地点的详细地址、坐标和进入路线。',0,37),
(39,29,'SHELTER_EXACT_LOCATION','避险场所精确位置','L4','HIGHEST','避险安置场所内部位置、容量、入口、管理人员和应急启用条件等未公开信息。',0,38),
(40,30,'AFFECTED_TRANSFER_PERSON','受灾与转移人员信息','L4','HIGHEST','受灾、失联、伤亡、转移和安置人员的姓名、证件、联系方式、家庭及安置状态。',0,39),
(41,30,'EVACUATION_EMERGENCY_PLAN','转移路线与应急预案','L4','HIGHEST','人员转移路线、集结点、责任分工、抢险部署和未公开应急预案内容。',0,40),
(42,30,'WARNING_CONSULTATION','预警阈值与会商结论','L4','HIGHEST','尚未发布的预警阈值、风险研判、会商意见、响应建议和调度决策依据。',0,41),
(43,31,'PRECISE_COORDINATE','高精度空间坐标','L3','HIGH','能够精确定位水利工程、监测站点、应急设施和敏感对象的高精度经纬度或平面坐标。',0,42),
(44,31,'SENSITIVE_FACILITY_BOUNDARY','敏感设施范围边界','L4','HIGHEST','关键工程、保护区、物资仓库和其他敏感设施的高精度边界、WKT或空间文件。',0,43),
(45,32,'UNPUBLISHED_APPROVAL','未公开规划审批资料','L3','HIGH','处于编制、审查、征求意见或审批过程中的水利规划、项目材料和评审意见。',0,44),
(46,32,'CONTRACT_CONTENT','合同协议内容','L3','HIGH','水利建设、运维、采购、服务和协作过程中形成的合同、协议及其商务条款。',0,45),
(47,33,'ELECTRONIC_SIGNATURE','电子签章与签名','L4','HIGHEST','电子印章、个人签名、签章验证信息及其文件存储地址。',0,46),
(48,33,'FILE_DOWNLOAD_ADDRESS','附件路径与下载地址','L3','HIGH','内部文件存储路径、对象存储地址、下载链接、临时授权参数和共享地址。',0,47),
(49,34,'LOGIN_ACCOUNT','登录账号','L3','HIGH','登录水利业务系统、数据平台、监测平台和运维系统的用户账号。',0,48),
(50,34,'LOGIN_PASSWORD','登录口令','L4','HIGHEST','用户登录口令、初始密码、设备口令和其他用于身份认证的秘密信息。',0,49),
(51,35,'API_KEY_TOKEN','API密钥与访问令牌','L4','HIGHEST','API Key、Access Token、Refresh Token、Secret及其他接口调用鉴权凭证。',0,50),
(52,35,'DATABASE_CONNECTION','数据库连接信息','L4','HIGHEST','数据库地址、端口、库名、用户名、密码和JDBC连接字符串。',0,51),
(53,35,'CRYPTO_PRIVATE_KEY','加密密钥与证书私钥','L4','HIGHEST','数据加密密钥、签名密钥、证书私钥、密钥口令及密钥材料存储地址。',0,52),
(54,36,'IP_MAC_ADDRESS','IP与MAC地址','L3','HIGH','服务器、终端、监测设备和用户访问产生的IP地址、MAC地址及关联端口。',0,53),
(55,36,'AUDIT_LOG','审计日志','L3','HIGH','记录用户身份、操作对象、操作内容、访问来源和执行结果的审计记录。',0,54),
(56,36,'SYSTEM_OPERATION_LOG','系统运行日志','L3','HIGH','系统、服务、任务和设备运行产生的日志，可能包含路径、账号、网络地址和异常堆栈。',0,55);
DO $migration$
DECLARE
 item record;
 target_id bigint;
 parent_value bigint;
 level_value bigint;
 matches integer;
 relation_name text;
 sequence_name text;
 sequence_value bigint;
 sequence_called boolean;
 first_id numeric;
 sequence_max bigint;
 sequence_increment bigint;
 sequence_cycles boolean;
BEGIN
 -- 在真实表锁内对齐既有序列，保持下一值不倒退；事务失败时序列重启一并回滚。
 FOR relation_name, sequence_name IN SELECT * FROM (VALUES
   ('dh_governance_category_data','dh_foundation_category_data_seq'),
   ('dh_governance_safety_category','dh_safety_category_seq')) AS s(tab,seq) LOOP
   SELECT seqincrement,seqmax,seqcycle INTO sequence_increment,sequence_max,sequence_cycles
     FROM pg_sequence WHERE seqrelid=sequence_name::regclass;
   IF sequence_increment IS DISTINCT FROM 1 OR sequence_cycles IS DISTINCT FROM false THEN
     RAISE EXCEPTION '分类迁入要求序列 % 步长一且禁止循环',sequence_name;
   END IF;
   EXECUTE format('SELECT last_value,is_called FROM %I',sequence_name) INTO sequence_value,sequence_called;
   EXECUTE format('SELECT greatest(coalesce(max(id),0)::numeric+1,$1::numeric+$2) FROM %I',relation_name)
     INTO first_id USING sequence_value,CASE WHEN sequence_called THEN 1 ELSE 0 END;
   IF first_id+128>sequence_max THEN RAISE EXCEPTION '分类序列剩余空间不足'; END IF;
   EXECUTE format('ALTER SEQUENCE %I RESTART WITH %s',sequence_name,first_id);
 END LOOP;
 FOR item IN SELECT * FROM qdata_sensitive_directory_seed ORDER BY sort LOOP
   parent_value:=0;
   IF item.parent_source_id<>0 THEN
     SELECT resolved_id INTO parent_value FROM qdata_sensitive_directory_seed WHERE source_id=item.parent_source_id;
     IF parent_value IS NULL THEN RAISE EXCEPTION '敏感目录缺少父级'; END IF;
   END IF;
   SELECT count(*),min(id) INTO matches,target_id FROM dh_governance_category_data
     WHERE category_code='QDATA_SENSITIVE_'||item.source_code;
   IF matches>1 OR EXISTS(SELECT 1 FROM dh_governance_category_data WHERE id=target_id AND
     (deleted<>0 OR parent_id<>parent_value)) THEN RAISE EXCEPTION '敏感目录身份冲突：%',item.source_code; END IF;
   IF matches=0 THEN
     target_id:=nextval('dh_foundation_category_data_seq');
     INSERT INTO dh_governance_category_data(id,parent_id,category_name,category_code,sort,status,remark,creator,updater)
       VALUES(target_id,parent_value,item.name,'QDATA_SENSITIVE_'||item.source_code,item.sort,0,
         'qData 敏感分类目录来源号：'||item.source_id,'migration:0226','migration:0226');
   END IF;
   UPDATE qdata_sensitive_directory_seed SET resolved_id=target_id WHERE source_id=item.source_id;
 END LOOP;
 FOR item IN SELECT * FROM qdata_sensitive_category_seed ORDER BY sort LOOP
   SELECT resolved_id INTO parent_value FROM qdata_sensitive_directory_seed WHERE source_id=item.directory_source_id;
   SELECT count(*),min(id) INTO matches,level_value FROM dh_governance_safety_level
     WHERE safety_level=item.level_name AND status=0 AND deleted=0;
   IF matches<>1 OR parent_value IS NULL OR level_value>2147483647 THEN
     RAISE EXCEPTION '敏感分类缺少唯一可用分级或目录：%',item.source_id;
   END IF;
   SELECT count(*),min(id) INTO matches,target_id FROM dh_governance_safety_category
     WHERE category_id=parent_value AND short_name=item.short_name;
   IF matches>1 OR EXISTS(SELECT 1 FROM dh_governance_safety_category WHERE id=target_id AND deleted<>0) THEN
     RAISE EXCEPTION '敏感分类身份冲突：%',item.source_id;
   END IF;
   IF matches=0 THEN
     INSERT INTO dh_governance_safety_category(id,category_id,name,short_name,data_level_id,priority,rule_id,remark,
       status,sort,description,creator,updater)
     VALUES(nextval('dh_safety_category_seq'),parent_value,item.name,item.short_name,level_value::integer,item.priority,NULL,
       'qData 敏感分类来源号：'||item.source_id||'；源接口未提供可执行脱敏规则配置',
       item.status,item.sort,item.description,'migration:0226','migration:0226');
   END IF;
 END LOOP;
END;
$migration$;
COMMIT;
