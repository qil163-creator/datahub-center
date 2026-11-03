-- Migration: 0255 作业管理与整库同步、作业运维实例
-- Purpose: 新增平台共享作业编排、受管调度状态、可追踪执行记录和显式演示历史数据，补齐两类运维菜单。
-- Depends on: 0254_dataforge_offline-realtime-task-menus.sql；0217_dataforge_operations-instances.sql；0237_dataforge_full-sync-wizard.sql。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；结构按存在性补齐，演示数据按业务唯一名和请求编号首次插入，菜单按组件校验。
-- Rollback: 先确认没有活动 DS 作业或调度，再移除 migration:0255 菜单与新表；删除新表会丢失作业配置及历史记录，不自动删除远端 DS 定义。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='120s';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_job_category_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_job_category(id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_job_category_seq'),
 parent_id bigint NOT NULL DEFAULT 0,name varchar(100) NOT NULL,sort integer NOT NULL DEFAULT 0,
 creator varchar(64) NOT NULL DEFAULT '',create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '',update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0);
ALTER SEQUENCE dh_dataforge_job_category_seq OWNED BY dh_dataforge_job_category.id;
COMMENT ON TABLE dh_dataforge_job_category IS '作业类目';
COMMENT ON COLUMN dh_dataforge_job_category.id IS '记录编号';
COMMENT ON COLUMN dh_dataforge_job_category.parent_id IS '父类目编号，零表示根类目';
COMMENT ON COLUMN dh_dataforge_job_category.name IS '名称';
COMMENT ON COLUMN dh_dataforge_job_category.sort IS '排序';
COMMENT ON COLUMN dh_dataforge_job_category.creator IS '创建人';
COMMENT ON COLUMN dh_dataforge_job_category.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_job_category.updater IS '更新人';
COMMENT ON COLUMN dh_dataforge_job_category.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_job_category.deleted IS '逻辑删除标记';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_job_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_job(id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_job_seq'),
 revision integer NOT NULL DEFAULT 0,category_id bigint NOT NULL REFERENCES dh_dataforge_job_category(id),
 name varchar(200) NOT NULL,owner_id bigint,owner_name varchar(100),owner_phone varchar(50),
 status smallint NOT NULL DEFAULT 0 CHECK(status IN(0,1)),schedule_status smallint NOT NULL DEFAULT 0 CHECK(schedule_status IN(0,1)),
 strategy varchar(32) NOT NULL DEFAULT 'PARALLEL' CHECK(strategy IN('PARALLEL','SERIAL_WAIT','SERIAL_DISCARD','SERIAL_PRIORITY')),
 cron_expression varchar(128),description varchar(2000),flow_json jsonb NOT NULL DEFAULT '{"nodes":[],"edges":[]}'::jsonb CHECK(jsonb_typeof(flow_json)='object'),
 ds_project_code bigint,ds_definition_code bigint,ds_schedule_id integer,
 operation_state varchar(16) NOT NULL DEFAULT 'IDLE' CHECK(operation_state IN('IDLE','BUSY')),
 creator varchar(64) NOT NULL DEFAULT '',create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '',update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0);
ALTER SEQUENCE dh_dataforge_job_seq OWNED BY dh_dataforge_job.id;
COMMENT ON TABLE dh_dataforge_job IS '作业编排配置与受管调度控制状态';
COMMENT ON COLUMN dh_dataforge_job.id IS '记录编号';
COMMENT ON COLUMN dh_dataforge_job.revision IS '乐观锁修订号';
COMMENT ON COLUMN dh_dataforge_job.category_id IS '作业类目编号';
COMMENT ON COLUMN dh_dataforge_job.name IS '名称';
COMMENT ON COLUMN dh_dataforge_job.owner_id IS '负责人编号';
COMMENT ON COLUMN dh_dataforge_job.owner_name IS '负责人姓名';
COMMENT ON COLUMN dh_dataforge_job.owner_phone IS '负责人联系方式';
COMMENT ON COLUMN dh_dataforge_job.status IS '配置上线状态或实例执行状态';
COMMENT ON COLUMN dh_dataforge_job.schedule_status IS '受管调度开关';
COMMENT ON COLUMN dh_dataforge_job.strategy IS '执行策略';
COMMENT ON COLUMN dh_dataforge_job.cron_expression IS '上海时区 Quartz 调度表达式';
COMMENT ON COLUMN dh_dataforge_job.description IS '业务描述，演示记录明确注明来源';
COMMENT ON COLUMN dh_dataforge_job.flow_json IS '不包含凭据的作业节点和连线';
COMMENT ON COLUMN dh_dataforge_job.ds_project_code IS '受管调度器项目编号';
COMMENT ON COLUMN dh_dataforge_job.ds_definition_code IS '受管工作流定义编号';
COMMENT ON COLUMN dh_dataforge_job.ds_schedule_id IS '受管周期编号';
COMMENT ON COLUMN dh_dataforge_job.operation_state IS '远端控制操作占用状态；异常中断需管理员核对远端后恢复';
COMMENT ON COLUMN dh_dataforge_job.creator IS '创建人';
COMMENT ON COLUMN dh_dataforge_job.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_job.updater IS '更新人';
COMMENT ON COLUMN dh_dataforge_job.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_job.deleted IS '逻辑删除标记';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_job_record_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_job_record(id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_job_record_seq'),
 job_id bigint NOT NULL REFERENCES dh_dataforge_job(id),origin varchar(16) NOT NULL DEFAULT 'PLATFORM' CHECK(origin IN('SOURCE','PLATFORM')),
 request_id varchar(64),instance_name varchar(512) NOT NULL,status varchar(16) NOT NULL CHECK(status IN('WAITING','RUNNING','SUCCESS','FAILURE','STOPPED','UNKNOWN','IDLE')),
 execution_type varchar(16) NOT NULL DEFAULT 'START',ds_project_code bigint,ds_instance_id integer,
 start_time timestamp,end_time timestamp,duration bigint,log_content text,flow_json jsonb,children_json jsonb NOT NULL DEFAULT '[]'::jsonb,
 creator varchar(64) NOT NULL DEFAULT '',create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '',update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0);
