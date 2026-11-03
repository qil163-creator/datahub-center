-- Migration: 0235 qData 三条分类脱敏白名单及本地授权映射。
-- Purpose: 复用原白名单与主体及审计表，补并发版本和来源事实，将三条真实来源政策按用户明确决定绑定本地稳定管理员用户一，仅对指定分类的查询读取生效；按新取得的完整来源身份及未维护摘要补齐二十五条真实敏感规则关联。
-- Depends on: 0234_governance_qdata-classification-rules.sql 与 0227_asset_sensitive-list.sql；当前单租户治理白名单三表、原名三个序列，以及系统用户一为启用且未删除的超级管理员成员；不依赖外部用户编号的本地同号身份。
-- Target: 已有 PostgreSQL 平台开发或已核定映射的部署库；新库使用包含本迁移的 datahub-center.sql。其他环境须先核验用户一仍为部署目标的已授权稳定管理员身份。
-- Repeatable: 是；按来源编号迁入一次，已存在或逻辑删除的来源定义连同主体、授权事件整体跳过，人工维护不覆盖；本地同名或同分类政策冲突时整笔失败，禁止自动认领。
-- Rollback: 单事务失败完整回滚；提交后须先停新版策略维护入口，再按执行前备份恢复三条政策和精确旧种子墓碑；不得删除人工修改或授权审计，禁止自动撤销已使用政策。
-- 来源：2026-10-23 经授权只读取得的三条白名单完整业务字段；来源用户二的公开显示姓名只作追溯，用户本轮明确指定映射到本地稳定用户一，不迁入真实登录账号或凭据。
-- 授权：本地状态为 AUTHORIZED 直接授权且启用，依据为 USER_CONFIRMED_IMPORT；IMPORT_AUTHORIZATION 事件是本轮迁入决定，不是伪造的 qData 审批，来源审批人及审批历史不补造。
-- 范围：仅 USER 主体、CATEGORY 精确分类和 QUERY 读取契约，禁止扩展为角色全体、全局范围或全部服务场景；截止日期完整包含当天。
-- 发布边界：旧版只接受 APPROVED，故不会使新增 AUTHORIZED 政策生效；新版须同时核验精确分类、授权依据与对应授权事件，禁止将直接授权政策降为旧版审批通过状态。
-- 风险：短时锁定治理定义、白名单和主体审计表；仅在原始整行摘要、主体摘要、过期状态及无审批记录同时匹配时软删旧测试种子，其他对象保留。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
SELECT pg_advisory_xact_lock(1900010000);
LOCK TABLE dh_governance_safety_whitelist,dh_governance_safety_whitelist_subject,
 dh_governance_safety_whitelist_approval IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE dh_asset_sensitive_list ADD COLUMN IF NOT EXISTS rule_id bigint;
COMMENT ON COLUMN dh_asset_sensitive_list.rule_id IS '本地明确绑定的治理脱敏规则编号，来源未提供时为空，不按分类或规则名称推测；跨服务不建立数据库外键';
CREATE INDEX IF NOT EXISTS idx_asset_sensitive_active_rule_id ON dh_asset_sensitive_list(rule_id,id)
 WHERE deleted=0 AND rule_id IS NOT NULL;

ALTER TABLE dh_governance_safety_whitelist
 ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1,
 ADD COLUMN IF NOT EXISTS source_record_id varchar(64),
 ADD COLUMN IF NOT EXISTS source_creator_name varchar(128),
 ADD COLUMN IF NOT EXISTS source_updater_name varchar(128),
 ADD COLUMN IF NOT EXISTS source_create_time timestamp,
 ADD COLUMN IF NOT EXISTS source_update_time timestamp,
 ADD COLUMN IF NOT EXISTS source_subjects_json jsonb,
 ADD COLUMN IF NOT EXISTS authorization_basis varchar(64),
 ADD COLUMN IF NOT EXISTS authorization_reason text;
