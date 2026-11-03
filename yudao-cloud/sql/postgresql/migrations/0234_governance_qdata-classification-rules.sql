-- Migration: 0234 qData 数据分类与真实脱敏规则迁入。
-- Purpose: 复用三十六个目录和五十六个分类，补充来源事实、并发版本及四级敏感度，迁入五十二项真实掩码规则及不可变版本；精确软删除未维护且无引用的原始旧种子。
-- Depends on: 0226_governance_qdata-sensitive-classifications.sql 及当前单租户治理基线；已有 category_data/safety_category/safety_level/safety_rule/safety_rule_ver 和原名规则序列；不依赖本轮部门申请迁移的数据。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；按来源身份迁入一次，已迁入、人工维护或逻辑删除的定义、规则版本和关联不回填覆盖；同名但身份不明的本地规则失败并完整回滚，需核对后重试。
-- Rollback: 单事务失败完整回滚；提交后从执行前备份恢复受影响定义、规则及旧种子墓碑，先核验引用，不自动删除规则历史；新增字段和约束回滚前须停新版应用。
-- 来源：2026-10-23 经授权只读取得的三十六目录、五十六分类、四分级及五十二条完整规则详情；仅保存业务定义、公开显示姓名和时间，不迁入登录账号、凭据、联系方式或连接信息。
-- 风险：本事务短时锁定治理定义及现有相关引用表；旧种子须完整内容摘要、审计、未维护状态和无引用同时匹配才软删除，保留人工对象及其祖先；启用七条、禁用四十五条真实状态原样保留。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '90s';
SELECT pg_advisory_xact_lock(1900010000);
LOCK TABLE dh_governance_category_data, dh_governance_safety_category, dh_governance_safety_level,
 dh_governance_safety_rule, dh_governance_safety_rule_ver IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE dh_governance_category_data
 ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1,
 ADD COLUMN IF NOT EXISTS source_record_id varchar(64);
COMMENT ON COLUMN dh_governance_category_data.revision IS '目录本地并发版本，从一开始递增';
COMMENT ON COLUMN dh_governance_category_data.source_record_id IS 'qData 来源目录编号，只读追溯，包含逻辑删除墓碑';
ALTER TABLE dh_governance_safety_category
 ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1,
 ADD COLUMN IF NOT EXISTS source_record_id varchar(64),
 ADD COLUMN IF NOT EXISTS source_creator_name varchar(128),
 ADD COLUMN IF NOT EXISTS source_updater_name varchar(128),
 ADD COLUMN IF NOT EXISTS source_create_time timestamp,
 ADD COLUMN IF NOT EXISTS source_update_time timestamp;
COMMENT ON COLUMN dh_governance_safety_category.revision IS '分类本地并发版本，从一开始递增';
ALTER TABLE dh_governance_safety_rule
 ADD COLUMN IF NOT EXISTS source_record_id varchar(64),
 ADD COLUMN IF NOT EXISTS source_creator_name varchar(128),
 ADD COLUMN IF NOT EXISTS source_updater_name varchar(128),
 ADD COLUMN IF NOT EXISTS source_create_time timestamp,
 ADD COLUMN IF NOT EXISTS source_update_time timestamp;
ALTER TABLE dh_governance_safety_level ADD COLUMN IF NOT EXISTS sensitive_level integer;
COMMENT ON COLUMN dh_governance_safety_level.sensitive_level IS '敏感度，允许一至一百的整数；既有未配置分级可为空';
DO $structure$
DECLARE item text;
BEGIN
 FOR item IN SELECT unnest(ARRAY['dh_governance_safety_category','dh_governance_safety_rule']) LOOP
   EXECUTE format('COMMENT ON COLUMN %I.source_record_id IS %L',item,'qData 来源业务编号，只读追溯，不作为本地主键或授权依据，包含逻辑删除墓碑');
   EXECUTE format('COMMENT ON COLUMN %I.source_creator_name IS %L',item,'来源创建人公开显示姓名，不替代本地创建审计');
   EXECUTE format('COMMENT ON COLUMN %I.source_updater_name IS %L',item,'来源更新人公开显示姓名，本地维护后清空');
   EXECUTE format('COMMENT ON COLUMN %I.source_create_time IS %L',item,'来源创建时间，仅用于展示，不改写本地创建审计');
   EXECUTE format('COMMENT ON COLUMN %I.source_update_time IS %L',item,'来源更新时间，本地维护后清空，不改写本地审计');
 END LOOP;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_category_data'::regclass AND conname='ck_classification_directory_revision') THEN
   ALTER TABLE dh_governance_category_data ADD CONSTRAINT ck_classification_directory_revision CHECK(revision>0);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_category'::regclass AND conname='ck_safety_category_revision') THEN
   ALTER TABLE dh_governance_safety_category ADD CONSTRAINT ck_safety_category_revision CHECK(revision>0);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_level'::regclass AND conname='ck_safety_level_sensitivity') THEN
   ALTER TABLE dh_governance_safety_level ADD CONSTRAINT ck_safety_level_sensitivity CHECK(sensitive_level IS NULL OR sensitive_level BETWEEN 1 AND 100);
 END IF;
END;
$structure$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_classification_directory_source ON dh_governance_category_data(source_record_id) WHERE source_record_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_safety_category_source ON dh_governance_safety_category(source_record_id) WHERE source_record_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_safety_rule_source ON dh_governance_safety_rule(source_record_id) WHERE source_record_id IS NOT NULL;