ALTER SEQUENCE dh_dataforge_job_record_seq OWNED BY dh_dataforge_job_record.id;
COMMENT ON TABLE dh_dataforge_job_record IS '作业执行实例，SOURCE 为演示历史，PLATFORM 为真实提交';
COMMENT ON COLUMN dh_dataforge_job_record.id IS '记录编号';
COMMENT ON COLUMN dh_dataforge_job_record.job_id IS '所属作业编号';
COMMENT ON COLUMN dh_dataforge_job_record.origin IS 'SOURCE 演示历史或 PLATFORM 真实运行';
COMMENT ON COLUMN dh_dataforge_job_record.request_id IS '运行幂等请求编号';
COMMENT ON COLUMN dh_dataforge_job_record.instance_name IS '实例名称';
COMMENT ON COLUMN dh_dataforge_job_record.status IS '配置上线状态或实例执行状态';
COMMENT ON COLUMN dh_dataforge_job_record.execution_type IS '启动或停止类型';
COMMENT ON COLUMN dh_dataforge_job_record.ds_project_code IS '受管调度器项目编号';
COMMENT ON COLUMN dh_dataforge_job_record.ds_instance_id IS '受管调度器实例编号';
COMMENT ON COLUMN dh_dataforge_job_record.start_time IS '执行开始时间';
COMMENT ON COLUMN dh_dataforge_job_record.end_time IS '执行结束时间';
COMMENT ON COLUMN dh_dataforge_job_record.duration IS '执行时长，单位秒';
COMMENT ON COLUMN dh_dataforge_job_record.log_content IS '不含凭据的执行日志摘要';
COMMENT ON COLUMN dh_dataforge_job_record.flow_json IS '不包含凭据的作业节点和连线';
COMMENT ON COLUMN dh_dataforge_job_record.children_json IS '历史任务子实例，演示数据不表示真实运行结果';
COMMENT ON COLUMN dh_dataforge_job_record.creator IS '创建人';
COMMENT ON COLUMN dh_dataforge_job_record.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_job_record.updater IS '更新人';
COMMENT ON COLUMN dh_dataforge_job_record.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_job_record.deleted IS '逻辑删除标记';

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_ops_aux_snapshot_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_ops_aux_snapshot(id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_ops_aux_snapshot_seq'),
 kind varchar(16) NOT NULL CHECK(kind='FULL_SYNC'),task_id bigint NOT NULL,category_id bigint,
 task_name varchar(200) NOT NULL,instance_name varchar(512) NOT NULL,status varchar(16) NOT NULL,
 start_time timestamp,end_time timestamp,duration bigint,owner_name varchar(100),description varchar(2000),log_content text,flow_json jsonb,
 creator varchar(64) NOT NULL DEFAULT '',create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '',update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0);