COMMENT ON COLUMN dh_governance_safety_whitelist.revision IS '白名单本地并发版本，从一开始递增';
COMMENT ON COLUMN dh_governance_safety_whitelist.source_record_id IS 'qData 来源白名单编号，仅追溯，不代替本地主键，包含逻辑删除墓碑';
COMMENT ON COLUMN dh_governance_safety_whitelist.source_creator_name IS '来源创建人的公开显示姓名，不替代本地审计';
COMMENT ON COLUMN dh_governance_safety_whitelist.source_updater_name IS '来源修改人的公开显示姓名，本地维护后清空';
COMMENT ON COLUMN dh_governance_safety_whitelist.source_create_time IS '来源创建时间，只读展示，不覆盖本地创建审计';
COMMENT ON COLUMN dh_governance_safety_whitelist.source_update_time IS '来源更新时间，本地维护后清空，不覆盖本地更新审计';
COMMENT ON COLUMN dh_governance_safety_whitelist.source_subjects_json IS '来源主体编号、公开显示名及状态的只读追溯数组，绝不用于本地主体匹配或授权';
COMMENT ON COLUMN dh_governance_safety_whitelist.authorization_basis IS '当前本地授权依据：USER_CONFIRMED_IMPORT 用户确认迁入，LOCAL_POLICY_AUTHORIZATION 本地管理权限配置；旧审批协议可为空';
COMMENT ON COLUMN dh_governance_safety_whitelist.authorization_reason IS '本地授权或用户确认映射的依据说明，不冒充来源审批意见';
COMMENT ON COLUMN dh_governance_safety_whitelist.approval_status IS '本地状态：DRAFT/PENDING/APPROVED/REJECTED 为旧审批协议；AUTHORIZED 为管理权限直接政策授权，必须另有授权依据及事件';
ALTER TABLE dh_governance_safety_whitelist_approval ALTER COLUMN action TYPE varchar(32);
COMMENT ON COLUMN dh_governance_safety_whitelist_approval.action IS '审计动作：旧 SUBMIT/APPROVE/REJECT 审批，本地 IMPORT_AUTHORIZATION 迁入决定或 LOCAL_POLICY_AUTHORIZATION 策略授权，不冒充远端审批';
DO $structure$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_whitelist'::regclass AND conname='ck_safety_whitelist_revision') THEN
   ALTER TABLE dh_governance_safety_whitelist ADD CONSTRAINT ck_safety_whitelist_revision CHECK(revision>0);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_whitelist'::regclass AND conname='ck_safety_whitelist_source_subjects') THEN
   ALTER TABLE dh_governance_safety_whitelist ADD CONSTRAINT ck_safety_whitelist_source_subjects CHECK(source_subjects_json IS NULL OR jsonb_typeof(source_subjects_json)='array');
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_whitelist'::regclass AND conname='ck_safety_whitelist_workflow') THEN
   ALTER TABLE dh_governance_safety_whitelist ADD CONSTRAINT ck_safety_whitelist_workflow CHECK(approval_status IN('DRAFT','PENDING','APPROVED','REJECTED','AUTHORIZED'));
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_whitelist'::regclass AND conname='ck_safety_whitelist_authorized_scope') THEN
   ALTER TABLE dh_governance_safety_whitelist ADD CONSTRAINT ck_safety_whitelist_authorized_scope CHECK(approval_status<>'AUTHORIZED' OR (
     subject_type='USER' AND object_type='CATEGORY' AND scene='QUERY' AND object_id IS NOT NULL AND object_id>0
     AND authorization_basis IS NOT NULL AND authorization_basis IN('USER_CONFIRMED_IMPORT','LOCAL_POLICY_AUTHORIZATION')
     AND authorization_reason IS NOT NULL AND btrim(authorization_reason)<>'' AND approver IS NULL));
 END IF;
END;
$structure$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_safety_whitelist_source ON dh_governance_safety_whitelist(source_record_id) WHERE source_record_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_safety_whitelist_query_category ON dh_governance_safety_whitelist(object_id)
 WHERE deleted=0 AND object_type='CATEGORY' AND subject_type='USER' AND scene='QUERY';
