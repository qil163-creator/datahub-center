-- Migration: 0192 批量文件入库。
-- Purpose: 新增批量文件任务、受管素材、持久执行快照、基础设施文件回执及数据接入菜单权限。
-- Depends on: 0191_governance_standard-element-workbench.sql；数据接入目录、infra 文件及平台身份权限表已就绪。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；表、序列、索引按存在性创建，菜单按组件及权限身份补齐，授权按缺失关系补齐。
-- Rollback: 失败时事务回滚；提交后新增配置与执行审计不可丢弃，回退应用时保留本次新增表，需删除时先备份。
-- 风险：执行本脚本不会连接外部数据库或导入文件；菜单写入持有短锁，建议在维护窗口执行。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS infra_managed_file_seq;
CREATE TABLE IF NOT EXISTS infra_managed_file (
 id bigint PRIMARY KEY DEFAULT nextval('infra_managed_file_seq'),
 owner_service varchar(64) NOT NULL,
 operation_key varchar(128) NOT NULL,
 name varchar(255) NOT NULL,
 sha256 varchar(64) NOT NULL,
 size bigint NOT NULL CHECK (size BETWEEN 0 AND 20971520),
 config_id bigint NOT NULL,
 path varchar(1024) NOT NULL,
 state varchar(32) NOT NULL CHECK (state IN ('PENDING','UPLOADING','READY','FAILED','CLEANUP_PENDING','DELETED')),
 file_id bigint,
 creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
 CONSTRAINT uk_infra_managed_file_operation UNIQUE (owner_service, operation_key)
);
CREATE INDEX IF NOT EXISTS idx_infra_managed_file_pending ON infra_managed_file(state,update_time,id) WHERE state IN ('PENDING','UPLOADING','CLEANUP_PENDING','DELETED');