ALTER SEQUENCE dh_dataforge_ops_aux_snapshot_seq OWNED BY dh_dataforge_ops_aux_snapshot.id;
COMMENT ON TABLE dh_dataforge_ops_aux_snapshot IS '整库同步演示历史快照，不参与平台执行状态机';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.id IS '记录编号';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.kind IS '运维页面种类';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.task_id IS '历史任务编号';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.category_id IS '作业类目编号';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.task_name IS '任务名称';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.instance_name IS '实例名称';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.status IS '配置上线状态或实例执行状态';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.start_time IS '执行开始时间';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.end_time IS '执行结束时间';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.duration IS '执行时长，单位秒';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.owner_name IS '负责人姓名';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.description IS '业务描述，演示记录明确注明来源';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.log_content IS '不含凭据的执行日志摘要';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.flow_json IS '不包含凭据的作业节点和连线';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.creator IS '创建人';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.create_time IS '创建时间';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.updater IS '更新人';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.update_time IS '更新时间';
COMMENT ON COLUMN dh_dataforge_ops_aux_snapshot.deleted IS '逻辑删除标记';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_dataforge_job_name ON dh_dataforge_job(name) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_job_category ON dh_dataforge_job(category_id) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_dataforge_job_request ON dh_dataforge_job_record(request_id) WHERE deleted=0 AND request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_dh_dataforge_job_record_task ON dh_dataforge_job_record(job_id,start_time DESC,id DESC) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_dataforge_job_ds_instance ON dh_dataforge_job_record(ds_project_code,ds_instance_id) WHERE deleted=0 AND ds_instance_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_dataforge_ops_aux_demo ON dh_dataforge_ops_aux_snapshot(kind,instance_name) WHERE deleted=0;

DO $demo$
DECLARE label text; category_id bigint; job_id bigint; task record; graph jsonb; nodes jsonb; edges jsonb; children jsonb; rownum integer:=0; instance_time timestamp;
BEGIN
 FOR label IN SELECT unnest(ARRAY['河道水情业务','灌区管理业务','防洪减灾业务','农村供水业务','水土保持业务','水资源管理业务','水利工程管理业务','生态流量监管业务']) LOOP
  IF NOT EXISTS(SELECT 1 FROM dh_dataforge_job_category c WHERE c.name=label AND c.deleted=0) THEN
   INSERT INTO dh_dataforge_job_category(parent_id,name,sort,creator,updater) VALUES(0,label,rownum,'migration:0255','migration:0255');
  END IF;
  rownum:=rownum+1;
 END LOOP;
 rownum:=0;
 FOR label IN SELECT unnest(ARRAY['河道水情_月水位统计数据产品_每日作业','灌区管理_用水量汇总_每日作业','防洪减灾_雨水情监测_每日作业']) LOOP
  rownum:=rownum+1;
  SELECT c.id INTO category_id FROM dh_dataforge_job_category c WHERE c.deleted=0 ORDER BY c.sort,c.id OFFSET rownum-1 LIMIT 1;
  nodes:='[]'::jsonb;edges:='[]'::jsonb;children:='[]'::jsonb;
  FOR task IN SELECT id,name,'INTEGRATION'::text kind FROM dh_dataforge_integration_task WHERE deleted=0 ORDER BY id LIMIT 2 LOOP
   nodes:=nodes||jsonb_build_array(jsonb_build_object('id','task_'||task.id,'kind',task.kind,'taskId',task.id::text,'name',task.name,'x',100+jsonb_array_length(nodes)*300,'y',170));
   IF jsonb_array_length(nodes)>1 THEN edges:=edges||jsonb_build_array(jsonb_build_object('source',nodes->0->>'id','target','task_'||task.id));END IF;
   children:=children||jsonb_build_array(jsonb_build_object('id',task.id::text,'kind',task.kind,'origin','SOURCE','taskId',task.id::text,'taskName',task.name,'instanceName',task.name||'-演示历史','status','SUCCESS','executionType','START','startTime','2026-10-31 02:00:00','endTime','2026-10-31 02:00:12','duration',12,'description','演示历史数据，仅用于页面查看，不代表本平台执行'));
  END LOOP;
  graph:=jsonb_build_object('nodes',nodes,'edges',edges);
  IF NOT EXISTS(SELECT 1 FROM dh_dataforge_job j WHERE j.name=label AND j.deleted=0) THEN
   INSERT INTO dh_dataforge_job(category_id,name,strategy,cron_expression,description,flow_json,creator,updater)
   VALUES(category_id,label,CASE WHEN rownum=1 THEN 'SERIAL_WAIT' ELSE 'PARALLEL' END,'0 0 2 * * ?','演示作业，可编辑配置。历史实例为测试数据，不表示本平台真实执行。',graph,'migration:0255','migration:0255');
  END IF;
  SELECT j.id INTO job_id FROM dh_dataforge_job j WHERE j.name=label AND j.deleted=0;
  instance_time:='2026-10-31 02:00:00'::timestamp+make_interval(mins=>rownum);
  IF NOT EXISTS(SELECT 1 FROM dh_dataforge_job_record WHERE request_id='demo0255_job_'||rownum) THEN
   INSERT INTO dh_dataforge_job_record(job_id,origin,request_id,instance_name,status,start_time,end_time,duration,log_content,flow_json,children_json,creator,updater)
   VALUES(job_id,'SOURCE','demo0255_job_'||rownum,label||'-1-20261031020000',CASE WHEN rownum=3 THEN 'FAILURE' ELSE 'SUCCESS' END,instance_time,instance_time+interval '12 seconds',12,'演示历史日志：仅用于页面与弹窗查看，不代表本平台执行。',graph,children,'migration:0255','migration:0255');
  END IF;
  INSERT INTO dh_dataforge_ops_aux_snapshot(kind,task_id,category_id,task_name,instance_name,status,start_time,end_time,duration,description,log_content,creator,updater)
  VALUES('FULL_SYNC',job_id,category_id,CASE rownum WHEN 1 THEN '基础业务数据整库同步' WHEN 2 THEN '水利监测数据整库同步' ELSE '业务档案整库同步' END,'整库同步-演示历史-'||rownum,CASE WHEN rownum=3 THEN 'FAILURE' ELSE 'SUCCESS' END,instance_time,instance_time+interval '42 seconds',42,'演示历史数据，仅供页面查看，不表示本平台同步成功。','演示日志：已准备页面展示所需测试数据，未连接源库或目标库。','migration:0255','migration:0255')
  ON CONFLICT(kind,instance_name) WHERE deleted=0 DO NOTHING;
 END LOOP;