CREATE OR REPLACE FUNCTION dh_safety_whitelist_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $guard$
BEGIN
 IF OLD.authorization_basis IS NOT NULL AND btrim(OLD.authorization_basis)<>''
   AND (NEW.authorization_basis IS NULL OR btrim(NEW.authorization_basis)='' OR NEW.approval_status<>'AUTHORIZED') THEN
   RAISE EXCEPTION '直接授权政策须由专用策略入口维护，不得降为旧审批状态' USING ERRCODE='23514';
 END IF;
 -- 通用旧入口未显式推进版本时自动推进；工作台已用 CAS 推进一次时不重复累加。
 IF NEW.revision=OLD.revision THEN
   NEW.revision:=OLD.revision+1;
 ELSIF NEW.revision IS DISTINCT FROM OLD.revision+1 THEN
   RAISE EXCEPTION '白名单修订只能递增一' USING ERRCODE='23514';
 END IF;
 NEW.source_updater_name:=NULL;
 NEW.source_update_time:=NULL;
 NEW.update_time:=CURRENT_TIMESTAMP;
 RETURN NEW;
END;
$guard$;
COMMENT ON FUNCTION dh_safety_whitelist_revision_guard() IS '覆盖白名单所有更新入口的版本递增与来源修改事实清理；真实本地操作者仍由业务应用写入';
DROP TRIGGER IF EXISTS trg_safety_whitelist_revision_guard ON dh_governance_safety_whitelist;
CREATE TRIGGER trg_safety_whitelist_revision_guard BEFORE UPDATE ON dh_governance_safety_whitelist
 FOR EACH ROW EXECUTE FUNCTION dh_safety_whitelist_revision_guard();

CREATE TEMP TABLE qdata_whitelist_policy_seed(
 source_id bigint PRIMARY KEY,category_source_id bigint,name text,description text,start_date date,end_date date,
 status smallint,creator_name text,updater_name text,source_created timestamp,source_updated timestamp,source_subjects jsonb
) ON COMMIT DROP;
INSERT INTO qdata_whitelist_policy_seed VALUES
(1,24,'河道水情测站编码白名单','在河道水情资产和数据服务场景中查看完整测站编码，用于资产核验、模型维护和问题定位。','2026-08-04','2099-12-31',0,'吴同','吴同','2026-08-04 19:31:55','2026-08-04 19:38:14','[{"sourceUserId":"2","displayName":"吴同","effectiveCategory":"1","validFlag":true}]'),
(2,26,'河道水情测站位置白名单','查看水文站、水位站、流量站的精确经纬度，用于监测站点管理、数据核验和运行维护。','2026-08-04','2099-12-31',0,'吴同','吴同','2026-08-04 19:33:31','2026-08-04 19:38:48','[{"sourceUserId":"2","displayName":"吴同","effectiveCategory":"1","validFlag":true}]'),
(3,43,'河道水情空间坐标白名单','授权吴同查看河流、河段、断面及一张图空间要素的高精度坐标与几何信息，用于地图展示、空间校核和资产维护。','2026-08-04','2099-12-31',0,'吴同','吴同','2026-08-04 19:34:31','2026-08-04 19:37:20','[{"sourceUserId":"2","displayName":"吴同","effectiveCategory":"1","validFlag":true}]');
DO $policies$
DECLARE item record; target_id bigint; category_id_value bigint; parent_id_value bigint; parent_status smallint;
 decision_reason text; relation_name text; sequence_name text; sequence_value bigint; sequence_called boolean;
 first_id numeric; sequence_max bigint; sequence_increment bigint; sequence_cycles boolean; visited_directories bigint[];