CREATE SEQUENCE IF NOT EXISTS dh_batch_file_task_seq;
CREATE TABLE IF NOT EXISTS dh_batch_file_task (
 id bigint PRIMARY KEY DEFAULT nextval('dh_batch_file_task_seq'),
 task_name varchar(100) NOT NULL,
 responsible_user_id bigint NOT NULL, responsible_name varchar(100) NOT NULL, responsible_phone varchar(32) NOT NULL,
 description varchar(256), remark varchar(256),
 target_database_id bigint NOT NULL, target_database_name varchar(200) NOT NULL,
 online boolean NOT NULL DEFAULT false, revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
 config_json text NOT NULL CHECK (jsonb_typeof(config_json::jsonb) = 'object'),
 last_status varchar(32) NOT NULL DEFAULT 'NOT_EXECUTED' CHECK (last_status IN ('NOT_EXECUTED','QUEUED','RUNNING','SUCCESS','PARTIAL_SUCCESS','FAILED','UNKNOWN')),
 last_run_id bigint, last_run_time timestamp,
 file_count integer NOT NULL CHECK (file_count BETWEEN 1 AND 20), target_count integer NOT NULL CHECK (target_count BETWEEN 1 AND 20),
 success_files integer NOT NULL DEFAULT 0 CHECK (success_files >= 0), failed_files integer NOT NULL DEFAULT 0 CHECK (failed_files >= 0),
 creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_file_task_live_name ON dh_batch_file_task(lower(task_name)) WHERE deleted=0;

CREATE SEQUENCE IF NOT EXISTS dh_batch_file_upload_seq;
CREATE TABLE IF NOT EXISTS dh_batch_file_upload (
 id bigint PRIMARY KEY DEFAULT nextval('dh_batch_file_upload_seq'),
 owner_id bigint NOT NULL, operation_key varchar(64) NOT NULL UNIQUE, storage_file_id bigint,
 name varchar(255) NOT NULL, file_type varchar(8) NOT NULL CHECK (file_type IN ('csv','xls','xlsx')),
 source varchar(16) NOT NULL CHECK (source IN ('LOCAL','SYSTEM')), connection_id bigint, path varchar(2048),
 size bigint NOT NULL CHECK (size BETWEEN 1 AND 20971520), sha256 varchar(64) NOT NULL,
 state varchar(24) NOT NULL CHECK (state IN ('PENDING','READY','FAILED','CLEANUP_PENDING','CLEANED')),
 row_count integer CHECK (row_count BETWEEN 0 AND 100000), fields_json text CHECK (fields_json IS NULL OR jsonb_typeof(fields_json::jsonb)='array'),
 task_id bigint REFERENCES dh_batch_file_task(id), expires_at timestamp NOT NULL,
 creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE INDEX IF NOT EXISTS idx_batch_file_upload_owner ON dh_batch_file_upload(owner_id,task_id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_batch_file_upload_cleanup ON dh_batch_file_upload(expires_at) WHERE task_id IS NULL AND state<>'CLEANED';

CREATE SEQUENCE IF NOT EXISTS dh_batch_file_run_seq;
CREATE TABLE IF NOT EXISTS dh_batch_file_run (
 id bigint PRIMARY KEY DEFAULT nextval('dh_batch_file_run_seq'),
 task_id bigint NOT NULL REFERENCES dh_batch_file_task(id), request_id varchar(64) NOT NULL UNIQUE, execution_user_id bigint NOT NULL,
 config_json text NOT NULL CHECK (jsonb_typeof(config_json::jsonb)='object'),
 status varchar(32) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCESS','PARTIAL_SUCCESS','FAILED','UNKNOWN')),
 lease_token varchar(64), lease_until timestamp, start_time timestamp, end_time timestamp,
 file_count integer NOT NULL CHECK (file_count BETWEEN 1 AND 20),
 success_count integer NOT NULL DEFAULT 0 CHECK (success_count >= 0), failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
 written_rows bigint NOT NULL DEFAULT 0 CHECK (written_rows >= 0),
 items_json text NOT NULL CHECK (jsonb_typeof(items_json::jsonb)='array'), error_message varchar(500),
 resolved_by bigint, resolved_time timestamp, resolution_reason varchar(500),
 creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_file_run_active ON dh_batch_file_run(task_id)
 WHERE deleted=0 AND (status IN ('QUEUED','RUNNING') OR (status='UNKNOWN' AND resolved_time IS NULL));
CREATE INDEX IF NOT EXISTS idx_batch_file_run_task ON dh_batch_file_run(task_id,id DESC) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_batch_file_run_lease ON dh_batch_file_run(status,lease_until) WHERE deleted=0 AND status IN ('QUEUED','RUNNING');

COMMENT ON TABLE infra_managed_file IS '受服务身份约束的文件存储意图及可重放回执';
COMMENT ON TABLE dh_batch_file_task IS '批量文件入库配置，保存不触发外部写入';
COMMENT ON TABLE dh_batch_file_upload IS '批量文件入库素材登记及归属';
COMMENT ON TABLE dh_batch_file_run IS '批量文件入库持久执行快照及逐文件结果';

-- 所有字段均补充中文说明，循环仅用于减少相同审计字段的重复语句。
DO $comments$
DECLARE item record; table_name text;
BEGIN
 FOR item IN SELECT * FROM (VALUES
 ('infra_managed_file','id','受管文件编号'),('infra_managed_file','owner_service','认证得到的调用服务身份'),
 ('infra_managed_file','operation_key','调用服务范围内稳定幂等键'),('infra_managed_file','name','原文件名称'),
 ('infra_managed_file','sha256','内容摘要'),('infra_managed_file','size','内容字节数'),('infra_managed_file','config_id','基础设施文件配置编号'),
 ('infra_managed_file','path','服务端生成的对象路径'),('infra_managed_file','state','存储生命周期状态'),('infra_managed_file','file_id','已登记的平台文件编号'),
 ('dh_batch_file_task','id','任务编号'),('dh_batch_file_task','task_name','任务名称'),('dh_batch_file_task','responsible_user_id','负责人编号'),
 ('dh_batch_file_task','responsible_name','负责人名称快照'),('dh_batch_file_task','responsible_phone','负责人联系电话快照'),
 ('dh_batch_file_task','description','任务描述'),('dh_batch_file_task','remark','任务备注'),('dh_batch_file_task','target_database_id','目标数据源编号'),
 ('dh_batch_file_task','target_database_name','目标数据源名称快照'),('dh_batch_file_task','online','是否上线'),('dh_batch_file_task','revision','并发编辑修订号'),
 ('dh_batch_file_task','config_json','文件结构及目标分组配置，不含连接凭据'),('dh_batch_file_task','last_status','最近执行状态'),
 ('dh_batch_file_task','last_run_id','最近执行实例编号'),('dh_batch_file_task','last_run_time','最近执行时间'),
 ('dh_batch_file_task','file_count','配置文件数量'),('dh_batch_file_task','target_count','目标表数量'),
 ('dh_batch_file_task','success_files','最近实例成功文件数'),('dh_batch_file_task','failed_files','最近实例失败文件数'),
 ('dh_batch_file_upload','id','素材编号'),('dh_batch_file_upload','owner_id','上传者编号'),('dh_batch_file_upload','operation_key','跨服务存储幂等键'),
 ('dh_batch_file_upload','storage_file_id','基础设施受管文件编号'),('dh_batch_file_upload','name','文件名称'),('dh_batch_file_upload','file_type','文件扩展类型'),
 ('dh_batch_file_upload','source','文件来源：本地或系统文件'),('dh_batch_file_upload','connection_id','系统文件连接编号'),
 ('dh_batch_file_upload','path','来源逻辑路径'),('dh_batch_file_upload','size','文件字节数'),('dh_batch_file_upload','sha256','文件内容摘要'),
 ('dh_batch_file_upload','state','上传登记状态'),('dh_batch_file_upload','row_count','解析数据行数'),('dh_batch_file_upload','fields_json','原始字段结构'),
 ('dh_batch_file_upload','task_id','绑定任务编号'),('dh_batch_file_upload','expires_at','未绑定素材回收时间'),
 ('dh_batch_file_run','id','执行实例编号'),('dh_batch_file_run','task_id','所属任务编号'),('dh_batch_file_run','request_id','浏览器执行命令幂等键'),
 ('dh_batch_file_run','execution_user_id','执行人编号'),('dh_batch_file_run','config_json','受理时冻结的任务配置'),('dh_batch_file_run','status','执行结果状态'),
 ('dh_batch_file_run','lease_token','执行领取令牌'),('dh_batch_file_run','lease_until','领取有效期'),('dh_batch_file_run','start_time','执行开始时间'),
 ('dh_batch_file_run','end_time','执行结束时间'),('dh_batch_file_run','file_count','文件总数'),('dh_batch_file_run','success_count','成功文件数'),
 ('dh_batch_file_run','failed_count','失败文件数'),('dh_batch_file_run','written_rows','已确认写入行数'),('dh_batch_file_run','items_json','逐文件写入结果'),
 ('dh_batch_file_run','error_message','脱敏结果说明'),('dh_batch_file_run','resolved_by','人工核对人编号'),
 ('dh_batch_file_run','resolved_time','人工核对时间'),('dh_batch_file_run','resolution_reason','人工确认终止与核对目标的说明')
 ) descriptions(table_name,column_name,description) LOOP
  EXECUTE format('COMMENT ON COLUMN %I.%I IS %L',item.table_name,item.column_name,item.description);
 END LOOP;
 FOREACH table_name IN ARRAY ARRAY['infra_managed_file','dh_batch_file_task','dh_batch_file_upload','dh_batch_file_run'] LOOP
  EXECUTE format('COMMENT ON COLUMN %I.creator IS %L',table_name,'创建人');
  EXECUTE format('COMMENT ON COLUMN %I.create_time IS %L',table_name,'创建时间');
  EXECUTE format('COMMENT ON COLUMN %I.updater IS %L',table_name,'更新人');
  EXECUTE format('COMMENT ON COLUMN %I.update_time IS %L',table_name,'更新时间');
  EXECUTE format('COMMENT ON COLUMN %I.deleted IS %L',table_name,'逻辑删除标记');
 END LOOP;
END $comments$;

LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE page_id bigint; new_id bigint; high_id bigint; matches integer; item record;
BEGIN
 IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id=5987 AND path='integrate' AND deleted=0) THEN
  RAISE EXCEPTION '0192 数据接入父菜单缺失';
 END IF;
 SELECT count(*),min(id) INTO matches,page_id FROM system_menu WHERE component='dataforge/batchfile/index' AND deleted=0;
 IF matches>1 THEN RAISE EXCEPTION '0192 批量文件入库页面重复'; END IF;
 IF matches=0 THEN
  IF EXISTS(SELECT 1 FROM system_menu WHERE deleted=0 AND (permission='dataforge:batch-file:query' OR (parent_id=5987 AND path='batch-file'))) THEN
   RAISE EXCEPTION '0192 批量文件菜单身份冲突';
  END IF;
  SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO page_id FROM system_menu;
  INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
  VALUES(page_id,'批量文件入库','dataforge:batch-file:query',2,4,5987,'batch-file','carbon:document-import','dataforge/batchfile/index','DataforgeBatchFile',0,true,true,true,'migration:0192','migration:0192',0);
 ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=page_id AND parent_id=5987 AND path='batch-file' AND permission='dataforge:batch-file:query' AND type=2) THEN
  RAISE EXCEPTION '0192 批量文件菜单归属冲突';
 END IF;
 FOR item IN SELECT * FROM (VALUES ('create','新增及上传',1),('update','配置及上下线',2),('delete','删除任务',3),('execute','执行及异常核对',4)) permissions(code,label,sort) LOOP
  SELECT count(*) INTO matches FROM system_menu WHERE permission='dataforge:batch-file:'||item.code AND deleted=0;
  IF matches=0 THEN
   SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO new_id FROM system_menu;
   INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
   VALUES(new_id,item.label,'dataforge:batch-file:'||item.code,3,item.sort,page_id,'',0,true,true,true,'migration:0192','migration:0192',0);
  ELSIF matches<>1 OR NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='dataforge:batch-file:'||item.code AND parent_id=page_id AND type=3 AND deleted=0) THEN
   RAISE EXCEPTION '0192 批量文件权限归属冲突';
  END IF;
 END LOOP;
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
 PERFORM setval('system_menu_seq',high_id); PERFORM setval('system_menu_id_seq',high_id);
 -- 仅补齐超级管理员授权，其他角色由管理员按职责分配。
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater,deleted)
 SELECT high_id+row_number() OVER(ORDER BY r.id,r.tenant_id,m.id),r.id,m.id,r.tenant_id,'migration:0192','migration:0192',0
 FROM system_role r CROSS JOIN system_menu m
 WHERE r.code='super_admin' AND r.deleted=0 AND m.deleted=0 AND (m.id=page_id OR m.parent_id=page_id)
 AND NOT EXISTS(SELECT 1 FROM system_role_menu old WHERE old.role_id=r.id AND old.tenant_id=r.tenant_id AND old.menu_id=m.id AND old.deleted=0);
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 PERFORM setval('system_role_menu_seq',high_id);
END $menus$;
COMMIT;