END $demo$;

LOCK TABLE system_menu,system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE root_id bigint; ops_id bigint; job_id bigint; page_id bigint; new_id bigint; high_id bigint; entry record;
BEGIN
 SELECT id INTO root_id FROM system_menu WHERE deleted=0 AND trim(both '/' from path)='develop' AND parent_id=0;
 IF root_id IS NULL THEN RAISE EXCEPTION '0255 数据研发父菜单缺失'; END IF;
 SELECT id INTO ops_id FROM system_menu WHERE parent_id=root_id AND path='operations' AND deleted=0;
 IF ops_id IS NULL THEN RAISE EXCEPTION '0255 既有运维管理菜单缺失，请先执行0217';END IF;
 FOR entry IN SELECT * FROM (VALUES
 ('作业管理','job','dataforge/job/index','DataforgeJob',root_id,4,'dataforge:job:query'),
 ('整库同步实例','full-sync','dataforge/ops/fullsync/index','DataforgeOpsFullSync',ops_id,3,'dataforge:ops-instance:query'),
 ('作业管理实例','job','dataforge/ops/job/index','DataforgeOpsJob',ops_id,4,'dataforge:ops-instance:query')
 ) e(label,path,component,component_name,parent,sort,permission) LOOP
  SELECT id INTO page_id FROM system_menu WHERE component=entry.component AND deleted=0;
  IF page_id IS NULL THEN
   IF EXISTS(SELECT 1 FROM system_menu WHERE parent_id=entry.parent AND path=entry.path AND deleted=0) THEN RAISE EXCEPTION '0255 菜单路径冲突：%',entry.path; END IF;
   SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO page_id FROM system_menu;
   INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
   VALUES(page_id,entry.label,entry.permission,2,entry.sort,entry.parent,entry.path,'carbon:task',entry.component,entry.component_name,0,true,true,true,'migration:0255','migration:0255',0);
  ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=page_id AND parent_id=entry.parent AND path=entry.path AND permission=entry.permission) THEN RAISE EXCEPTION '0255 菜单组件身份冲突：%',entry.component;END IF;
  IF entry.component='dataforge/job/index' THEN job_id:=page_id;END IF;
 END LOOP;
 FOR entry IN SELECT * FROM (VALUES('新增作业','create'),('编辑作业与配置','update'),('删除作业','delete'),('运行与停止作业','run')) e(label,code) LOOP
  IF NOT EXISTS(SELECT 1 FROM system_menu WHERE parent_id=job_id AND permission='dataforge:job:'||entry.code AND deleted=0) THEN
   SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO new_id FROM system_menu;
   INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
   VALUES(new_id,entry.label,'dataforge:job:'||entry.code,3,1,job_id,'',0,true,true,true,'migration:0255','migration:0255',0);
  END IF;
 END LOOP;
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
 PERFORM setval('system_menu_seq',high_id);PERFORM setval('system_menu_id_seq',high_id);
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater,deleted)
 SELECT high_id+row_number() OVER(ORDER BY r.id,r.tenant_id,m.id),r.id,m.id,r.tenant_id,'migration:0255','migration:0255',0 FROM system_role r CROSS JOIN system_menu m
 WHERE r.code='super_admin' AND r.deleted=0 AND m.deleted=0 AND m.creator='migration:0255'
 AND NOT EXISTS(SELECT 1 FROM system_role_menu old WHERE old.role_id=r.id AND old.tenant_id=r.tenant_id AND old.menu_id=m.id AND old.deleted=0);
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;PERFORM setval('system_role_menu_seq',high_id);
END $menus$;
COMMIT;