BEGIN
 -- 只在仍需首次迁入时核验用户明确选择的稳定身份；重放不恢复被后来删除或修改的本地政策。
 IF EXISTS(SELECT 1 FROM qdata_whitelist_policy_seed s WHERE NOT EXISTS(
   SELECT 1 FROM dh_governance_safety_whitelist w WHERE w.source_record_id=s.source_id::text)) THEN
   PERFORM 1 FROM system_users u JOIN system_user_role ur ON ur.user_id=u.id AND ur.deleted=0
     JOIN system_role r ON r.id=ur.role_id AND r.deleted=0 AND r.status=0
     WHERE u.id=1 AND u.status=0 AND u.deleted=0 AND r.code='super_admin' FOR SHARE OF u,ur,r;
   IF NOT FOUND THEN RAISE EXCEPTION '用户指定的稳定本地管理员身份一不可用，禁止猜测或改绑其他角色成员'; END IF;
 END IF;
 -- 保留原名序列；事务性对齐现有主键，失败时重启状态一并回滚，重放不倒退。
 FOR relation_name,sequence_name IN SELECT * FROM (VALUES
   ('dh_governance_safety_whitelist','dh_safety_whitelist_seq'),
   ('dh_governance_safety_whitelist_subject','dh_safety_whitelist_subject_seq'),
   ('dh_governance_safety_whitelist_approval','dh_safety_whitelist_approval_seq')) s(tab,seq) LOOP
   SELECT seqincrement,seqmax,seqcycle INTO sequence_increment,sequence_max,sequence_cycles FROM pg_sequence WHERE seqrelid=sequence_name::regclass;
   IF sequence_increment IS DISTINCT FROM 1 OR sequence_cycles IS DISTINCT FROM false THEN RAISE EXCEPTION '白名单序列 % 必须步长一且不循环',sequence_name; END IF;
   EXECUTE format('SELECT last_value,is_called FROM %I',sequence_name) INTO sequence_value,sequence_called;
   EXECUTE format('SELECT greatest(coalesce(max(id),0)::numeric+1,$1::numeric+$2) FROM %I',relation_name)
     INTO first_id USING sequence_value,CASE WHEN sequence_called THEN 1 ELSE 0 END;
   IF first_id+3>sequence_max THEN RAISE EXCEPTION '白名单序列剩余空间不足'; END IF;
   EXECUTE format('ALTER SEQUENCE %I RESTART WITH %s',sequence_name,first_id);
 END LOOP;
 FOR item IN SELECT * FROM qdata_whitelist_policy_seed ORDER BY source_id LOOP
   IF EXISTS(SELECT 1 FROM dh_governance_safety_whitelist WHERE source_record_id=item.source_id::text) THEN CONTINUE; END IF;
   SELECT c.id,c.category_id INTO category_id_value,parent_id_value FROM dh_governance_safety_category c
     JOIN dh_governance_safety_level l ON l.id=c.data_level_id AND l.deleted=0 AND l.status=0
     WHERE c.source_record_id=item.category_source_id::text AND c.deleted=0 AND c.status=0;
   IF category_id_value IS NULL THEN RAISE EXCEPTION '来源分类 % 缺失或停用，禁止使用远端编号充当本地主键',item.category_source_id; END IF;
   visited_directories:=ARRAY[]::bigint[];
   WHILE parent_id_value IS NOT NULL AND parent_id_value<>0 LOOP
     IF parent_id_value=ANY(visited_directories) THEN RAISE EXCEPTION '来源分类 % 的目录路径成环',item.category_source_id; END IF;
     visited_directories:=array_append(visited_directories,parent_id_value);
     SELECT parent_id,status INTO parent_id_value,parent_status FROM dh_governance_category_data WHERE id=parent_id_value AND deleted=0;
     IF NOT FOUND OR parent_status<>0 THEN RAISE EXCEPTION '来源分类 % 的有效目录路径不完整',item.category_source_id; END IF;
   END LOOP;
   IF EXISTS(SELECT 1 FROM dh_governance_safety_whitelist WHERE btrim(name)=btrim(item.name)
     OR (object_type='CATEGORY' AND subject_type='USER' AND scene='QUERY' AND object_id=category_id_value AND deleted=0)) THEN
     RAISE EXCEPTION '来源白名单 % 与本地同名或同分类政策身份冲突，禁止认领或覆盖，须人工核验',item.source_id;
   END IF;
   target_id:=nextval('dh_safety_whitelist_seq');
   decision_reason:=format('用户本轮明确授权将 qData 白名单 %s 的来源用户二映射为已核定的本地稳定管理员用户一；仅本地分类 %s 的 QUERY 读取。该事件为本地迁入授权决定，不表示来源存在审批。',item.source_id,category_id_value);
   INSERT INTO dh_governance_safety_whitelist(id,name,subject_type,scene,object_type,object_id,effective_time,expire_time,
     approver,status,description,approval_status,revision,source_record_id,source_creator_name,source_updater_name,
     source_create_time,source_update_time,source_subjects_json,authorization_basis,authorization_reason,creator,updater)
     VALUES(target_id,item.name,'USER','QUERY','CATEGORY',category_id_value,item.start_date::timestamp,
       item.end_date::timestamp+INTERVAL '1 day'-INTERVAL '1 microsecond',NULL,item.status,item.description,'AUTHORIZED',1,
       item.source_id::text,item.creator_name,item.updater_name,item.source_created,item.source_updated,item.source_subjects,
       'USER_CONFIRMED_IMPORT',decision_reason,'migration:0235','migration:0235');
   INSERT INTO dh_governance_safety_whitelist_subject(id,whitelist_id,subject_id,creator,updater)
     VALUES(nextval('dh_safety_whitelist_subject_seq'),target_id,1,'migration:0235','migration:0235');
   INSERT INTO dh_governance_safety_whitelist_approval(id,whitelist_id,action,opinion,creator,updater)
     VALUES(nextval('dh_safety_whitelist_approval_seq'),target_id,'IMPORT_AUTHORIZATION',decision_reason,'migration:0235','migration:0235');
 END LOOP;