CREATE TEMP TABLE qdata_classification_directory_seed(
 source_id bigint PRIMARY KEY, source_code text, resolved_id bigint
) ON COMMIT DROP;
INSERT INTO qdata_classification_directory_seed(source_id,source_code) VALUES
(1,'A01'),
(2,'A02'),
(3,'A03'),
(4,'A04'),
(5,'A05'),
(6,'A06'),
(7,'A07'),
(8,'A08'),
(9,'A09'),
(10,'A01A01'),
(11,'A01A02'),
(12,'A01A03'),
(13,'A02A01'),
(14,'A02A02'),
(15,'A02A03'),
(16,'A03A01'),
(17,'A03A02'),
(18,'A03A03'),
(19,'A04A01'),
(20,'A04A02'),
(21,'A04A03'),
(22,'A05A01'),
(23,'A05A02'),
(24,'A05A03'),
(25,'A06A01'),
(26,'A06A02'),
(27,'A06A03'),
(28,'A07A01'),
(29,'A07A02'),
(30,'A07A03'),
(31,'A08A01'),
(32,'A08A02'),
(33,'A08A03'),
(34,'A09A01'),
(35,'A09A02'),
(36,'A09A03');
CREATE TEMP TABLE qdata_classification_seed(
 source_id bigint PRIMARY KEY, directory_source_id bigint, short_name text,
 creator_name text, updater_name text, source_created timestamp, source_updated timestamp,
 resolved_id bigint, newly_claimed boolean NOT NULL DEFAULT false, allow_initial_rule boolean NOT NULL DEFAULT false
) ON COMMIT DROP;
INSERT INTO qdata_classification_seed(source_id,directory_source_id,short_name,creator_name,updater_name,source_created,source_updated) VALUES
(56,36,'SYSTEM_OPERATION_LOG','吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09'),
(55,36,'AUDIT_LOG','吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09'),
(54,36,'IP_MAC_ADDRESS','吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09'),
(53,35,'CRYPTO_PRIVATE_KEY','吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09'),
(52,35,'DATABASE_CONNECTION','吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09'),
(51,35,'API_KEY_TOKEN','吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09'),
(50,34,'LOGIN_PASSWORD','吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09'),
(49,34,'LOGIN_ACCOUNT','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(48,33,'FILE_DOWNLOAD_ADDRESS','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(47,33,'ELECTRONIC_SIGNATURE','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(46,32,'CONTRACT_CONTENT','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(45,32,'UNPUBLISHED_APPROVAL','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(44,31,'SENSITIVE_FACILITY_BOUNDARY','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(43,31,'PRECISE_COORDINATE','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(42,30,'WARNING_CONSULTATION','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(41,30,'EVACUATION_EMERGENCY_PLAN','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(40,30,'AFFECTED_TRANSFER_PERSON','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(39,29,'SHELTER_EXACT_LOCATION','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(38,29,'WAREHOUSE_EXACT_LOCATION','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(37,28,'RESCUE_TEAM_CONTACT','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(36,28,'FLOOD_RESPONSIBLE_CONTACT','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(35,26,'RIVER_POLLUTION_CLUE','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(34,27,'WATER_DISPATCH_PLAN','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(33,25,'WATER_METERING_DETAIL','吴同','吴同','2026-07-29 19:35:08','2026-07-29 19:35:08'),
(32,25,'WATER_PERMIT_NO','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(31,25,'UNIFIED_CREDIT_CODE','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(30,25,'WATER_USER_IDENTITY','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(29,23,'DEVICE_NETWORK_ADDRESS','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(28,24,'VIDEO_ACCESS','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(27,23,'TELEMETRY_PARAMETER','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(26,22,'STATION_EXACT_LOCATION','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(25,22,'DEVICE_IDENTIFIER','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(24,22,'STATION_CODE','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(23,21,'GATE_PUMP_CONTROL','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(22,21,'PROJECT_DISPATCH_COMMAND','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(21,20,'SAFETY_HAZARD','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(20,19,'PROJECT_KEY_PARAMETER','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(19,19,'PROJECT_EXACT_LOCATION','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(18,19,'WATER_PROJECT_CODE','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(17,18,'BANK_SETTLEMENT','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(16,16,'EMERGENCY_CONTACT','吴同','吴同','2026-07-29 19:35:07','2026-07-29 19:35:07'),
(15,17,'CONTACT_ADDRESS','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(14,16,'EMAIL_ADDRESS','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(13,16,'FIXED_TELEPHONE','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(12,16,'MOBILE_PHONE','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(11,15,'PERSON_BIOMETRIC','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(10,14,'ORG_POSITION','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(9,13,'USER_ACCOUNT','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(8,13,'EMPLOYEE_NO','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(7,13,'OTHER_CERT_NO','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(6,13,'ID_CARD','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(5,13,'PERSON_NAME','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(4,12,'PUB_POLICY_STANDARD','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(3,11,'PUB_PROJECT_PROFILE','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(2,11,'PUB_RIVER_LAKE','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06'),
(1,10,'PUB_RAIN_WATER','吴同','吴同','2026-07-29 19:35:06','2026-07-29 19:35:06');
CREATE TEMP TABLE qdata_mask_rule_seed(
 source_id bigint PRIMARY KEY, category_source_id bigint UNIQUE, rule_name text, description text,
 status smallint, sort integer, creator_name text, updater_name text, source_created timestamp, source_updated timestamp,
 config jsonb, resolved_id bigint, newly_created boolean NOT NULL DEFAULT false
) ON COMMIT DROP;
INSERT INTO qdata_mask_rule_seed(source_id,category_source_id,rule_name,description,status,sort,creator_name,updater_name,source_created,source_updated,config) VALUES
(1,5,'自然人姓名展示脱敏规则','适用于长度不少于2个字符的规范姓名，保留首字符并遮蔽第二字符。 该规则已启用。',0,1,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":2,"end":2}],"maskChar":"*","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(2,6,'身份证号码展示脱敏规则','适用于18位居民身份证号码，保留前6位和后4位。 该规则已启用。',0,2,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":7,"end":14}],"maskChar":"********","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(3,7,'其他证件号码展示脱敏规则','证件类型和长度不固定，默认禁用；启用前应按具体证件格式调整脱敏区间。 该规则保留为配置模板，当前处于禁用状态。',1,3,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(4,8,'人员工号展示脱敏规则','工号编码规则不统一，默认禁用；启用前应依据单位编码长度设置保留位。 该规则保留为配置模板，当前处于禁用状态。',1,4,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(5,9,'用户账号展示脱敏规则','账号长度不固定，默认禁用；启用前应确定最小长度和需要保留的识别位。 该规则保留为配置模板，当前处于禁用状态。',1,5,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(6,10,'组织岗位信息展示脱敏规则','组织岗位为变长文本，当前固定区间算法不能安全全文脱敏，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,6,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(7,11,'人员照片与生物特征展示脱敏规则','照片及生物特征应通过访问控制或文件替换保护，当前字符区间算法不适合直接启用。 该规则保留为配置模板，当前处于禁用状态。',1,7,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(8,12,'手机号码展示脱敏规则','适用于11位手机号码，保留前3位和后4位。 该规则已启用。',0,8,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":4,"end":7}],"maskChar":"****","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(9,13,'固定电话展示脱敏规则','固定电话格式和长度不统一，默认禁用；启用前应按区号和分机格式设置区间。 该规则保留为配置模板，当前处于禁用状态。',1,9,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(10,14,'电子邮箱展示脱敏规则','邮箱用户名长度不固定，当前算法不能动态保留域名，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,10,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(11,15,'联系地址展示脱敏规则','详细地址为变长文本，当前固定区间算法不能安全遮蔽剩余全部字符，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,11,'吴同','吴同','2026-07-29 19:35:09','2026-07-29 19:35:09','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(12,16,'应急通讯信息展示脱敏规则','仅适用于已校验为11位手机号码的应急通讯字段，保留前3位和后4位。 该规则已启用。',0,12,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":4,"end":7}],"maskChar":"****","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(13,17,'银行账户与结算信息展示脱敏规则','银行卡和结算账号长度不统一，默认禁用；启用前应按具体账号规则配置。 该规则保留为配置模板，当前处于禁用状态。',1,13,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(14,18,'工程编码展示脱敏规则','工程编码规则存在地区和系统差异，默认禁用；启用前应明确保留的业务识别段。 该规则保留为配置模板，当前处于禁用状态。',1,14,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(15,19,'工程精确位置展示脱敏规则','位置描述格式不固定，默认禁用；应优先采用坐标降精度或访问控制。 该规则保留为配置模板，当前处于禁用状态。',1,15,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(16,20,'关键技术参数展示脱敏规则','参数类型包含数值和变长文本，当前脱敏实现仅适用于字符串，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,16,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(17,21,'安全鉴定与隐患信息展示脱敏规则','安全鉴定和隐患内容为变长文本，默认禁用；应结合权限控制或全文替换能力保护。 该规则保留为配置模板，当前处于禁用状态。',1,17,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(18,22,'工程调度指令展示脱敏规则','调度指令为变长文本并可能包含数值，当前固定区间算法不适合直接启用。 该规则保留为配置模板，当前处于禁用状态。',1,18,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(19,23,'闸泵控制参数展示脱敏规则','控制参数类型复杂，需先区分字符串和数值字段并设置专用规则，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,19,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(20,24,'测站编码展示脱敏规则','测站编码规则不统一，默认禁用；启用前应确定保留的流域和站类识别段。 该规则保留为配置模板，当前处于禁用状态。',1,20,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(21,25,'设备唯一标识展示脱敏规则','设备标识长度和编码方式不统一，默认禁用；启用前应按设备类型设置。 该规则保留为配置模板，当前处于禁用状态。',1,21,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(22,26,'测站精确位置展示脱敏规则','测站位置应采用坐标降精度或访问控制，当前固定字符区间规则默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,22,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(23,27,'遥测通信参数展示脱敏规则','遥测参数格式多样且可能包含凭证，默认禁用；应拆分字段后配置专用规则。 该规则保留为配置模板，当前处于禁用状态。',1,23,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(24,28,'视频访问地址与图像展示脱敏规则','视频地址和图像应进行权限控制或整体替换，当前固定区间算法默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,24,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(25,29,'设备网络地址展示脱敏规则','IP与MAC格式不同，合并分类无法使用单一区间规则，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,25,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(26,30,'取用水户身份展示脱敏规则','该分类包含多种字段格式，应拆分到姓名、证件或信用代码规则后再启用。 该规则保留为配置模板，当前处于禁用状态。',1,26,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(27,31,'统一社会信用代码展示脱敏规则','适用于18位统一社会信用代码，保留前6位和后4位。 该规则已启用。',0,27,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":7,"end":14}],"maskChar":"********","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(28,32,'取水许可证号展示脱敏规则','许可证编号规则存在地区和年代差异，默认禁用；启用前应确认编号格式。 该规则保留为配置模板，当前处于禁用状态。',1,28,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(29,33,'取用水计量明细展示脱敏规则','计量值通常为数值类型，当前脱敏实现直接按字符串处理存在风险，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,29,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(30,34,'水资源调度方案展示脱敏规则','调度方案为变长文本，默认禁用；应采用文件权限、全文替换或专用访问策略。 该规则保留为配置模板，当前处于禁用状态。',1,30,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(31,35,'河湖问题与污染线索展示脱敏规则','问题线索通常为变长文本并含多类个人信息，默认禁用；应结合访问控制保护。 该规则保留为配置模板，当前处于禁用状态。',1,31,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(32,36,'防汛责任人通讯信息展示脱敏规则','仅适用于已校验为11位手机号码的责任人通讯字段，保留前3位和后4位。 该规则已启用。',0,32,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":4,"end":7}],"maskChar":"****","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(33,37,'抢险队伍通讯信息展示脱敏规则','仅适用于已校验为11位手机号码的抢险队伍通讯字段，保留前3位和后4位。 该规则已启用。',0,33,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":4,"end":7}],"maskChar":"****","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(34,38,'物资仓库精确位置展示脱敏规则','仓库位置格式不固定，默认禁用；应采用坐标降精度和授权访问方式保护。 该规则保留为配置模板，当前处于禁用状态。',1,34,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(35,39,'避险场所精确位置展示脱敏规则','场所位置和说明为变长文本，当前固定区间算法默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,35,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(36,40,'受灾与转移人员信息展示脱敏规则','该分类包含多种个人信息，应按具体字段分别关联姓名、证件和手机号规则，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,36,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(37,41,'转移路线与应急预案展示脱敏规则','预案和路线为变长文本或空间数据，当前固定区间算法默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,37,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(38,42,'预警阈值与会商结论展示脱敏规则','会商结论为变长文本且阈值可能为数值，当前脱敏实现不适合直接启用。 该规则保留为配置模板，当前处于禁用状态。',1,38,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(39,43,'高精度空间坐标展示脱敏规则','坐标应使用数值降精度算法，当前字符区间算法无法稳定处理不同坐标格式，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,39,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(40,44,'敏感设施范围边界展示脱敏规则','边界数据应采用空间泛化或访问控制，当前字符区间算法默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,40,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(41,45,'未公开规划审批资料展示脱敏规则','规划审批资料为文件或长文本，默认禁用；应通过文件权限和水印审计保护。 该规则保留为配置模板，当前处于禁用状态。',1,41,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(42,46,'合同协议内容展示脱敏规则','合同协议为长文本，当前固定区间算法不能完整遮蔽，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,42,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(43,47,'电子签章与签名展示脱敏规则','电子签章应通过文件替换和访问控制保护，当前字符区间算法默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,43,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(44,48,'附件路径与下载地址展示脱敏规则','路径和URL长度不固定，当前算法不能动态隐藏查询参数和剩余路径，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,44,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(45,49,'登录账号展示脱敏规则','登录账号长度不固定，默认禁用；启用前应设置最小长度及保留位。 该规则保留为配置模板，当前处于禁用状态。',1,45,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(46,50,'登录口令展示脱敏规则','口令必须全文隐藏；当前固定区间算法不能安全处理任意长度口令，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,46,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(47,51,'API密钥与访问令牌展示脱敏规则','密钥和令牌必须全文隐藏且长度不固定，当前固定区间算法默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,47,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(48,52,'数据库连接信息展示脱敏规则','连接串包含多段敏感内容，当前固定区间算法无法按结构脱敏，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,48,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(49,53,'加密密钥与证书私钥展示脱敏规则','密钥材料必须全文隐藏并禁止普通查询，当前固定区间算法默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,49,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(50,54,'IP与MAC地址展示脱敏规则','IPv4、IPv6和MAC格式不同，当前单一区间规则不能统一处理，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,50,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(51,55,'审计日志展示脱敏规则','审计日志为结构化或长文本，需按字段分别脱敏并保留审计价值，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,51,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}'),
(52,56,'系统运行日志展示脱敏规则','运行日志为变长文本，需使用日志专用清洗与密钥识别能力，默认禁用。 该规则保留为配置模板，当前处于禁用状态。',1,52,'吴同','吴同','2026-07-29 19:35:10','2026-07-29 19:35:10','{"intervals":[{"start":1,"end":1}],"maskChar":"******","applicationScene":["1","2"],"maskType":"2","replaceRule":"1"}');
DO $identity$
DECLARE item record; target_id bigint; parent_value bigint; matches integer; original record;
BEGIN
 -- 首次补来源编号仅认领旧迁移创建且带原来源号备注的行，不按同名或远端数字主键猜测。
 FOR item IN SELECT * FROM qdata_classification_directory_seed ORDER BY source_id LOOP
   SELECT count(*),min(id) INTO matches,target_id FROM dh_governance_category_data WHERE source_record_id=item.source_id::text;
   IF matches=0 THEN
     SELECT count(*),min(id) INTO matches,target_id FROM dh_governance_category_data
       WHERE source_record_id IS NULL AND creator='migration:0226'
         AND remark='qData 敏感分类目录来源号：'||item.source_id;
     IF matches<>1 THEN RAISE EXCEPTION '目录来源身份缺失或冲突：%，请核验既有0226定义，禁止重造主键',item.source_id; END IF;
     UPDATE dh_governance_category_data SET source_record_id=item.source_id::text WHERE id=target_id;
   END IF;
   UPDATE qdata_classification_directory_seed SET resolved_id=target_id WHERE source_id=item.source_id;
 END LOOP;
 FOR item IN SELECT * FROM qdata_classification_seed ORDER BY source_id LOOP
   SELECT count(*),min(id) INTO matches,target_id FROM dh_governance_safety_category WHERE source_record_id=item.source_id::text;
   IF matches=0 THEN
     SELECT count(*),min(id) INTO matches,target_id FROM dh_governance_safety_category
       WHERE source_record_id IS NULL AND creator='migration:0226'
         AND split_part(remark,'；',1)='qData 敏感分类来源号：'||item.source_id;
     IF matches<>1 THEN RAISE EXCEPTION '分类来源身份缺失或冲突：%，请核验既有0226定义，禁止同名认领',item.source_id; END IF;
     SELECT * INTO original FROM dh_governance_safety_category WHERE id=target_id;
     UPDATE dh_governance_safety_category SET source_record_id=item.source_id::text,
       source_creator_name=item.creator_name,source_create_time=item.source_created,
       source_updater_name=CASE WHEN updater='migration:0226' AND revision=1 THEN item.updater_name ELSE NULL END,
       source_update_time=CASE WHEN updater='migration:0226' AND revision=1 THEN item.source_updated ELSE NULL END
       WHERE id=target_id;
     UPDATE qdata_classification_seed SET newly_claimed=true,
       allow_initial_rule=(original.updater='migration:0226' AND original.revision=1 AND original.deleted=0 AND original.rule_id IS NULL)
       WHERE source_id=item.source_id;
   END IF;
   UPDATE qdata_classification_seed SET resolved_id=target_id WHERE source_id=item.source_id;
 END LOOP;
END;
$identity$;

-- 四个等级仅补尚未配置的真实敏感度，保留其本地主键、名称、颜色及审计。
UPDATE dh_governance_safety_level SET sensitive_level=10 WHERE safety_level='L1' AND sensitive_level IS NULL AND deleted=0;
UPDATE dh_governance_safety_level SET sensitive_level=40 WHERE safety_level='L2' AND sensitive_level IS NULL AND deleted=0;
UPDATE dh_governance_safety_level SET sensitive_level=70 WHERE safety_level='L3' AND sensitive_level IS NULL AND deleted=0;
UPDATE dh_governance_safety_level SET sensitive_level=100 WHERE safety_level='L4' AND sensitive_level IS NULL AND deleted=0;

DO $rules$
DECLARE item record; relation_name text; sequence_name text; target_id bigint; version_id bigint;
 sequence_value bigint; sequence_called boolean; first_id numeric; sequence_max bigint; sequence_increment bigint; sequence_cycles boolean;
BEGIN
 -- 两个原名序列事务性前移，避免已有人工规则导致主键冲突；重放不倒退、不修改任何旧版本。
 FOR relation_name,sequence_name IN SELECT * FROM (VALUES
   ('dh_governance_safety_rule','dh_safety_rule_seq'),('dh_governance_safety_rule_ver','dh_safety_rule_ver_seq')) s(tab,seq) LOOP
   SELECT seqincrement,seqmax,seqcycle INTO sequence_increment,sequence_max,sequence_cycles FROM pg_sequence WHERE seqrelid=sequence_name::regclass;
   IF sequence_increment IS DISTINCT FROM 1 OR sequence_cycles IS DISTINCT FROM false THEN RAISE EXCEPTION '规则序列 % 必须步长一且不循环',sequence_name; END IF;
   EXECUTE format('SELECT last_value,is_called FROM %I',sequence_name) INTO sequence_value,sequence_called;
   EXECUTE format('SELECT greatest(coalesce(max(id),0)::numeric+1,$1::numeric+$2) FROM %I',relation_name)
     INTO first_id USING sequence_value,CASE WHEN sequence_called THEN 1 ELSE 0 END;
   IF first_id+52>sequence_max THEN RAISE EXCEPTION '规则序列空间不足'; END IF;
   EXECUTE format('ALTER SEQUENCE %I RESTART WITH %s',sequence_name,first_id);
 END LOOP;
 FOR item IN SELECT * FROM qdata_mask_rule_seed ORDER BY source_id LOOP
   SELECT id INTO target_id FROM dh_governance_safety_rule WHERE source_record_id=item.source_id::text;
   IF target_id IS NULL THEN
     IF EXISTS(SELECT 1 FROM dh_governance_safety_rule WHERE btrim(rule_name)=btrim(item.rule_name)) THEN
       RAISE EXCEPTION '来源规则%与本地同名规则身份不明，需人工核对，禁止认领本地作者或参数',item.source_id;
     END IF;
     target_id:=nextval('dh_safety_rule_seq');
     version_id:=nextval('dh_safety_rule_ver_seq');
     INSERT INTO dh_governance_safety_rule(id,rule_name,method_code,source_type,status,sort,description,
       source_record_id,source_creator_name,source_updater_name,source_create_time,source_update_time,creator,updater)
       VALUES(target_id,item.rule_name,'MASK','CUSTOM',item.status,item.sort,item.description,
         item.source_id::text,item.creator_name,item.updater_name,item.source_created,item.source_updated,'migration:0234','migration:0234');
     INSERT INTO dh_governance_safety_rule_ver(id,rule_id,version_no,schema_version,config_json,config_checksum,status,
       published_by,published_time,creator,updater)
       VALUES(version_id,target_id,1,1,item.config,encode(sha256(convert_to(item.config::text,'UTF8')),'hex'),'PUBLISHED',
         'migration:0234',CURRENT_TIMESTAMP,'migration:0234','migration:0234');
     UPDATE dh_governance_safety_rule SET current_ver_id=version_id WHERE id=target_id;
     UPDATE qdata_mask_rule_seed SET newly_created=true WHERE source_id=item.source_id;
   END IF;
   UPDATE qdata_mask_rule_seed SET resolved_id=target_id WHERE source_id=item.source_id;
 END LOOP;
 -- 仅首次认领且仍未维护的分类补规则绑定；重放不覆盖人为解除绑定、改配或逻辑删除。
 UPDATE dh_governance_safety_category c SET rule_id=r.resolved_id
   FROM qdata_classification_seed s JOIN qdata_mask_rule_seed r ON r.category_source_id=s.source_id
   WHERE c.id=s.resolved_id AND s.newly_claimed AND s.allow_initial_rule AND c.rule_id IS NULL;
END;
$rules$;

-- 原始旧种子摘要来自0233新库基线，包含全部原字段和微秒审计；不会按编号范围直接删除。
CREATE TEMP TABLE qdata_old_classification_seed(id bigint PRIMARY KEY, expected_hash text) ON COMMIT DROP;
INSERT INTO qdata_old_classification_seed VALUES
(1,'9c6cd24c6b8edcf237bebfff7e9b6fb7eb3c99c85d6dec8da4e09ed909872e3e');
CREATE TEMP TABLE qdata_old_directory_seed(id bigint PRIMARY KEY, expected_hash text) ON COMMIT DROP;
INSERT INTO qdata_old_directory_seed VALUES
(98,'e3fe534da566e05e3265517ac7175e7bba03f5b8f8be99f4adbebcb968cb7cd5'),
(99,'6eeab72cbe6b8f31b1a9b8f278982241d4d150299ffef9828b761e1cd99d2957'),
(100,'acd9442fc1461cb18f47b4b325faa886d8c644bcfc8548fb131bd138754557fb'),
(101,'18e0e9f140049abdb23cfc5d5fcfabac2e68b3e2246a20facb8a251968583555'),
(102,'0e0769d8854e5b53ce91e4eaf58afb8f6c801f0597713fa23a74a1b397675850'),
(103,'679503b97f7615f0042ad9a3f6f95dbca40e1d99499768d150232c294c272f9d'),
(104,'96cf0ec312364d215ca0efebca70043a5408da39a0d420bd929029e5a7e11359'),
(105,'a0e269f646068eed13aaad915c471e9e87e5b27cd349c098f8e46af231830771'),
(106,'e18d0afac45d3064f2b3aed6844333c4b393c004646fa03386e3a8b69846f12b'),
(107,'eb0613b72423fc7a89d03f42e2abf92c7cf3ca2a2e3142c586f91c983c4af3ad'),
(108,'1f990041c06fb160adc6cfda80c19a850980a91abc08cbd69ce6009d2067be8c'),
(109,'69be61997bbc6e325187daad965b1dd7780e2523129684c36b5adf1e0c5aa15f'),
(110,'202356738486cacfa8715f9506a607834777ae7638824fa8ed7886da5480e0c7'),
(111,'7d6351c8bde80237c152554f28eb19bcb71e03086fa2e35f116f1a319f7bd6ae'),
(112,'ee2f07f502b20ffe3880d39cd413d146f6f97df2a9078236d9a66a0b1e463044'),
(113,'71db69302f7573693bcaae68346108e463a71c74fb352d9883b5d64b5ca8d45a'),
(114,'9bc2e0229084c3ed6d730740132d480fb62fabfea3a7184098dc99df558769dd'),
(115,'6ea26ca58ae93472aeaa74322c4ebe88d4649127a5af384a2b86af0e24ef10d2'),
(116,'4782acfd65eed3bbde8c34acb737105baa925818599b00a24fd89f2af37d1030'),
(117,'383b6ef3987b53ae7e81061e9efa5a96be9af5d70baad43977411283e921dea3'),
(118,'baebedf5d008410db3daea0a91f01d4fe5b486ae30911f39f0ba3ef779fa4651'),
(119,'aae741aa73aa7d952d8264b7131e1b5955faf87fc12e36edd9efdc1471a89a37'),
(120,'333ff0053b02e72eecfe60a5f55d01f3efb52ca445924f4b95a67e6588fe972e'),
(121,'b375b676f91666d98a6ad061ab8e9da992dfecd3b882802ffe840ac78204c8ff'),
(122,'c2a2a0ab3d8117ea35508a96449f89de2c0e33fb5f3db2d2cb3f16802fd52bd7'),
(123,'3be964b36cdf12acb9000ca498efce75b9f62a0d78379b8b3f0b8be81f374010'),
(124,'fe83538f112db8a29e1904989f1f8e5ac4e2bb454fc7e054c70dfd06133fe367'),
(125,'0e53a48c606f1c0b24af5fb1fa8362a94b1dd833c4e451547e2c426ea4675f08'),
(126,'fdd00752895d1517bf4c3ee8dbab1edb748d783556108d860dc0ceab7d4fe0f7'),
(127,'39f55895a7f384b8fa793bba9f735243ec2d137f36987bffd13ec8851314be42'),
(128,'60e97cf65c63af36d55aa3b6e039fb0d6de2c479943da646375066f0719b5581'),
(129,'0613c010db49db31d88000d39bbceb366fa4de2eaaccdce6ceb1c49450c95da4'),
(130,'55da2544d19fe0e9c2905a1cf293370ba4a3022e23b7e28fa0312de2c413544a'),
(131,'838a9c4a106bcc0d65568011cb7ac53b82447b5c2febe42eb3f2fe53131573a2'),
(132,'deea5e5097902e225973d3084129d387fdd328ec555eaee5ba19c89fb56bfb30'),
(133,'4525ad434b97534a98af13ca5fc1f5e40bd69775fce94d5db6f0a2588076458d'),
(134,'b379558001f7436cc6fd343bdba2add5305b4d9b3b3ddea8762b775d3b22c1d3'),
(135,'fbaa1c5fade4f132aebab4f193c2929cd379f5e09c8f596297a3a0ee896da16d'),
(136,'feec0b49f3d9ae95e7addd38ab5cdbde032d022d4e0d7389041f17c1171a0a98'),
(137,'f5bb38d50427a2d2d72525f07cea14fe17da80dd46b5fe188eef9ee1c4403515'),
(138,'8ab9b6026a503e83164a4e9623f963b3974124a65549f41da8db8091fc0e4baa'),
(139,'5bf2db7a3082d7b86aaf47d9bdb92b717b2fa3aaecced8a466f1d37add698802'),
(140,'8e2b886986f10bfcd97c358d0aed73048ad6fecc60ab6a4c0cca3e53260ac8ed'),
(141,'e9f5b008b1f7efbcf26c1ce9907e3b6e65ba0f89591b1707fb6fba0422abed00'),
(142,'f53f5aabc8ba67d63d973c43bee77f3555b5677cd58c15e9600085798cf09a53'),
(143,'6c183e8cd7139bc2811a32fdd5f8fc8add972bccd2bb46ced26eba11bed692b4'),
(144,'88e0e73baa0f828cbeaa9ba63f07317913cfa03e5f4bb8d61683ab4e07c180df'),
(145,'561fa9cdc66731a36ebf84c320052a2b1c52dda6637f9ce29a6d815c2b833d10'),
(146,'c7a5b84e6dd3aad0ab6d43fffe71e2b10681367fd4b404438d9718dcacff7def'),
(147,'320cace36a15f85a6655921ee4ac53cffa5cd51498496709ea2f6a6b7b0597d4'),
(148,'e76270c92d70010d170f1690a825e3c83a7216f3096dd98b589bbb495005be0a'),
(149,'79874d083c9aa01904faa83cdd5f9bf3fc7ccb8a6b657a1fa655d80e6d2248c9'),
(150,'65a14990ed7560c7721647abba4400f091afe46addf281c5746fc582d8a7cfee'),
(151,'1c0b1e74ef8693762d9643de6d2268bb0aff155ed272e17503304a14b317f922'),
(152,'c96d7114c6e92e5ab02e38cf77607c6bc46b84fce31db3c371de1c10169cac7d'),
(153,'5b5ea72d1e869b047ae286d17fa10c98c161039dadf42b1fa11edfdb30a8f97c'),
(154,'6eb7e619655a3cc57600b6a60bec88d4f8d4845433c2b24b914af60dd4a96edf'),
(155,'d17464c2c45394a3e9258b515409f53e9c0b2b846fc4aa85262f2c754acbf33d'),
(156,'a37f1ee88f49cf8b7f41c131a6ea9f835a0c46df26ee2e83689025208ec744cf'),
(157,'31894228abbf806d4a719ed63cdc8fcfd6afbe7f59a85ee0edb0cde9f11c9f74'),
(158,'948feb038200c4efe67ea71ddbec347cb75127cd1c51f21828c2cd00f1a00af2'),
(159,'8563cda6a3d4d13a64d762b2cc4196974ee0996a6384c705400637d07a5e6385'),
(160,'4dc242cc5ae7e3b06d89e101754e515411d2050ef7559a939547f33a65e3b394'),
(161,'edea59de2cbb490e4498ab1ea9b96cb54b75acec4f668f8add7b46d5e50e56f0'),
(162,'a20fbf111b79c095dbf47302652742cd18c6d281b06fa5e32981ba0f93d0db78'),
(163,'abfaa1816f48290a33dfba9351f06163d6c98fca8964c8bbe517bfba2ec765b0'),
(164,'8818713ba0955c0930991601233184586b35aa6c99fda572157ca22e0e170f71'),
(165,'513054eb623b9693681ce7613673111632b8d56ab4b817fb945eba9d367a5733'),
(166,'1b84733a2b8cb093d04abedd04f4013bdefed2291e1bfa3adacae7a6dd4be2c1'),
(167,'52605d8ca84638fd2fa3c41a09f073f7c4aa2b17a696e4324e611a197e223b71'),
(168,'d3aa3914744ed36134dc16083c425d2be98ae7041ab42b2dac50464d073e8375'),
(169,'f4125a28328574c548ce5ca92eab6c9f98262d6754bff9db8510941376e140b5'),
(170,'5d145c9a758a6240d22b231171c5a7d7089b7d9155acde5c327373524a42473c'),
(171,'ad5e9c7c87665afd43501a559a01043be8f4d6eeb635da84ef30ae21923fd447'),
(172,'732f04d415f9bf3a4d566995c62746baf916f8cd2a874105fe1a735a2e811201'),
(173,'0647b2a7516f7bbb6fe7763bb70c4ea589172f535cbd1f18dbc733af8d1bc993'),
(174,'d6cbf3d21a159d4b9dfc4f3aec53f1640264c43e75b2a7c31f397770dd45fd92'),
(175,'ae0717235433ea80d084d649d82bb72ca3d475075061f1b62e92a92852f14960'),
(176,'6fbb419aec24411adc11c11bd0d970f266f36f8847c6302d6cd5c762aaa62b0d'),
(177,'26080025e77be0b4e132136cf6c882d667739fb625cefee6a8723ad6604bc13d'),
(178,'2dfe4ea40a78380d527317b89ccd69156f6fd88379297dd662d9c0b9106c47ae'),
(179,'9b7cee59723592920a5b830f53ed1477ca6d638ba0b3112ef3722c2d7b06e1d6'),
(180,'e443d8b80cd0490554c595d2ca48fa7b6543d0313a3c0f99929cacb4cd55031c'),
(181,'7401ef565920968a5c0dfa50ecfa9f3f7454779c32faebb7b8b542c79b7f7449'),
(182,'f373247ddb527cdce5dd88a6ce7924f9e1397c1b61a52914e13a01f5ce668fde'),
(183,'35b6110bd55b9cb6a5a2c1f3eed36678af5828f3c5db84db3647734ebbdfaa34'),
(184,'cc27d25e9bef3988d1c3c666e1d2ccc61ea6621855c796afefc51421cbdee3a5');
DO $reference_locks$
DECLARE relation_name text;
BEGIN
 -- 引用核验与软删除处于同一事务锁窗口，阻止检查后并发新增已知引用。
 FOR relation_name IN SELECT name FROM (
   SELECT unnest(ARRAY['dh_governance_safety_field_tag','dh_governance_safety_identify_rule','dh_asset_sensitive_list']) AS name
   UNION SELECT conrelid::regclass::text FROM pg_constraint WHERE contype='f'
     AND confrelid IN ('dh_governance_safety_category'::regclass,'dh_governance_category_data'::regclass)
 ) references_to_lock ORDER BY name LOOP
   IF to_regclass(relation_name) IS NOT NULL THEN
     EXECUTE format('LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE',to_regclass(relation_name));
   END IF;
 END LOOP;
END;
$reference_locks$;
DO $cleanup$
DECLARE item record; reference record; has_reference boolean; qualified text; deleted_clause text; affected integer; deleted_count integer:=0;
BEGIN
 -- 真实使用方为安全字段打标、识别规则、资产敏感清单；另检查所有实际外键，不触碰标准/质量各自的独立目录表。
 FOR item IN SELECT c.id FROM dh_governance_safety_category c JOIN qdata_old_classification_seed s USING(id)
   WHERE c.deleted=0 AND c.revision=1 AND c.source_record_id IS NULL
     AND encode(sha256(convert_to((to_jsonb(c)-ARRAY['revision','source_record_id','source_creator_name','source_updater_name','source_create_time','source_update_time'])::text,'UTF8')),'hex')=s.expected_hash LOOP
   has_reference:=false;
   FOR qualified IN SELECT unnest(ARRAY['dh_governance_safety_field_tag','dh_governance_safety_identify_rule','dh_asset_sensitive_list']) LOOP
     IF to_regclass(qualified) IS NOT NULL THEN
       EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I WHERE category_id=$1 AND deleted=0)',qualified) INTO has_reference USING item.id;
       IF has_reference THEN EXIT; END IF;
     END IF;
   END LOOP;
   IF NOT has_reference THEN
     FOR reference IN SELECT r.conrelid::regclass AS rel,a.attname
       FROM pg_constraint r JOIN pg_attribute a ON a.attrelid=r.conrelid AND a.attnum=r.conkey[1]
       WHERE r.contype='f' AND r.confrelid='dh_governance_safety_category'::regclass LOOP
       deleted_clause:=CASE WHEN EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=reference.rel AND attname='deleted' AND NOT attisdropped) THEN ' AND deleted=0' ELSE '' END;
       EXECUTE format('SELECT EXISTS(SELECT 1 FROM %s WHERE %I=$1%s)',reference.rel,reference.attname,deleted_clause) INTO has_reference USING item.id;
       IF has_reference THEN EXIT; END IF;
     END LOOP;
   END IF;
   IF NOT has_reference THEN
     UPDATE dh_governance_safety_category SET deleted=1,revision=revision+1,updater='migration:0234',update_time=CURRENT_TIMESTAMP WHERE id=item.id;
   END IF;
 END LOOP;
 -- 自叶向根处理，任何已维护节点、手工子节点或有效分类都会阻止其祖先删除。
 LOOP
   affected:=0;
   FOR item IN SELECT d.id FROM dh_governance_category_data d JOIN qdata_old_directory_seed s USING(id)
     WHERE d.deleted=0 AND d.revision=1 AND d.source_record_id IS NULL
       AND encode(sha256(convert_to((to_jsonb(d)-ARRAY['revision','source_record_id'])::text,'UTF8')),'hex')=s.expected_hash
       AND NOT EXISTS(SELECT 1 FROM dh_governance_category_data child WHERE child.parent_id=d.id AND child.deleted=0)
       AND NOT EXISTS(SELECT 1 FROM dh_governance_safety_category c WHERE c.category_id=d.id AND c.deleted=0) LOOP
     has_reference:=false;
     FOR reference IN SELECT r.conrelid::regclass AS rel,a.attname
       FROM pg_constraint r JOIN pg_attribute a ON a.attrelid=r.conrelid AND a.attnum=r.conkey[1]
       WHERE r.contype='f' AND r.confrelid='dh_governance_category_data'::regclass LOOP
       deleted_clause:=CASE WHEN EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=reference.rel AND attname='deleted' AND NOT attisdropped) THEN ' AND deleted=0' ELSE '' END;
       EXECUTE format('SELECT EXISTS(SELECT 1 FROM %s WHERE %I=$1%s)',reference.rel,reference.attname,deleted_clause) INTO has_reference USING item.id;
       IF has_reference THEN EXIT; END IF;
     END LOOP;
     IF NOT has_reference THEN
       UPDATE dh_governance_category_data SET deleted=1,revision=revision+1,updater='migration:0234',update_time=CURRENT_TIMESTAMP WHERE id=item.id;
       affected:=affected+1;
     END IF;
   END LOOP;
   deleted_count:=deleted_count+affected;
   EXIT WHEN affected=0;
 END LOOP;
 RAISE NOTICE '本次软删除原始旧目录%个；仍保留的原始旧目录/分类意味着存在维护或有效引用，不强行匹配来源数量',deleted_count;
END;
$cleanup$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_safety_category_directory_name ON dh_governance_safety_category(category_id,btrim(name)) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_classification_directory_parent_name ON dh_governance_category_data(coalesce(parent_id,0),btrim(category_name)) WHERE deleted=0;
DO $foreign_keys$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_category'::regclass AND conname='fk_safety_category_directory') THEN
   ALTER TABLE dh_governance_safety_category ADD CONSTRAINT fk_safety_category_directory FOREIGN KEY(category_id) REFERENCES dh_governance_category_data(id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_category'::regclass AND conname='fk_safety_category_level') THEN
   ALTER TABLE dh_governance_safety_category ADD CONSTRAINT fk_safety_category_level FOREIGN KEY(data_level_id) REFERENCES dh_governance_safety_level(id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_category'::regclass AND conname='fk_safety_category_rule') THEN
   ALTER TABLE dh_governance_safety_category ADD CONSTRAINT fk_safety_category_rule FOREIGN KEY(rule_id) REFERENCES dh_governance_safety_rule(id);
 END IF;
END;
$foreign_keys$;
COMMIT;