END;
$policies$;

-- 仅精确清理原始且已过期的旧测试政策及两个原始主体；任何维护、审批历史或主体变更都会保留旧行。
DO $old_seed$
DECLARE current_subject_hash text; current_subject_count bigint;
BEGIN
 SELECT count(*),encode(sha256(convert_to(coalesce(jsonb_agg(to_jsonb(s) ORDER BY id),'[]'::jsonb)::text,'UTF8')),'hex')
   INTO current_subject_count,current_subject_hash FROM dh_governance_safety_whitelist_subject s WHERE whitelist_id=1;
 IF current_subject_count=2 AND current_subject_hash='2a831840fcccc4ac4aaaecc12347ee34f6a536e049db94f43556d914a059ca11'
   AND NOT EXISTS(SELECT 1 FROM dh_governance_safety_whitelist_approval WHERE whitelist_id=1)
   AND EXISTS(SELECT 1 FROM dh_governance_safety_whitelist w WHERE id=1 AND deleted=0 AND revision=1
     AND source_record_id IS NULL AND expire_time<CURRENT_TIMESTAMP
     AND encode(sha256(convert_to((to_jsonb(w)-ARRAY['revision','source_record_id','source_creator_name','source_updater_name',
       'source_create_time','source_update_time','source_subjects_json','authorization_basis','authorization_reason'])::text,'UTF8')),'hex')='f2badeecf1bb655a85db98613680c6f95d41bf966ec7c159c4f90a36342ef6ac') THEN
   UPDATE dh_governance_safety_whitelist SET deleted=1,revision=revision+1,updater='migration:0235',update_time=CURRENT_TIMESTAMP WHERE id=1;
   UPDATE dh_governance_safety_whitelist_subject SET deleted=1,updater='migration:0235',update_time=CURRENT_TIMESTAMP WHERE whitelist_id=1;
 END IF;
END;
$old_seed$;
DO $foreign_keys$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_whitelist_subject'::regclass AND conname='fk_safety_whitelist_subject_parent') THEN
   ALTER TABLE dh_governance_safety_whitelist_subject ADD CONSTRAINT fk_safety_whitelist_subject_parent FOREIGN KEY(whitelist_id) REFERENCES dh_governance_safety_whitelist(id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='dh_governance_safety_whitelist_approval'::regclass AND conname='fk_safety_whitelist_approval_parent') THEN
   ALTER TABLE dh_governance_safety_whitelist_approval ADD CONSTRAINT fk_safety_whitelist_approval_parent FOREIGN KEY(whitelist_id) REFERENCES dh_governance_safety_whitelist(id);
 END IF;
END;
$foreign_keys$;

-- 来源规则二十对应十四条清单、规则二十二对应十一条；此处依据逐规则接口返回的明确关系，不根据名称或分类猜测。
CREATE TEMP TABLE qdata_sensitive_rule_binding_seed(
 source_record_id text PRIMARY KEY,source_rule_id text,source_asset_id text,source_column_id text,column_name text,
 source_category_id text,table_name text,expected_hash text
) ON COMMIT DROP;
INSERT INTO qdata_sensitive_rule_binding_seed VALUES
('1','20','1','8','STCD','24','DWD_RH_MON_ST_Z_HIS_R','77df6bd5d777a85f19649f117de5bf1e4d6ea297dc03f1e442169d5c3b844ef1'),
('2','20','2','24','STCD','24','DWS_RH_STAT_ST_Z_MON_S','438f0662a01be42b6a5bbec45377db009202d843c636bec97c9f4cb7f27f12cd'),
('3','20','3','41','STCD','24','ODS_RH_MON_ST_Z_HIS_R','38b8e55d43f832d8c0e044ffff08513d5db68f451f30ac7148af5f3a74138d57'),
('5','20','5','77','STCD','24','DWS_RH_HIST_ST_Z_DAY_S','7dbcbfbbf0399dd28fb6570bc3c6332a078d2019a225a220b16184ae9cdb90a4'),
('8','20','7','111','STCD','24','DIM_RH_BO_ST_Z_STBPRP_B','e4606a2a135ad6eaa119570b0067c5739eaea4e26dff94bb747c1debcdff2579'),
('11','20','8','130','STCD','24','ODS_RH_BO_ST_Z_STBPRP_B','6c3160b3f83d984abf0cb35b946be5e1c291821b1353a884191f7de91714d11c'),
('14','20','9','149','STCD','24','DIM_RH_BO_ST_STBPRP_B','e0a0c5bb04cec061abf2b7bf8a10e0eab18fc7dc5e233bd31594bcd91bdb6d37'),
('15','20','10','177','STCD','24','DWS_RH_STAT_ST_Z_YEAR_S','8dccef133715452f4f24615d3a6689ff50d9cc59b8bf6cb3b6d96edfd80bccb0'),
('18','20','9','149','STCD','24','DIM_RH_BO_ST_STBPRP_B','7cf6e33c2466348b35e909e34b704e169ad3744272714e71edba0914b0530b37'),
('20','20','14','253','STCD','24','DWD_RH_RTW_ST_Q_R','e0454904c8684de390e623fb6c0c24b2434d9c75dc3dba0f1698c03069eb35ea'),
('21','20','15','264','STCD','24','ODS_RH_RTW_ST_Q_R','846fd3743ecd043f1a15f045001005f9b3c8c29ea30f86ab710bf463d0046b26'),
('23','20','16','269','STCD','24','DIM_RH_BO_ST_Q_STBPRP_B','580091824521f1cdf09b6296e9ab227b9b76eb8fe59afdb11d517ac3c3ed6be3'),
('26','20','12','216','STCD','24','DWS_RH_HIST_ST_Q_MON_S','af8cca999c4f9c6f1f3891ddebf6942dd1da37c9830951433c9152a032de33bb'),
('27','20','12','216','STCD','24','DWS_RH_HIST_ST_Q_MON_S','c0cff934a06b6ecf2127d44b7eb2be3c922aae3710b014c86ab9a37902eae91e'),
('6','22','7','125','LGTD','26','DIM_RH_BO_ST_Z_STBPRP_B','664c22100ad3b0afdd95fa0feeea63dd7727516fd836952446e5c47a9dd83e5c'),
('7','22','7','126','LTTD','26','DIM_RH_BO_ST_Z_STBPRP_B','6f418d56634ebf0df72e827260cd1f00b586ff616d3a814416e2105c4949239a'),
('9','22','8','144','LGTD','26','ODS_RH_BO_ST_Z_STBPRP_B','fdff5e53e7d29fb7486ba0577020ab03e59c14236a9d651a198fce3b8a34e753'),
('10','22','8','145','LTTD','26','ODS_RH_BO_ST_Z_STBPRP_B','cf90bd55a6f99392af3e7dff93aa4fb307c9865c58e809fc1ab656e5562e05c9'),
('12','22','9','163','LGTD','26','DIM_RH_BO_ST_STBPRP_B','63045d5ab0013dd9444af7acd347e1ec4676813c07255252c239fe542f59ac21'),
('13','22','9','164','LTTD','26','DIM_RH_BO_ST_STBPRP_B','25642deb57b244e39e45af4eef781ddb8a4f8257441b8213625240cf8c978ea1'),
('16','22','9','163','LGTD','26','DIM_RH_BO_ST_STBPRP_B','74c97f28e536dde2d87762a981761c969c7691412b40bebc47687b055b7ff400'),
('17','22','9','164','LTTD','26','DIM_RH_BO_ST_STBPRP_B','6914160615d774c515882cb3bd61e4acd4e4959113118e4c6cb0df3427eba012'),
('22','22','16','283','LGTD','26','DIM_RH_BO_ST_Q_STBPRP_B','7a3781d542dddc41636de83d09b4f654431a8cc176c3638c383fd32f3bf4607e'),
('24','22','16','284','LTTD','26','DIM_RH_BO_ST_Q_STBPRP_B','c319bbb77d011d19b05810d2de57e16025dc27dee888f5fb39ab78568bfee86b'),
('25','22','16','283','LGTD','26','DIM_RH_BO_ST_Q_STBPRP_B','f66d1330935fad7468e1722860a7cf528670a3d8e7396050128e9ee2f6ea5927');
DO $sensitive_bindings$
DECLARE item record; current_row record; local_rule_id bigint; bound_count integer:=0;
BEGIN
 FOR item IN SELECT * FROM qdata_sensitive_rule_binding_seed ORDER BY source_record_id::numeric LOOP
   SELECT s.id,s.revision,s.rule_id,
     encode(sha256(convert_to(((to_jsonb(s)-ARRAY['id','asset_id','category_id','rule_id'])||
       jsonb_build_object('sourceAssetId',d.source_asset_id,'sourceCategoryId',g.source_record_id))::text,'UTF8')),'hex') AS current_hash
     INTO current_row
     FROM dh_asset_sensitive_list s JOIN dh_asset_import_dataset d ON d.asset_id=s.asset_id AND d.deleted=0
     JOIN dh_asset_data_asset a ON a.id=s.asset_id AND a.deleted=0
     JOIN dh_governance_safety_category g ON g.id=s.category_id AND g.deleted=0
     WHERE s.source_record_id=item.source_record_id AND s.deleted=0 AND d.source_asset_id=item.source_asset_id
       AND s.column_id=item.source_column_id::bigint AND s.column_name=item.column_name
       AND g.source_record_id=item.source_category_id AND a.table_name=item.table_name;
   IF NOT FOUND OR current_row.revision<>0 OR current_row.rule_id IS NOT NULL OR current_row.current_hash<>item.expected_hash THEN CONTINUE; END IF;
   SELECT r.id INTO local_rule_id FROM dh_governance_safety_rule r
     JOIN dh_governance_safety_category g ON g.rule_id=r.id AND g.source_record_id=item.source_category_id AND g.deleted=0
     WHERE r.source_record_id=item.source_rule_id AND r.deleted=0;
   IF local_rule_id IS NULL THEN CONTINUE; END IF;
   -- 仅推进本次技术绑定修订，业务字段、来源姓名与来源审计时间完整保留；后续解绑也不会被重放填回。
   UPDATE dh_asset_sensitive_list SET rule_id=local_rule_id,revision=revision+1 WHERE id=current_row.id AND revision=0 AND rule_id IS NULL;
   bound_count:=bound_count+1;
 END LOOP;
 RAISE NOTICE '本次按明确来源关系补入敏感规则绑定%条；人工维护、墓碑或身份不完整的行保持原样',bound_count;
END;
$sensitive_bindings$;
COMMIT;
