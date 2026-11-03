-- Migration: 0217 运维管理数据集成与数据开发实例。
-- Purpose: 复用集成实例事实，补齐只读流程和脱敏日志，迁入24条开发实例并建立运维菜单及权限。
-- Depends on: 0216_dataforge_project-categories.sql；0215_dataforge_integration-workbench.sql 已包含59条集成实例及集成任务。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的全量基线。
-- Repeatable: 是；结构按存在性补齐，来源实例按主键首次迁入，详情只补充尚未标记来源时间的记录，重放保留后续平台修改。
-- Rollback: 事务失败全部回滚，序列可能前移；提交后可删除本迁移创建的运维菜单、开发只读快照表和集成扩展列，移除菜单前应撤回角色授权。
-- 迁入的运行中状态仅代表源系统采集快照，不触发本平台执行或停止。日志已逐行清理连接身份、密码、连接串及私网地址。
-- 集成列扩展短时锁表，菜单排序只在目录首次创建时调整；锁等待超过十秒即失败。不在共享或生产数据库自动执行。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_dataforge_integration_record
 ADD COLUMN IF NOT EXISTS owner_name varchar(64),
 ADD COLUMN IF NOT EXISTS creator_name varchar(64),
 ADD COLUMN IF NOT EXISTS description varchar(256),
 ADD COLUMN IF NOT EXISTS source_create_time timestamp,
 ADD COLUMN IF NOT EXISTS source_update_time timestamp,
 ADD COLUMN IF NOT EXISTS flow_json jsonb,
 ADD COLUMN IF NOT EXISTS log_content text;
COMMENT ON COLUMN dh_dataforge_integration_record.owner_name IS '源实例负责人显示姓名，不作为执行身份';
COMMENT ON COLUMN dh_dataforge_integration_record.creator_name IS '源实例创建人显示姓名';
COMMENT ON COLUMN dh_dataforge_integration_record.description IS '源实例描述';
COMMENT ON COLUMN dh_dataforge_integration_record.source_create_time IS '源实例创建时间，保持源页面本地日期时间';
COMMENT ON COLUMN dh_dataforge_integration_record.source_update_time IS '源实例更新时间，保持源页面本地日期时间';
COMMENT ON COLUMN dh_dataforge_integration_record.flow_json IS '只读节点和连线快照，不包含参数或连接信息';
COMMENT ON COLUMN dh_dataforge_integration_record.log_content IS '已脱敏且不超过一百万字符的源实例日志';

CREATE TABLE IF NOT EXISTS dh_dataforge_ops_development_snapshot (
 id bigint PRIMARY KEY,
 task_id bigint NOT NULL,
 category_id bigint NOT NULL,
 task_name varchar(256) NOT NULL,
 instance_name varchar(512) NOT NULL,
 task_type varchar(16) NOT NULL CHECK(task_type IN ('REALTIME','OFFLINE')),
 execution_type varchar(16) NOT NULL CHECK(execution_type IN ('START','STOP')),
 status varchar(16) NOT NULL CHECK(status IN ('WAITING','RUNNING','SUCCESS','FAILURE','IDLE','STOPPED')),
 start_time timestamp,
 end_time timestamp,
 duration bigint CHECK(duration >= 0),
 owner_name varchar(64),
 creator_name varchar(64),
 description varchar(256),
 log_content text CHECK(char_length(log_content) <= 1000000),
 creator varchar(64) NOT NULL DEFAULT '',
 create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '',
 update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1))
);
COMMENT ON TABLE dh_dataforge_ops_development_snapshot IS '运维管理源系统数据开发实例只读快照，不参与本平台执行状态机';
DO $comments$
DECLARE item record;
BEGIN
 FOR item IN SELECT * FROM (VALUES
 ('id','源实例编号，与平台运行实例通过来源区分'),('task_id','源任务编号，仅用于快照关联'),
 ('category_id','已由0216迁入的本平台开发目录编号'),('task_name','源任务名称'),('instance_name','源任务实例完整名称'),
 ('task_type','任务模式：实时或离线'),('execution_type','执行类型：启动任务流或停止'),('status','源实例执行状态'),
 ('start_time','源实例开始时间'),('end_time','源实例结束时间'),('duration','源实例采集时运行时长，单位秒'),
 ('owner_name','负责人显示姓名'),('creator_name','创建人显示姓名'),('description','源实例描述'),
 ('log_content','已脱敏的源日志文本，不超过一百万字符'),('creator','平台导入审计标识'),('create_time','源实例创建时间'),
 ('updater','平台导入审计标识'),('update_time','源实例更新时间'),('deleted','逻辑删除标记')
 ) descriptions(column_name,description) LOOP
  EXECUTE format('COMMENT ON COLUMN dh_dataforge_ops_development_snapshot.%I IS %L',item.column_name,item.description);
 END LOOP;
END $comments$;
CREATE INDEX IF NOT EXISTS idx_dataforge_ops_dev_start ON dh_dataforge_ops_development_snapshot(start_time DESC,id DESC) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_dataforge_ops_dev_category ON dh_dataforge_ops_development_snapshot(category_id,status,start_time DESC) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_dataforge_integration_record_start ON dh_dataforge_integration_record(start_time DESC,id DESC) WHERE deleted=0;

-- 完整迁入已核实的24条开发实例，源任务和平台任务的编号空间保持分离。
INSERT INTO dh_dataforge_ops_development_snapshot(id,task_id,category_id,task_name,instance_name,task_type,execution_type,status,start_time,end_time,duration,owner_name,creator_name,description,log_content,creator,updater,create_time,update_time) VALUES
 (1239,32,14,'河道水情_ODS到DWD_实时流量明细_实时【外部表->DWD】','河道水情_ODS到DWD_实时流量明细_实时【外部表->DWD】-10-20261022133914008','REALTIME','START','RUNNING','2026-10-22 13:39:14',NULL,29106,'吴同','吴同',NULL,'','migration:0217','migration:0217','2026-10-22 13:39:14.023000','2026-10-22 21:44:19.177000'),
 (1237,31,8,'河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】','河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】-4-20261022133850921','OFFLINE','START','RUNNING','2026-10-22 13:38:50',NULL,29130,'吴同','吴同',NULL,'','migration:0217','migration:0217','2026-10-22 13:38:50.938000','2026-10-22 21:44:19.189000'),
 (1232,31,8,'河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】','河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】-4-20261022091217440','OFFLINE','STOP','STOPPED','2026-10-22 09:12:17','2026-10-22 09:21:48',571,'吴同','吴同',NULL,'','migration:0217','migration:0217','2026-10-22 09:12:17.457000','2026-10-22 09:21:48.532000'),
 (869,32,14,'河道水情_ODS到DWD_实时流量明细_实时【外部表->DWD】','河道水情_ODS到DWD_实时流量明细_实时【外部表->DWD】-10-20260827203205651','REALTIME','STOP','STOPPED','2026-08-27 20:32:05','2026-08-27 20:32:19',14,'吴同','吴同',NULL,'','migration:0217','migration:0217','2026-08-27 20:32:05.659000','2026-08-27 20:32:19.653000'),
 (868,33,7,'河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】','河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】-25-20260827203200634','REALTIME','STOP','STOPPED','2026-08-27 20:32:00','2026-08-27 20:32:21',21,'吴同','吴同',NULL,'[2026-08-27 20:32:00.658] INFO
***********************************************************************************************[INFO] 2026-08-27 20:32:00.662 - *********************************  Initialize task context  ***********************************
[2026-08-27 20:32:00.663] INFO ***********************************************************************************************
[2026-08-27 20:32:00.663] INFO Begin to initialize task
[2026-08-27 20:32:00.664] INFO Set task startTime: 1787833920664
[2026-08-27 20:32:00.665] INFO Set task appId: 868_876
[2026-08-27 20:32:00.666] INFO End initialize task {
  "taskInstanceId" : 876,
  "taskName" : "河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】",
  "firstSubmitTime" : 1787833920651,
  "startTime" : 1787833920664,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260827/180629079832128/25/868/876.log",
  "processId" : 0,
  "processDefineCode" : 180629079832128,
  "processDefineVersion" : 25,
  "processInstanceId" : 868,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "868"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260827"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260826"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "876"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260827203200"
    }
  },
  "taskAppId" : "868_876",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-27 20:32:00.668] INFO
***********************************************************************************************[INFO] 2026-08-27 20:32:00.668 - *********************************  Load task instance plugin  *********************************
[2026-08-27 20:32:00.669] INFO ***********************************************************************************************
[2026-08-27 20:32:00.672] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-27 20:32:00.673] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-27 20:32:00.674] INFO TenantCode: root check successfully
[2026-08-27 20:32:00.677] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-27 20:32:00.677] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-27 20:32:00.678 - Download upstream files: [] successfully
[2026-08-27 20:32:00.679] INFO Task plugin instance: FLINK create successfully
[2026-08-27 20:32:00.680] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "standalone",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "SET ''execution.runtime-mode'' = ''streaming'';",
[连接身份与敏感参数已脱敏]
  "executeMode" : "STREAM"
}
[2026-08-27 20:32:00.682] INFO Writing content: set parallelism.default=1;
SET ''execution.runtime-mode'' = ''streaming'';[INFO] 2026-08-27 20:32:00.683 - To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_25/868/876/868_876_init.sql
[2026-08-27 20:32:00.685] INFO Writing content: -- =====================================================
-- 1. 开启 Checkpoint
-- MySQL CDC 仍然需要 Checkpoint 保存 Binlog 位点
-- =====================================================
SET ''execution.checkpointing.interval'' = ''10s'';
-- =====================================================
-- 2. 创建 MySQL CDC 源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_Q_R;
CREATE TABLE MYSQL_ST_Q_R (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''mysql-cdc'',
    ''hostname'' = ''mysql57'',
    ''port'' = ''3306'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''database-name'' = ''RIVER_WATER'',
    ''table-name'' = ''st_q_r'',
        -- 只读取任务启动后产生的 Binlog
    --''scan.startup.mode'' = ''latest-offset'',
    ''debezium.snapshot.mode''=''latest-offset'',
    -- 不读取历史快照，因此不需要 chunk key
    ''scan.incremental.snapshot.enabled'' = ''true''
);
-- =====================================================
-- 3. 创建 Doris 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''ODS.ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''sink.label-prefix'' = ''mysql_cdc_st_q_r_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.enable-delete'' = ''true'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
        -- Doris 2.1 不支持压缩 JSON，必须关闭压缩
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 4. 启动同步
-- =====================================================
INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R;
[2026-08-27 20:32:00.686] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_25/868/876/868_876_node.sql
[2026-08-27 20:32:00.687] INFO Success initialized task plugin instance successfully
[2026-08-27 20:32:00.688] INFO Set taskVarPool: null successfully
[2026-08-27 20:32:00.689] INFO
***********************************************************************************************[INFO] 2026-08-27 20:32:00.689 - *********************************  Execute task instance  *************************************
[2026-08-27 20:32:00.690] INFO ***********************************************************************************************
[2026-08-27 20:32:00.691] INFO Final Shell file is:
[2026-08-27 20:32:00.692] INFO ****************************** Script Content *****************************************************************
[2026-08-27 20:32:00.692] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_25/868/876/868_876_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_25/868/876/868_876_node.sql
[2026-08-27 20:32:00.693] INFO ****************************** Script Content *****************************************************************
[2026-08-27 20:32:00.694] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_25/868/876/868_876.sh
[2026-08-27 20:32:00.697] INFO processInstanceId:868,taskInstanceId:876
[2026-08-27 20:32:00.697] INFO process start, process id is: 1549
[2026-08-27 20:32:02.699] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_25/868/876/868_876_init.sql
	Aug 27, 2026 8:32:02 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     ''connector'' = ''mysql-cdc'',
	>     ''hostname'' = ''mysql57'',
	>     ''port'' = ''3306'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>     ''database-name'' = ''RIVER_WATER'',
	>     ''table-name'' = ''st_q_r'',
	>         -- 只读取任务启动后产生的 Binlog
	>     --''scan.startup.mode'' = ''latest-offset'',
	>     ''debezium.snapshot.mode''=''latest-offset'',
	>     -- 不读取历史快照，因此不需要 chunk key
	>     ''scan.incremental.snapshot.enabled'' = ''true''
	>
[2026-08-27 20:32:03.700] INFO ->
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	>
	> ) WITH (
	>     ''connector'' = ''doris'',
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>     ''table.identifier'' = ''ODS.ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''mysql_cdc_st_q_r_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>     ''sink.enable-delete'' = ''true'',
	>
	>     ''sink.properties.format'' = ''json'',
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>         -- Doris 2.1 不支持压缩 JSON，必须关闭压缩
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-27 20:32:05.702] INFO ->
	Flink SQL> [34;1m[INFO] Submitting SQL update statement to the cluster...[0m
	[34;1m[INFO] SQL update statement has been successfully submitted to the cluster:[0m
	Job ID: ff311dbf239b21b825ef1b05450faca1
[2026-08-27 20:32:06.704] INFO ->
	Flink SQL>
	Shutting down the session...
	done.
[2026-08-27 20:32:06.705] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_25/868/876, processId:1549 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-27 20:32:06.707] INFO Start finding appId in /opt/dolphinscheduler/logs/20260827/180629079832128/25/868/876.log, fetch way: log
[2026-08-27 20:32:06.709] INFO
***********************************************************************************************[INFO] 2026-08-27 20:32:06.710 - *********************************  Finalize task instance  ************************************
[2026-08-27 20:32:06.711] INFO ***********************************************************************************************
[2026-08-27 20:32:06.712] INFO Task exit status is non-terminal: TaskExecutionStatus{code=1, desc=''running''}, will keep WorkerTaskExecutor alive
[2026-08-27 20:32:06.713] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-27 20:32:00.645000','2026-08-27 20:32:21.863000'),
 (867,28,20,'河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】','河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】-5-20260827203057529','OFFLINE','START','SUCCESS','2026-08-27 20:30:57','2026-08-27 20:31:05',8,'吴同','吴同',NULL,'[2026-08-27 20:30:57.546] INFO
***********************************************************************************************[INFO] 2026-08-27 20:30:57.548 - *********************************  Initialize task context  ***********************************
[2026-08-27 20:30:57.550] INFO Set task startTime: 1787833857550
[2026-08-27 20:30:57.552] INFO End initialize task {
  "taskInstanceId" : 875,
  "taskName" : "河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】",
  "firstSubmitTime" : 1787833857539,
  "startTime" : 1787833857550,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260827/180628671770176/5/867/875.log",
  "processId" : 0,
  "processDefineCode" : 180628671770176,
  "processDefineVersion" : 5,
  "processInstanceId" : 867,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "867"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260827"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260826"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "875"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628671770176"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628671770176"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260827203057"
    }
  },
  "taskAppId" : "867_875",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-27 20:30:57.556] INFO ***********************************************************************************************
[2026-08-27 20:30:57.560] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-27 20:30:57.560] INFO TenantCode: root check successfully
[2026-08-27 20:30:57.566] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-27 20:30:57.567] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-27 20:30:57.568 - Download upstream files: [] successfully
[2026-08-27 20:30:57.568] INFO Task plugin instance: FLINK create successfully
[2026-08-27 20:30:57.569] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "standalone",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "SET execution.runtime-mode = batch;",
[连接身份与敏感参数已脱敏]
  "executeMode" : "BATCH"
}
[2026-08-27 20:30:57.572] INFO Writing content: set parallelism.default=1;
SET execution.runtime-mode = batch;[INFO] 2026-08-27 20:30:57.573 - To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_5/867/875/867_875_init.sql
[2026-08-27 20:30:57.575] INFO Writing content: -- =====================================================
-- 河道水情_DWD到DWS_多年平均水位
-- 多年平均口径汇总_每日
-- =====================================================
-- =====================================================
-- 1. Flink 批处理基础配置
-- =====================================================
-- 使用批处理模式
SET ''execution.runtime-mode'' = ''batch'';
-- 等待批处理任务执行完成后再退出
SET ''table.dml-sync'' = ''true'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- =====================================================
-- 2. 创建 Doris 日水位统计源表
-- =====================================================
DROP TABLE IF EXISTS DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE;
CREATE TABLE DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE (
    `ID` BIGINT,
    `OBJTP` STRING,
    `OBJCD` STRING,
    `OBJNM` STRING,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `STATDT` DATE,
    `STTPRD` STRING,
    `BGNTM` TIMESTAMP(0),
    `ENDTM` TIMESTAMP(0),
    `AVGZ` DECIMAL(15, 6),
    `MAXZ` DECIMAL(10, 3),
    `MINZ` DECIMAL(10, 3),
    `ZRR` DECIMAL(10, 3)
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''DWS.DWS_RH_HIST_ST_Z_DAY_S'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
);
-- =====================================================
-- 3. 创建 Doris 多年平均水位目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_DWS_RH_STAT_ST_Z_MYAVG_S;
CREATE TABLE DORIS_DWS_RH_STAT_ST_Z_MYAVG_S (
    `ID` BIGINT,
    `OBJTP` STRING,
    `OBJCD` STRING,
    `OBJNM` STRING,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `STATDT` DATE,
    `STTPRD` STRING,
    `BGNTM` TIMESTAMP(0),
    `ENDTM` TIMESTAMP(0),
    `AVGZ` DECIMAL(10, 3),
    `MAXZ` DECIMAL(10, 3),
    `MINZ` DECIMAL(10, 3),
    `ZRR` DECIMAL(10, 3)
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''DWS.DWS_RH_STAT_ST_Z_MYAVG_S'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''sink.label-prefix'' = ''doris_batch_st_z_myavg_s_dws_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
    -- Doris 2.1 不支持压缩 JSON
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 4. 计算多年平均水位和历史极值
-- 汇总维度：测站编码 + 月日
-- =====================================================
INSERT INTO DORIS_DWS_RH_STAT_ST_Z_MYAVG_S
SELECT
    -- 使用当前分组中最小的原始记录ID作为汇总记录ID
    MIN(`ID`) AS `ID`,
    -- 同一测站的基础属性原则上保持一致
    MAX(`OBJTP`) AS `OBJTP`,
    MAX(`OBJCD`) AS `OBJCD`,
    MAX(`OBJNM`) AS `OBJNM`,
    MAX(`RVCD`) AS `RVCD`,
    MAX(`RVNM`) AS `RVNM`,
    MAX(`RSCD`) AS `RSCD`,
    MAX(`RSNM`) AS `RSNM`,
    MAX(`SECCD`) AS `SECCD`,
    MAX(`SECNM`) AS `SECNM`,
    `STCD`,
    MAX(`STNM`) AS `STNM`,
    -- 使用2000年承载月日，兼容2月29日
    CAST(CONCAT(''2000-'', `MONTH_DAY`) AS DATE) AS `STATDT`,
    -- 多年平均统计周期
    ''MYAVG'' AS `STTPRD`,
    -- 历史数据覆盖的最早开始时间
    MIN(`BGNTM`) AS `BGNTM`,
    -- 历史数据覆盖的最晚结束时间
    MAX(`ENDTM`) AS `ENDTM`,
    -- 不同年份同一月日的平均水位
    CAST(AVG(`AVGZ`) AS DECIMAL(10, 3)) AS `AVGZ`,
    -- 不同年份同一月日的历史最高水位
    CAST(MAX(`MAXZ`) AS DECIMAL(10, 3)) AS `MAXZ`,
    -- 不同年份同一月日的历史最低水位
    CAST(MIN(`MINZ`) AS DECIMAL(10, 3)) AS `MINZ`,
    -- 不同年份同一月日的平均上涨速率
    CAST(AVG(`ZRR`) AS DECIMAL(10, 3)) AS `ZRR`
FROM (
    SELECT
        `ID`,
        `OBJTP`,
        `OBJCD`,
        `OBJNM`,
        `RVCD`,
        `RVNM`,
        `RSCD`,
        `RSNM`,
        `SECCD`,
        `SECNM`,
        -- 源表STCD为CHAR类型，去除可能存在的尾部空格
        TRIM(`STCD`) AS `STCD`,
        `STNM`,
        `BGNTM`,
        `ENDTM`,
        `AVGZ`,
        `MAXZ`,
        `MINZ`,
        `ZRR`,
        -- 提取统计日期的月日
        DATE_FORMAT(
            CAST(`STATDT` AS TIMESTAMP),
            ''MM-dd''
        ) AS `MONTH_DAY`
    FROM DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE
) SOURCE_DATA
GROUP BY
    `STCD`,
    `MONTH_DAY`;
[2026-08-27 20:30:57.576] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_5/867/875/867_875_node.sql
[2026-08-27 20:30:57.578] INFO Success initialized task plugin instance successfully
[2026-08-27 20:30:57.578] INFO Set taskVarPool: null successfully
[2026-08-27 20:30:57.579] INFO
***********************************************************************************************[INFO] 2026-08-27 20:30:57.580 - *********************************  Execute task instance  *************************************
[2026-08-27 20:30:57.581] INFO ***********************************************************************************************
[2026-08-27 20:30:57.582] INFO Final Shell file is:
[2026-08-27 20:30:57.583] INFO ****************************** Script Content *****************************************************************
[2026-08-27 20:30:57.584] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_5/867/875/867_875_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_5/867/875/867_875_node.sql
[2026-08-27 20:30:57.585] INFO ****************************** Script Content *****************************************************************
[2026-08-27 20:30:57.585] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_5/867/875/867_875.sh
[2026-08-27 20:30:57.588] INFO processInstanceId:867,taskInstanceId:875
[2026-08-27 20:30:57.588] INFO processInstanceId:867,taskInstanceId:875
[2026-08-27 20:30:57.588] INFO process start, process id is: 1288
[2026-08-27 20:30:59.591] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_5/867/875/867_875_init.sql
	Aug 27, 2026 8:30:59 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-27 20:31:00.592] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE (
	>     `ID` BIGINT,
	>     `OBJTP` STRING,
	>     `OBJCD` STRING,
	>     `OBJNM` STRING,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `STATDT` DATE,
	>     `STTPRD` STRING,
	>     `BGNTM` TIMESTAMP(0),
	>     `ENDTM` TIMESTAMP(0),
	>     `AVGZ` DECIMAL(15, 6),
	>     `MAXZ` DECIMAL(10, 3),
	>     `MINZ` DECIMAL(10, 3),
	>     `ZRR` DECIMAL(10, 3)
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''DWS.DWS_RH_HIST_ST_Z_DAY_S'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWS_RH_STAT_ST_Z_MYAVG_S (
	>     `ID` BIGINT,
	>     `OBJTP` STRING,
	>     `OBJCD` STRING,
	>     `OBJNM` STRING,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `STATDT` DATE,
	>     `STTPRD` STRING,
	>     `BGNTM` TIMESTAMP(0),
	>     `ENDTM` TIMESTAMP(0),
	>     `AVGZ` DECIMAL(10, 3),
	>     `MAXZ` DECIMAL(10, 3),
	>     `MINZ` DECIMAL(10, 3),
	>     `ZRR` DECIMAL(10, 3)
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''DWS.DWS_RH_STAT_ST_Z_MYAVG_S'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''doris_batch_st_z_myavg_s_dws_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>
	>     ''sink.properties.format'' = ''json'',
	>
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	>
	> -- =====================================================
	> -- 4. 计算多年平均水位和历史极值
	> -- 汇总维度：测站编码 + 月日
	> -- =====================================================
	>
	> INSERT INTO DORIS_DWS_RH_STAT_ST_Z_MYAVG_S
	> SELECT
	>     -- 使用当前分组中最小的原始记录ID作为汇总记录ID
	>     MIN(`ID`) AS `ID`,
	>
	>     -- 同一测站的基础属性原则上保持一致
	>     MAX(`OBJTP`) AS `OBJTP`,
	>     MAX(`OBJCD`) AS `OBJCD`,
	>     MAX(`OBJNM`) AS `OBJNM`,
	>     MAX(`RVCD`) AS `RVCD`,
	>     MAX(`RVNM`) AS `RVNM`,
	>     MAX(`RSCD`) AS `RSCD`,
	>     MAX(`RSNM`) AS `RSNM`,
	>     MAX(`SECCD`) AS `SECCD`,
	>     MAX(`SECNM`) AS `SECNM`,
	>
	>     `STCD`,
	>
	>     MAX(`STNM`) AS `STNM`,
	>
	>     -- 使用2000年承载月日，兼容2月29日
	>     CAST(CONCAT(''2000-'', `MONTH_DAY`) AS DATE) AS `STATDT`,
	>
	>     -- 多年平均统计周期
	>     ''MYAVG'' AS `STTPRD`,
	>
	>     -- 历史数据覆盖的最早开始时间
	>     MIN(`BGNTM`) AS `BGNTM`,
	>
	>     -- 历史数据覆盖的最晚结束时间
	>     MAX(`ENDTM`) AS `ENDTM`,
	>
	>     -- 不同年份同一月日的平均水位
	>     CAST(AVG(`AVGZ`) AS DECIMAL(10, 3)) AS `AVGZ`,
	>
	>     -- 不同年份同一月日的历史最高水位
	>     CAST(MAX(`MAXZ`) AS DECIMAL(10, 3)) AS `MAXZ`,
	>
	>     -- 不同年份同一月日的历史最低水位
	>     CAST(MIN(`MINZ`) AS DECIMAL(10, 3)) AS `MINZ`,
	>
	>     -- 不同年份同一月日的平均上涨速率
	>     CAST(AVG(`ZRR`) AS DECIMAL(10, 3)) AS `ZRR`
	>
	> FROM (
	>     SELECT
	>         `ID`,
	>         `OBJTP`,
	>         `OBJCD`,
	>         `OBJNM`,
	>         `RVCD`,
	>         `RVNM`,
	>         `RSCD`,
	>         `RSNM`,
	>         `SECCD`,
	>         `SECNM`,
	>
	>         -- 源表STCD为CHAR类型，去除可能存在的尾部空格
	>         TRIM(`STCD`) AS `STCD`,
	>
	>         `STNM`,
	>         `BGNTM`,
	>         `ENDTM`,
	>         `AVGZ`,
	>         `MAXZ`,
	>         `MINZ`,
	>         `ZRR`,
	>
	>         -- 提取统计日期的月日
	>         DATE_FORMAT(
	>             CAST(`STATDT` AS TIMESTAMP),
	>             ''MM-dd''
	>         ) AS `MONTH_DAY`
	>
	>     FROM DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE
[2026-08-27 20:31:03.595] INFO ->
	> )[34;1m[INFO] Complete execution of the SQL update statement.[0m
[2026-08-27 20:31:04.600] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_5/867/875, processId:1288 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-27 20:31:04.602] INFO Start finding appId in /opt/dolphinscheduler/logs/20260827/180628671770176/5/867/875.log, fetch way: log
[2026-08-27 20:31:04.618] INFO ***********************************************************************************************
[2026-08-27 20:31:04.627] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_5/867/875
[2026-08-27 20:31:04.633] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-27 20:30:57.539000','2026-08-27 20:31:05.580000'),
 (866,28,20,'河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】','河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】-4-20260827203039489','OFFLINE','STOP','STOPPED','2026-08-27 20:30:39','2026-08-27 20:30:44',5,'吴同','吴同',NULL,'','migration:0217','migration:0217','2026-08-27 20:30:39.502000','2026-08-27 20:30:44.860000'),
 (865,31,8,'河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】','河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】-4-20260827203023453','OFFLINE','START','SUCCESS','2026-08-27 20:30:23','2026-08-27 20:30:29',6,'吴同','吴同',NULL,'[2026-08-27 20:30:23.474] INFO
***********************************************************************************************[INFO] 2026-08-27 20:30:23.479 - *********************************  Initialize task context  ***********************************
[2026-08-27 20:30:23.480] INFO ***********************************************************************************************
[2026-08-27 20:30:23.481] INFO Begin to initialize task
[2026-08-27 20:30:23.482] INFO Set task startTime: 1787833823482
[2026-08-27 20:30:23.483] INFO Set task appId: 865_873
[2026-08-27 20:30:23.485] INFO End initialize task {
  "taskInstanceId" : 873,
  "taskName" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】",
  "firstSubmitTime" : 1787833823467,
  "startTime" : 1787833823482,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260827/180628998558272/4/865/873.log",
  "processId" : 0,
  "processDefineCode" : 180628998558272,
  "processDefineVersion" : 4,
  "processInstanceId" : 865,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "865"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260827"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260826"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "873"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628998558272"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628998558272"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260827203023"
    }
  },
  "taskAppId" : "865_873",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-27 20:30:23.487] INFO
***********************************************************************************************[INFO] 2026-08-27 20:30:23.488 - *********************************  Load task instance plugin  *********************************
[2026-08-27 20:30:23.489] INFO ***********************************************************************************************
[2026-08-27 20:30:23.492] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-27 20:30:23.493] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-27 20:30:23.494] INFO TenantCode: root check successfully
[2026-08-27 20:30:23.506] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873/865_873_init.sql
[2026-08-27 20:30:23.496] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873 check successfully
[2026-08-27 20:30:23.497] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-27 20:30:23.511] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873/865_873_node.sql
[2026-08-27 20:30:23.498] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-27 20:30:23.499 - Download upstream files: [] successfully
[2026-08-27 20:30:23.517] INFO *********************************  Execute task instance  *************************************
[2026-08-27 20:30:23.501] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "standalone",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "SET execution.runtime-mode = batch;",
[连接身份与敏感参数已脱敏]
  "executeMode" : "BATCH"
}
[2026-08-27 20:30:23.521] INFO Final Shell file is:
[2026-08-27 20:30:23.509] INFO Writing content: -- =====================================================
-- 1. Flink 批处理基础配置
-- =====================================================
-- 使用批处理模式
SET ''execution.runtime-mode'' = ''batch'';
-- 等待批处理任务执行完成后再退出
SET ''table.dml-sync'' = ''true'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- =====================================================
-- 2. 创建 MySQL JDBC 批处理源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_PPTN_HIS_R;
CREATE TABLE MYSQL_ST_PPTN_HIS_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `DRP` DECIMAL(10, 2)
) WITH (
    ''connector'' = ''jdbc'',
    ''url'' = ''[REDACTED_CONNECTION]'',
    ''table-name'' = ''st_pptn_his_r'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
);
-- =====================================================
-- 3. 创建 Doris ODS 目标表
-- 保留原始历史降雨记录
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_MON_ST_PPTN_HIS_R;
CREATE TABLE DORIS_ODS_RH_MON_ST_PPTN_HIS_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `DRP` DECIMAL(10, 2)
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''ODS.ODS_RH_MON_ST_PPTN_HIS_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''sink.label-prefix'' = ''mysql_batch_st_pptn_his_r_ods_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
    -- Doris 2.1 不支持压缩 JSON
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 4. 启动 MySQL 到 Doris 批量同步
-- 不进行清洗和转换，保留原始历史降雨记录
-- =====================================================
INSERT INTO DORIS_ODS_RH_MON_ST_PPTN_HIS_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `DRP`
FROM MYSQL_ST_PPTN_HIS_R;
[2026-08-27 20:30:23.523] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873/865_873_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873/865_873_node.sql
[2026-08-27 20:30:23.513] INFO Success initialized task plugin instance successfully
[2026-08-27 20:30:23.525] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873/865_873.sh
[2026-08-27 20:30:23.515] INFO
***********************************************************************************************[INFO] 2026-08-27 20:30:23.519 - ***********************************************************************************************
[2026-08-27 20:30:23.522] INFO ****************************** Script Content *****************************************************************
[2026-08-27 20:30:23.524] INFO ****************************** Script Content *****************************************************************
[2026-08-27 20:30:23.529] INFO processInstanceId:865,taskInstanceId:873
[2026-08-27 20:30:23.529] INFO process start, process id is: 788
[2026-08-27 20:30:25.532] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873/865_873_init.sql
	Aug 27, 2026 8:30:25 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-27 20:30:26.534] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_PPTN_HIS_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `DRP` DECIMAL(10, 2)
	> ) WITH (
	>     ''connector'' = ''jdbc'',
	>
	>     ''url'' = ''[REDACTED_CONNECTION]'',
	>
	>     ''table-name'' = ''st_pptn_his_r'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_MON_ST_PPTN_HIS_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `DRP` DECIMAL(10, 2)
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''ODS.ODS_RH_MON_ST_PPTN_HIS_R'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''mysql_batch_st_pptn_his_r_ods_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>
	>     ''sink.properties.format'' = ''json'',
	>
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-27 20:30:28.536] INFO ->
	Flink SQL> [34;1m[INFO] Complete execution of the SQL update statement.[0m
	Flink SQL>
	Shutting down the session...
	done.
[2026-08-27 20:30:28.538] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873, processId:788 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-27 20:30:28.539] INFO Start finding appId in /opt/dolphinscheduler/logs/20260827/180628998558272/4/865/873.log, fetch way: log
[2026-08-27 20:30:28.541] INFO
***********************************************************************************************[INFO] 2026-08-27 20:30:28.543 - ***********************************************************************************************
[2026-08-27 20:30:28.544] INFO Upload output files: [] successfully
[2026-08-27 20:30:28.548] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-27 20:30:28.549] INFO Remove the current task execute context from worker cache
[2026-08-27 20:30:28.550] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873
[2026-08-27 20:30:28.553] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_4/865/873
[2026-08-27 20:30:28.554] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-27 20:30:23.464000','2026-08-27 20:30:29.559000'),
 (864,28,20,'河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】','河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】-4-20260827202710070','OFFLINE','START','SUCCESS','2026-08-27 20:27:10','2026-08-27 20:27:18',8,'吴同','吴同',NULL,'','migration:0217','migration:0217','2026-08-27 20:27:10.083000','2026-08-27 20:27:18.412000'),
 (863,31,8,'河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】','河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】-3-20260827202639874','OFFLINE','START','SUCCESS','2026-08-27 20:26:39','2026-08-27 20:26:48',9,'吴同','吴同',NULL,'','migration:0217','migration:0217','2026-08-27 20:26:40.055000','2026-08-27 20:26:48.440000'),
 (700,28,20,'河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】','河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】-3-20260805133358015','OFFLINE','START','SUCCESS','2026-08-05 13:33:58','2026-08-05 13:34:42',44,'吴同','吴同',NULL,'[2026-08-05 13:33:58.070] INFO
***********************************************************************************************[INFO] 2026-08-05 13:33:58.110 - *********************************  Initialize task context  ***********************************
[2026-08-05 13:33:58.115] INFO ***********************************************************************************************
[2026-08-05 13:33:58.118] INFO Begin to initialize task
[2026-08-05 13:33:58.122] INFO Set task startTime: 1785908038122
[2026-08-05 13:33:58.128] INFO Set task appId: 700_708
[2026-08-05 13:33:58.135] INFO End initialize task {
  "taskInstanceId" : 708,
  "taskName" : "河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】",
  "firstSubmitTime" : 1785908038050,
  "startTime" : 1785908038122,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260805/180628671770176/3/700/708.log",
  "processId" : 0,
  "processDefineCode" : 180628671770176,
  "processDefineVersion" : 3,
  "processInstanceId" : 700,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "700"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260805"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "708"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628671770176"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628671770176"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260805133358"
    }
  },
  "taskAppId" : "700_708",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-05 13:33:58.146] INFO
***********************************************************************************************[INFO] 2026-08-05 13:33:58.153 - *********************************  Load task instance plugin  *********************************
[2026-08-05 13:33:58.163] INFO ***********************************************************************************************
[2026-08-05 13:33:58.180] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-05 13:33:58.188] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-05 13:33:58.204] INFO TenantCode: root check successfully
[2026-08-05 13:33:58.223] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-05 13:33:58.236] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-05 13:33:58.245 - Download upstream files: [] successfully
[2026-08-05 13:33:58.252] INFO Task plugin instance: FLINK create successfully
[2026-08-05 13:33:58.258] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "SET execution.runtime-mode = batch;",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-05 13:33:58.278] INFO Writing content: set execution.target=local;
set parallelism.default=1;
SET execution.runtime-mode = batch;
[2026-08-05 13:33:58.288] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_3/700/708/700_708_init.sql
[2026-08-05 13:33:58.314] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_3/700/708/700_708_node.sql
[2026-08-05 13:33:58.338] INFO Set taskVarPool: null successfully
[2026-08-05 13:33:58.356] INFO *********************************  Execute task instance  *************************************
[2026-08-05 13:33:58.369] INFO ***********************************************************************************************
[2026-08-05 13:33:58.400] INFO ****************************** Script Content *****************************************************************
[2026-08-05 13:33:58.420] INFO ****************************** Script Content *****************************************************************
[2026-08-05 13:33:58.434] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_3/700/708/700_708.sh
[2026-08-05 13:33:58.461] INFO processInstanceId:700,taskInstanceId:708
[2026-08-05 13:33:58.462] INFO processInstanceId:700,taskInstanceId:708
[2026-08-05 13:33:58.461] INFO process start, process id is: 162696
[2026-08-05 13:34:12.486] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_3/700/708/700_708_init.sql
	Aug 05, 2026 1:34:12 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
[2026-08-05 13:34:13.490] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE (
	>     `ID` BIGINT,
	>     `OBJTP` STRING,
	>     `OBJCD` STRING,
	>     `OBJNM` STRING,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `STATDT` DATE,
	>     `STTPRD` STRING,
	>     `BGNTM` TIMESTAMP(0),
	>     `ENDTM` TIMESTAMP(0),
	>     `AVGZ` DECIMAL(15, 6),
	>     `MAXZ` DECIMAL(10, 3),
	>     `MINZ` DECIMAL(10, 3),
	>     `ZRR` DECIMAL(10, 3)
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''DWS.DWS_RH_HIST_ST_Z_DAY_S'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
[2026-08-05 13:34:14.495] INFO ->
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWS_RH_STAT_ST_Z_MYAVG_S (
	>     `ID` BIGINT,
	>     `OBJTP` STRING,
	>     `OBJCD` STRING,
	>     `OBJNM` STRING,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `STATDT` DATE,
	>     `STTPRD` STRING,
	>     `BGNTM` TIMESTAMP(0),
	>     `ENDTM` TIMESTAMP(0),
	>     `AVGZ` DECIMAL(10, 3),
	>     `MAXZ` DECIMAL(10, 3),
	>     `MINZ` DECIMAL(10, 3),
	>     `ZRR` DECIMAL(10, 3)
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''DWS.DWS_RH_STAT_ST_Z_MYAVG_S'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''doris_batch_st_z_myavg_s_dws_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>
	>     ''sink.properties.format'' = ''json'',
	>
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	>
	> -- =====================================================
	> -- 4. 计算多年平均水位和历史极值
	> -- 汇总维度：测站编码 + 月日
	> -- =====================================================
	>
	> INSERT INTO DORIS_DWS_RH_STAT_ST_Z_MYAVG_S
	> SELECT
	>     -- 使用当前分组中最小的原始记录ID作为汇总记录ID
	>     MIN(`ID`) AS `ID`,
	>
	>     -- 同一测站的基础属性原则上保持一致
	>     MAX(`OBJTP`) AS `OBJTP`,
	>     MAX(`OBJCD`) AS `OBJCD`,
	>     MAX(`OBJNM`) AS `OBJNM`,
	>     MAX(`RVCD`) AS `RVCD`,
	>     MAX(`RVNM`) AS `RVNM`,
	>     MAX(`RSCD`) AS `RSCD`,
	>     MAX(`RSNM`) AS `RSNM`,
	>     MAX(`SECCD`) AS `SECCD`,
	>     MAX(`SECNM`) AS `SECNM`,
	>
	>     `STCD`,
	>
	>     MAX(`STNM`) AS `STNM`,
	>
	>     -- 使用2000年承载月日，兼容2月29日
	>     CAST(CONCAT(''2000-'', `MONTH_DAY`) AS DATE) AS `STATDT`,
	>
	>     -- 多年平均统计周期
	>     ''MYAVG'' AS `STTPRD`,
	>
	>     -- 历史数据覆盖的最早开始时间
	>     MIN(`BGNTM`) AS `BGNTM`,
	>
	>     -- 历史数据覆盖的最晚结束时间
	>     MAX(`ENDTM`) AS `ENDTM`,
	>
	>     -- 不同年份同一月日的平均水位
	>     CAST(AVG(`AVGZ`) AS DECIMAL(10, 3)) AS `AVGZ`,
	>
	>     -- 不同年份同一月日的历史最高水位
	>     CAST(MAX(`MAXZ`) AS DECIMAL(10, 3)) AS `MAXZ`,
	>
	>     -- 不同年份同一月日的历史最低水位
	>     CAST(MIN(`MINZ`) AS DECIMAL(10, 3)) AS `MINZ`,
	>
	>     -- 不同年份同一月日的平均上涨速率
	>     CAST(AVG(`ZRR`) AS DECIMAL(10, 3)) AS `ZRR`
	>
	> FROM (
	>     SELECT
	>         `ID`,
	>         `OBJTP`,
	>         `OBJCD`,
	>         `OBJNM`,
	>         `RVCD`,
	>         `RVNM`,
	>         `RSCD`,
	>         `RSNM`,
	>         `SECCD`,
	>         `SECNM`,
	>
	>         -- 源表STCD为CHAR类型，去除可能存在的尾部空格
	>         TRIM(`STCD`) AS `STCD`,
	>
	>         `STNM`,
	>         `BGNTM`,
	>         `ENDTM`,
	>         `AVGZ`,
	>         `MAXZ`,
	>         `MINZ`,
	>         `ZRR`,
	>
	>         -- 提取统计日期的月日
	>         DATE_FORMAT(
	>             CAST(`STATDT` AS TIMESTAMP),
	>             ''MM-dd''
	>         ) AS `MONTH_DAY`
	>
	>     FROM DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE
[2026-08-05 13:34:40.507] INFO ->
	> )[34;1m[INFO] Complete execution of the SQL update statement.[0m
[2026-08-05 13:34:41.514] INFO ->
	Flink SQL>
	Shutting down the session...
	done.
[2026-08-05 13:34:41.519] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_3/700/708, processId:162696 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-05 13:34:41.524] INFO Start finding appId in /opt/dolphinscheduler/logs/20260805/180628671770176/3/700/708.log, fetch way: log
[2026-08-05 13:34:41.532] INFO
***********************************************************************************************[INFO] 2026-08-05 13:34:41.535 - *********************************  Finalize task instance  ************************************
[2026-08-05 13:34:41.538] INFO ***********************************************************************************************
[2026-08-05 13:34:41.542] INFO Upload output files: [] successfully
[2026-08-05 13:34:41.548] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-05 13:34:41.551] INFO Remove the current task execute context from worker cache
[2026-08-05 13:34:41.555] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_3/700/708
[2026-08-05 13:34:41.558] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_3/700/708
[2026-08-05 13:34:41.563] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-05 13:33:58.043000','2026-08-05 13:34:42.496000'),
 (699,31,8,'河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】','河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】-2-20260805133345896','OFFLINE','START','SUCCESS','2026-08-05 13:33:45','2026-08-05 13:34:22',37,'吴同','吴同',NULL,'[2026-08-05 13:33:46.054] INFO
***********************************************************************************************[INFO] 2026-08-05 13:33:46.101 - *********************************  Initialize task context  ***********************************
[2026-08-05 13:33:46.109] INFO ***********************************************************************************************
[2026-08-05 13:33:46.115] INFO Begin to initialize task
[2026-08-05 13:33:46.124] INFO Set task startTime: 1785908026124
[2026-08-05 13:33:46.132] INFO Set task appId: 699_707
[2026-08-05 13:33:46.139] INFO End initialize task {
  "taskInstanceId" : 707,
  "taskName" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】",
  "firstSubmitTime" : 1785908026012,
  "startTime" : 1785908026124,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260805/180628998558272/2/699/707.log",
  "processId" : 0,
  "processDefineCode" : 180628998558272,
  "processDefineVersion" : 2,
  "processInstanceId" : 699,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "699"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260805"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "707"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628998558272"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628998558272"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260805133346"
    }
  },
  "taskAppId" : "699_707",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-05 13:33:46.146] INFO
***********************************************************************************************[INFO] 2026-08-05 13:33:46.154 - *********************************  Load task instance plugin  *********************************
[2026-08-05 13:33:46.158] INFO ***********************************************************************************************
[2026-08-05 13:33:46.171] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-05 13:33:46.176] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-05 13:33:46.181] INFO TenantCode: root check successfully
[2026-08-05 13:33:46.194] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707 check successfully
[2026-08-05 13:33:46.201] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-05 13:33:46.238] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707/699_707_init.sql
[2026-08-05 13:33:46.214] INFO Download upstream files: [] successfully
[2026-08-05 13:33:46.221] INFO Task plugin instance: FLINK create successfully
[2026-08-05 13:33:46.233] INFO Writing content: set execution.target=local;
set parallelism.default=1;
SET execution.runtime-mode = batch;
[2026-08-05 13:33:46.248] INFO Writing content: -- =====================================================
-- 1. Flink 批处理基础配置
-- =====================================================
-- 使用批处理模式
SET ''execution.runtime-mode'' = ''batch'';
-- 等待批处理任务执行完成后再退出
SET ''table.dml-sync'' = ''true'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- =====================================================
-- 2. 创建 MySQL JDBC 批处理源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_PPTN_HIS_R;
CREATE TABLE MYSQL_ST_PPTN_HIS_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `DRP` DECIMAL(10, 2)
) WITH (
    ''connector'' = ''jdbc'',
    ''url'' = ''[REDACTED_CONNECTION]'',
    ''table-name'' = ''st_pptn_his_r'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
);
-- =====================================================
-- 3. 创建 Doris ODS 目标表
-- 保留原始历史降雨记录
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_MON_ST_PPTN_HIS_R;
CREATE TABLE DORIS_ODS_RH_MON_ST_PPTN_HIS_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `DRP` DECIMAL(10, 2)
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''ODS.ODS_RH_MON_ST_PPTN_HIS_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''sink.label-prefix'' = ''mysql_batch_st_pptn_his_r_ods_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
    -- Doris 2.1 不支持压缩 JSON
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 4. 启动 MySQL 到 Doris 批量同步
-- 不进行清洗和转换，保留原始历史降雨记录
-- =====================================================
INSERT INTO DORIS_ODS_RH_MON_ST_PPTN_HIS_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `DRP`
FROM MYSQL_ST_PPTN_HIS_R;
[2026-08-05 13:33:46.265] INFO Set taskVarPool: null successfully
[2026-08-05 13:33:46.259] INFO Success initialized task plugin instance successfully
[2026-08-05 13:33:46.272] INFO *********************************  Execute task instance  *************************************
[2026-08-05 13:33:46.280] INFO ***********************************************************************************************
[2026-08-05 13:33:46.284] INFO Final Shell file is:
[2026-08-05 13:33:46.287] INFO ****************************** Script Content *****************************************************************
[2026-08-05 13:33:46.290] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707/699_707_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707/699_707_node.sql
[2026-08-05 13:33:46.295] INFO ****************************** Script Content *****************************************************************
[2026-08-05 13:33:46.301] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707/699_707.sh
[2026-08-05 13:33:46.320] INFO process start, process id is: 162415
[2026-08-05 13:33:46.320] INFO processInstanceId:699,taskInstanceId:707
[2026-08-05 13:33:46.321] INFO processInstanceId:699,taskInstanceId:707
[2026-08-05 13:33:57.352] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707/699_707_init.sql
	Aug 05, 2026 1:33:56 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-05 13:33:58.356] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_PPTN_HIS_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `DRP` DECIMAL(10, 2)
	> ) WITH (
	>     ''connector'' = ''jdbc'',
	>
	>     ''url'' = ''[REDACTED_CONNECTION]'',
	>
	>     ''table-name'' = ''st_pptn_his_r'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_MON_ST_PPTN_HIS_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `DRP` DECIMAL(10, 2)
[2026-08-05 13:33:59.380] INFO ->
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''ODS.ODS_RH_MON_ST_PPTN_HIS_R'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''mysql_batch_st_pptn_his_r_ods_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>
	>     ''sink.properties.format'' = ''json'',
	>
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-05 13:34:16.399] INFO ->
	Flink SQL> Loading class `com.mysql.jdbc.Driver''. This is deprecated. The new driver class is `com.mysql.cj.jdbc.Driver''. The driver is automatically registered via the SPI and manual loading of the driver class is generally unnecessary.[INFO] 2026-08-05 13:34:21.408 -  ->
	[34;1m[INFO] Complete execution of the SQL update statement.[0m
	Flink SQL>
	Shutting down the session...
	done.
[2026-08-05 13:34:21.412] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707, processId:162415 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-05 13:34:21.417] INFO Start finding appId in /opt/dolphinscheduler/logs/20260805/180628998558272/2/699/707.log, fetch way: log
[2026-08-05 13:34:21.423] INFO
***********************************************************************************************[INFO] 2026-08-05 13:34:21.428 - *********************************  Finalize task instance  ************************************
[2026-08-05 13:34:21.432] INFO ***********************************************************************************************
[2026-08-05 13:34:21.438] INFO Upload output files: [] successfully
[2026-08-05 13:34:21.444] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-05 13:34:21.448] INFO Remove the current task execute context from worker cache
[2026-08-05 13:34:21.451] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707
[2026-08-05 13:34:21.456] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_2/699/707
[2026-08-05 13:34:21.462] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-05 13:33:45.994000','2026-08-05 13:34:22.472000'),
 (671,28,20,'河道水情_DWD到DWS_年平均水位_年平均汇总_每日【统计】','河道水情_DWD到DWS_多年平均水位_多年平均口径汇总_每日-1-20260804214051879','OFFLINE','START','SUCCESS','2026-08-04 21:40:51','2026-08-04 21:41:34',43,'吴同','吴同',NULL,'[2026-08-04 21:40:51.930] INFO
***********************************************************************************************[INFO] 2026-08-04 21:40:51.966 - *********************************  Initialize task context  ***********************************
[2026-08-04 21:40:51.974] INFO ***********************************************************************************************
[2026-08-04 21:40:51.978] INFO Begin to initialize task
[2026-08-04 21:40:51.981] INFO Set task startTime: 1785850851981
[2026-08-04 21:40:51.985] INFO Set task appId: 671_679
[2026-08-04 21:40:51.991] INFO End initialize task {
  "taskInstanceId" : 679,
  "taskName" : "河道水情_DWD到DWS_多年平均水位_多年平均口径汇总_每日",
  "firstSubmitTime" : 1785850851908,
  "startTime" : 1785850851981,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180628671770176/1/671/679.log",
  "processId" : 0,
  "processDefineCode" : 180628671770176,
  "processDefineVersion" : 1,
  "processInstanceId" : 671,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_DWD到DWS_多年平均水位_多年平均口径汇总_每日"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "671"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "679"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_DWD到DWS_多年平均水位_多年平均口径汇总_每日"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628671770176"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628671770176"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804214051"
    }
  },
  "taskAppId" : "671_679",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 21:40:51.997] INFO
***********************************************************************************************[INFO] 2026-08-04 21:40:52.005 - *********************************  Load task instance plugin  *********************************
[2026-08-04 21:40:52.008] INFO ***********************************************************************************************
[2026-08-04 21:40:52.014] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 21:40:52.017] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 21:40:52.022] INFO TenantCode: root check successfully
[2026-08-04 21:40:52.052] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "SET execution.runtime-mode = batch;",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 21:40:52.033] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 21:40:52.040] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 21:40:52.067 - To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_1/671/679/671_679_init.sql
[2026-08-04 21:40:52.048] INFO Task plugin instance: FLINK create successfully
[2026-08-04 21:40:52.062] INFO Writing content: set execution.target=local;
set parallelism.default=1;
SET execution.runtime-mode = batch;
[2026-08-04 21:40:52.075] INFO Writing content: -- =====================================================
-- 河道水情_DWD到DWS_多年平均水位
-- 多年平均口径汇总_每日
-- =====================================================
-- =====================================================
-- 1. Flink 批处理基础配置
-- =====================================================
-- 使用批处理模式
SET ''execution.runtime-mode'' = ''batch'';
-- 等待批处理任务执行完成后再退出
SET ''table.dml-sync'' = ''true'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- =====================================================
-- 2. 创建 Doris 日水位统计源表
-- =====================================================
DROP TABLE IF EXISTS DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE;
CREATE TABLE DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE (
    `ID` BIGINT,
    `OBJTP` STRING,
    `OBJCD` STRING,
    `OBJNM` STRING,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `STATDT` DATE,
    `STTPRD` STRING,
    `BGNTM` TIMESTAMP(0),
    `ENDTM` TIMESTAMP(0),
    `AVGZ` DECIMAL(15, 6),
    `MAXZ` DECIMAL(10, 3),
    `MINZ` DECIMAL(10, 3),
    `ZRR` DECIMAL(10, 3)
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''DWS.DWS_RH_HIST_ST_Z_DAY_S'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
);
-- =====================================================
-- 3. 创建 Doris 多年平均水位目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_DWS_RH_STAT_ST_Z_MYAVG_S;
CREATE TABLE DORIS_DWS_RH_STAT_ST_Z_MYAVG_S (
    `ID` BIGINT,
    `OBJTP` STRING,
    `OBJCD` STRING,
    `OBJNM` STRING,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `STATDT` DATE,
    `STTPRD` STRING,
    `BGNTM` TIMESTAMP(0),
    `ENDTM` TIMESTAMP(0),
    `AVGZ` DECIMAL(10, 3),
    `MAXZ` DECIMAL(10, 3),
    `MINZ` DECIMAL(10, 3),
    `ZRR` DECIMAL(10, 3)
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''DWS.DWS_RH_STAT_ST_Z_MYAVG_S'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''sink.label-prefix'' = ''doris_batch_st_z_myavg_s_dws_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
    -- Doris 2.1 不支持压缩 JSON
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 4. 计算多年平均水位和历史极值
-- 汇总维度：测站编码 + 月日
-- =====================================================
INSERT INTO DORIS_DWS_RH_STAT_ST_Z_MYAVG_S
SELECT
    -- 使用当前分组中最小的原始记录ID作为汇总记录ID
    MIN(`ID`) AS `ID`,
    -- 同一测站的基础属性原则上保持一致
    MAX(`OBJTP`) AS `OBJTP`,
    MAX(`OBJCD`) AS `OBJCD`,
    MAX(`OBJNM`) AS `OBJNM`,
    MAX(`RVCD`) AS `RVCD`,
    MAX(`RVNM`) AS `RVNM`,
    MAX(`RSCD`) AS `RSCD`,
    MAX(`RSNM`) AS `RSNM`,
    MAX(`SECCD`) AS `SECCD`,
    MAX(`SECNM`) AS `SECNM`,
    `STCD`,
    MAX(`STNM`) AS `STNM`,
    -- 使用2000年承载月日，兼容2月29日
    CAST(CONCAT(''2000-'', `MONTH_DAY`) AS DATE) AS `STATDT`,
    -- 多年平均统计周期
    ''MYAVG'' AS `STTPRD`,
    -- 历史数据覆盖的最早开始时间
    MIN(`BGNTM`) AS `BGNTM`,
    -- 历史数据覆盖的最晚结束时间
    MAX(`ENDTM`) AS `ENDTM`,
    -- 不同年份同一月日的平均水位
    CAST(AVG(`AVGZ`) AS DECIMAL(10, 3)) AS `AVGZ`,
    -- 不同年份同一月日的历史最高水位
    CAST(MAX(`MAXZ`) AS DECIMAL(10, 3)) AS `MAXZ`,
    -- 不同年份同一月日的历史最低水位
    CAST(MIN(`MINZ`) AS DECIMAL(10, 3)) AS `MINZ`,
    -- 不同年份同一月日的平均上涨速率
    CAST(AVG(`ZRR`) AS DECIMAL(10, 3)) AS `ZRR`
FROM (
    SELECT
        `ID`,
        `OBJTP`,
        `OBJCD`,
        `OBJNM`,
        `RVCD`,
        `RVNM`,
        `RSCD`,
        `RSNM`,
        `SECCD`,
        `SECNM`,
        -- 源表STCD为CHAR类型，去除可能存在的尾部空格
        TRIM(`STCD`) AS `STCD`,
        `STNM`,
        `BGNTM`,
        `ENDTM`,
        `AVGZ`,
        `MAXZ`,
        `MINZ`,
        `ZRR`,
        -- 提取统计日期的月日
        DATE_FORMAT(
            CAST(`STATDT` AS TIMESTAMP),
            ''MM-dd''
        ) AS `MONTH_DAY`
    FROM DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE
) SOURCE_DATA
GROUP BY
    `STCD`,
    `MONTH_DAY`;
[2026-08-04 21:40:52.083] INFO Success initialized task plugin instance successfully
[2026-08-04 21:40:52.088] INFO Set taskVarPool: null successfully
[2026-08-04 21:40:52.093] INFO
***********************************************************************************************[INFO] 2026-08-04 21:40:52.097 - *********************************  Execute task instance  *************************************
[2026-08-04 21:40:52.102] INFO ***********************************************************************************************
[2026-08-04 21:40:52.111] INFO Final Shell file is:
[2026-08-04 21:40:52.114] INFO ****************************** Script Content *****************************************************************
[2026-08-04 21:40:52.117] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_1/671/679/671_679_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_1/671/679/671_679_node.sql
[2026-08-04 21:40:52.122] INFO ****************************** Script Content *****************************************************************
[2026-08-04 21:40:52.127] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_1/671/679/671_679.sh
[2026-08-04 21:40:52.149] INFO processInstanceId:671,taskInstanceId:679
[2026-08-04 21:40:52.149] INFO processInstanceId:671,taskInstanceId:679
[2026-08-04 21:40:52.149] INFO process start, process id is: 142870
[2026-08-04 21:41:02.174] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_1/671/679/671_679_init.sql
	Aug 04, 2026 9:41:01 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 21:41:03.178] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE (
	>     `ID` BIGINT,
	>     `OBJTP` STRING,
	>     `OBJCD` STRING,
	>     `OBJNM` STRING,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `STATDT` DATE,
	>     `STTPRD` STRING,
	>     `BGNTM` TIMESTAMP(0),
	>     `ENDTM` TIMESTAMP(0),
	>     `AVGZ` DECIMAL(15, 6),
	>     `MAXZ` DECIMAL(10, 3),
	>     `MINZ` DECIMAL(10, 3),
	>     `ZRR` DECIMAL(10, 3)
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''DWS.DWS_RH_HIST_ST_Z_DAY_S'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWS_RH_STAT_ST_Z_MYAVG_S (
	>     `ID` BIGINT,
	>     `OBJTP` STRING,
	>     `OBJCD` STRING,
	>     `OBJNM` STRING,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `STATDT` DATE,
	>     `STTPRD` STRING,
	>     `BGNTM` TIMESTAMP(0),
	>     `ENDTM` TIMESTAMP(0),
	>     `AVGZ` DECIMAL(10, 3),
	>     `MAXZ` DECIMAL(10, 3),
	>     `MINZ` DECIMAL(10, 3),
	>     `ZRR` DECIMAL(10, 3)
[2026-08-04 21:41:04.183] INFO ->
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''DWS.DWS_RH_STAT_ST_Z_MYAVG_S'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''doris_batch_st_z_myavg_s_dws_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>
	>     ''sink.properties.format'' = ''json'',
	>
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	>
	> -- =====================================================
	> -- 4. 计算多年平均水位和历史极值
	> -- 汇总维度：测站编码 + 月日
	> -- =====================================================
	>
	> INSERT INTO DORIS_DWS_RH_STAT_ST_Z_MYAVG_S
	> SELECT
	>     -- 使用当前分组中最小的原始记录ID作为汇总记录ID
	>     MIN(`ID`) AS `ID`,
	>
	>     -- 同一测站的基础属性原则上保持一致
	>     MAX(`OBJTP`) AS `OBJTP`,
	>     MAX(`OBJCD`) AS `OBJCD`,
	>     MAX(`OBJNM`) AS `OBJNM`,
	>     MAX(`RVCD`) AS `RVCD`,
	>     MAX(`RVNM`) AS `RVNM`,
	>     MAX(`RSCD`) AS `RSCD`,
	>     MAX(`RSNM`) AS `RSNM`,
	>     MAX(`SECCD`) AS `SECCD`,
	>     MAX(`SECNM`) AS `SECNM`,
	>
	>     `STCD`,
	>
	>     MAX(`STNM`) AS `STNM`,
	>
	>     -- 使用2000年承载月日，兼容2月29日
	>     CAST(CONCAT(''2000-'', `MONTH_DAY`) AS DATE) AS `STATDT`,
	>
	>     -- 多年平均统计周期
	>     ''MYAVG'' AS `STTPRD`,
	>
	>     -- 历史数据覆盖的最早开始时间
	>     MIN(`BGNTM`) AS `BGNTM`,
	>
	>     -- 历史数据覆盖的最晚结束时间
	>     MAX(`ENDTM`) AS `ENDTM`,
	>
	>     -- 不同年份同一月日的平均水位
	>     CAST(AVG(`AVGZ`) AS DECIMAL(10, 3)) AS `AVGZ`,
	>
	>     -- 不同年份同一月日的历史最高水位
	>     CAST(MAX(`MAXZ`) AS DECIMAL(10, 3)) AS `MAXZ`,
	>
	>     -- 不同年份同一月日的历史最低水位
	>     CAST(MIN(`MINZ`) AS DECIMAL(10, 3)) AS `MINZ`,
	>
	>     -- 不同年份同一月日的平均上涨速率
	>     CAST(AVG(`ZRR`) AS DECIMAL(10, 3)) AS `ZRR`
	>
	> FROM (
	>     SELECT
	>         `ID`,
	>         `OBJTP`,
	>         `OBJCD`,
	>         `OBJNM`,
	>         `RVCD`,
	>         `RVNM`,
	>         `RSCD`,
	>         `RSNM`,
	>         `SECCD`,
	>         `SECNM`,
	>
	>         -- 源表STCD为CHAR类型，去除可能存在的尾部空格
	>         TRIM(`STCD`) AS `STCD`,
	>
	>         `STNM`,
	>         `BGNTM`,
	>         `ENDTM`,
	>         `AVGZ`,
	>         `MAXZ`,
	>         `MINZ`,
	>         `ZRR`,
	>
	>         -- 提取统计日期的月日
	>         DATE_FORMAT(
	>             CAST(`STATDT` AS TIMESTAMP),
	>             ''MM-dd''
	>         ) AS `MONTH_DAY`
	>
	>     FROM DORIS_DWS_RH_HIST_ST_Z_DAY_S_SOURCE
[2026-08-04 21:41:32.193] INFO ->
	> )[34;1m[INFO] Complete execution of the SQL update statement.[0m
[2026-08-04 21:41:33.203] INFO ->
	Flink SQL>
	Shutting down the session...
	done.
[2026-08-04 21:41:33.208] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_1/671/679, processId:142870 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 21:41:33.211] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180628671770176/1/671/679.log, fetch way: log
[2026-08-04 21:41:33.219] INFO
***********************************************************************************************[INFO] 2026-08-04 21:41:33.224 - *********************************  Finalize task instance  ************************************
[2026-08-04 21:41:33.228] INFO ***********************************************************************************************
[2026-08-04 21:41:33.234] INFO Upload output files: [] successfully
[2026-08-04 21:41:33.241] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 21:41:33.244] INFO Remove the current task execute context from worker cache
[2026-08-04 21:41:33.247] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_1/671/679
[2026-08-04 21:41:33.251] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628671770176_1/671/679
[2026-08-04 21:41:33.257] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 21:40:51.904000','2026-08-04 21:41:34.295000'),
 (661,31,8,'河道水情_历史库到ODS_历史雨量明细_增量_每小时【监测数据】','河道水情_历史库到ODS_历史雨量明细_增量_每小时-1-20260804211659860','OFFLINE','START','SUCCESS','2026-08-04 21:16:59','2026-08-04 21:17:34',35,'吴同','吴同',NULL,'[2026-08-04 21:16:59.916] INFO
***********************************************************************************************[INFO] 2026-08-04 21:16:59.944 - *********************************  Initialize task context  ***********************************
[2026-08-04 21:16:59.948] INFO ***********************************************************************************************
[2026-08-04 21:16:59.951] INFO Begin to initialize task
[2026-08-04 21:16:59.953] INFO Set task startTime: 1785849419953
[2026-08-04 21:16:59.958] INFO Set task appId: 661_665
[2026-08-04 21:16:59.962] INFO End initialize task {
  "taskInstanceId" : 665,
  "taskName" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时",
  "firstSubmitTime" : 1785849419894,
  "startTime" : 1785849419953,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180628998558272/1/661/665.log",
  "processId" : 0,
  "processDefineCode" : 180628998558272,
  "processDefineVersion" : 1,
  "processInstanceId" : 661,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "661"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "665"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_历史库到ODS_历史雨量明细_增量_每小时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628998558272"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180628998558272"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804211659"
    }
  },
  "taskAppId" : "661_665",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 21:16:59.973] INFO
***********************************************************************************************[INFO] 2026-08-04 21:16:59.978 - *********************************  Load task instance plugin  *********************************
[2026-08-04 21:16:59.982] INFO ***********************************************************************************************
[2026-08-04 21:16:59.992] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 21:16:59.998] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 21:17:00.002] INFO TenantCode: root check successfully
[2026-08-04 21:17:00.012] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665 check successfully
[2026-08-04 21:17:00.015] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 21:17:00.021] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 21:17:00.028 - Download upstream files: [] successfully
[2026-08-04 21:17:00.035] INFO Task plugin instance: FLINK create successfully
[2026-08-04 21:17:00.038] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "SET execution.runtime-mode = batch;",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 21:17:00.053] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665/661_665_init.sql
[2026-08-04 21:17:00.076] INFO Success initialized task plugin instance successfully
[2026-08-04 21:17:00.081] INFO Set taskVarPool: null successfully
[2026-08-04 21:17:00.088] INFO
***********************************************************************************************[INFO] 2026-08-04 21:17:00.092 - *********************************  Execute task instance  *************************************
[2026-08-04 21:17:00.096] INFO ***********************************************************************************************
[2026-08-04 21:17:00.100] INFO Final Shell file is:
[2026-08-04 21:17:00.106] INFO ****************************** Script Content *****************************************************************
[2026-08-04 21:17:00.109] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665/661_665_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665/661_665_node.sql
[2026-08-04 21:17:00.112] INFO ****************************** Script Content *****************************************************************
[2026-08-04 21:17:00.115] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665/661_665.sh
[2026-08-04 21:17:00.129] INFO processInstanceId:661,taskInstanceId:665
[2026-08-04 21:17:00.129] INFO processInstanceId:661,taskInstanceId:665
[2026-08-04 21:17:00.129] INFO process start, process id is: 138884
[2026-08-04 21:17:11.144] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665/661_665_init.sql
	Aug 04, 2026 9:17:10 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 21:17:12.149] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_PPTN_HIS_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `DRP` DECIMAL(10, 2)
	> ) WITH (
	>     ''connector'' = ''jdbc'',
	>
	>     ''url'' = ''[REDACTED_CONNECTION]'',
	>
	>     ''table-name'' = ''st_pptn_his_r'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_MON_ST_PPTN_HIS_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `DRP` DECIMAL(10, 2)
[2026-08-04 21:17:13.156] INFO ->
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     ''table.identifier'' = ''ODS.ODS_RH_MON_ST_PPTN_HIS_R'',
	>
[连接身份与敏感参数已脱敏]
	>
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''mysql_batch_st_pptn_his_r_ods_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>
	>     ''sink.properties.format'' = ''json'',
	>
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 21:17:29.167] INFO ->
	Flink SQL> Loading class `com.mysql.jdbc.Driver''. This is deprecated. The new driver class is `com.mysql.cj.jdbc.Driver''. The driver is automatically registered via the SPI and manual loading of the driver class is generally unnecessary.[INFO] 2026-08-04 21:17:32.175 -  ->
	[34;1m[INFO] Complete execution of the SQL update statement.[0m
[2026-08-04 21:17:33.181] INFO ->
	Flink SQL>
	Shutting down the session...
	done.
[2026-08-04 21:17:33.185] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665, processId:138884 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 21:17:33.191] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180628998558272/1/661/665.log, fetch way: log
[2026-08-04 21:17:33.202] INFO
***********************************************************************************************[INFO] 2026-08-04 21:17:33.205 - *********************************  Finalize task instance  ************************************
[2026-08-04 21:17:33.208] INFO ***********************************************************************************************
[2026-08-04 21:17:33.212] INFO Upload output files: [] successfully
[2026-08-04 21:17:33.217] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 21:17:33.221] INFO Remove the current task execute context from worker cache
[2026-08-04 21:17:33.225] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665
[2026-08-04 21:17:33.233] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180628998558272_1/661/665
[2026-08-04 21:17:33.238] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 21:16:59.890000','2026-08-04 21:17:34.059000'),
 (658,32,14,'河道水情_ODS到DWD_实时流量明细_实时【外部表->DWD】','河道水情_ODS到DWD_实时流量明细_实时-5-20260804205535466','REALTIME','START','SUCCESS','2026-08-04 20:55:35','2026-08-04 20:55:52',17,'吴同','吴同',NULL,'[2026-08-04 20:55:35.503] INFO
***********************************************************************************************[INFO] 2026-08-04 20:55:35.518 - *********************************  Initialize task context  ***********************************
[2026-08-04 20:55:35.522] INFO ***********************************************************************************************
[2026-08-04 20:55:35.525] INFO Begin to initialize task
[2026-08-04 20:55:35.529] INFO Set task startTime: 1785848135529
[2026-08-04 20:55:35.534] INFO Set task appId: 658_662
[2026-08-04 20:55:35.537] INFO End initialize task {
  "taskInstanceId" : 662,
  "taskName" : "河道水情_ODS到DWD_实时流量明细_实时",
  "firstSubmitTime" : 1785848135492,
  "startTime" : 1785848135529,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629051429440/5/658/662.log",
  "processId" : 0,
  "processDefineCode" : 180629051429440,
  "processDefineVersion" : 5,
  "processInstanceId" : 658,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_ODS到DWD_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "658"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "662"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_ODS到DWD_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629051429440"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629051429440"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804205535"
    }
  },
  "taskAppId" : "658_662",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 20:55:35.543] INFO
***********************************************************************************************[INFO] 2026-08-04 20:55:35.546 - *********************************  Load task instance plugin  *********************************
[2026-08-04 20:55:35.549] INFO ***********************************************************************************************
[2026-08-04 20:55:35.554] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 20:55:35.558] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 20:55:35.561] INFO TenantCode: root check successfully
[2026-08-04 20:55:35.566] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662 check successfully
[2026-08-04 20:55:35.569] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 20:55:35.573] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 20:55:35.583 - Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "SET execution.runtime-mode = batch;",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 20:55:35.587] INFO Writing content: set execution.target=local;
set parallelism.default=1;
SET execution.runtime-mode = batch;
[2026-08-04 20:55:35.591] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662/658_662_init.sql
[2026-08-04 20:55:35.594] INFO Writing content: -- =====================================================
-- 1. Flink 基础配置
-- =====================================================
-- 开启 Checkpoint
SET ''execution.checkpointing.interval'' = ''10s'';
-- 本地模式持续等待实时任务，不让 SQL Client 提交后退出
SET ''table.dml-sync'' = ''true'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- =====================================================
-- 2. 创建 MySQL CDC 实时源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_Q_R;
CREATE TABLE MYSQL_ST_Q_R (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''mysql-cdc'',
    ''hostname'' = ''mysql57'',
    ''port'' = ''3306'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''database-name'' = ''RIVER_WATER'',
    ''table-name'' = ''st_q_r'',
    -- 每个同时运行的 MySQL CDC 任务必须使用不同的 Server ID
    ''server-id'' = ''6502'',
    -- 只读取任务启动后产生的 Binlog
    ''scan.startup.mode'' = ''latest-offset'',
    -- latest-offset 不执行历史快照
    ''scan.incremental.snapshot.enabled'' = ''false''
);
-- =====================================================
-- 3. 创建 Doris ODS 目标表
-- 保存未经清洗的原始实时流量数据
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''ODS.ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''sink.label-prefix'' = ''mysql_cdc_st_q_r_ods_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.enable-delete'' = ''true'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
    -- Doris 2.1 不支持压缩 JSON
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 4. 创建 Doris DWD 目标表
-- 保存过滤负数后的实时流量数据
-- =====================================================
DROP TABLE IF EXISTS DORIS_DWD_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_DWD_RH_RTW_ST_Q_R (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''DWD.DWD_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''sink.label-prefix'' = ''mysql_cdc_st_q_r_dwd_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.enable-delete'' = ''true'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
    -- Doris 2.1 不支持压缩 JSON
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 5. 同时启动 ODS 和 DWD 实时同步
-- =====================================================
EXECUTE STATEMENT SET
BEGIN
-- -----------------------------------------------------
-- 5.1 原始数据直接写入 ODS
-- 新增、更新、删除均同步
-- -----------------------------------------------------
INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R;
-- -----------------------------------------------------
-- 5.2 清洗后写入 DWD
-- Q 小于 0 的异常数据不写入
-- Q 为空的数据暂时保留
-- 站点和断面字段使用源表已有信息
-- -----------------------------------------------------
INSERT INTO DORIS_DWD_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R
WHERE `Q` IS NULL
   OR `Q` >= CAST(0 AS DECIMAL(14, 3));
END;
[2026-08-04 20:55:35.598] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662/658_662_node.sql
[2026-08-04 20:55:35.601] INFO Success initialized task plugin instance successfully
[2026-08-04 20:55:35.604] INFO Set taskVarPool: null successfully
[2026-08-04 20:55:35.607] INFO
***********************************************************************************************[INFO] 2026-08-04 20:55:35.610 - *********************************  Execute task instance  *************************************
[2026-08-04 20:55:35.614] INFO ***********************************************************************************************
[2026-08-04 20:55:35.617] INFO Final Shell file is:
[2026-08-04 20:55:35.620] INFO ****************************** Script Content *****************************************************************
[2026-08-04 20:55:35.625] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662/658_662_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662/658_662_node.sql
[2026-08-04 20:55:35.628] INFO ****************************** Script Content *****************************************************************
[2026-08-04 20:55:35.631] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662/658_662.sh
[2026-08-04 20:55:35.640] INFO processInstanceId:658,taskInstanceId:662
[2026-08-04 20:55:35.641] INFO processInstanceId:658,taskInstanceId:662
[2026-08-04 20:55:35.641] INFO process start, process id is: 137208
[2026-08-04 20:55:46.650] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662/658_662_init.sql
	Aug 04, 2026 8:55:46 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
[2026-08-04 20:55:47.657] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 20:55:48.666] INFO ->
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     ''connector'' = ''mysql-cdc'',
	>
	>     ''hostname'' = ''mysql57'',
	>     ''port'' = ''3306'',
	>
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>
	>     ''database-name'' = ''RIVER_WATER'',
	>     ''table-name'' = ''st_q_r'',
	>
	>     -- 每个同时运行的 MySQL CDC 任务必须使用不同的 Server ID
	>     ''server-id'' = ''6502'',
	>
	>     -- 只读取任务启动后产生的 Binlog
	>     ''scan.startup.mode'' = ''latest-offset'',
	>
	>     -- latest-offset 不执行历史快照
	>     ''scan.incremental.snapshot.enabled'' = ''false''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>     ''table.identifier'' = ''ODS.ODS_RH_RTW_ST_Q_R'',
	>
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''mysql_cdc_st_q_r_ods_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>     ''sink.enable-delete'' = ''true'',
	>
	>     ''sink.properties.format'' = ''json'',
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
[2026-08-04 20:55:49.674] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWD_RH_RTW_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     ''connector'' = ''doris'',
	>
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>     ''table.identifier'' = ''DWD.DWD_RH_RTW_ST_Q_R'',
	>
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>
	>     ''sink.label-prefix'' = ''mysql_cdc_st_q_r_dwd_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>     ''sink.enable-delete'' = ''true'',
	>
	>     ''sink.properties.format'' = ''json'',
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 20:55:50.681] INFO ->
	Flink SQL>
	>
	> -- =====================================================
	> -- 5. 同时启动 ODS 和 DWD 实时同步
	> -- =====================================================
	>
	> EXECUTE STATEMENT SET
	> BEGIN
	>
	> -- -----------------------------------------------------
	> -- 5.1 原始数据直接写入 ODS
	> -- 新增、更新、删除均同步
	> -- -----------------------------------------------------
	>
	> INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
	> SELECT
	>     `ID`,
	>     `RVCD`,
	>     `RVNM`,
	>     `RSCD`,
	>     `RSNM`,
	>     `SECCD`,
	>     `SECNM`,
	>     `STCD`,
	>     `STNM`,
	>     `TM`,
	>     `Q`
	> FROM MYSQL_ST_Q_R;
	>
	>
	> -- -----------------------------------------------------
	> -- 5.2 清洗后写入 DWD
	> -- Q 小于 0 的异常数据不写入
	> -- Q 为空的数据暂时保留
	> -- 站点和断面字段使用源表已有信息
	> -- -----------------------------------------------------
	>
	> INSERT INTO DORIS_DWD_RH_RTW_ST_Q_R
	> SELECT
	>     `ID`,
	>     `RVCD`,
	>     `RVNM`,
	>     `RSCD`,
	>     `RSNM`,
	>     `SECCD`,
	>     `SECNM`,
	>     `STCD`,
	>     `STNM`,
	>     `TM`,
	>     `Q`
	> FROM MYSQL_ST_Q_R
	> WHERE `Q` IS NULL
[2026-08-04 20:55:51.687] INFO ->
	>    OR `Q` >= CAST(0 AS DECIMAL(14, 3))[31;1m[ERROR] Could not execute SQL statement. Reason:
	org.apache.flink.table.api.ValidationException: Querying an unbounded table ''default_catalog.default_database.MYSQL_ST_Q_R'' in batch mode is not allowed. The table source is unbounded.[0m
[2026-08-04 20:55:52.693] INFO ->
	Shutting down the session...
	done.
[2026-08-04 20:55:52.702] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662, processId:137208 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 20:55:52.707] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629051429440/5/658/662.log, fetch way: log
[2026-08-04 20:55:52.714] INFO
***********************************************************************************************[INFO] 2026-08-04 20:55:52.717 - *********************************  Finalize task instance  ************************************
[2026-08-04 20:55:52.722] INFO ***********************************************************************************************
[2026-08-04 20:55:52.726] INFO Upload output files: [] successfully
[2026-08-04 20:55:52.737] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 20:55:52.740] INFO Remove the current task execute context from worker cache
[2026-08-04 20:55:52.743] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662
[2026-08-04 20:55:52.747] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_5/658/662
[2026-08-04 20:55:52.751] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 20:55:35.484000','2026-08-04 20:55:52.918000'),
 (657,32,14,'河道水情_ODS到DWD_实时流量明细_实时【外部表->DWD】','河道水情_ODS到DWD_实时流量明细_实时-3-20260804204556575','REALTIME','START','SUCCESS','2026-08-04 20:45:56','2026-08-04 20:46:29',33,'吴同','吴同',NULL,'[2026-08-04 20:45:56.607] INFO
***********************************************************************************************[INFO] 2026-08-04 20:45:56.621 - *********************************  Initialize task context  ***********************************
[2026-08-04 20:45:56.625] INFO ***********************************************************************************************
[2026-08-04 20:45:56.630] INFO Begin to initialize task
[2026-08-04 20:45:56.636] INFO Set task startTime: 1785847556636
[2026-08-04 20:45:56.639] INFO Set task appId: 657_661
[2026-08-04 20:45:56.643] INFO End initialize task {
  "taskInstanceId" : 661,
  "taskName" : "河道水情_ODS到DWD_实时流量明细_实时",
  "firstSubmitTime" : 1785847556597,
  "startTime" : 1785847556636,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629051429440/3/657/661.log",
  "processId" : 0,
  "processDefineCode" : 180629051429440,
  "processDefineVersion" : 3,
  "processInstanceId" : 657,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_ODS到DWD_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "657"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "661"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_ODS到DWD_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629051429440"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629051429440"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804204556"
    }
  },
  "taskAppId" : "657_661",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 20:45:56.648] INFO
***********************************************************************************************[INFO] 2026-08-04 20:45:56.652 - *********************************  Load task instance plugin  *********************************
[2026-08-04 20:45:56.656] INFO ***********************************************************************************************
[2026-08-04 20:45:56.670] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 20:45:56.674] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 20:45:56.678] INFO TenantCode: root check successfully
[2026-08-04 20:45:56.682] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661 check successfully
[2026-08-04 20:45:56.686] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 20:45:56.692] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 20:45:56.698 - Download upstream files: [] successfully
[2026-08-04 20:45:56.706] INFO Task plugin instance: FLINK create successfully
[2026-08-04 20:45:56.710] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 20:45:56.714] INFO Writing content: set execution.target=local;
set parallelism.default=1;
[2026-08-04 20:45:56.717] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661/657_661_init.sql
[2026-08-04 20:45:56.722] INFO Writing content: -- =====================================================
-- 1. 开启 Checkpoint
-- Doris Sink 使用 Checkpoint 触发数据写入
-- =====================================================
SET ''execution.checkpointing.interval'' = ''10s'';
-- =====================================================
-- 2. 创建 Doris ODS 源表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R_SOURCE;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R_SOURCE (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''ODS.ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
);
-- =====================================================
-- 3. 创建 Doris DWD 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_DWD_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_DWD_RH_RTW_ST_Q_R (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''DWD.DWD_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    -- 每个任务使用独立的 Label 前缀
    ''sink.label-prefix'' = ''doris_ods_to_dwd_st_q_r_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.enable-delete'' = ''true'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
    -- Doris 2.1 不支持压缩 JSON
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 4. 执行 ODS 到 DWD 清洗任务
-- 处理规则：
-- 1. 过滤流量小于 0 的异常数据
-- 2. 保留流量为空的数据
-- 3. 将 ODS 中已有的站点和断面信息写入 DWD
-- =====================================================
INSERT INTO DORIS_DWD_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM DORIS_ODS_RH_RTW_ST_Q_R_SOURCE
WHERE `Q` IS NULL
   OR `Q` >= CAST(0 AS DECIMAL(14, 3));
[2026-08-04 20:45:56.727] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661/657_661_node.sql
[2026-08-04 20:45:56.731] INFO Success initialized task plugin instance successfully
[2026-08-04 20:45:56.742] INFO
***********************************************************************************************[INFO] 2026-08-04 20:45:56.748 - ***********************************************************************************************
[2026-08-04 20:45:56.754] INFO Final Shell file is:
[2026-08-04 20:45:56.760] INFO ****************************** Script Content *****************************************************************
[2026-08-04 20:45:56.764] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661/657_661_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661/657_661_node.sql
[2026-08-04 20:45:56.770] INFO ****************************** Script Content *****************************************************************
[2026-08-04 20:45:56.775] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661/657_661.sh
[2026-08-04 20:45:56.786] INFO processInstanceId:657,taskInstanceId:661
[2026-08-04 20:45:56.786] INFO processInstanceId:657,taskInstanceId:661
[2026-08-04 20:45:56.786] INFO process start, process id is: 136282
[2026-08-04 20:46:07.815] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661/657_661_init.sql
	Aug 04, 2026 8:46:07 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
[2026-08-04 20:46:08.819] INFO ->
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R_SOURCE (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     ''connector'' = ''doris'',
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>     ''table.identifier'' = ''ODS.ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 20:46:09.827] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWD_RH_RTW_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     ''connector'' = ''doris'',
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>     ''table.identifier'' = ''DWD.DWD_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>
	>     -- 每个任务使用独立的 Label 前缀
	>     ''sink.label-prefix'' = ''doris_ods_to_dwd_st_q_r_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>     ''sink.enable-delete'' = ''true'',
	>
	>     ''sink.properties.format'' = ''json'',
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	>
	> -- =====================================================
	> -- 4. 执行 ODS 到 DWD 清洗任务
	> -- 处理规则：
	> -- 1. 过滤流量小于 0 的异常数据
	> -- 2. 保留流量为空的数据
	> -- 3. 将 ODS 中已有的站点和断面信息写入 DWD
	> -- =====================================================
	>
	> INSERT INTO DORIS_DWD_RH_RTW_ST_Q_R
	> SELECT
	>     `ID`,
	>     `RVCD`,
	>     `RVNM`,
	>     `RSCD`,
	>     `RSNM`,
	>     `SECCD`,
	>     `SECNM`,
	>     `STCD`,
	>     `STNM`,
	>     `TM`,
	>     `Q`
	> FROM DORIS_ODS_RH_RTW_ST_Q_R_SOURCE
	> WHERE `Q` IS NULL
[2026-08-04 20:46:22.837] INFO ->
	>    OR `Q` >= CAST(0 AS DECIMAL(14, 3))[34;1m[INFO] Submitting SQL update statement to the cluster...[0m
	[34;1m[INFO] SQL update statement has been successfully submitted to the cluster:[0m
	Job ID: 2dd58398b570c9ebb9b36d875136540b
[2026-08-04 20:46:28.842] INFO ->
	Flink SQL>
	Shutting down the session...
	done.
[2026-08-04 20:46:28.846] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661, processId:136282 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 20:46:28.852] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629051429440/3/657/661.log, fetch way: log
[2026-08-04 20:46:28.861] INFO
***********************************************************************************************[INFO] 2026-08-04 20:46:28.868 - *********************************  Finalize task instance  ************************************
[2026-08-04 20:46:28.871] INFO ***********************************************************************************************
[2026-08-04 20:46:28.875] INFO Upload output files: [] successfully
[2026-08-04 20:46:28.880] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 20:46:28.883] INFO Remove the current task execute context from worker cache
[2026-08-04 20:46:28.886] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661
[2026-08-04 20:46:28.892] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/657/661
[2026-08-04 20:46:28.904] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 20:45:56.595000','2026-08-04 20:46:29.854000'),
 (655,32,14,'河道水情_ODS到DWD_实时流量明细_实时【外部表->DWD】','河道水情_ODS到DWD_实时流量明细_实时-3-20260804203739848','REALTIME','START','SUCCESS','2026-08-04 20:37:39','2026-08-04 20:38:25',46,'吴同','吴同',NULL,'[2026-08-04 20:37:39.924] INFO
***********************************************************************************************[INFO] 2026-08-04 20:37:39.961 - *********************************  Initialize task context  ***********************************
[2026-08-04 20:37:39.967] INFO ***********************************************************************************************
[2026-08-04 20:37:39.975] INFO Begin to initialize task
[2026-08-04 20:37:39.981] INFO Set task startTime: 1785847059981
[2026-08-04 20:37:39.986] INFO Set task appId: 655_659
[2026-08-04 20:37:39.994] INFO End initialize task {
  "taskInstanceId" : 659,
  "taskName" : "河道水情_ODS到DWD_实时流量明细_实时",
  "firstSubmitTime" : 1785847059895,
  "startTime" : 1785847059981,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629051429440/3/655/659.log",
  "processId" : 0,
  "processDefineCode" : 180629051429440,
  "processDefineVersion" : 3,
  "processInstanceId" : 655,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_ODS到DWD_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "655"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "659"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_ODS到DWD_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629051429440"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629051429440"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804203739"
    }
  },
  "taskAppId" : "655_659",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 20:37:40.004] INFO
***********************************************************************************************[INFO] 2026-08-04 20:37:40.009 - *********************************  Load task instance plugin  *********************************
[2026-08-04 20:37:40.017] INFO ***********************************************************************************************
[2026-08-04 20:37:40.028] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 20:37:40.034] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 20:37:40.039] INFO TenantCode: root check successfully
[2026-08-04 20:37:40.049] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659 check successfully
[2026-08-04 20:37:40.052] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 20:37:40.059] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 20:37:40.066 - Download upstream files: [] successfully
[2026-08-04 20:37:40.071] INFO Task plugin instance: FLINK create successfully
[2026-08-04 20:37:40.079] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 20:37:40.085] INFO Writing content: set execution.target=local;
set parallelism.default=1;
[2026-08-04 20:37:40.089] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659/655_659_init.sql
[2026-08-04 20:37:40.096] INFO Writing content: -- =====================================================
-- 1. 开启 Checkpoint
-- Doris Sink 使用 Checkpoint 触发数据写入
-- =====================================================
SET ''execution.checkpointing.interval'' = ''10s'';
-- =====================================================
-- 2. 创建 Doris ODS 源表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R_SOURCE;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R_SOURCE (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''ODS.ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
);
-- =====================================================
-- 3. 创建 Doris DWD 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_DWD_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_DWD_RH_RTW_ST_Q_R (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    ''connector'' = ''doris'',
    ''fenodes'' = ''[INTERNAL_HOST]'',
    ''table.identifier'' = ''DWD.DWD_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    -- 每个任务使用独立的 Label 前缀
    ''sink.label-prefix'' = ''doris_ods_to_dwd_st_q_r_20260804_01'',
    ''sink.enable-2pc'' = ''false'',
    ''sink.enable-delete'' = ''true'',
    ''sink.properties.format'' = ''json'',
    ''sink.properties.read_json_by_line'' = ''true'',
    -- Doris 2.1 不支持压缩 JSON
    ''sink.properties.compress_type'' = ''''
);
-- =====================================================
-- 4. 执行 ODS 到 DWD 清洗任务
-- 处理规则：
-- 1. 过滤流量小于 0 的异常数据
-- 2. 保留流量为空的数据
-- 3. 将 ODS 中已有的站点和断面信息写入 DWD
-- =====================================================
INSERT INTO DORIS_DWD_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM DORIS_ODS_RH_RTW_ST_Q_R_SOURCE
WHERE `Q` IS NULL
   OR `Q` >= CAST(0 AS DECIMAL(14, 3));
[2026-08-04 20:37:40.103] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659/655_659_node.sql
[2026-08-04 20:37:40.107] INFO Success initialized task plugin instance successfully
[2026-08-04 20:37:40.120] INFO Set taskVarPool: null successfully
[2026-08-04 20:37:40.128] INFO
***********************************************************************************************[INFO] 2026-08-04 20:37:40.133 - *********************************  Execute task instance  *************************************
[2026-08-04 20:37:40.138] INFO ***********************************************************************************************
[2026-08-04 20:37:40.146] INFO Final Shell file is:
[2026-08-04 20:37:40.150] INFO ****************************** Script Content *****************************************************************
[2026-08-04 20:37:40.154] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659/655_659_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659/655_659_node.sql
[2026-08-04 20:37:40.160] INFO ****************************** Script Content *****************************************************************
[2026-08-04 20:37:40.164] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659/655_659.sh
[2026-08-04 20:37:40.179] INFO processInstanceId:655,taskInstanceId:659
[2026-08-04 20:37:40.179] INFO process start, process id is: 134993
[2026-08-04 20:37:52.194] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659/655_659_init.sql
	Aug 04, 2026 8:37:52 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
[2026-08-04 20:37:53.204] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R_SOURCE (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     ''connector'' = ''doris'',
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>     ''table.identifier'' = ''ODS.ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 20:37:54.216] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_DWD_RH_RTW_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     ''connector'' = ''doris'',
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>     ''table.identifier'' = ''DWD.DWD_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>
	>     -- 每个任务使用独立的 Label 前缀
	>     ''sink.label-prefix'' = ''doris_ods_to_dwd_st_q_r_20260804_01'',
	>
	>     ''sink.enable-2pc'' = ''false'',
	>     ''sink.enable-delete'' = ''true'',
	>
	>     ''sink.properties.format'' = ''json'',
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- Doris 2.1 不支持压缩 JSON
	>     ''sink.properties.compress_type'' = ''''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	>
	> -- =====================================================
	> -- 4. 执行 ODS 到 DWD 清洗任务
	> -- 处理规则：
	> -- 1. 过滤流量小于 0 的异常数据
	> -- 2. 保留流量为空的数据
	> -- 3. 将 ODS 中已有的站点和断面信息写入 DWD
	> -- =====================================================
	>
	> INSERT INTO DORIS_DWD_RH_RTW_ST_Q_R
	> SELECT
	>     `ID`,
	>     `RVCD`,
	>     `RVNM`,
	>     `RSCD`,
	>     `RSNM`,
	>     `SECCD`,
	>     `SECNM`,
	>     `STCD`,
	>     `STNM`,
	>     `TM`,
	>     `Q`
	> FROM DORIS_ODS_RH_RTW_ST_Q_R_SOURCE
	> WHERE `Q` IS NULL
[2026-08-04 20:38:11.226] INFO ->
	>    OR `Q` >= CAST(0 AS DECIMAL(14, 3))[34;1m[INFO] Submitting SQL update statement to the cluster...[0m
	[34;1m[INFO] SQL update statement has been successfully submitted to the cluster:[0m
	Job ID: fb5c10b9e249dc32375750f17910aa14
[2026-08-04 20:38:24.234] INFO ->
	Flink SQL>
	Shutting down the session...
	done.
[2026-08-04 20:38:25.242] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659, processId:134993 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 20:38:25.247] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629051429440/3/655/659.log, fetch way: log
[2026-08-04 20:38:25.255] INFO
***********************************************************************************************[INFO] 2026-08-04 20:38:25.259 - *********************************  Finalize task instance  ************************************
[2026-08-04 20:38:25.266] INFO ***********************************************************************************************
[2026-08-04 20:38:25.270] INFO Upload output files: [] successfully
[2026-08-04 20:38:25.276] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 20:38:25.280] INFO Remove the current task execute context from worker cache
[2026-08-04 20:38:25.283] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659
[2026-08-04 20:38:25.288] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629051429440_3/655/659
[2026-08-04 20:38:25.292] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 20:37:39.891000','2026-08-04 20:38:25.880000'),
 (634,33,7,'河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】','河道水情_监测库到ODS_实时流量明细_实时-11-20260804184701142','REALTIME','START','SUCCESS','2026-08-04 18:47:01','2026-08-04 18:47:33',32,'吴同','吴同',NULL,'[2026-08-04 18:47:01.179] INFO
***********************************************************************************************[INFO] 2026-08-04 18:47:01.196 - *********************************  Initialize task context  ***********************************
[2026-08-04 18:47:01.201] INFO ***********************************************************************************************
[2026-08-04 18:47:01.204] INFO Begin to initialize task
[2026-08-04 18:47:01.208] INFO Set task startTime: 1785840421208
[2026-08-04 18:47:01.210] INFO Set task appId: 634_638
[2026-08-04 18:47:01.214] INFO End initialize task {
  "taskInstanceId" : 638,
  "taskName" : "河道水情_监测库到ODS_实时流量明细_实时",
  "firstSubmitTime" : 1785840421164,
  "startTime" : 1785840421208,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629079832128/11/634/638.log",
  "processId" : 0,
  "processDefineCode" : 180629079832128,
  "processDefineVersion" : 11,
  "processInstanceId" : 634,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "634"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "638"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804184701"
    }
  },
  "taskAppId" : "634_638",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 18:47:01.219] INFO
***********************************************************************************************[INFO] 2026-08-04 18:47:01.224 - *********************************  Load task instance plugin  *********************************
[2026-08-04 18:47:01.227] INFO ***********************************************************************************************
[2026-08-04 18:47:01.232] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 18:47:01.235] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 18:47:01.239] INFO TenantCode: root check successfully
[2026-08-04 18:47:01.243] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638 check successfully
[2026-08-04 18:47:01.246] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 18:47:01.249] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 18:47:01.253 - Download upstream files: [] successfully
[2026-08-04 18:47:01.257] INFO Task plugin instance: FLINK create successfully
[2026-08-04 18:47:01.273] INFO Writing content: set execution.target=local;
set parallelism.default=1;
[2026-08-04 18:47:01.279] INFO Writing content: -- 开启 Checkpoint
SET ''execution.checkpointing.interval'' = ''10s'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- 让 SQL Client 持续等待实时任务运行
SET ''table.dml-sync'' = ''true'';
-- =====================================================
-- 1. 创建 MySQL CDC 源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_Q_R;
CREATE TABLE MYSQL_ST_Q_R (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    -- 使用 MySQL CDC 2.4.1
    ''connector'' = ''mysql-cdc'',
    -- MySQL Docker 容器名称
    ''hostname'' = ''mysql57'',
    -- MySQL 端口
    ''port'' = ''3306'',
    -- MySQL 用户名
[连接身份与敏感参数已脱敏]
    -- MySQL 密码
[连接身份与敏感参数已脱敏]
    -- MySQL 数据库
    ''database-name'' = ''RIVER_WATER'',
    -- MySQL 表
    ''table-name'' = ''st_q_r'',
    -- MySQL 时区
    ''server-time-zone'' = ''Asia/Shanghai'',
    -- CDC Server ID
    ''server-id'' = ''5401'',
    -- 源物理表没有主键，关闭增量快照
    ''scan.incremental.snapshot.enabled'' = ''false'',
    -- 只读取任务启动后产生的 Binlog
    ''scan.startup.mode'' = ''latest-offset'',
    -- 连接超时时间
    ''connect.timeout'' = ''30s'',
    -- 连接失败重试次数
    ''connect.max-retries'' = ''3'',
    -- CDC 心跳间隔
    ''heartbeat.interval'' = ''30s''
);
-- =====================================================
-- 2. 创建 Doris 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
    `ID` BIGINT NOT NULL,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3),
    PRIMARY KEY (`ID`) NOT ENFORCED
) WITH (
    -- 使用 Doris Flink Connector
    ''connector'' = ''doris'',
    -- Doris FE HTTP 地址
    ''fenodes'' = ''[INTERNAL_HOST]'',
    -- Doris JDBC 地址
    ''jdbc-url'' = ''[REDACTED_CONNECTION]'',
    -- Doris 目标数据库和表
    ''table.identifier'' = ''RIVER_WATER.ODS_RH_RTW_ST_Q_R'',
    -- Doris 用户名
[连接身份与敏感参数已脱敏]
    -- Doris 密码
[连接身份与敏感参数已脱敏]
    -- 使用新的全局唯一 Label 前缀
    ''sink.label-prefix'' = ''mysql_cdc_st_q_r_02'',
    -- 关闭两阶段提交，避免恢复旧事务时报 transaction not found
    ''sink.enable-2pc'' = ''false'',
    -- 支持 CDC DELETE
    ''sink.enable-delete'' = ''true'',
    -- JSON 格式写入
    ''sink.properties.format'' = ''json'',
    -- 每行一条 JSON
    ''sink.properties.read_json_by_line'' = ''true'',
    -- 写入失败最大重试次数
    ''sink.max-retries'' = ''3''
);
-- =====================================================
-- 3. 启动 MySQL 到 Doris 实时同步
-- =====================================================
INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R;
[2026-08-04 18:47:01.283] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638/634_638_node.sql
[2026-08-04 18:47:01.286] INFO Success initialized task plugin instance successfully
[2026-08-04 18:47:01.290] INFO Set taskVarPool: null successfully
[2026-08-04 18:47:01.294] INFO
***********************************************************************************************[INFO] 2026-08-04 18:47:01.297 - *********************************  Execute task instance  *************************************
[2026-08-04 18:47:01.300] INFO ***********************************************************************************************
[2026-08-04 18:47:01.305] INFO Final Shell file is:
[2026-08-04 18:47:01.309] INFO ****************************** Script Content *****************************************************************
[2026-08-04 18:47:01.312] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638/634_638_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638/634_638_node.sql
[2026-08-04 18:47:01.316] INFO ****************************** Script Content *****************************************************************
[2026-08-04 18:47:01.319] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638/634_638.sh
[2026-08-04 18:47:01.333] INFO processInstanceId:634,taskInstanceId:638
[2026-08-04 18:47:01.333] INFO processInstanceId:634,taskInstanceId:638
[2026-08-04 18:47:01.333] INFO process start, process id is: 55872
[2026-08-04 18:47:20.350] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638/634_638_init.sql
	Aug 04, 2026 6:47:19 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
[2026-08-04 18:47:21.357] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
[2026-08-04 18:47:22.370] INFO ->
	> ) WITH (
	>     -- 使用 MySQL CDC 2.4.1
	>     ''connector'' = ''mysql-cdc'',
	>
	>     -- MySQL Docker 容器名称
	>     ''hostname'' = ''mysql57'',
	>
	>     -- MySQL 端口
	>     ''port'' = ''3306'',
	>
	>     -- MySQL 用户名
[连接身份与敏感参数已脱敏]
	>
	>     -- MySQL 密码
[连接身份与敏感参数已脱敏]
	>
	>     -- MySQL 数据库
	>     ''database-name'' = ''RIVER_WATER'',
	>
	>     -- MySQL 表
	>     ''table-name'' = ''st_q_r'',
	>
	>     -- MySQL 时区
	>     ''server-time-zone'' = ''Asia/Shanghai'',
	>
	>     -- CDC Server ID
	>     ''server-id'' = ''5401'',
	>
	>     -- 源物理表没有主键，关闭增量快照
	>     ''scan.incremental.snapshot.enabled'' = ''false'',
	>
	>     -- 只读取任务启动后产生的 Binlog
	>     ''scan.startup.mode'' = ''latest-offset'',
	>
	>     -- 连接超时时间
	>     ''connect.timeout'' = ''30s'',
	>
	>     -- 连接失败重试次数
	>     ''connect.max-retries'' = ''3'',
	>
	>     -- CDC 心跳间隔
	>     ''heartbeat.interval'' = ''30s''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
[2026-08-04 18:47:23.376] INFO ->
	> ) WITH (
	>     -- 使用 Doris Flink Connector
	>     ''connector'' = ''doris'',
	>
	>     -- Doris FE HTTP 地址
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     -- Doris JDBC 地址
	>     ''jdbc-url'' = ''[REDACTED_CONNECTION]'',
	>
	>     -- Doris 目标数据库和表
	>     ''table.identifier'' = ''RIVER_WATER.ODS_RH_RTW_ST_Q_R'',
	>
	>     -- Doris 用户名
[连接身份与敏感参数已脱敏]
	>
	>     -- Doris 密码
[连接身份与敏感参数已脱敏]
	>
	>     -- 使用新的全局唯一 Label 前缀
	>     ''sink.label-prefix'' = ''mysql_cdc_st_q_r_02'',
	>
	>     -- 关闭两阶段提交，避免恢复旧事务时报 transaction not found
	>     ''sink.enable-2pc'' = ''false'',
	>
	>     -- 支持 CDC DELETE
	>     ''sink.enable-delete'' = ''true'',
	>
	>     -- JSON 格式写入
	>     ''sink.properties.format'' = ''json'',
	>
	>     -- 每行一条 JSON
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- 写入失败最大重试次数
	>     ''sink.max-retries'' = ''3''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 18:47:31.393] INFO ->
	Flink SQL> [31;1m[ERROR] Could not execute SQL statement. Reason:
	org.apache.doris.shaded.com.fasterxml.jackson.databind.exc.InvalidFormatException: Cannot deserialize value of type `int` from String "errCode=7,detailMessage=unknowntable,tableName=ODS_RH_RTW_ST_Q_R": not a valid `int` value
	 at [Source: (String)""errCode=7,detailMessage=unknowntable,tableName=ODS_RH_RTW_ST_Q_R""; line: 1, column: 1][0m
[2026-08-04 18:47:32.406] INFO ->
	Shutting down the session...
	done.
[2026-08-04 18:47:32.412] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638, processId:55872 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 18:47:32.415] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629079832128/11/634/638.log, fetch way: log
[2026-08-04 18:47:32.428] INFO
***********************************************************************************************[INFO] 2026-08-04 18:47:32.432 - *********************************  Finalize task instance  ************************************
[2026-08-04 18:47:32.437] INFO ***********************************************************************************************
[2026-08-04 18:47:32.444] INFO Upload output files: [] successfully
[2026-08-04 18:47:32.450] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 18:47:32.454] INFO Remove the current task execute context from worker cache
[2026-08-04 18:47:32.458] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638
[2026-08-04 18:47:32.465] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_11/634/638
[2026-08-04 18:47:32.470] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 18:47:01.160000','2026-08-04 18:47:33.077000'),
 (626,33,7,'河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】','河道水情_监测库到ODS_实时流量明细_实时-9-20260804183359700','REALTIME','START','SUCCESS','2026-08-04 18:33:59','2026-08-04 18:34:22',23,'吴同','吴同',NULL,'[2026-08-04 18:33:59.753] INFO
***********************************************************************************************[INFO] 2026-08-04 18:33:59.776 - *********************************  Initialize task context  ***********************************
[2026-08-04 18:33:59.781] INFO ***********************************************************************************************
[2026-08-04 18:33:59.785] INFO Begin to initialize task
[2026-08-04 18:33:59.792] INFO Set task startTime: 1785839639792
[2026-08-04 18:33:59.798] INFO Set task appId: 626_629
[2026-08-04 18:33:59.804] INFO End initialize task {
  "taskInstanceId" : 629,
  "taskName" : "河道水情_监测库到ODS_实时流量明细_实时",
  "firstSubmitTime" : 1785839639728,
  "startTime" : 1785839639792,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629079832128/9/626/629.log",
  "processId" : 0,
  "processDefineCode" : 180629079832128,
  "processDefineVersion" : 9,
  "processInstanceId" : 626,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "626"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "629"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804183359"
    }
  },
  "taskAppId" : "626_629",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 18:33:59.813] INFO
***********************************************************************************************[INFO] 2026-08-04 18:33:59.818 - *********************************  Load task instance plugin  *********************************
[2026-08-04 18:33:59.823] INFO ***********************************************************************************************
[2026-08-04 18:33:59.835] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 18:33:59.845] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 18:33:59.848] INFO TenantCode: root check successfully
[2026-08-04 18:33:59.858] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629 check successfully
[2026-08-04 18:33:59.893] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 18:33:59.864] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 18:33:59.874] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 18:33:59.881 - Download upstream files: [] successfully
[2026-08-04 18:33:59.885] INFO Task plugin instance: FLINK create successfully
[2026-08-04 18:33:59.903] INFO Writing content: set execution.target=local;
set parallelism.default=1;
[2026-08-04 18:33:59.926] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629/626_629_node.sql
[2026-08-04 18:33:59.936] INFO Success initialized task plugin instance successfully
[2026-08-04 18:33:59.944] INFO Set taskVarPool: null successfully
[2026-08-04 18:33:59.951] INFO
***********************************************************************************************[INFO] 2026-08-04 18:33:59.958 - *********************************  Execute task instance  *************************************
[2026-08-04 18:33:59.963] INFO ***********************************************************************************************
[2026-08-04 18:33:59.968] INFO Final Shell file is:
[2026-08-04 18:33:59.972] INFO ****************************** Script Content *****************************************************************
[2026-08-04 18:33:59.978] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629/626_629_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629/626_629_node.sql
[2026-08-04 18:33:59.982] INFO ****************************** Script Content *****************************************************************
[2026-08-04 18:33:59.987] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629/626_629.sh
[2026-08-04 18:34:00.012] INFO processInstanceId:626,taskInstanceId:629
[2026-08-04 18:34:00.013] INFO processInstanceId:626,taskInstanceId:629
[2026-08-04 18:34:00.013] INFO process start, process id is: 47427
[2026-08-04 18:34:11.037] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629/626_629_init.sql
	Aug 04, 2026 6:34:10 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
[2026-08-04 18:34:12.042] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
[2026-08-04 18:34:13.047] INFO ->
	> ) WITH (
	>     -- 使用 MySQL CDC 2.4.1
	>     ''connector'' = ''mysql-cdc'',
	>
	>     -- MySQL Docker 容器名称
	>     ''hostname'' = ''mysql57'',
	>
	>     -- MySQL 端口
	>     ''port'' = ''3306'',
	>
	>     -- MySQL 用户名
[连接身份与敏感参数已脱敏]
	>
	>     -- MySQL 密码
[连接身份与敏感参数已脱敏]
	>
	>     -- MySQL 数据库
	>     ''database-name'' = ''RIVER_WATER'',
	>
	>     -- MySQL 表
	>     ''table-name'' = ''st_q_r'',
	>
	>     -- MySQL 时区
	>     ''server-time-zone'' = ''Asia/Shanghai'',
	>
	>     -- CDC Server ID
	>     ''server-id'' = ''5401'',
	>
	>     -- MySQL 物理表没有主键，关闭增量快照
	>     ''scan.incremental.snapshot.enabled'' = ''false'',
	>
	>     -- 只读取任务启动后产生的 Binlog
	>     ''scan.startup.mode'' = ''latest-offset'',
	>
	>     -- 连接超时时间
	>     ''connect.timeout'' = ''30s'',
	>
	>     -- 连接失败重试次数
	>     ''connect.max-retries'' = ''3'',
	>
	>     -- CDC 心跳间隔
	>     ''heartbeat.interval'' = ''30s''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT NOT NULL,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3),
	>
	>     PRIMARY KEY (`ID`) NOT ENFORCED
	> ) WITH (
	>     -- 使用 Doris Flink Connector
	>     ''connector'' = ''doris'',
	>
	>     -- Doris FE HTTP 端口
	>     -- Stream Load 实际通过该端口写入
	>     ''fenodes'' = ''[INTERNAL_HOST]'',
	>
	>     -- Doris MySQL 协议地址
	>     -- Connector 可用于获取元数据和表结构
	>     ''jdbc-url'' = ''[REDACTED_CONNECTION]'',
	>
	>     -- Doris 目标数据库和目标表
	>     ''table.identifier'' = ''RIVER_WATER.ODS_RH_RTW_ST_Q_R'',
	>
	>     -- Doris 用户名
[连接身份与敏感参数已脱敏]
	>
	>     -- Doris 密码
[连接身份与敏感参数已脱敏]
	>
	>     -- Stream Load Label 前缀
	>     -- 同一个运行任务必须保持唯一
	>     ''sink.label-prefix'' = ''mysql_cdc_st_q_r_01'',
	>
	>     -- 使用 JSON 格式写入
	>     ''sink.properties.format'' = ''json'',
	>
	>     -- 每行按照一条 JSON 数据解析
	>     ''sink.properties.read_json_by_line'' = ''true'',
	>
	>     -- 支持同步 MySQL DELETE 事件
	>     ''sink.enable-delete'' = ''true'',
	>
	>     -- 开启两阶段提交，配合 Checkpoint 保证一致性
	>     ''sink.enable-2pc'' = ''true'',
	>
	>     -- 写入失败最大重试次数
	>     ''sink.max-retries'' = ''3''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 18:34:21.053] INFO ->
	Flink SQL> [31;1m[ERROR] Could not execute SQL statement. Reason:
	org.apache.doris.shaded.com.fasterxml.jackson.databind.exc.InvalidFormatException: Cannot deserialize value of type `int` from String "errCode=7,detailMessage=unknowntable,tableName=ODS_RH_RTW_ST_Q_R": not a valid `int` value
	 at [Source: (String)""errCode=7,detailMessage=unknowntable,tableName=ODS_RH_RTW_ST_Q_R""; line: 1, column: 1][0m
[2026-08-04 18:34:22.062] INFO ->
	Shutting down the session...
	done.
[2026-08-04 18:34:22.070] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629, processId:47427 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 18:34:22.075] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629079832128/9/626/629.log, fetch way: log
[2026-08-04 18:34:22.082] INFO
***********************************************************************************************[INFO] 2026-08-04 18:34:22.086 - *********************************  Finalize task instance  ************************************
[2026-08-04 18:34:22.092] INFO ***********************************************************************************************
[2026-08-04 18:34:22.097] INFO Upload output files: [] successfully
[2026-08-04 18:34:22.108] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 18:34:22.112] INFO Remove the current task execute context from worker cache
[2026-08-04 18:34:22.116] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629
[2026-08-04 18:34:22.122] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_9/626/629
[2026-08-04 18:34:22.127] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 18:33:59.719000','2026-08-04 18:34:22.867000'),
 (618,33,7,'河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】','河道水情_监测库到ODS_实时流量明细_实时-7-20260804181445786','REALTIME','START','SUCCESS','2026-08-04 18:14:45','2026-08-04 18:15:05',20,'吴同','吴同',NULL,'[2026-08-04 18:14:45.832] INFO
***********************************************************************************************[INFO] 2026-08-04 18:14:45.862 - *********************************  Initialize task context  ***********************************
[2026-08-04 18:14:45.870] INFO ***********************************************************************************************
[2026-08-04 18:14:45.874] INFO Begin to initialize task
[2026-08-04 18:14:45.877] INFO Set task startTime: 1785838485877
[2026-08-04 18:14:45.880] INFO Set task appId: 618_621
[2026-08-04 18:14:45.883] INFO End initialize task {
  "taskInstanceId" : 621,
  "taskName" : "河道水情_监测库到ODS_实时流量明细_实时",
  "firstSubmitTime" : 1785838485817,
  "startTime" : 1785838485877,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629079832128/7/618/621.log",
  "processId" : 0,
  "processDefineCode" : 180629079832128,
  "processDefineVersion" : 7,
  "processInstanceId" : 618,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "618"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "621"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804181445"
    }
  },
  "taskAppId" : "618_621",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 18:14:45.892] INFO
***********************************************************************************************[INFO] 2026-08-04 18:14:45.898 - *********************************  Load task instance plugin  *********************************
[2026-08-04 18:14:45.902] INFO ***********************************************************************************************
[2026-08-04 18:14:45.914] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 18:14:45.919] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 18:14:45.924] INFO TenantCode: root check successfully
[2026-08-04 18:14:45.933] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621 check successfully
[2026-08-04 18:14:45.939] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 18:14:45.948] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 18:14:45.980 - Writing content: set execution.target=local;
set parallelism.default=1;
[2026-08-04 18:14:45.952] INFO Download upstream files: [] successfully
[2026-08-04 18:14:45.959] INFO Task plugin instance: FLINK create successfully
[2026-08-04 18:14:45.994] INFO Writing content: -- 开启 Checkpoint
SET ''execution.checkpointing.interval'' = ''10s'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- 等待实时任务持续运行，避免本地模式提交后退出
SET ''table.dml-sync'' = ''true'';
-- =====================================================
-- 1. 创建 MySQL CDC 源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_Q_R;
CREATE TABLE MYSQL_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    ''connector'' = ''mysql-cdc'',
    ''hostname'' = ''mysql57'',
    ''port'' = ''3306'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''server-time-zone'' = ''Asia/Shanghai'',
    -- 源表没有主键，关闭增量快照
    ''scan.incremental.snapshot.enabled'' = ''false'',
    -- 不读取历史数据，只读取任务启动后产生的 Binlog
    ''scan.startup.mode'' = ''latest-offset'',
    ''database-name'' = ''RIVER_WATER'',
    ''table-name'' = ''st_q_r'',
    ''server-id'' = ''5401'',
    ''connect.timeout'' = ''30s'',
    ''connect.max-retries'' = ''3'',
    ''heartbeat.interval'' = ''30s''
);
-- =====================================================
-- 2. 创建 Doris JDBC 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    ''connector'' = ''jdbc'',
    ''url'' = ''[REDACTED_CONNECTION]'',
    ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
    ''sink.buffer-flush.max-rows'' = ''1000'',
    ''sink.buffer-flush.interval'' = ''1s'',
    ''sink.max-retries'' = ''3'',
    ''sink.parallelism'' = ''1''
);
-- =====================================================
-- 3. 启动 MySQL 到 Doris 实时同步
-- =====================================================
INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R;
[2026-08-04 18:14:45.984] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621/618_621_init.sql
[2026-08-04 18:14:46.002] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621/618_621_node.sql
[2026-08-04 18:14:46.013] INFO Success initialized task plugin instance successfully
[2026-08-04 18:14:46.019] INFO Set taskVarPool: null successfully
[2026-08-04 18:14:46.024] INFO
***********************************************************************************************[INFO] 2026-08-04 18:14:46.030 - *********************************  Execute task instance  *************************************
[2026-08-04 18:14:46.035] INFO ***********************************************************************************************
[2026-08-04 18:14:46.044] INFO Final Shell file is:
[2026-08-04 18:14:46.048] INFO ****************************** Script Content *****************************************************************
[2026-08-04 18:14:46.051] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621/618_621_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621/618_621_node.sql
[2026-08-04 18:14:46.056] INFO ****************************** Script Content *****************************************************************
[2026-08-04 18:14:46.063] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621/618_621.sh
[2026-08-04 18:14:46.085] INFO processInstanceId:618,taskInstanceId:621
[2026-08-04 18:14:46.086] INFO process start, process id is: 42649
[2026-08-04 18:14:58.101] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621/618_621_init.sql
	Aug 04, 2026 6:14:57 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 18:14:59.106] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
	> ) WITH (
	>     ''connector'' = ''mysql-cdc'',
	>     ''hostname'' = ''mysql57'',
	>     ''port'' = ''3306'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>     ''server-time-zone'' = ''Asia/Shanghai'',
	>
	>     -- 源表没有主键，关闭增量快照
	>     ''scan.incremental.snapshot.enabled'' = ''false'',
	>
	>     -- 不读取历史数据，只读取任务启动后产生的 Binlog
	>     ''scan.startup.mode'' = ''latest-offset'',
	>
	>     ''database-name'' = ''RIVER_WATER'',
	>     ''table-name'' = ''st_q_r'',
	>     ''server-id'' = ''5401'',
	>     ''connect.timeout'' = ''30s'',
	>     ''connect.max-retries'' = ''3'',
	>     ''heartbeat.interval'' = ''30s''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 18:15:00.111] INFO ->
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
	> ) WITH (
	>     ''connector'' = ''jdbc'',
	>     ''url'' = ''[REDACTED_CONNECTION]'',
	>     ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
[连接身份与敏感参数已脱敏]
[连接身份与敏感参数已脱敏]
	>     ''sink.buffer-flush.max-rows'' = ''1000'',
	>     ''sink.buffer-flush.interval'' = ''1s'',
	>     ''sink.max-retries'' = ''3'',
	>     ''sink.parallelism'' = ''1''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 18:15:04.116] INFO ->
	Flink SQL> [31;1m[ERROR] Could not execute SQL statement. Reason:
	java.lang.IllegalStateException: please declare primary key for sink table when query contains update/delete record.[0m
[2026-08-04 18:15:05.138] INFO ->
	Shutting down the session...
	done.
[2026-08-04 18:15:05.142] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621, processId:42649 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 18:15:05.147] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629079832128/7/618/621.log, fetch way: log
[2026-08-04 18:15:05.153] INFO
***********************************************************************************************[INFO] 2026-08-04 18:15:05.158 - *********************************  Finalize task instance  ************************************
[2026-08-04 18:15:05.161] INFO ***********************************************************************************************
[2026-08-04 18:15:05.168] INFO Upload output files: [] successfully
[2026-08-04 18:15:05.176] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 18:15:05.179] INFO Remove the current task execute context from worker cache
[2026-08-04 18:15:05.182] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621
[2026-08-04 18:15:05.187] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_7/618/621
[2026-08-04 18:15:05.193] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 18:14:45.815000','2026-08-04 18:15:05.757000'),
 (613,33,7,'河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】','河道水情_监测库到ODS_实时流量明细_实时-6-20260804180529821','REALTIME','START','SUCCESS','2026-08-04 18:05:29','2026-08-04 18:05:44',15,'吴同','吴同',NULL,'[2026-08-04 18:05:29.861] INFO
***********************************************************************************************[INFO] 2026-08-04 18:05:29.887 - *********************************  Initialize task context  ***********************************
[2026-08-04 18:05:29.892] INFO ***********************************************************************************************
[2026-08-04 18:05:29.898] INFO Begin to initialize task
[2026-08-04 18:05:29.905] INFO Set task startTime: 1785837929905
[2026-08-04 18:05:29.908] INFO Set task appId: 613_616
[2026-08-04 18:05:29.912] INFO End initialize task {
  "taskInstanceId" : 616,
  "taskName" : "河道水情_监测库到ODS_实时流量明细_实时",
  "firstSubmitTime" : 1785837929847,
  "startTime" : 1785837929905,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629079832128/6/613/616.log",
  "processId" : 0,
  "processDefineCode" : 180629079832128,
  "processDefineVersion" : 6,
  "processInstanceId" : 613,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "613"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "616"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804180529"
    }
  },
  "taskAppId" : "613_616",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 18:05:29.917] INFO
***********************************************************************************************[INFO] 2026-08-04 18:05:29.919 - *********************************  Load task instance plugin  *********************************
[2026-08-04 18:05:29.923] INFO ***********************************************************************************************
[2026-08-04 18:05:29.928] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 18:05:29.931] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 18:05:29.934] INFO TenantCode: root check successfully
[2026-08-04 18:05:29.939] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616 check successfully
[2026-08-04 18:05:29.942] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 18:05:29.945] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 18:05:29.948 - Download upstream files: [] successfully
[2026-08-04 18:05:29.951] INFO Task plugin instance: FLINK create successfully
[2026-08-04 18:05:29.954] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 18:05:29.958] INFO Writing content: set execution.target=local;
set parallelism.default=1;
[2026-08-04 18:05:29.961] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616/613_616_init.sql
[2026-08-04 18:05:29.965] INFO Writing content: -- 开启 Checkpoint
SET ''execution.checkpointing.interval'' = ''10s'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- =====================================================
-- 1. 创建 MySQL CDC 源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_Q_R;
CREATE TABLE MYSQL_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    -- 使用 MySQL CDC 连接器
    ''connector'' = ''mysql-cdc'',
    -- MySQL Docker 容器名称
    ''hostname'' = ''mysql57'',
    -- MySQL 端口
    ''port'' = ''3306'',
    -- MySQL 用户名
[连接身份与敏感参数已脱敏]
    -- MySQL 密码
[连接身份与敏感参数已脱敏]
    -- MySQL 数据库名称
    ''database-name'' = ''RIVER_WATER'',
    -- MySQL 表名称
    ''table-name'' = ''st_q_r'',
    -- MySQL CDC Server ID
    ''server-id'' = ''5401'',
    -- MySQL 时区
    ''server-time-zone'' = ''Asia/Shanghai'',
    -- 从任务启动后的最新 Binlog 位置开始读取
    ''scan.startup.mode'' = ''latest-offset'',
    -- 连接超时时间
    ''connect.timeout'' = ''30s'',
    -- 连接失败重试次数
    ''connect.max-retries'' = ''3'',
    -- CDC 心跳间隔
    ''heartbeat.interval'' = ''30s''
);
-- =====================================================
-- 2. 创建 Doris JDBC 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    -- 使用 JDBC 连接器，通过 Doris MySQL 协议写入
    ''connector'' = ''jdbc'',
    -- Doris MySQL 协议地址
    ''url'' = ''[REDACTED_CONNECTION]'',
    -- Doris 目标表名称
    ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
    -- Doris 用户名
[连接身份与敏感参数已脱敏]
    -- Doris 密码
[连接身份与敏感参数已脱敏]
    -- 累计 1000 条数据后批量提交
    ''sink.buffer-flush.max-rows'' = ''1000'',
    -- 最长每 1 秒提交一次
    ''sink.buffer-flush.interval'' = ''1s'',
    -- 写入失败重试次数
    ''sink.max-retries'' = ''3'',
    -- Doris 写入并行度
    ''sink.parallelism'' = ''1''
);
-- =====================================================
-- 3. 启动 MySQL 到 Doris 的实时同步
-- =====================================================
INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R;
[2026-08-04 18:05:29.968] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616/613_616_node.sql
[2026-08-04 18:05:29.971] INFO Success initialized task plugin instance successfully
[2026-08-04 18:05:29.974] INFO Set taskVarPool: null successfully
[2026-08-04 18:05:29.977] INFO
***********************************************************************************************[INFO] 2026-08-04 18:05:29.980 - *********************************  Execute task instance  *************************************
[2026-08-04 18:05:29.983] INFO ***********************************************************************************************
[2026-08-04 18:05:29.987] INFO Final Shell file is:
[2026-08-04 18:05:29.990] INFO ****************************** Script Content *****************************************************************
[2026-08-04 18:05:29.994] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616/613_616_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616/613_616_node.sql
[2026-08-04 18:05:29.997] INFO ****************************** Script Content *****************************************************************
[2026-08-04 18:05:30.000] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616/613_616.sh
[2026-08-04 18:05:30.008] INFO processInstanceId:613,taskInstanceId:616
[2026-08-04 18:05:30.008] INFO processInstanceId:613,taskInstanceId:616
[2026-08-04 18:05:30.008] INFO process start, process id is: 40309
[2026-08-04 18:05:40.019] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616/613_616_init.sql
	Aug 04, 2026 6:05:39 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 18:05:41.033] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
	> ) WITH (
	>     -- 使用 MySQL CDC 连接器
	>     ''connector'' = ''mysql-cdc'',
	>     -- MySQL Docker 容器名称
	>     ''hostname'' = ''mysql57'',
	>     -- MySQL 端口
	>     ''port'' = ''3306'',
	>     -- MySQL 用户名
[连接身份与敏感参数已脱敏]
	>     -- MySQL 密码
[连接身份与敏感参数已脱敏]
	>     -- MySQL 数据库名称
	>     ''database-name'' = ''RIVER_WATER'',
	>     -- MySQL 表名称
	>     ''table-name'' = ''st_q_r'',
	>     -- MySQL CDC Server ID
	>     ''server-id'' = ''5401'',
	>     -- MySQL 时区
	>     ''server-time-zone'' = ''Asia/Shanghai'',
	>     -- 从任务启动后的最新 Binlog 位置开始读取
	>     ''scan.startup.mode'' = ''latest-offset'',
	>     -- 连接超时时间
	>     ''connect.timeout'' = ''30s'',
	>     -- 连接失败重试次数
	>     ''connect.max-retries'' = ''3'',
	>     -- CDC 心跳间隔
	>     ''heartbeat.interval'' = ''30s''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 18:05:42.041] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
	> ) WITH (
	>     -- 使用 JDBC 连接器，通过 Doris MySQL 协议写入
	>     ''connector'' = ''jdbc'',
	>     -- Doris MySQL 协议地址
	>     ''url'' = ''[REDACTED_CONNECTION]'',
	>     -- Doris 目标表名称
	>     ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
	>     -- Doris 用户名
[连接身份与敏感参数已脱敏]
	>     -- Doris 密码
[连接身份与敏感参数已脱敏]
	>     -- 累计 1000 条数据后批量提交
	>     ''sink.buffer-flush.max-rows'' = ''1000'',
	>     -- 最长每 1 秒提交一次
	>     ''sink.buffer-flush.interval'' = ''1s'',
	>     -- 写入失败重试次数
	>     ''sink.max-retries'' = ''3'',
	>     -- Doris 写入并行度
	>     ''sink.parallelism'' = ''1''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 18:05:43.055] INFO ->
	Flink SQL> [31;1m[ERROR] Could not execute SQL statement. Reason:
	org.apache.flink.table.api.ValidationException: ''scan.incremental.snapshot.chunk.key-column'' is required for table without primary key when ''scan.incremental.snapshot.enabled'' enabled.[0m
[2026-08-04 18:05:44.064] INFO ->
	Shutting down the session...
	done.
[2026-08-04 18:05:44.071] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616, processId:40309 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 18:05:44.075] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629079832128/6/613/616.log, fetch way: log
[2026-08-04 18:05:44.082] INFO
***********************************************************************************************[INFO] 2026-08-04 18:05:44.084 - *********************************  Finalize task instance  ************************************
[2026-08-04 18:05:44.088] INFO ***********************************************************************************************
[2026-08-04 18:05:44.092] INFO Upload output files: [] successfully
[2026-08-04 18:05:44.104] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 18:05:44.107] INFO Remove the current task execute context from worker cache
[2026-08-04 18:05:44.111] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616
[2026-08-04 18:05:44.115] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_6/613/616
[2026-08-04 18:05:44.118] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 18:05:29.841000','2026-08-04 18:05:44.781000'),
 (608,33,7,'河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】','河道水情_监测库到ODS_实时流量明细_实时-5-20260804175410222','REALTIME','START','SUCCESS','2026-08-04 17:54:10','2026-08-04 17:54:27',17,'吴同','吴同',NULL,'[2026-08-04 17:54:10.321] INFO
***********************************************************************************************[INFO] 2026-08-04 17:54:10.359 - *********************************  Initialize task context  ***********************************
[2026-08-04 17:54:10.375] INFO ***********************************************************************************************
[2026-08-04 17:54:10.382] INFO Begin to initialize task
[2026-08-04 17:54:10.388] INFO Set task startTime: 1785837250388
[2026-08-04 17:54:10.394] INFO Set task appId: 608_611
[2026-08-04 17:54:10.407] INFO End initialize task {
  "taskInstanceId" : 611,
  "taskName" : "河道水情_监测库到ODS_实时流量明细_实时",
  "firstSubmitTime" : 1785837250281,
  "startTime" : 1785837250388,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629079832128/5/608/611.log",
  "processId" : 0,
  "processDefineCode" : 180629079832128,
  "processDefineVersion" : 5,
  "processInstanceId" : 608,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "608"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "611"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804175410"
    }
  },
  "taskAppId" : "608_611",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 17:54:10.423] INFO *********************************  Load task instance plugin  *********************************
[2026-08-04 17:54:10.432] INFO ***********************************************************************************************
[2026-08-04 17:54:10.469] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 17:54:10.483] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611 check successfully
[2026-08-04 17:54:10.488] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 17:54:10.475] INFO TenantCode: root check successfully
[2026-08-04 17:54:10.502] INFO Task plugin instance: FLINK create successfully
[2026-08-04 17:54:10.510] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 17:54:10.515] INFO Writing content: set execution.target=local;
set parallelism.default=1;
[2026-08-04 17:54:10.520] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611/608_611_init.sql
[2026-08-04 17:54:10.525] INFO Writing content: -- 开启 Checkpoint
SET ''execution.checkpointing.interval'' = ''10s'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- =====================================================
-- 1. 创建 MySQL CDC 源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_Q_R;
CREATE TABLE MYSQL_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    -- 使用 MySQL CDC 连接器
    ''connector'' = ''mysql-cdc'',
    -- MySQL Docker 容器名称
    ''hostname'' = ''mysql57'',
    -- MySQL 端口
    ''port'' = ''3306'',
    -- MySQL 用户名
[连接身份与敏感参数已脱敏]
    -- MySQL 密码
[连接身份与敏感参数已脱敏]
    -- MySQL 数据库名称
    ''database-name'' = ''RIVER_WATER'',
    -- MySQL 表名称
    ''table-name'' = ''st_q_r'',
    -- MySQL CDC Server ID
    ''server-id'' = ''5401'',
    -- MySQL 时区
    ''server-time-zone'' = ''Asia/Shanghai'',
    -- 从任务启动后的最新 Binlog 位置开始读取
    ''scan.startup.mode'' = ''latest-offset'',
    -- 将更新和删除事件转换为追加数据
    ''scan.read-changelog-as-append-only.enabled'' = ''true'',
    -- 连接超时时间
    ''connect.timeout'' = ''30s'',
    -- 连接失败重试次数
    ''connect.max-retries'' = ''3'',
    -- CDC 心跳间隔
    ''heartbeat.interval'' = ''30s''
);
-- =====================================================
-- 2. 创建 Doris JDBC 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    -- 使用 JDBC 连接器，通过 Doris MySQL 协议写入
    ''connector'' = ''jdbc'',
    -- Doris MySQL 协议地址
    ''url'' = ''[REDACTED_CONNECTION]'',
    -- Doris 目标表名称
    ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
    -- Doris 用户名
[连接身份与敏感参数已脱敏]
    -- Doris 密码
[连接身份与敏感参数已脱敏]
    -- 累计 1000 条数据后批量提交
    ''sink.buffer-flush.max-rows'' = ''1000'',
    -- 最长每 1 秒提交一次
    ''sink.buffer-flush.interval'' = ''1s'',
    -- 写入失败重试次数
    ''sink.max-retries'' = ''3'',
    -- Doris 写入并行度
    ''sink.parallelism'' = ''1''
);
-- =====================================================
-- 3. 启动 MySQL 到 Doris 的实时同步
-- =====================================================
INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R;
[2026-08-04 17:54:10.530] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611/608_611_node.sql
[2026-08-04 17:54:10.534] INFO Success initialized task plugin instance successfully
[2026-08-04 17:54:10.537] INFO Set taskVarPool: null successfully
[2026-08-04 17:54:10.541] INFO
***********************************************************************************************[INFO] 2026-08-04 17:54:10.546 - *********************************  Execute task instance  *************************************
[2026-08-04 17:54:10.549] INFO ***********************************************************************************************
[2026-08-04 17:54:10.555] INFO Final Shell file is:
[2026-08-04 17:54:10.560] INFO ****************************** Script Content *****************************************************************
[2026-08-04 17:54:10.564] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611/608_611_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611/608_611_node.sql
[2026-08-04 17:54:10.569] INFO ****************************** Script Content *****************************************************************
[2026-08-04 17:54:10.573] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611/608_611.sh
[2026-08-04 17:54:10.589] INFO processInstanceId:608,taskInstanceId:611
[2026-08-04 17:54:10.590] INFO processInstanceId:608,taskInstanceId:611
[2026-08-04 17:54:10.589] INFO process start, process id is: 37427
[2026-08-04 17:54:22.605] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611/608_611_init.sql
	Aug 04, 2026 5:54:21 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 17:54:23.610] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
	> ) WITH (
	>     -- 使用 MySQL CDC 连接器
	>     ''connector'' = ''mysql-cdc'',
	>     -- MySQL Docker 容器名称
	>     ''hostname'' = ''mysql57'',
	>     -- MySQL 端口
	>     ''port'' = ''3306'',
	>     -- MySQL 用户名
[连接身份与敏感参数已脱敏]
	>     -- MySQL 密码
[连接身份与敏感参数已脱敏]
	>     -- MySQL 数据库名称
	>     ''database-name'' = ''RIVER_WATER'',
	>     -- MySQL 表名称
	>     ''table-name'' = ''st_q_r'',
	>     -- MySQL CDC Server ID
	>     ''server-id'' = ''5401'',
	>     -- MySQL 时区
	>     ''server-time-zone'' = ''Asia/Shanghai'',
	>     -- 从任务启动后的最新 Binlog 位置开始读取
	>     ''scan.startup.mode'' = ''latest-offset'',
	>     -- 将更新和删除事件转换为追加数据
	>     ''scan.read-changelog-as-append-only.enabled'' = ''true'',
	>     -- 连接超时时间
	>     ''connect.timeout'' = ''30s'',
	>     -- 连接失败重试次数
	>     ''connect.max-retries'' = ''3'',
	>     -- CDC 心跳间隔
	>     ''heartbeat.interval'' = ''30s''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
[2026-08-04 17:54:24.622] INFO ->
	> ) WITH (
	>     -- 使用 JDBC 连接器，通过 Doris MySQL 协议写入
	>     ''connector'' = ''jdbc'',
	>     -- Doris MySQL 协议地址
	>     ''url'' = ''[REDACTED_CONNECTION]'',
	>     -- Doris 目标表名称
	>     ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
	>     -- Doris 用户名
[连接身份与敏感参数已脱敏]
	>     -- Doris 密码
[连接身份与敏感参数已脱敏]
	>     -- 累计 1000 条数据后批量提交
	>     ''sink.buffer-flush.max-rows'' = ''1000'',
	>     -- 最长每 1 秒提交一次
	>     ''sink.buffer-flush.interval'' = ''1s'',
	>     -- 写入失败重试次数
	>     ''sink.max-retries'' = ''3'',
	>     -- Doris 写入并行度
	>     ''sink.parallelism'' = ''1''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 17:54:25.633] INFO ->
	Flink SQL> [31;1m[ERROR] Could not execute SQL statement. Reason:
	org.apache.flink.table.api.ValidationException: Unsupported options found for ''mysql-cdc''.
	Unsupported options:
	scan.read-changelog-as-append-only.enabled
	Supported options:
	chunk-key.even-distribution.factor.lower-bound
	chunk-key.even-distribution.factor.upper-bound
	chunk-meta.group.size
	connect.max-retries
	connect.timeout
	connection.pool.size
	connector
	database-name
	heartbeat.interval
	hostname
[连接身份与敏感参数已脱敏]
	port
	property-version
	scan.incremental.close-idle-reader.enabled
	scan.incremental.snapshot.chunk.key-column
	scan.incremental.snapshot.chunk.size
	scan.incremental.snapshot.enabled
	scan.newly-added-table.enabled
	scan.snapshot.fetch.size
	scan.startup.mode
	scan.startup.specific-offset.file
	scan.startup.specific-offset.gtid-set
	scan.startup.specific-offset.pos
	scan.startup.specific-offset.skip-events
	scan.startup.specific-offset.skip-rows
	scan.startup.timestamp-millis
	server-id
	server-time-zone
	split-key.even-distribution.factor.lower-bound
	split-key.even-distribution.factor.upper-bound
	table-name
[连接身份与敏感参数已脱敏]
[2026-08-04 17:54:26.641] INFO ->
	Shutting down the session...
	done.
[2026-08-04 17:54:26.646] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611, processId:37427 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 17:54:26.650] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629079832128/5/608/611.log, fetch way: log
[2026-08-04 17:54:26.658] INFO
***********************************************************************************************[INFO] 2026-08-04 17:54:26.662 - *********************************  Finalize task instance  ************************************
[2026-08-04 17:54:26.668] INFO ***********************************************************************************************
[2026-08-04 17:54:26.673] INFO Upload output files: [] successfully
[2026-08-04 17:54:26.679] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 17:54:26.682] INFO Remove the current task execute context from worker cache
[2026-08-04 17:54:26.685] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611
[2026-08-04 17:54:26.691] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/608/611
[2026-08-04 17:54:26.695] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 17:54:10.296000','2026-08-04 17:54:27.720000'),
 (604,33,7,'河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】','河道水情_监测库到ODS_实时流量明细_实时-5-20260804174741780','REALTIME','START','SUCCESS','2026-08-04 17:47:41','2026-08-04 17:47:57',16,'吴同','吴同',NULL,'[2026-08-04 17:47:41.831] INFO
***********************************************************************************************[INFO] 2026-08-04 17:47:41.854 - *********************************  Initialize task context  ***********************************
[2026-08-04 17:47:41.858] INFO ***********************************************************************************************
[2026-08-04 17:47:41.862] INFO Begin to initialize task
[2026-08-04 17:47:41.864] INFO Set task startTime: 1785836861864
[2026-08-04 17:47:41.868] INFO Set task appId: 604_607
[2026-08-04 17:47:41.871] INFO End initialize task {
  "taskInstanceId" : 607,
  "taskName" : "河道水情_监测库到ODS_实时流量明细_实时",
  "firstSubmitTime" : 1785836861816,
  "startTime" : 1785836861864,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629079832128/5/604/607.log",
  "processId" : 0,
  "processDefineCode" : 180629079832128,
  "processDefineVersion" : 5,
  "processInstanceId" : 604,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "604"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "607"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804174741"
    }
  },
  "taskAppId" : "604_607",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 17:47:41.876] INFO
***********************************************************************************************[INFO] 2026-08-04 17:47:41.879 - *********************************  Load task instance plugin  *********************************
[2026-08-04 17:47:41.882] INFO ***********************************************************************************************
[2026-08-04 17:47:41.890] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 17:47:41.893] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 17:47:41.896] INFO TenantCode: root check successfully
[2026-08-04 17:47:41.901] INFO WorkflowInstanceExecDir: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607 check successfully
[2026-08-04 17:47:41.904] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 17:47:41.908] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 17:47:41.910 - Download upstream files: [] successfully
[2026-08-04 17:47:41.917] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 17:47:41.925] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607/604_607_init.sql
[2026-08-04 17:47:41.928] INFO Writing content: -- 开启 Checkpoint
SET ''execution.checkpointing.interval'' = ''10s'';
-- 设置默认并行度
SET ''parallelism.default'' = ''1'';
-- =====================================================
-- 1. 创建 MySQL CDC 源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_Q_R;
CREATE TABLE MYSQL_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    -- 使用 MySQL CDC 连接器
    ''connector'' = ''mysql-cdc'',
    -- MySQL Docker 容器名称
    ''hostname'' = ''mysql57'',
    -- MySQL 端口
    ''port'' = ''3306'',
    -- MySQL 用户名
[连接身份与敏感参数已脱敏]
    -- MySQL 密码
[连接身份与敏感参数已脱敏]
    -- MySQL 数据库名称
    ''database-name'' = ''RIVER_WATER'',
    -- MySQL 表名称
    ''table-name'' = ''st_q_r'',
    -- MySQL CDC Server ID
    ''server-id'' = ''5401'',
    -- MySQL 时区
    ''server-time-zone'' = ''Asia/Shanghai'',
    -- 从任务启动后的最新 Binlog 位置开始读取
    ''scan.startup.mode'' = ''latest-offset'',
    -- 将更新和删除事件转换为追加数据
    ''scan.read-changelog-as-append-only.enabled'' = ''true'',
    -- 连接超时时间
    ''connect.timeout'' = ''30s'',
    -- 连接失败重试次数
    ''connect.max-retries'' = ''3'',
    -- CDC 心跳间隔
    ''heartbeat.interval'' = ''30s''
);
-- =====================================================
-- 2. 创建 Doris JDBC 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    -- 使用 JDBC 连接器，通过 Doris MySQL 协议写入
    ''connector'' = ''jdbc'',
    -- Doris MySQL 协议地址
    ''url'' = ''[REDACTED_CONNECTION]'',
    -- Doris 目标表名称
    ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
    -- Doris 用户名
[连接身份与敏感参数已脱敏]
    -- Doris 密码
[连接身份与敏感参数已脱敏]
    -- 累计 1000 条数据后批量提交
    ''sink.buffer-flush.max-rows'' = ''1000'',
    -- 最长每 1 秒提交一次
    ''sink.buffer-flush.interval'' = ''1s'',
    -- 写入失败重试次数
    ''sink.max-retries'' = ''3'',
    -- Doris 写入并行度
    ''sink.parallelism'' = ''1''
);
-- =====================================================
-- 3. 启动 MySQL 到 Doris 的实时同步
-- =====================================================
INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R;
[2026-08-04 17:47:41.932] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607/604_607_node.sql
[2026-08-04 17:47:41.935] INFO Success initialized task plugin instance successfully
[2026-08-04 17:47:41.938] INFO Set taskVarPool: null successfully
[2026-08-04 17:47:41.941] INFO
***********************************************************************************************[INFO] 2026-08-04 17:47:41.944 - *********************************  Execute task instance  *************************************
[2026-08-04 17:47:41.947] INFO ***********************************************************************************************
[2026-08-04 17:47:41.950] INFO Final Shell file is:
[2026-08-04 17:47:41.953] INFO ****************************** Script Content *****************************************************************
[2026-08-04 17:47:41.957] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607/604_607_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607/604_607_node.sql
[2026-08-04 17:47:41.960] INFO ****************************** Script Content *****************************************************************
[2026-08-04 17:47:41.963] INFO Executing shell command : sudo -u root -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607/604_607.sh
[2026-08-04 17:47:41.972] INFO processInstanceId:604,taskInstanceId:607
[2026-08-04 17:47:41.972] INFO processInstanceId:604,taskInstanceId:607
[2026-08-04 17:47:41.972] INFO process start, process id is: 36314
[2026-08-04 17:47:53.980] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607/604_607_init.sql
	Aug 04, 2026 5:47:53 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
[2026-08-04 17:47:54.987] INFO ->
	> ) WITH (
	>     -- 使用 MySQL CDC 连接器
	>     ''connector'' = ''mysql-cdc'',
	>     -- MySQL Docker 容器名称
	>     ''hostname'' = ''mysql57'',
	>     -- MySQL 端口
	>     ''port'' = ''3306'',
	>     -- MySQL 用户名
[连接身份与敏感参数已脱敏]
	>     -- MySQL 密码
[连接身份与敏感参数已脱敏]
	>     -- MySQL 数据库名称
	>     ''database-name'' = ''RIVER_WATER'',
	>     -- MySQL 表名称
	>     ''table-name'' = ''st_q_r'',
	>     -- MySQL CDC Server ID
	>     ''server-id'' = ''5401'',
	>     -- MySQL 时区
	>     ''server-time-zone'' = ''Asia/Shanghai'',
	>     -- 从任务启动后的最新 Binlog 位置开始读取
	>     ''scan.startup.mode'' = ''latest-offset'',
	>     -- 将更新和删除事件转换为追加数据
	>     ''scan.read-changelog-as-append-only.enabled'' = ''true'',
	>     -- 连接超时时间
	>     ''connect.timeout'' = ''30s'',
	>     -- 连接失败重试次数
	>     ''connect.max-retries'' = ''3'',
	>     -- CDC 心跳间隔
	>     ''heartbeat.interval'' = ''30s''
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
	> ) WITH (
	>     -- 使用 JDBC 连接器，通过 Doris MySQL 协议写入
	>     ''connector'' = ''jdbc'',
	>     -- Doris MySQL 协议地址
	>     ''url'' = ''[REDACTED_CONNECTION]'',
	>     -- Doris 目标表名称
	>     ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
	>     -- Doris 用户名
[连接身份与敏感参数已脱敏]
	>     -- Doris 密码
[连接身份与敏感参数已脱敏]
	>     -- 累计 1000 条数据后批量提交
	>     ''sink.buffer-flush.max-rows'' = ''1000'',
	>     -- 最长每 1 秒提交一次
	>     ''sink.buffer-flush.interval'' = ''1s'',
	>     -- 写入失败重试次数
	>     ''sink.max-retries'' = ''3'',
	>     -- Doris 写入并行度
	>     ''sink.parallelism'' = ''1''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 17:47:55.994] INFO ->
	Flink SQL> [31;1m[ERROR] Could not execute SQL statement. Reason:
	org.apache.flink.table.api.ValidationException: Unsupported options found for ''mysql-cdc''.
	Unsupported options:
	scan.read-changelog-as-append-only.enabled
	Supported options:
	chunk-key.even-distribution.factor.lower-bound
	chunk-key.even-distribution.factor.upper-bound
	chunk-meta.group.size
	connect.max-retries
	connect.timeout
	connection.pool.size
	connector
	database-name
	heartbeat.interval
	hostname
[连接身份与敏感参数已脱敏]
	port
	property-version
	scan.incremental.close-idle-reader.enabled
	scan.incremental.snapshot.chunk.key-column
	scan.incremental.snapshot.chunk.size
	scan.incremental.snapshot.enabled
	scan.newly-added-table.enabled
	scan.snapshot.fetch.size
	scan.startup.mode
	scan.startup.specific-offset.file
	scan.startup.specific-offset.gtid-set
	scan.startup.specific-offset.pos
	scan.startup.specific-offset.skip-events
	scan.startup.specific-offset.skip-rows
	scan.startup.timestamp-millis
	server-id
	server-time-zone
	split-key.even-distribution.factor.lower-bound
	split-key.even-distribution.factor.upper-bound
	table-name
[连接身份与敏感参数已脱敏]
[2026-08-04 17:47:57.004] INFO ->
	Shutting down the session...
	done.
[2026-08-04 17:47:57.008] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607, processId:36314 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 17:47:57.012] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629079832128/5/604/607.log, fetch way: log
[2026-08-04 17:47:57.019] INFO
***********************************************************************************************[INFO] 2026-08-04 17:47:57.025 - *********************************  Finalize task instance  ************************************
[2026-08-04 17:47:57.028] INFO ***********************************************************************************************
[2026-08-04 17:47:57.035] INFO Upload output files: [] successfully
[2026-08-04 17:47:57.042] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 17:47:57.046] INFO Remove the current task execute context from worker cache
[2026-08-04 17:47:57.049] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607
[2026-08-04 17:47:57.053] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_5/604/607
[2026-08-04 17:47:57.058] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 17:47:41.813000','2026-08-04 17:47:57.639000'),
 (601,33,7,'河道水情_监测库到ODS_实时流量明细_实时【外部表->ODS】','河道水情_监测库到ODS_实时流量明细_实时-4-20260804174005878','REALTIME','START','SUCCESS','2026-08-04 17:40:05','2026-08-04 17:40:22',17,'吴同','吴同',NULL,'[2026-08-04 17:40:05.950] INFO
***********************************************************************************************[INFO] 2026-08-04 17:40:05.976 - *********************************  Initialize task context  ***********************************
[2026-08-04 17:40:05.980] INFO ***********************************************************************************************
[2026-08-04 17:40:05.985] INFO Begin to initialize task
[2026-08-04 17:40:05.991] INFO Set task startTime: 1785836405991
[2026-08-04 17:40:05.997] INFO Set task appId: 601_604
[2026-08-04 17:40:06.002] INFO End initialize task {
  "taskInstanceId" : 604,
  "taskName" : "河道水情_监测库到ODS_实时流量明细_实时",
  "firstSubmitTime" : 1785836405918,
  "startTime" : 1785836405991,
  "taskType" : "FLINK",
  "workflowInstanceHost" : "[REDACTED]",
[连接身份与敏感参数已脱敏]
  "logPath" : "/opt/dolphinscheduler/logs/20260804/180629079832128/4/601/604.log",
  "processId" : 0,
  "processDefineCode" : 180629079832128,
  "processDefineVersion" : 4,
  "processInstanceId" : 601,
  "scheduleTime" : 0,
  "executorId" : 1,
  "cmdTypeIfComplement" : 0,
  "tenantCode" : "default",
  "processDefineId" : 0,
  "projectId" : 0,
  "projectCode" : 152317790975712,
[连接身份与敏感参数已脱敏]
  "environmentConfig" : "export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH",
  "prepareParamsMap" : {
    "system.task.definition.name" : {
      "prop" : "system.task.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.project.name" : {
      "prop" : "system.project.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : null
    },
    "system.project.code" : {
      "prop" : "system.project.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "152317790975712"
    },
    "system.workflow.instance.id" : {
      "prop" : "system.workflow.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "601"
    },
    "system.biz.curdate" : {
      "prop" : "system.biz.curdate",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804"
    },
    "system.biz.date" : {
      "prop" : "system.biz.date",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260803"
    },
    "system.task.instance.id" : {
      "prop" : "system.task.instance.id",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "604"
    },
    "system.workflow.definition.name" : {
      "prop" : "system.workflow.definition.name",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "河道水情_监测库到ODS_实时流量明细_实时"
    },
    "system.task.definition.code" : {
      "prop" : "system.task.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.workflow.definition.code" : {
      "prop" : "system.workflow.definition.code",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "180629079832128"
    },
    "system.datetime" : {
      "prop" : "system.datetime",
      "direct" : "IN",
      "type" : "VARCHAR",
      "value" : "20260804174005"
    }
  },
  "taskAppId" : "601_604",
  "taskTimeout" : 2147483647,
  "workerGroup" : "default",
  "delayTime" : 0,
  "currentExecutionStatus" : "SUBMITTED_SUCCESS",
  "endTime" : 0,
  "dryRun" : 0,
  "paramsMap" : { },
  "cpuQuota" : -1,
  "memoryMax" : -1,
  "testFlag" : 0,
  "logBufferEnable" : false,
  "dispatchFailTimes" : 0,
  "encrypt" : false
}
[2026-08-04 17:40:06.013] INFO
***********************************************************************************************[INFO] 2026-08-04 17:40:06.017 - *********************************  Load task instance plugin  *********************************
[2026-08-04 17:40:06.021] INFO ***********************************************************************************************
[2026-08-04 17:40:06.040] INFO Send task status RUNNING_EXECUTION master: [INTERNAL_HOST]
[2026-08-04 17:40:06.051] INFO Current tenant is default tenant, will use bootstrap user: root to execute the task
[2026-08-04 17:40:06.057] INFO TenantCode: root check successfully
[2026-08-04 17:40:06.100] INFO Initialize flink task params {
  "localParams" : [ ],
  "varPool" : [ ],
  "mainJar" : null,
  "mainClass" : "",
  "deployMode" : "local",
  "mainArgs" : null,
  "slot" : 1,
  "parallelism" : 1,
  "appName" : null,
  "taskManager" : 2,
  "jobManagerMemory" : "1G",
  "taskManagerMemory" : "2G",
  "resourceList" : [ ],
  "yarnQueue" : "",
  "others" : null,
  "flinkVersion" : ">=1.13",
  "programType" : "SQL",
  "initScript" : "",
[连接身份与敏感参数已脱敏]
  "executeMode" : null
}
[2026-08-04 17:40:06.075] INFO Create TaskChannel: org.apache.dolphinscheduler.plugin.task.flink.FlinkTaskChannel successfully
[2026-08-04 17:40:06.081] INFO Download resources successfully:
ResourceContext(resourceItemMap={})[INFO] 2026-08-04 17:40:06.117 - To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_4/601/604/601_604_init.sql
[2026-08-04 17:40:06.086] INFO Download upstream files: [] successfully
[2026-08-04 17:40:06.094] INFO Task plugin instance: FLINK create successfully
[2026-08-04 17:40:06.112] INFO Writing content: set execution.target=local;
set parallelism.default=1;
[2026-08-04 17:40:06.134] INFO To file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_4/601/604/601_604_node.sql
[2026-08-04 17:40:06.128] INFO Writing content: -- =====================================================
-- 1. 创建 MySQL CDC 源表
-- =====================================================
DROP TABLE IF EXISTS MYSQL_ST_Q_R;
CREATE TABLE MYSQL_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    -- 使用 MySQL CDC 连接器
    ''connector'' = ''mysql-cdc'',
    -- MySQL Docker 容器名称
    ''hostname'' = ''mysql57'',
    -- MySQL 端口
    ''port'' = ''3306'',
    -- MySQL 用户名
[连接身份与敏感参数已脱敏]
    -- MySQL 密码
[连接身份与敏感参数已脱敏]
    -- MySQL 数据库名称
    ''database-name'' = ''RIVER_WATER'',
    -- MySQL 表名称
    ''table-name'' = ''st_q_r'',
    -- MySQL CDC Server ID
    ''server-id'' = ''5401'',
    -- MySQL 时区
    ''server-time-zone'' = ''Asia/Shanghai'',
    -- 从任务启动后的最新 Binlog 位置开始读取
    ''scan.startup.mode'' = ''latest-offset'',
    -- 将更新和删除事件转换为追加数据
    ''scan.read-changelog-as-append-only.enabled'' = ''true'',
    -- 连接超时时间
    ''connect.timeout'' = ''30s'',
    -- 连接失败重试次数
    ''connect.max-retries'' = ''3'',
    -- CDC 心跳间隔
    ''heartbeat.interval'' = ''30s''
);
-- =====================================================
-- 2. 创建 Doris JDBC 目标表
-- =====================================================
DROP TABLE IF EXISTS DORIS_ODS_RH_RTW_ST_Q_R;
CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
    `ID` BIGINT,
    `RVCD` STRING,
    `RVNM` STRING,
    `RSCD` STRING,
    `RSNM` STRING,
    `SECCD` STRING,
    `SECNM` STRING,
    `STCD` STRING,
    `STNM` STRING,
    `TM` TIMESTAMP(0),
    `Q` DECIMAL(14, 3)
) WITH (
    -- 使用 JDBC 连接器，通过 Doris MySQL 协议写入
    ''connector'' = ''jdbc'',
    -- Doris MySQL 协议地址
    ''url'' = ''[REDACTED_CONNECTION]'',
    -- Doris 目标表名称
    ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
    -- Doris 用户名
[连接身份与敏感参数已脱敏]
    -- Doris 密码
[连接身份与敏感参数已脱敏]
    -- 累计 1000 条数据后批量提交
    ''sink.buffer-flush.max-rows'' = ''1000'',
    -- 最长每 1 秒提交一次
    ''sink.buffer-flush.interval'' = ''1s'',
    -- 写入失败重试次数
    ''sink.max-retries'' = ''3'',
    -- Doris 写入并行度
    ''sink.parallelism'' = ''1''
);
-- =====================================================
-- 3. 启动 MySQL 到 Doris 的实时同步
-- =====================================================
INSERT INTO DORIS_ODS_RH_RTW_ST_Q_R
SELECT
    `ID`,
    `RVCD`,
    `RVNM`,
    `RSCD`,
    `RSNM`,
    `SECCD`,
    `SECNM`,
    `STCD`,
    `STNM`,
    `TM`,
    `Q`
FROM MYSQL_ST_Q_R;
[2026-08-04 17:40:06.147] INFO Set taskVarPool: null successfully
[2026-08-04 17:40:06.150] INFO
***********************************************************************************************[INFO] 2026-08-04 17:40:06.155 - *********************************  Execute task instance  *************************************
[2026-08-04 17:40:06.161] INFO ***********************************************************************************************
[2026-08-04 17:40:06.174] INFO Final Shell file is:
[2026-08-04 17:40:06.184] INFO ****************************** Script Content *****************************************************************
[2026-08-04 17:40:06.197] INFO #!/bin/bash
[连接身份与敏感参数已脱敏]
cd $BASEDIR
export FLINK_HOME=/opt/soft/flink; export SPARK_HOME=/opt/soft/spark; export DATAX_LAUNCHER=/opt/soft/datax/bin/datax.py; export PYTHON_LAUNCHER=/usr/bin/python3; export JAVA_HOME=/opt/java/openjdk; export PATH=$FLINK_HOME/bin:$SPARK_HOME/bin:$JAVA_HOME/bin:/opt/soft/datax/bin:/usr/bin:$PATH
sql-client.sh -i /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_4/601/604/601_604_init.sql -f /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_4/601/604/601_604_node.sql
[2026-08-04 17:40:06.203] INFO ****************************** Script Content *****************************************************************
[2026-08-04 17:40:06.225] INFO processInstanceId:601,taskInstanceId:604
[2026-08-04 17:40:06.225] INFO processInstanceId:601,taskInstanceId:604
[2026-08-04 17:40:06.225] INFO process start, process id is: 35042
[2026-08-04 17:40:18.247] INFO ->
	Successfully initialized from sql script: file:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_4/601/604/601_604_init.sql
	Aug 04, 2026 5:40:18 PM org.jline.utils.Log logr
	WARNING: Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)
	[34;1m[INFO] Executing SQL from file.[0m
	Command history file path: /root/.flink-sql-history
[2026-08-04 17:40:19.253] INFO ->
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE MYSQL_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
	> ) WITH (
	>     -- 使用 MySQL CDC 连接器
	>     ''connector'' = ''mysql-cdc'',
	>     -- MySQL Docker 容器名称
	>     ''hostname'' = ''mysql57'',
	>     -- MySQL 端口
	>     ''port'' = ''3306'',
	>     -- MySQL 用户名
[连接身份与敏感参数已脱敏]
	>     -- MySQL 密码
[连接身份与敏感参数已脱敏]
	>     -- MySQL 数据库名称
	>     ''database-name'' = ''RIVER_WATER'',
	>     -- MySQL 表名称
	>     ''table-name'' = ''st_q_r'',
	>     -- MySQL CDC Server ID
	>     ''server-id'' = ''5401'',
	>     -- MySQL 时区
	>     ''server-time-zone'' = ''Asia/Shanghai'',
	>     -- 从任务启动后的最新 Binlog 位置开始读取
	>     ''scan.startup.mode'' = ''latest-offset'',
	>     -- 将更新和删除事件转换为追加数据
	>     ''scan.read-changelog-as-append-only.enabled'' = ''true'',
	>     -- 连接超时时间
	>     ''connect.timeout'' = ''30s'',
	>     -- 连接失败重试次数
	>     ''connect.max-retries'' = ''3'',
	>     -- CDC 心跳间隔
	>     ''heartbeat.interval'' = ''30s''
[2026-08-04 17:40:20.273] INFO ->
	> )[34;1m[INFO] Execute statement succeed.[0m
	Flink SQL> [34;1m[INFO] Execute statement succeed.[0m
	Flink SQL>
	> CREATE TABLE DORIS_ODS_RH_RTW_ST_Q_R (
	>     `ID` BIGINT,
	>     `RVCD` STRING,
	>     `RVNM` STRING,
	>     `RSCD` STRING,
	>     `RSNM` STRING,
	>     `SECCD` STRING,
	>     `SECNM` STRING,
	>     `STCD` STRING,
	>     `STNM` STRING,
	>     `TM` TIMESTAMP(0),
	>     `Q` DECIMAL(14, 3)
	> ) WITH (
	>     -- 使用 JDBC 连接器，通过 Doris MySQL 协议写入
	>     ''connector'' = ''jdbc'',
	>     -- Doris MySQL 协议地址
	>     ''url'' = ''[REDACTED_CONNECTION]'',
	>     -- Doris 目标表名称
	>     ''table-name'' = ''ODS_RH_RTW_ST_Q_R'',
	>     -- Doris 用户名
[连接身份与敏感参数已脱敏]
	>     -- Doris 密码
[连接身份与敏感参数已脱敏]
	>     -- 累计 1000 条数据后批量提交
	>     ''sink.buffer-flush.max-rows'' = ''1000'',
	>     -- 最长每 1 秒提交一次
	>     ''sink.buffer-flush.interval'' = ''1s'',
	>     -- 写入失败重试次数
	>     ''sink.max-retries'' = ''3'',
	>     -- Doris 写入并行度
	>     ''sink.parallelism'' = ''1''
	> )[34;1m[INFO] Execute statement succeed.[0m
[2026-08-04 17:40:21.278] INFO ->
	Flink SQL> [31;1m[ERROR] Could not execute SQL statement. Reason:
	org.apache.flink.table.api.ValidationException: Unsupported options found for ''mysql-cdc''.
	Unsupported options:
	scan.read-changelog-as-append-only.enabled
	Supported options:
	chunk-key.even-distribution.factor.lower-bound
	chunk-key.even-distribution.factor.upper-bound
	chunk-meta.group.size
	connect.max-retries
	connect.timeout
	connection.pool.size
	connector
	database-name
	heartbeat.interval
	hostname
[连接身份与敏感参数已脱敏]
	port
	property-version
	scan.incremental.close-idle-reader.enabled
	scan.incremental.snapshot.chunk.key-column
	scan.incremental.snapshot.chunk.size
	scan.incremental.snapshot.enabled
	scan.newly-added-table.enabled
	scan.snapshot.fetch.size
	scan.startup.mode
	scan.startup.specific-offset.file
	scan.startup.specific-offset.gtid-set
	scan.startup.specific-offset.pos
	scan.startup.specific-offset.skip-events
	scan.startup.specific-offset.skip-rows
	scan.startup.timestamp-millis
	server-id
	server-time-zone
	split-key.even-distribution.factor.lower-bound
	split-key.even-distribution.factor.upper-bound
	table-name
[连接身份与敏感参数已脱敏]
[2026-08-04 17:40:22.282] INFO ->
	Shutting down the session...
	done.
[2026-08-04 17:40:22.287] INFO process has exited. execute path:/tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_4/601/604, processId:35042 ,exitStatusCode:0 ,processWaitForStatus:true ,processExitValue:0
[2026-08-04 17:40:22.294] INFO Start finding appId in /opt/dolphinscheduler/logs/20260804/180629079832128/4/601/604.log, fetch way: log
[2026-08-04 17:40:22.304] INFO
***********************************************************************************************[INFO] 2026-08-04 17:40:22.307 - *********************************  Finalize task instance  ************************************
[2026-08-04 17:40:22.311] INFO ***********************************************************************************************
[2026-08-04 17:40:22.314] INFO Upload output files: [] successfully
[2026-08-04 17:40:22.325] INFO Send task execute status: SUCCESS to master : [INTERNAL_HOST]
[2026-08-04 17:40:22.329] INFO Remove the current task execute context from worker cache
[2026-08-04 17:40:22.336] INFO The current execute mode isn''t develop mode, will clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_4/601/604
[2026-08-04 17:40:22.340] INFO Success clear the task execute file: /tmp/dolphinscheduler/exec/process/[执行身份]/152317790975712/180629079832128_4/601/604
[2026-08-04 17:40:22.343] INFO FINALIZE_SESSION','migration:0217','migration:0217','2026-08-04 17:40:05.914000','2026-08-04 17:40:22.645000')
ON CONFLICT(id) DO NOTHING;

-- 补齐全部59条源集成实例的真实详情和只读节点参数；不从源运行状态推断平台运行进程。
WITH source_details(id,creator_name,description,create_time,update_time,flow_json,log_content) AS (VALUES
 (1271,'吴同','','2026-10-22 19:11:02','2026-10-22 21:53:19','{"nodes":[{"id":"187627141565792","label":"表输入组件","type":"1","x":190,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"187627141567840","label":"转换组件","type":"31","x":300,"y":250,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"清洗降雨量为负数的值","field":"DRP","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"187627141570912","label":"表输出组件","type":"91","x":530,"y":330,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"187627141565792","target":"187627141567840"},{"source":"187627141567840","target":"187627141570912"}]}',''),
 (1269,'吴同','','2026-10-22 19:02:07','2026-10-22 21:50:13','{"nodes":[{"id":"187627794164064","label":"表输入组件","type":"1","x":210,"y":190,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"187627813783904","label":"值映射","type":"47","x":340,"y":270,"publicConfig":[{"name":"节点名称","value":"值映射"},{"name":"组件类型","value":"值映射"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicFields":[{"name":"","description":"","type":"","primary":false,"length":"","scale":""}]},{"id":"187627819151712","label":"增加常量","type":"48","x":430,"y":310,"publicConfig":[{"name":"节点名称","value":"增加常量"},{"name":"组件类型","value":"增加常量"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicFields":[{"name":"Source_identifier","description":"","type":"","primary":false,"length":"","scale":""}]},{"id":"187627822707040","label":"数值范围","type":"49","x":520,"y":370,"publicConfig":[{"name":"节点名称","value":"数值范围"},{"name":"组件类型","value":"数值范围"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}]},{"id":"187627824861536","label":"表输出组件","type":"91","x":620,"y":410,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ADS"},{"name":"数据表","value":"ADS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"},{"name":"DRP_Value","description":"","type":"","primary":false,"length":"","scale":""},{"name":"Source_identifier","description":"","type":"String","primary":false,"length":"","scale":""},{"name":"DRP_Value_range","description":"","type":"","primary":false,"length":"","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"},{"source":"DRP_Value","target":"WEAT"},{"source":"Source_identifier","target":"Humid"}]}],"edges":[{"source":"187627794164064","target":"187627813783904"},{"source":"187627813783904","target":"187627819151712"},{"source":"187627819151712","target":"187627822707040"},{"source":"187627822707040","target":"187627824861536"}]}',''),
 (1268,'吴同','','2026-10-22 18:58:15','2026-10-22 21:53:19','{"nodes":[{"id":"187627209392480","label":"表输入组件","type":"1","x":190,"y":180,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"187627587340640","label":"条件分发","type":"CONDITION_DISTRIBUTE","x":290,"y":280,"publicConfig":[{"name":"节点名称","value":"条件分发"},{"name":"组件类型","value":"条件分发"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}]},{"id":"187627209397600","label":">100表输出组件","type":"91","x":510,"y":250,"publicConfig":[{"name":"节点名称","value":">100表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]},{"id":"187627609738592","label":"<100表输出组件","type":"91","x":430,"y":410,"publicConfig":[{"name":"节点名称","value":"<100表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWS"},{"name":"数据表","value":"DWS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"187627209392480","target":"187627587340640"},{"source":"187627587340640","target":"187627209397600"},{"source":"187627587340640","target":"187627609738592"}]}',''),
 (1267,'吴同','','2026-10-22 17:07:49','2026-10-22 18:55:26','{"nodes":[{"id":"187627141565792","label":"表输入组件","type":"1","x":190,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"187627141567840","label":"转换组件","type":"31","x":300,"y":250,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"清洗降雨量为负数的值","field":"DRP","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"187627141570912","label":"表输出组件","type":"91","x":530,"y":330,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[]}',''),
 (1247,'吴同','','2026-10-22 14:52:35','2026-10-22 15:42:52','{"nodes":[{"id":"187616737518944","label":"表输入组件","type":"1","x":230,"y":230,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"187616737520992","label":"表输出组件","type":"91","x":380,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"187616737518944","target":"187616737520992"}]}',''),
 (180829751976928,'吴同','','2026-08-06 21:12:34','2026-08-06 21:12:44','{"nodes":[{"id":"180640620423744","label":"表输入组件","type":"1","x":300,"y":150,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_SEC_B"},{"name":"字段","value":"ID, SECCD, SECNM, RVCD, RVNM, RSCD, RSNM, ADDVCD, ADDVNM, SECTP, WRZ, GRZ, DFLZ, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECTP","description":"断面类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"GRZ","description":"保证水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"DFLZ","description":"设计洪水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640628469312","label":"表输出组件","type":"91","x":610,"y":320,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_SEC_B"},{"name":"字段","value":"ID, SECCD, SECNM, RVCD, RVNM, RSCD, RSNM, ADDVCD, ADDVNM, SECTP, WRZ, GRZ, DFLZ, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECTP","description":"断面类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"GRZ","description":"保证水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"DFLZ","description":"设计洪水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"SECTP","target":"SECTP"},{"source":"WRZ","target":"WRZ"},{"source":"GRZ","target":"GRZ"},{"source":"DFLZ","target":"DFLZ"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640620423744","target":"180640628469312"}]}',''),
 (180829707866400,'吴同','','2026-08-06 21:11:51','2026-08-06 21:12:01','{"nodes":[{"id":"180640530184768","label":"表输入组件","type":"1","x":440,"y":200,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_RCH_B"},{"name":"字段","value":"ID, RSCD, RSNM, RVCD, RVNM, RSTP, RSLEN, UPSECCD, UPSECNM, DNSECCD, DNSECNM, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSTP","description":"河段类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSLEN","description":"河段长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"UPSECCD","description":"上游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"UPSECNM","description":"上游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DNSECCD","description":"下游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"DNSECNM","description":"下游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640554352192","label":"表输出组件","type":"91","x":600,"y":320,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_RCH_B"},{"name":"字段","value":"ID, RSCD, RSNM, RVCD, RVNM, RSTP, RSLEN, UPSECCD, UPSECNM, DNSECCD, DNSECNM, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSTP","description":"河段类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSLEN","description":"河段长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"UPSECCD","description":"上游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"UPSECNM","description":"上游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DNSECCD","description":"下游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"DNSECNM","description":"下游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSTP","target":"RSTP"},{"source":"RSLEN","target":"RSLEN"},{"source":"UPSECCD","target":"UPSECCD"},{"source":"UPSECNM","target":"UPSECNM"},{"source":"DNSECCD","target":"DNSECCD"},{"source":"DNSECNM","target":"DNSECNM"},{"source":"GEOMJSON","target":"GEOMJSON"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640530184768","target":"180640554352192"}]}',''),
 (180829090983840,'吴同','','2026-08-06 21:01:49','2026-08-06 21:01:58','{"nodes":[{"id":"180629980610112","label":"表输入组件","type":"1","x":230,"y":230,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180630021398080","label":"表输出组件","type":"91","x":380,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180629980610112","target":"180630021398080"}]}','[2026-08-06 21:01:50.253] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 21:01:50.259] INFO 开始数据库输入节点
[2026-08-06 21:01:50.263] INFO 开始任务时间: 2026-08-06 21:01:50.262
[2026-08-06 21:01:50.278] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180629980610112",
	"parameter":{
		"dbName":"RIVER_WATER",
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"MySql",
		"readerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"RIVER_WATER"
			},
			"dbName":"RIVER_WATER",
			"dbType":"MySql",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":3306,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":12,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"st_z_stbprp_b"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":2
}
[2026-08-06 21:01:55.778] INFO 输入数据量为：6
[2026-08-06 21:01:57.038] INFO 部分数据：
+----+-----+--------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
|ID  |STCD |STNM                |RVCD |RVNM  |RSCD |RSNM        |SECCD |SECNM         |ADDVCD|ADDVNM|STTP  |STLVL |MNTITEM|COMMODE|LGTD        |LTTD       |EL    |USFL|
+----+-----+--------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
|1201|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|330101|青山区|水位站|基本站|水位   |4G/北斗|119.92000000|30.57000000|32.500|1   |
|1203|ST003|清源下游一断面水文站|RV001|清源河|RS002|清源河下游段|SEC003|清源下游一断面|330103|滨河区|水位站|基本站|水位   |4G/北斗|120.05000000|30.39000000|20.200|1   |
|1205|ST005|云溪上游一断面水文站|RV002|云溪河|RS003|云溪河上游段|SEC005|云溪上游一断面|330201|云岭区|水位站|基本站|水位   |4G/北斗|120.27000000|30.42000000|38.100|1   |
|1207|ST007|云溪下游一断面水文站|RV002|云溪河|RS004|云溪河下游段|SEC007|云溪下游一断面|330203|临江区|水位站|基本站|水位   |4G/北斗|120.41000000|30.22000000|22.700|1   |
|1209|ST009|碧川上游一断面水文站|RV003|碧川河|RS005|碧川河上游段|SEC009|碧川上游一断面|330301|碧峰区|水位站|基本站|水位   |4G/北斗|120.60000000|30.12000000|41.500|1   |
|1211|ST011|碧川下游一断面水文站|RV003|碧川河|RS006|碧川河下游段|SEC011|碧川下游一断面|330303|川南区|水位站|基本站|水位   |4G/北斗|120.71000000|29.96000000|24.800|1   |
+----+-----+--------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
[2026-08-06 21:01:57.054] INFO 任务成功
[2026-08-06 21:01:57.061] INFO FINALIZE_SESSION
[2026-08-06 21:01:57.100] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 21:01:57.104] INFO 开始数据库输出节点
[2026-08-06 21:01:57.118] INFO 开始任务时间: 2026-08-06 21:01:57.117
[2026-08-06 21:01:57.125] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180630021398080",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"ODS",
		"datasourceId":1,
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_BO_ST_Z_STBPRP_B"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 21:01:57.134] INFO 写入方式为：追加写
[2026-08-06 21:01:58.224] INFO 任务成功
[2026-08-06 21:01:58.227] INFO FINALIZE_SESSION'),
 (180828987542560,'吴同','','2026-08-06 21:00:08','2026-08-06 21:00:17','{"nodes":[{"id":"180629980610112","label":"表输入组件","type":"1","x":230,"y":230,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180630021398080","label":"表输出组件","type":"91","x":380,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180629980610112","target":"180630021398080"}]}','[2026-08-06 21:00:09.375] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 21:00:09.382] INFO 开始数据库输入节点
[2026-08-06 21:00:09.386] INFO 开始任务时间: 2026-08-06 21:00:09.386
[2026-08-06 21:00:09.402] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180629980610112",
	"parameter":{
		"dbName":"RIVER_WATER",
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"MySql",
		"readerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"RIVER_WATER"
			},
			"dbName":"RIVER_WATER",
			"dbType":"MySql",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":3306,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":12,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"st_z_stbprp_b"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":2
}
[2026-08-06 21:00:15.327] INFO 输入数据量为：6
[2026-08-06 21:00:16.537] INFO 部分数据：
+----+-----+--------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
|ID  |STCD |STNM                |RVCD |RVNM  |RSCD |RSNM        |SECCD |SECNM         |ADDVCD|ADDVNM|STTP  |STLVL |MNTITEM|COMMODE|LGTD        |LTTD       |EL    |USFL|
+----+-----+--------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
|1201|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|330101|青山区|水位站|基本站|水位   |4G/北斗|119.92000000|30.57000000|32.500|1   |
|1203|ST003|清源下游一断面水文站|RV001|清源河|RS002|清源河下游段|SEC003|清源下游一断面|330103|滨河区|水位站|基本站|水位   |4G/北斗|120.05000000|30.39000000|20.200|1   |
|1205|ST005|云溪上游一断面水文站|RV002|云溪河|RS003|云溪河上游段|SEC005|云溪上游一断面|330201|云岭区|水位站|基本站|水位   |4G/北斗|120.27000000|30.42000000|38.100|1   |
|1207|ST007|云溪下游一断面水文站|RV002|云溪河|RS004|云溪河下游段|SEC007|云溪下游一断面|330203|临江区|水位站|基本站|水位   |4G/北斗|120.41000000|30.22000000|22.700|1   |
|1209|ST009|碧川上游一断面水文站|RV003|碧川河|RS005|碧川河上游段|SEC009|碧川上游一断面|330301|碧峰区|水位站|基本站|水位   |4G/北斗|120.60000000|30.12000000|41.500|1   |
|1211|ST011|碧川下游一断面水文站|RV003|碧川河|RS006|碧川河下游段|SEC011|碧川下游一断面|330303|川南区|水位站|基本站|水位   |4G/北斗|120.71000000|29.96000000|24.800|1   |
+----+-----+--------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
[2026-08-06 21:00:16.549] INFO 任务成功
[2026-08-06 21:00:16.553] INFO FINALIZE_SESSION
[2026-08-06 21:00:16.585] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 21:00:16.591] INFO 开始数据库输出节点
[2026-08-06 21:00:16.596] INFO 开始任务时间: 2026-08-06 21:00:16.596
[2026-08-06 21:00:16.602] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180630021398080",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"ODS",
		"datasourceId":1,
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_BO_ST_Z_STBPRP_B"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 21:00:16.610] INFO 写入方式为：追加写
[2026-08-06 21:00:17.781] INFO 任务成功
[2026-08-06 21:00:17.786] INFO FINALIZE_SESSION'),
 (180828841586528,'吴同','','2026-08-06 20:57:45','2026-08-06 20:57:54','{"nodes":[{"id":"180640656332352","label":"表输入组件","type":"1","x":180,"y":150,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640673126976","label":"表输出组件","type":"91","x":310,"y":280,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640656332352","target":"180640673126976"}]}','[2026-08-06 20:57:46.767] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:57:46.776] INFO 开始任务时间: 2026-08-06 20:57:46.776
[2026-08-06 20:57:46.772] INFO 开始数据库输入节点
[2026-08-06 20:57:46.791] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180640656332352",
	"parameter":{
		"dbName":"ODS",
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"Doris",
		"readerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":1,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_BO_ST_STBPRP_B"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:57:52.947] INFO 输入数据量为：0
[2026-08-06 20:57:53.412] INFO 部分数据：
+---+----+----+----+----+----+----+-----+-----+------+------+----+-----+-------+-------+----+----+---+----+
|ID |STCD|STNM|RVCD|RVNM|RSCD|RSNM|SECCD|SECNM|ADDVCD|ADDVNM|STTP|STLVL|MNTITEM|COMMODE|LGTD|LTTD|EL |USFL|
+---+----+----+----+----+----+----+-----+-----+------+------+----+-----+-------+-------+----+----+---+----+
+---+----+----+----+----+----+----+-----+-----+------+------+----+-----+-------+-------+----+----+---+----+
[2026-08-06 20:57:53.424] INFO 任务成功
[2026-08-06 20:57:53.428] INFO FINALIZE_SESSION
[2026-08-06 20:57:53.469] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:57:53.475] INFO 开始数据库输出节点
[2026-08-06 20:57:53.480] INFO 开始任务时间: 2026-08-06 20:57:53.480
[2026-08-06 20:57:53.485] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180640673126976",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"DIM",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"DIM",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"DIM",
		"datasourceId":3,
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"DIM_RH_BO_ST_STBPRP_B"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:57:53.493] INFO 写入方式为：追加写
[2026-08-06 20:57:54.574] INFO 任务成功
[2026-08-06 20:57:54.579] INFO FINALIZE_SESSION'),
 (180828817622144,'吴同','','2026-08-06 20:57:22','2026-08-06 20:57:29','{"nodes":[{"id":"180639954870848","label":"表输入组件","type":"1","x":130,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_pptn_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"180639964181056","label":"表输出组件","type":"91","x":220,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"180639954870848","target":"180639964181056"}]}','[2026-08-06 20:57:23.654] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:57:23.662] INFO 开始数据库输入节点
[2026-08-06 20:57:23.667] INFO 开始任务时间: 2026-08-06 20:57:23.667
[2026-08-06 20:57:23.684] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180639954870848",
	"parameter":{
		"dbName":"RIVER_WATER",
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"DRP"
		],
		"dbType":"MySql",
		"readerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"RIVER_WATER"
			},
			"dbName":"RIVER_WATER",
			"dbType":"MySql",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":3306,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":12,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"st_pptn_r"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:57:27.244] INFO 输入数据量为：6
[2026-08-06 20:57:28.555] INFO 部分数据：
+-------+-----+------+-----+------------+------+--------------+-----+------------------+-------------------+-----+
|ID     |RVCD |RVNM  |RSCD |RSNM        |SECCD |SECNM         |STCD |STNM              |TM                 |DRP  |
+-------+-----+------+-----+------------+------+--------------+-----+------------------+-------------------+-----+
|1001096|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|PP001|清源河上游段雨量站|2025-12-31 00:00:00|0.00 |
|2001096|RV001|清源河|RS002|清源河下游段|SEC003|清源下游一断面|PP002|清源河下游段雨量站|2025-12-31 00:00:00|0.00 |
|3001096|RV002|云溪河|RS003|云溪河上游段|SEC005|云溪上游一断面|PP003|云溪河上游段雨量站|2025-12-31 00:00:00|0.00 |
|4001096|RV002|云溪河|RS004|云溪河下游段|SEC007|云溪下游一断面|PP004|云溪河下游段雨量站|2025-12-31 00:00:00|0.00 |
|5001096|RV003|碧川河|RS005|碧川河上游段|SEC009|碧川上游一断面|PP005|碧川河上游段雨量站|2025-12-31 00:00:00|12.00|
|6001096|RV003|碧川河|RS006|碧川河下游段|SEC011|碧川下游一断面|PP006|碧川河下游段雨量站|2025-12-31 00:00:00|0.00 |
+-------+-----+------+-----+------------+------+--------------+-----+------------------+-------------------+-----+
[2026-08-06 20:57:28.567] INFO 任务成功
[2026-08-06 20:57:28.572] INFO FINALIZE_SESSION
[2026-08-06 20:57:28.611] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:57:28.616] INFO 开始数据库输出节点
[2026-08-06 20:57:28.623] INFO 开始任务时间: 2026-08-06 20:57:28.623
[2026-08-06 20:57:28.629] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180639964181056",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"ODS",
		"datasourceId":1,
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"DRP"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_RTW_ST_PPTN_R"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"DRP"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:57:28.639] INFO 写入方式为：追加写
[2026-08-06 20:57:29.519] INFO 任务成功
[2026-08-06 20:57:29.523] INFO FINALIZE_SESSION'),
 (180828802092064,'吴同','','2026-08-06 20:57:07','2026-08-06 20:57:24','{"nodes":[{"id":"180638398249536","label":"表输入组件","type":"1","x":230,"y":160,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_day_s"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180638458847808","label":"表输出组件","type":"91","x":370,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWS"},{"name":"数据表","value":"DWS_RH_HIST_ST_Z_DAY_S"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"AVGZ","target":"AVGZ"},{"source":"MAXZ","target":"MAXZ"},{"source":"MINZ","target":"MINZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180638398249536","target":"180638458847808"}]}','[2026-08-06 20:57:08.342] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:57:08.349] INFO 开始数据库输入节点
[2026-08-06 20:57:08.355] INFO 开始任务时间: 2026-08-06 20:57:08.355
[2026-08-06 20:57:08.372] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180638398249536",
	"parameter":{
		"dbName":"RIVER_WATER",
		"column":[
			"ID",
			"OBJTP",
			"OBJCD",
			"OBJNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"STATDT",
			"STTPRD",
			"BGNTM",
			"ENDTM",
			"AVGZ",
			"MAXZ",
			"MINZ",
			"ZRR"
		],
		"dbType":"MySql",
		"readerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"RIVER_WATER"
			},
			"dbName":"RIVER_WATER",
			"dbType":"MySql",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":3306,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":12,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"st_z_day_s"
		},
		"batchSize":"1024",
		"readModeType":1,
[敏感参数已脱敏]
	},
	"nodeVersion":3
}
[2026-08-06 20:57:14.389] INFO 输入数据量为：13152
[2026-08-06 20:57:15.907] INFO 部分数据：
+-------+-------+-----+--------------------+-----+------+-----+------------+------+--------------+-----+--------------------+----------+------+-------------------+-------------------+-----+-----+-----+-----+
|ID     |OBJTP  |OBJCD|OBJNM               |RVCD |RVNM  |RSCD |RSNM        |SECCD |SECNM         |STCD |STNM                |STATDT    |STTPRD|BGNTM              |ENDTM              |AVGZ |MAXZ |MINZ |ZRR  |
+-------+-------+-----+--------------------+-----+------+-----+------------+------+--------------+-----+--------------------+----------+------+-------------------+-------------------+-----+-----+-----+-----+
|1000001|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-01|DAY   |2023-01-01 00:00:00|2023-01-01 00:00:00|4.180|4.180|4.180|0.000|
|1000002|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-02|DAY   |2023-01-02 00:00:00|2023-01-02 00:00:00|4.189|4.189|4.189|0.000|
|1000003|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-03|DAY   |2023-01-03 00:00:00|2023-01-03 00:00:00|4.199|4.199|4.199|0.000|
|1000004|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-04|DAY   |2023-01-04 00:00:00|2023-01-04 00:00:00|4.208|4.208|4.208|0.000|
|1000005|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-05|DAY   |2023-01-05 00:00:00|2023-01-05 00:00:00|4.218|4.218|4.218|0.000|
|1000006|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-06|DAY   |2023-01-06 00:00:00|2023-01-06 00:00:00|4.227|4.227|4.227|0.000|
|1000007|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-07|DAY   |2023-01-07 00:00:00|2023-01-07 00:00:00|4.237|4.237|4.237|0.000|
|1000008|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-08|DAY   |2023-01-08 00:00:00|2023-01-08 00:00:00|4.246|4.246|4.246|0.000|
|1000009|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-09|DAY   |2023-01-09 00:00:00|2023-01-09 00:00:00|4.255|4.255|4.255|0.000|
|1000010|STATION|ST001|清源上游一断面水文站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-10|DAY   |2023-01-10 00:00:00|2023-01-10 00:00:00|4.265|4.265|4.265|0.000|
+-------+-------+-----+--------------------+-----+------+-----+------------+------+--------------+-----+--------------------+----------+------+-------------------+-------------------+-----+-----+-----+-----+
only showing top 10 rows
[2026-08-06 20:57:15.918] INFO 任务成功
[2026-08-06 20:57:15.923] INFO FINALIZE_SESSION
[2026-08-06 20:57:15.956] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:57:15.960] INFO 开始数据库输出节点
[2026-08-06 20:57:15.964] INFO 开始任务时间: 2026-08-06 20:57:15.964
[2026-08-06 20:57:15.969] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180638458847808",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"DWS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"DWS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"DWS",
		"datasourceId":4,
		"column":[
			"ID",
			"OBJTP",
			"OBJCD",
			"OBJNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"STATDT",
			"STTPRD",
			"BGNTM",
			"ENDTM",
			"AVGZ",
			"MAXZ",
			"MINZ",
			"ZRR"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"DWS_RH_HIST_ST_Z_DAY_S"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"OBJTP",
			"OBJCD",
			"OBJNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"STATDT",
			"STTPRD",
			"BGNTM",
			"ENDTM",
			"AVGZ",
			"MAXZ",
			"MINZ",
			"ZRR"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":4
}
[2026-08-06 20:57:15.976] INFO 写入方式为：追加写
[2026-08-06 20:57:24.136] INFO 任务成功
[2026-08-06 20:57:24.141] INFO FINALIZE_SESSION'),
 (180828790549248,'吴同','','2026-08-06 20:56:56','2026-08-06 20:57:05','{"nodes":[{"id":"180629980610112","label":"表输入组件","type":"1","x":230,"y":230,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180630021398080","label":"表输出组件","type":"91","x":380,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180629980610112","target":"180630021398080"}]}',''),
 (180828281719584,'吴同','','2026-08-06 20:48:39','2026-08-06 20:48:46','{"nodes":[{"id":"180640620423744","label":"表输入组件","type":"1","x":300,"y":150,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_SEC_B"},{"name":"字段","value":"ID, SECCD, SECNM, RVCD, RVNM, RSCD, RSNM, ADDVCD, ADDVNM, SECTP, WRZ, GRZ, DFLZ, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECTP","description":"断面类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"GRZ","description":"保证水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"DFLZ","description":"设计洪水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640628469312","label":"表输出组件","type":"91","x":610,"y":320,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_SEC_B"},{"name":"字段","value":"ID, SECCD, SECNM, RVCD, RVNM, RSCD, RSNM, ADDVCD, ADDVNM, SECTP, WRZ, GRZ, DFLZ, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECTP","description":"断面类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"GRZ","description":"保证水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"DFLZ","description":"设计洪水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"SECTP","target":"SECTP"},{"source":"WRZ","target":"WRZ"},{"source":"GRZ","target":"GRZ"},{"source":"DFLZ","target":"DFLZ"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640620423744","target":"180640628469312"}]}',''),
 (180828231566624,'吴同','','2026-08-06 20:47:50','2026-08-06 20:47:57','{"nodes":[{"id":"180640656332352","label":"表输入组件","type":"1","x":180,"y":150,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640673126976","label":"表输出组件","type":"91","x":310,"y":280,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640656332352","target":"180640673126976"}]}',''),
 (180828227551104,'吴同','','2026-08-06 20:47:46','2026-08-06 20:47:58','{"nodes":[{"id":"180640530184768","label":"表输入组件","type":"1","x":440,"y":200,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_RCH_B"},{"name":"字段","value":"ID, RSCD, RSNM, RVCD, RVNM, RSTP, RSLEN, UPSECCD, UPSECNM, DNSECCD, DNSECNM, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSTP","description":"河段类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSLEN","description":"河段长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"UPSECCD","description":"上游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"UPSECNM","description":"上游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DNSECCD","description":"下游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"DNSECNM","description":"下游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640554352192","label":"表输出组件","type":"91","x":600,"y":320,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_RCH_B"},{"name":"字段","value":"ID, RSCD, RSNM, RVCD, RVNM, RSTP, RSLEN, UPSECCD, UPSECNM, DNSECCD, DNSECNM, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSTP","description":"河段类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSLEN","description":"河段长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"UPSECCD","description":"上游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"UPSECNM","description":"上游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DNSECCD","description":"下游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"DNSECNM","description":"下游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSTP","target":"RSTP"},{"source":"RSLEN","target":"RSLEN"},{"source":"UPSECCD","target":"UPSECCD"},{"source":"UPSECNM","target":"UPSECNM"},{"source":"DNSECCD","target":"DNSECCD"},{"source":"DNSECNM","target":"DNSECNM"},{"source":"GEOMJSON","target":"GEOMJSON"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640530184768","target":"180640554352192"}]}',''),
 (180828225404608,'吴同','','2026-08-06 20:47:44','2026-08-06 20:47:53','{"nodes":[{"id":"180640318418496","label":"表输入组件","type":"1","x":350,"y":270,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_RV_B"},{"name":"字段","value":"ID, RVCD, RVNM, BASCD, BSNM, ADDVCD, ADDVNM, RVTP, RVGRD, RVLEN, SRCLC, ESTLC, LGTD, LTTD, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"BASCD","description":"流域编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"BSNM","description":"流域名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVTP","description":"河流类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVGRD","description":"河流等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVLEN","description":"河流长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"SRCLC","description":"河流起点位置","type":"varchar","primary":false,"length":"300","scale":""},{"name":"ESTLC","description":"河流终点位置","type":"varchar","primary":false,"length":"300","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640372906560","label":"表输出组件","type":"91","x":570,"y":370,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_RV_B"},{"name":"字段","value":"ID, RVCD, RVNM, ADDVCD, ADDVNM, LGTD, LTTD, USFL, ESTLC, BSNM, BASCD, SRCLC, RVTP, RVGRD, RVLEN, GEOMJSON"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""},{"name":"ESTLC","description":"河流终点位置","type":"varchar","primary":false,"length":"300","scale":""},{"name":"BSNM","description":"流域名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"BASCD","description":"流域编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SRCLC","description":"河流起点位置","type":"varchar","primary":false,"length":"300","scale":""},{"name":"RVTP","description":"河流类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVGRD","description":"河流等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVLEN","description":"河流长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"BASCD","target":"ADDVCD"},{"source":"BSNM","target":"ADDVNM"},{"source":"ADDVCD","target":"LGTD"},{"source":"ADDVNM","target":"LTTD"},{"source":"RVTP","target":"USFL"},{"source":"RVGRD","target":"ESTLC"},{"source":"RVLEN","target":"BSNM"},{"source":"SRCLC","target":"BASCD"},{"source":"ESTLC","target":"SRCLC"},{"source":"LGTD","target":"RVTP"},{"source":"LTTD","target":"RVGRD"},{"source":"GEOMJSON","target":"RVLEN"},{"source":"USFL","target":"GEOMJSON"}]}],"edges":[{"source":"180640318418496","target":"180640372906560"}]}',''),
 (180828190953728,'吴同','','2026-08-06 20:47:10','2026-08-06 20:47:19','{"nodes":[{"id":"180640027438656","label":"表输入组件","type":"1","x":190,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"180640061972032","label":"转换组件","type":"31","x":300,"y":250,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"清洗降雨量为负数的值","field":"DRP","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180640116290112","label":"表输出组件","type":"91","x":530,"y":330,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"180640027438656","target":"180640061972032"},{"source":"180640061972032","target":"180640116290112"}]}','[2026-08-06 20:47:11.878] INFO 开始数据库输入节点
[2026-08-06 20:47:11.883] INFO 开始任务时间: 2026-08-06 20:47:11.883
[2026-08-06 20:47:11.903] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180640027438656",
	"parameter":{
		"dbName":"ODS",
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"DRP"
		],
		"dbType":"Doris",
		"readerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":1,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_RTW_ST_PPTN_R"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:47:16.157] INFO 输入数据量为：12
[2026-08-06 20:47:17.605] INFO 部分数据：
+-------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+--------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+-------------------+-----+
|ID     |RVCD                                                                                 |RVNM                                                                                    |RSCD                                                                                 |RSNM                                                                                       |SECCD                                                                                |SECNM                                                                                       |STCD                                                                                 |STNM                                                                                          |TM                 |DRP  |
+-------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+--------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+-------------------+-----+
|4001096|RV002                                                                                |云溪河                                                                                  |RS004                                                                                |云溪河下游段                                                                               |SEC007                                                                               |云溪下游一断面                                                                              |PP004                                                                                |云溪河下游段雨量站                                                                            |2025-12-31 00:00:00|0.00 |
|4001096|RV002                                                                                |云溪河                                                                                  |RS004                                                                                |云溪河下游段                                                                               |SEC007                                                                               |云溪下游一断面                                                                              |PP004                                                                                |云溪河下游段雨量站                                                                            |2025-12-31 00:00:00|0.00 |
|6001096|RV003                                                                                |碧川河                                                                                  |RS006                                                                                |碧川河下游段                                                                               |SEC011                                                                               |碧川下游一断面                                                                              |PP006                                                                                |碧川河下游段雨量站                                                                            |2025-12-31 00:00:00|0.00 |
|6001096|RV003                                                                                |碧川河                                                                                  |RS006                                                                                |碧川河下游段                                                                               |SEC011                                                                               |碧川下游一断面                                                                              |PP006                                                                                |碧川河下游段雨量站                                                                            |2025-12-31 00:00:00|0.00 |
|2001096|RV001                                                                                |清源河                                                                                  |RS002                                                                                |清源河下游段                                                                               |SEC003                                                                               |清源下游一断面                                                                              |PP002                                                                                |清源河下游段雨量站                                                                            |2025-12-31 00:00:00|0.00 |
|2001096|RV001                                                                                |清源河                                                                                  |RS002                                                                                |清源河下游段                                                                               |SEC003                                                                               |清源下游一断面                                                                              |PP002                                                                                |清源河下游段雨量站                                                                            |2025-12-31 00:00:00|0.00 |
|3001096|RV002                                                                                |云溪河                                                                                  |RS003                                                                                |云溪河上游段                                                                               |SEC005                                                                               |云溪上游一断面                                                                              |PP003                                                                                |云溪河上游段雨量站                                                                            |2025-12-31 00:00:00|0.00 |
|3001096|RV002                                                                                |云溪河                                                                                  |RS003                                                                                |云溪河上游段                                                                               |SEC005                                                                               |云溪上游一断面                                                                              |PP003                                                                                |云溪河上游段雨量站                                                                            |2025-12-31 00:00:00|0.00 |
|5001096|RV003                                                                                |碧川河                                                                                  |RS005                                                                                |碧川河上游段                                                                               |SEC009                                                                               |碧川上游一断面                                                                              |PP005                                                                                |碧川河上游段雨量站                                                                            |2025-12-31 00:00:00|12.00|
|5001096|RV003                                                                                |碧川河                                                                                  |RS005                                                                                |碧川河上游段                                                                               |SEC009                                                                               |碧川上游一断面                                                                              |PP005                                                                                |碧川河上游段雨量站                                                                            |2025-12-31 00:00:00|12.00|
+-------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+--------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+-------------------+-----+
only showing top 10 rows
[2026-08-06 20:47:17.618] INFO 任务成功
[2026-08-06 20:47:17.624] INFO FINALIZE_SESSION
[2026-08-06 20:47:17.655] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:47:17.660] INFO 开始清洗节点
[2026-08-06 20:47:17.667] INFO 开始任务时间: 2026-08-06 20:47:17.666
[2026-08-06 20:47:17.673] INFO 任务参数：{
	"nodeName":"转换组件",
	"componentType":"31",
	"projectCode":152317790975712,
	"nodeCode":"180640061972032",
	"parameter":{
		"tableFields":[
			{
				"name":"清洗降雨量为负数的值",
				"ruleName":"数值边界调整",
				"ruleCode":"001",
				"status":"1",
				"whereClause":"",
				"columns":[
					"DRP"
				],
				"tableName":"",
				"ruleDesc":"",
				"type":"3",
				"ruleConfig":"{\"columns\":[\"DRP\"],\"min\":\"0\",\"max\":\"100\",\"handleType\":\"2\",\"parentName\":\"异常值修正类\"}",
				"id":"",
				"ruleType":"WITHIN_BOUNDARY",
				"parentName":"异常值修正类"
			}
		],
		"where":""
	},
	"nodeVersion":1
}
[2026-08-06 20:47:17.721] INFO 任务成功
[2026-08-06 20:47:17.729] INFO FINALIZE_SESSION
[2026-08-06 20:47:17.778] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:47:17.783] INFO 开始数据库输出节点
[2026-08-06 20:47:17.792] INFO 开始任务时间: 2026-08-06 20:47:17.791
[2026-08-06 20:47:17.797] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180640116290112",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"DWD",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"DWD",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"DWD",
		"datasourceId":2,
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"DRP"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"DWD_RH_RTW_ST_PPTN_R"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"DRP"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:47:17.805] INFO 写入方式为：追加写
[2026-08-06 20:47:19.068] INFO 任务成功
[2026-08-06 20:47:19.088] INFO FINALIZE_SESSION'),
 (180828188917568,'吴同','','2026-08-06 20:47:08','2026-08-06 20:47:20','{"nodes":[{"id":"180639954870848","label":"表输入组件","type":"1","x":130,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_pptn_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"180639964181056","label":"表输出组件","type":"91","x":220,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"180639954870848","target":"180639964181056"}]}',''),
 (180828185599040,'吴同','','2026-08-06 20:47:05','2026-08-06 20:47:13','{"nodes":[{"id":"180639866575424","label":"表输入组件","type":"1","x":340,"y":180,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_pp_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180639896659520","label":"表输出组件","type":"91","x":510,"y":270,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_PP_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180639866575424","target":"180639896659520"}]}','[2026-08-06 20:47:06.443] INFO 开始数据库输入节点
[2026-08-06 20:47:06.473] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180639866575424",
	"parameter":{
		"dbName":"RIVER_WATER",
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"MySql",
		"readerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"RIVER_WATER"
			},
			"dbName":"RIVER_WATER",
			"dbType":"MySql",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":3306,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":12,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"st_pp_stbprp_b"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:47:10.773] INFO 输入数据量为：6
[2026-08-06 20:47:12.446] INFO 部分数据：
+----+-----+------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
|ID  |STCD |STNM              |RVCD |RVNM  |RSCD |RSNM        |SECCD |SECNM         |ADDVCD|ADDVNM|STTP  |STLVL |MNTITEM|COMMODE|LGTD        |LTTD       |EL    |USFL|
+----+-----+------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
|5001|PP001|清源河上游段雨量站|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|330101|青山区|雨量站|基本站|降雨量 |4G     |119.92000000|30.57000000|32.500|1   |
|5002|PP002|清源河下游段雨量站|RV001|清源河|RS002|清源河下游段|SEC003|清源下游一断面|330103|滨河区|雨量站|基本站|降雨量 |4G     |120.05000000|30.39000000|20.200|1   |
|5003|PP003|云溪河上游段雨量站|RV002|云溪河|RS003|云溪河上游段|SEC005|云溪上游一断面|330201|云岭区|雨量站|基本站|降雨量 |4G     |120.27000000|30.42000000|38.100|1   |
|5004|PP004|云溪河下游段雨量站|RV002|云溪河|RS004|云溪河下游段|SEC007|云溪下游一断面|330203|临江区|雨量站|基本站|降雨量 |4G     |120.41000000|30.22000000|22.700|1   |
|5005|PP005|碧川河上游段雨量站|RV003|碧川河|RS005|碧川河上游段|SEC009|碧川上游一断面|330301|碧峰区|雨量站|基本站|降雨量 |4G     |120.60000000|30.12000000|41.500|1   |
|5006|PP006|碧川河下游段雨量站|RV003|碧川河|RS006|碧川河下游段|SEC011|碧川下游一断面|330303|川南区|雨量站|基本站|降雨量 |4G     |120.71000000|29.96000000|24.800|1   |
+----+-----+------------------+-----+------+-----+------------+------+--------------+------+------+------+------+-------+-------+------------+-----------+------+----+
[2026-08-06 20:47:12.465] INFO 任务成功
[2026-08-06 20:47:12.483] INFO FINALIZE_SESSION
[2026-08-06 20:47:12.548] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:47:12.561] INFO 开始数据库输出节点
[2026-08-06 20:47:12.572] INFO 开始任务时间: 2026-08-06 20:47:12.572
[2026-08-06 20:47:12.584] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180639896659520",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"ODS",
		"datasourceId":1,
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_BO_ST_PP_STBPRP_B"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:47:12.598] INFO 写入方式为：追加写
[2026-08-06 20:47:13.871] INFO 任务成功
[2026-08-06 20:47:13.888] INFO FINALIZE_SESSION'),
 (180828176013376,'吴同','','2026-08-06 20:46:55','2026-08-06 20:47:05','{"nodes":[{"id":"180638664062528","label":"表输入组件","type":"1","x":340,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_q_his_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Q"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Q","description":"流量，单位：立方米每秒","type":"decimal","primary":false,"length":"14","scale":"3"}]},{"id":"180638682468928","label":"表输出组件","type":"91","x":480,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_HIST_ST_Q_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Q"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Q","description":"流量，单位：立方米每秒","type":"decimal","primary":false,"length":"14","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Q","target":"Q"}]}],"edges":[{"source":"180638664062528","target":"180638682468928"}]}','[2026-08-06 20:46:57.100] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:57.107] INFO 开始数据库输入节点
[2026-08-06 20:46:57.114] INFO 开始任务时间: 2026-08-06 20:46:57.113
[2026-08-06 20:46:57.135] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180638664062528",
	"parameter":{
		"dbName":"RIVER_WATER",
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Q"
		],
		"dbType":"MySql",
		"readerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"RIVER_WATER"
			},
			"dbName":"RIVER_WATER",
			"dbType":"MySql",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":3306,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":12,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"st_q_his_r"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:47:00.785] INFO 输入数据量为：6576
[2026-08-06 20:47:02.263] INFO 部分数据：
+-------+-----+------+-----+------------+------+--------------+-----+--------------------+-------------------+------+
|ID     |RVCD |RVNM  |RSCD |RSNM        |SECCD |SECNM         |STCD |STNM                |TM                 |Q     |
+-------+-----+------+-----+------------+------+--------------+-----+--------------------+-------------------+------+
|1000001|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-01 00:00:00|49.000|
|1000002|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-02 00:00:00|49.344|
|1000003|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-03 00:00:00|49.688|
|1000004|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-04 00:00:00|50.032|
|1000005|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-05 00:00:00|50.375|
|1000006|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-06 00:00:00|50.718|
|1000007|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-07 00:00:00|51.061|
|1000008|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-08 00:00:00|51.403|
|1000009|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-09 00:00:00|51.744|
|1000010|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-10 00:00:00|52.084|
+-------+-----+------+-----+------------+------+--------------+-----+--------------------+-------------------+------+
only showing top 10 rows
[2026-08-06 20:47:02.292] INFO 任务成功
[2026-08-06 20:47:02.296] INFO FINALIZE_SESSION
[2026-08-06 20:47:02.332] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:47:02.337] INFO 开始数据库输出节点
[2026-08-06 20:47:02.342] INFO 开始任务时间: 2026-08-06 20:47:02.342
[2026-08-06 20:47:02.350] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180638682468928",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"ODS",
		"datasourceId":1,
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Q"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_HIST_ST_Q_HIS_R"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Q"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:47:02.359] INFO 写入方式为：追加写
[2026-08-06 20:47:05.556] INFO 任务成功
[2026-08-06 20:47:05.562] INFO FINALIZE_SESSION'),
 (180828163325472,'吴同','','2026-08-06 20:46:43','2026-08-06 20:46:54','{"nodes":[{"id":"180643343962688","label":"表输入组件","type":"1","x":270,"y":190,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"app_rv_wtrov_a"},{"name":"字段","value":"ID, SCENECD, SCENENM, MODCD, MODNM, CARDCD, CARDNM, DISPORD, STATDT, STTPRD, BGNTM, ENDTM, OBJTP, OBJCD, OBJNM, METRICCD, METRICNM, METRICVAL, METRICUNIT, TRDDIR, WRNST, PAYLOADJSON, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, Z, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SCENECD","description":"应用场景编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"SCENENM","description":"应用场景名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"MODCD","description":"展示模块编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"MODNM","description":"展示模块名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"CARDCD","description":"卡片编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"CARDNM","description":"卡片名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DISPORD","description":"展示顺序","type":"int","primary":false,"length":"10","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICCD","description":"指标编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"METRICNM","description":"指标名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICVAL","description":"指标值","type":"decimal","primary":false,"length":"18","scale":"4"},{"name":"METRICUNIT","description":"指标单位","type":"varchar","primary":false,"length":"50","scale":""},{"name":"TRDDIR","description":"趋势方向","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRNST","description":"预警状态","type":"varchar","primary":false,"length":"50","scale":""},{"name":"PAYLOADJSON","description":"展示数据内容，JSON格式","type":"text","primary":false,"length":"65535","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180643366276672","label":"表输出组件","type":"91","x":400,"y":270,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ADS"},{"name":"数据表","value":"ADS_AP_API_APP_RV_WTROV_A"},{"name":"字段","value":"ID, SCENECD, SCENENM, MODCD, MODNM, CARDCD, CARDNM, DISPORD, STATDT, STTPRD, BGNTM, ENDTM, OBJTP, OBJCD, OBJNM, METRICCD, METRICNM, METRICVAL, METRICUNIT, TRDDIR, WRNST, PAYLOADJSON, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, Z, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SCENECD","description":"应用场景编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"SCENENM","description":"应用场景名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"MODCD","description":"展示模块编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"MODNM","description":"展示模块名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"CARDCD","description":"卡片编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"CARDNM","description":"卡片名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DISPORD","description":"展示顺序","type":"int","primary":false,"length":"10","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICCD","description":"指标编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"METRICNM","description":"指标名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICVAL","description":"指标值","type":"decimal","primary":false,"length":"18","scale":"4"},{"name":"METRICUNIT","description":"指标单位","type":"varchar","primary":false,"length":"50","scale":""},{"name":"TRDDIR","description":"趋势方向","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRNST","description":"预警状态","type":"varchar","primary":false,"length":"50","scale":""},{"name":"PAYLOADJSON","description":"展示数据内容，JSON格式","type":"text","primary":false,"length":"65535","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"SCENECD","target":"SCENECD"},{"source":"SCENENM","target":"SCENENM"},{"source":"MODCD","target":"MODCD"},{"source":"MODNM","target":"MODNM"},{"source":"CARDCD","target":"CARDCD"},{"source":"CARDNM","target":"CARDNM"},{"source":"DISPORD","target":"DISPORD"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"METRICCD","target":"METRICCD"},{"source":"METRICNM","target":"METRICNM"},{"source":"METRICVAL","target":"METRICVAL"},{"source":"METRICUNIT","target":"METRICUNIT"},{"source":"TRDDIR","target":"TRDDIR"},{"source":"WRNST","target":"WRNST"},{"source":"PAYLOADJSON","target":"PAYLOADJSON"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"Z","target":"Z"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180643343962688","target":"180643366276672"}]}','[2026-08-06 20:46:44.822] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:44.851] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180643343962688",
	"parameter":{
		"dbName":"RIVER_WATER",
		"column":[
			"ID",
			"SCENECD",
			"SCENENM",
			"MODCD",
			"MODNM",
			"CARDCD",
			"CARDNM",
			"DISPORD",
			"STATDT",
			"STTPRD",
			"BGNTM",
			"ENDTM",
			"OBJTP",
			"OBJCD",
			"OBJNM",
			"METRICCD",
			"METRICNM",
			"METRICVAL",
			"METRICUNIT",
			"TRDDIR",
			"WRNST",
			"PAYLOADJSON",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"Z",
			"ZRR"
		],
		"dbType":"MySql",
		"readerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"RIVER_WATER"
			},
			"dbName":"RIVER_WATER",
			"dbType":"MySql",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":3306,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":12,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"app_rv_wtrov_a"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:46:51.760] INFO 输入数据量为：12
[2026-08-06 20:46:53.339] INFO 部分数据：
+---+--------+--------+--------+--------+--------+--------+-------+----------+--------+-------------------+-------------------+-------+------+--------------+--------+--------+---------+----------+------+-----+-----------------------------------------------------------------------------------+-----+------+-----+------------+------+--------------+-----+------+
|ID |SCENECD |SCENENM |MODCD   |MODNM   |CARDCD  |CARDNM  |DISPORD|STATDT    |STTPRD  |BGNTM              |ENDTM              |OBJTP  |OBJCD |OBJNM         |METRICCD|METRICNM|METRICVAL|METRICUNIT|TRDDIR|WRNST|PAYLOADJSON                                                                        |RVCD |RVNM  |RSCD |RSNM        |SECCD |SECNM         |Z    |ZRR   |
+---+--------+--------+--------+--------+--------+--------+-------+----------+--------+-------------------+-------------------+-------+------+--------------+--------+--------+---------+----------+------+-----+-----------------------------------------------------------------------------------+-----+------+-----+------------+------+--------------+-----+------+
|1  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|1      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC001|清源上游一断面|Z       |实时水位|4.1730   |m         |下降  |正常 |{"q": 48.742, "z": 4.173, "tm": "2025-12-31 00:00:00", "wrz": 5.100, "zrr": -0.050}|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|4.173|-0.050|
|3  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|3      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC003|清源下游一断面|Z       |实时水位|4.3330   |m         |平稳  |正常 |{"q": 56.742, "z": 4.333, "tm": "2025-12-31 00:00:00", "wrz": 5.200, "zrr": 0.000} |RV001|清源河|RS002|清源河下游段|SEC003|清源下游一断面|4.333|0.000 |
|5  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|5      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC005|云溪上游一断面|Z       |实时水位|4.4930   |m         |平稳  |正常 |{"q": 64.742, "z": 4.493, "tm": "2025-12-31 00:00:00", "wrz": 5.300, "zrr": 0.000} |RV002|云溪河|RS003|云溪河上游段|SEC005|云溪上游一断面|4.493|0.000 |
|7  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|7      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC007|云溪下游一断面|Z       |实时水位|4.6530   |m         |平稳  |正常 |{"q": 72.742, "z": 4.653, "tm": "2025-12-31 00:00:00", "wrz": 5.400, "zrr": 0.000} |RV002|云溪河|RS004|云溪河下游段|SEC007|云溪下游一断面|4.653|0.000 |
|9  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|9      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC009|碧川上游一断面|Z       |实时水位|4.8130   |m         |平稳  |正常 |{"q": 80.742, "z": 4.813, "tm": "2025-12-31 00:00:00", "wrz": 5.500, "zrr": 0.000} |RV003|碧川河|RS005|碧川河上游段|SEC009|碧川上游一断面|4.813|0.000 |
|11 |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|11     |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC011|碧川下游一断面|Z       |实时水位|4.9730   |m         |下降  |正常 |{"q": 88.742, "z": 4.973, "tm": "2025-12-31 00:00:00", "wrz": 5.600, "zrr": -0.050}|RV003|碧川河|RS006|碧川河下游段|SEC011|碧川下游一断面|4.973|-0.050|
|1  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|1      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC001|清源上游一断面|Z       |实时水位|4.1730   |m         |下降  |正常 |{"q": 48.742, "z": 4.173, "tm": "2025-12-31 00:00:00", "wrz": 5.100, "zrr": -0.050}|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|4.173|-0.050|
|3  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|3      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC003|清源下游一断面|Z       |实时水位|4.3330   |m         |平稳  |正常 |{"q": 56.742, "z": 4.333, "tm": "2025-12-31 00:00:00", "wrz": 5.200, "zrr": 0.000} |RV001|清源河|RS002|清源河下游段|SEC003|清源下游一断面|4.333|0.000 |
|5  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|5      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC005|云溪上游一断面|Z       |实时水位|4.4930   |m         |平稳  |正常 |{"q": 64.742, "z": 4.493, "tm": "2025-12-31 00:00:00", "wrz": 5.300, "zrr": 0.000} |RV002|云溪河|RS003|云溪河上游段|SEC005|云溪上游一断面|4.493|0.000 |
|7  |RV_WATER|河道水情|OVERVIEW|综合态势|LATEST_Z|最新水位|7      |2025-12-31|REALTIME|2025-12-31 00:00:00|2025-12-31 00:00:00|SECTION|SEC007|云溪下游一断面|Z       |实时水位|4.6530   |m         |平稳  |正常 |{"q": 72.742, "z": 4.653, "tm": "2025-12-31 00:00:00", "wrz": 5.400, "zrr": 0.000} |RV002|云溪河|RS004|云溪河下游段|SEC007|云溪下游一断面|4.653|0.000 |
+---+--------+--------+--------+--------+--------+--------+-------+----------+--------+-------------------+-------------------+-------+------+--------------+--------+--------+---------+----------+------+-----+-----------------------------------------------------------------------------------+-----+------+-----+------------+------+--------------+-----+------+
only showing top 10 rows
[2026-08-06 20:46:53.353] INFO 任务成功
[2026-08-06 20:46:53.359] INFO FINALIZE_SESSION
[2026-08-06 20:46:53.396] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:53.402] INFO 开始数据库输出节点
[2026-08-06 20:46:53.410] INFO 开始任务时间: 2026-08-06 20:46:53.409
[2026-08-06 20:46:53.416] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180643366276672",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ADS"
			},
			"dbName":"ADS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"ADS",
		"datasourceId":5,
		"column":[
			"ID",
			"SCENECD",
			"SCENENM",
			"MODCD",
			"MODNM",
			"CARDCD",
			"CARDNM",
			"DISPORD",
			"STATDT",
			"STTPRD",
			"BGNTM",
			"ENDTM",
			"OBJTP",
			"OBJCD",
			"OBJNM",
			"METRICCD",
			"METRICNM",
			"METRICVAL",
			"METRICUNIT",
			"TRDDIR",
			"WRNST",
			"PAYLOADJSON",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"Z",
			"ZRR"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"ADS_AP_API_APP_RV_WTROV_A"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"SCENECD",
			"SCENENM",
			"MODCD",
			"MODNM",
			"CARDCD",
			"CARDNM",
			"DISPORD",
			"STATDT",
			"STTPRD",
			"BGNTM",
			"ENDTM",
			"OBJTP",
			"OBJCD",
			"OBJNM",
			"METRICCD",
			"METRICNM",
			"METRICVAL",
			"METRICUNIT",
			"TRDDIR",
			"WRNST",
			"PAYLOADJSON",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"Z",
			"ZRR"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:46:53.431] INFO 写入方式为：追加写
[2026-08-06 20:46:54.757] INFO 任务成功
[2026-08-06 20:46:54.761] INFO FINALIZE_SESSION'),
 (180828152475584,'吴同','','2026-08-06 20:46:33','2026-08-06 20:46:50','{"nodes":[{"id":"180638398249536","label":"表输入组件","type":"1","x":230,"y":160,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_day_s"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180638458847808","label":"表输出组件","type":"91","x":370,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWS"},{"name":"数据表","value":"DWS_RH_HIST_ST_Z_DAY_S"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"AVGZ","target":"AVGZ"},{"source":"MAXZ","target":"MAXZ"},{"source":"MINZ","target":"MINZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180638398249536","target":"180638458847808"}]}',''),
 (180828144655136,'吴同','','2026-08-06 20:46:25','2026-08-06 20:46:44','{"nodes":[{"id":"180634940345920","label":"表输入组件","type":"1","x":280,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634974291520","label":"转换组件","type":"31","x":400,"y":180,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"水位范围清洗","field":"Z","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180635019662912","label":"表输出组件","type":"91","x":490,"y":260,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180634940345920","target":"180634974291520"},{"source":"180634974291520","target":"180635019662912"}]}','[2026-08-06 20:46:26.656] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:26.669] INFO 开始数据库输入节点
[2026-08-06 20:46:26.682] INFO 开始任务时间: 2026-08-06 20:46:26.681
[2026-08-06 20:46:26.710] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180634940345920",
	"parameter":{
		"dbName":"ODS",
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Z",
			"WRZ",
			"ZRR"
		],
		"dbType":"Doris",
		"readerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":1,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_MON_ST_Z_HIS_R"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:46:31.959] INFO 输入数据量为：19728
[2026-08-06 20:46:34.107] INFO 部分数据：
+-------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+--------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-----------------------------------------------------------------------------------------------+-------------------+-----+-----+-----+
|ID     |RVCD                                                                                 |RVNM                                                                                    |RSCD                                                                                 |RSNM                                                                                       |SECCD                                                                                |SECNM                                                                                       |STCD                                                                                 |STNM                                                                                           |TM                 |Z    |WRZ  |ZRR  |
+-------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+--------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-----------------------------------------------------------------------------------------------+-------------------+-----+-----+-----+
|1000007|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-07 00:00:00|4.237|5.100|0.000|
|1000007|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-07 00:00:00|4.237|5.100|0.000|
|1000007|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-07 00:00:00|4.237|5.100|0.000|
|1000012|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-12 00:00:00|4.283|5.100|0.000|
|1000012|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-12 00:00:00|4.283|5.100|0.000|
|1000012|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-12 00:00:00|4.283|5.100|0.000|
|1000020|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-20 00:00:00|4.357|5.100|0.000|
|1000020|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-20 00:00:00|4.357|5.100|0.000|
|1000020|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-01-20 00:00:00|4.357|5.100|0.000|
|1000048|RV001                                                                                |清源河                                                                                  |RS001                                                                                |清源河上游段                                                                               |SEC001                                                                               |清源上游一断面                                                                              |ST001                                                                                |清源上游一断面水文站                                                                           |2023-02-17 00:00:00|4.578|5.100|0.000|
+-------+-------------------------------------------------------------------------------------+----------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+--------------------------------------------------------------------------------------------+-------------------------------------------------------------------------------------+-----------------------------------------------------------------------------------------------+-------------------+-----+-----+-----+
only showing top 10 rows
[2026-08-06 20:46:34.122] INFO 任务成功
[2026-08-06 20:46:34.131] INFO FINALIZE_SESSION
[2026-08-06 20:46:34.179] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:34.187] INFO 开始清洗节点
[2026-08-06 20:46:34.198] INFO 开始任务时间: 2026-08-06 20:46:34.198
[2026-08-06 20:46:34.209] INFO 任务参数：{
	"nodeName":"转换组件",
	"componentType":"31",
	"projectCode":152317790975712,
	"nodeCode":"180634974291520",
	"parameter":{
		"tableFields":[
			{
				"name":"水位范围清洗",
				"ruleName":"数值边界调整",
				"ruleCode":"001",
				"status":"1",
				"whereClause":"",
				"columns":[
					"Z"
				],
				"tableName":"",
				"ruleDesc":"",
				"type":"3",
				"ruleConfig":"{\"columns\":[\"Z\"],\"min\":\"0\",\"max\":\"4\",\"handleType\":\"3\",\"parentName\":\"异常值修正类\"}",
				"id":"",
				"parentName":"异常值修正类",
				"ruleType":"WITHIN_BOUNDARY"
			}
		],
		"where":""
	},
	"nodeVersion":3
}
[2026-08-06 20:46:34.294] INFO 任务成功
[2026-08-06 20:46:34.301] INFO FINALIZE_SESSION
[2026-08-06 20:46:34.348] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:34.358] INFO 开始数据库输出节点
[2026-08-06 20:46:34.371] INFO 开始任务时间: 2026-08-06 20:46:34.370
[2026-08-06 20:46:34.380] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180635019662912",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"DWD",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"DWD",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"DWD",
		"datasourceId":2,
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Z",
			"WRZ",
			"ZRR"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"DWD_RH_MON_ST_Z_HIS_R"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Z",
			"WRZ",
			"ZRR"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:46:34.389] INFO 写入方式为：追加写
[2026-08-06 20:46:44.693] INFO 任务成功
[2026-08-06 20:46:44.704] INFO FINALIZE_SESSION'),
 (180828143077664,'吴同','','2026-08-06 20:46:24','2026-08-06 20:46:40','{"nodes":[{"id":"180644232485440","label":"表输入组件","type":"1","x":400,"y":200,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180644247765568","label":"表输出组件","type":"91","x":560,"y":310,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[]}','[2026-08-06 20:46:27.059] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:27.072] INFO 开始数据库输入节点
[2026-08-06 20:46:27.083] INFO 开始任务时间: 2026-08-06 20:46:27.083
[2026-08-06 20:46:27.109] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180644232485440",
	"parameter":{
		"dbName":"ODS",
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"Doris",
		"readerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":1,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_BO_ST_STBPRP_B"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":2
}
[2026-08-06 20:46:37.799] INFO 输入数据量为：0
[2026-08-06 20:46:38.507] INFO 部分数据：
+---+----+----+----+----+----+----+-----+-----+------+------+----+-----+-------+-------+----+----+---+----+
|ID |STCD|STNM|RVCD|RVNM|RSCD|RSNM|SECCD|SECNM|ADDVCD|ADDVNM|STTP|STLVL|MNTITEM|COMMODE|LGTD|LTTD|EL |USFL|
+---+----+----+----+----+----+----+-----+-----+------+------+----+-----+-------+-------+----+----+---+----+
+---+----+----+----+----+----+----+-----+-----+------+------+----+-----+-------+-------+----+----+---+----+
[2026-08-06 20:46:38.536] INFO 任务成功
[2026-08-06 20:46:38.541] INFO FINALIZE_SESSION
[2026-08-06 20:46:38.591] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:38.597] INFO 开始数据库输出节点
[2026-08-06 20:46:38.603] INFO 开始任务时间: 2026-08-06 20:46:38.603
[2026-08-06 20:46:38.617] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180644247765568",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"DIM",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"DIM",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"DIM",
		"datasourceId":3,
		"column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"DIM_RH_BO_ST_PP_STBPRP_B"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"STCD",
			"STNM",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"ADDVCD",
			"ADDVNM",
			"STTP",
			"STLVL",
			"MNTITEM",
			"COMMODE",
			"LGTD",
			"LTTD",
			"EL",
			"USFL"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":2
}
[2026-08-06 20:46:38.626] INFO 写入方式为：追加写
[2026-08-06 20:46:40.263] INFO 任务成功'),
 (180828138342976,'吴同','','2026-08-06 20:46:19','2026-08-06 20:46:38','{"nodes":[{"id":"180634814063168","label":"表输入组件","type":"1","x":220,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_his_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634825788992","label":"表输出组件","type":"91","x":440,"y":190,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180634814063168","target":"180634825788992"}]}','[2026-08-06 20:46:20.902] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:20.910] INFO 开始数据库输入节点
[2026-08-06 20:46:20.921] INFO 开始任务时间: 2026-08-06 20:46:20.921
[2026-08-06 20:46:20.940] INFO 任务参数：{
	"nodeName":"表输入组件",
	"componentType":"1",
	"projectCode":152317790975712,
	"nodeCode":"180634814063168",
	"parameter":{
		"dbName":"RIVER_WATER",
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Z",
			"WRZ",
			"ZRR"
		],
		"dbType":"MySql",
		"readerProperty":{
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"RIVER_WATER"
			},
			"dbName":"RIVER_WATER",
			"dbType":"MySql",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":3306,
[敏感参数已脱敏]
		},
[敏感参数已脱敏]
		"dateIncrementConfig":{
			"logic":"and",
			"dateFormat":"yyyy-MM-dd",
			"column":[
			]
		},
		"idIncrementConfig":{
			"incrementStart":""
		},
		"datasourceId":12,
		"where":"",
		"connection":{
[敏感参数已脱敏]
			"table":"st_z_his_r"
		},
		"batchSize":"1024",
		"readModeType":"1",
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:46:30.943] INFO 输入数据量为：6576
[2026-08-06 20:46:32.953] INFO 部分数据：
+-------+-----+------+-----+------------+------+--------------+-----+--------------------+-------------------+-----+-----+-----+
|ID     |RVCD |RVNM  |RSCD |RSNM        |SECCD |SECNM         |STCD |STNM                |TM                 |Z    |WRZ  |ZRR  |
+-------+-----+------+-----+------------+------+--------------+-----+--------------------+-------------------+-----+-----+-----+
|1000001|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-01 00:00:00|4.180|5.100|0.000|
|1000002|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-02 00:00:00|4.189|5.100|0.000|
|1000003|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-03 00:00:00|4.199|5.100|0.000|
|1000004|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-04 00:00:00|4.208|5.100|0.000|
|1000005|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-05 00:00:00|4.218|5.100|0.000|
|1000006|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-06 00:00:00|4.227|5.100|0.000|
|1000007|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-07 00:00:00|4.237|5.100|0.000|
|1000008|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-08 00:00:00|4.246|5.100|0.000|
|1000009|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-09 00:00:00|4.255|5.100|0.000|
|1000010|RV001|清源河|RS001|清源河上游段|SEC001|清源上游一断面|ST001|清源上游一断面水文站|2023-01-10 00:00:00|4.265|5.100|0.000|
+-------+-----+------+-----+------------+------+--------------+-----+--------------------+-------------------+-----+-----+-----+
only showing top 10 rows
[2026-08-06 20:46:32.972] INFO 任务成功
[2026-08-06 20:46:32.981] INFO FINALIZE_SESSION
[2026-08-06 20:46:33.029] INFO *********************************  Initialize task context  ***********************************
[2026-08-06 20:46:33.035] INFO 开始数据库输出节点
[2026-08-06 20:46:33.042] INFO 开始任务时间: 2026-08-06 20:46:33.042
[2026-08-06 20:46:33.051] INFO 任务参数：{
	"nodeName":"表输出组件",
	"componentType":"91",
	"projectCode":152317790975712,
	"nodeCode":"180634825788992",
	"parameter":{
[敏感参数已脱敏]
		"selectedColumns":[
		],
		"writerProperty":{
			"config":{
			},
			"datasourceConfig":{
[敏感参数已脱敏]
[敏感参数已脱敏]
				"dbname":"ODS",
				"config":"{}",
				"allowOperate":0,
				"useSSL":0,
				"sslConfig":{
				},
				"useKerberos":0,
				"kerberosConfig":{
				}
			},
			"dbName":"ODS",
			"dbType":"Doris",
[敏感参数已脱敏]
[敏感参数已脱敏]
			"port":9030,
[敏感参数已脱敏]
		},
		"dbName":"ODS",
		"datasourceId":1,
		"column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Z",
			"WRZ",
			"ZRR"
		],
		"dbType":"Doris",
		"connection":{
[敏感参数已脱敏]
			"table":"ODS_RH_MON_ST_Z_HIS_R"
		},
		"batchSize":"1024",
		"target_column":[
			"ID",
			"RVCD",
			"RVNM",
			"RSCD",
			"RSNM",
			"SECCD",
			"SECNM",
			"STCD",
			"STNM",
			"TM",
			"Z",
			"WRZ",
			"ZRR"
		],
		"writeModeType":2,
[敏感参数已脱敏]
	},
	"nodeVersion":1
}
[2026-08-06 20:46:33.061] INFO 写入方式为：追加写
[2026-08-06 20:46:38.718] INFO 任务成功
[2026-08-06 20:46:38.725] INFO FINALIZE_SESSION'),
 (180828134271424,'吴同','','2026-08-06 20:46:15','2026-08-06 20:46:27','{"nodes":[{"id":"180629980610112","label":"表输入组件","type":"1","x":230,"y":230,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180630021398080","label":"表输出组件","type":"91","x":380,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180629980610112","target":"180630021398080"}]}',''),
 (180734941784448,'吴同','','2026-08-05 19:29:28','2026-08-05 19:30:43','{"nodes":[{"id":"180638664062528","label":"表输入组件","type":"1","x":340,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_q_his_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Q"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Q","description":"流量，单位：立方米每秒","type":"decimal","primary":false,"length":"14","scale":"3"}]},{"id":"180638682468928","label":"表输出组件","type":"91","x":480,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_HIST_ST_Q_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Q"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Q","description":"流量，单位：立方米每秒","type":"decimal","primary":false,"length":"14","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Q","target":"Q"}]}],"edges":[{"source":"180638664062528","target":"180638682468928"}]}',''),
 (180734923026784,'吴同','','2026-08-05 19:29:10','2026-08-05 19:29:35','{"nodes":[{"id":"180640656332352","label":"表输入组件","type":"1","x":180,"y":150,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640673126976","label":"表输出组件","type":"91","x":310,"y":280,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640656332352","target":"180640673126976"}]}',''),
 (180734876660416,'吴同','','2026-08-05 19:28:24','2026-08-05 19:29:34','{"nodes":[{"id":"180639954870848","label":"表输入组件","type":"1","x":130,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_pptn_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"180639964181056","label":"表输出组件","type":"91","x":220,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"180639954870848","target":"180639964181056"}]}',''),
 (180734807399776,'吴同','','2026-08-05 19:27:16','2026-08-05 19:27:44','{"nodes":[{"id":"180640027438656","label":"表输入组件","type":"1","x":190,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"180640061972032","label":"转换组件","type":"31","x":300,"y":250,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"清洗降雨量为负数的值","field":"DRP","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180640116290112","label":"表输出组件","type":"91","x":530,"y":330,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"180640027438656","target":"180640061972032"},{"source":"180640061972032","target":"180640116290112"}]}',''),
 (180734797282592,'吴同','','2026-08-05 19:27:07','2026-08-05 19:28:10','{"nodes":[{"id":"180639866575424","label":"表输入组件","type":"1","x":340,"y":180,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_pp_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180639896659520","label":"表输出组件","type":"91","x":510,"y":270,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_PP_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180639866575424","target":"180639896659520"}]}',''),
 (180733273618976,'吴同','','2026-08-05 19:02:19','2026-08-05 19:03:39','{"nodes":[{"id":"180643343962688","label":"表输入组件","type":"1","x":270,"y":190,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"app_rv_wtrov_a"},{"name":"字段","value":"ID, SCENECD, SCENENM, MODCD, MODNM, CARDCD, CARDNM, DISPORD, STATDT, STTPRD, BGNTM, ENDTM, OBJTP, OBJCD, OBJNM, METRICCD, METRICNM, METRICVAL, METRICUNIT, TRDDIR, WRNST, PAYLOADJSON, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, Z, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SCENECD","description":"应用场景编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"SCENENM","description":"应用场景名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"MODCD","description":"展示模块编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"MODNM","description":"展示模块名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"CARDCD","description":"卡片编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"CARDNM","description":"卡片名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DISPORD","description":"展示顺序","type":"int","primary":false,"length":"10","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICCD","description":"指标编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"METRICNM","description":"指标名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICVAL","description":"指标值","type":"decimal","primary":false,"length":"18","scale":"4"},{"name":"METRICUNIT","description":"指标单位","type":"varchar","primary":false,"length":"50","scale":""},{"name":"TRDDIR","description":"趋势方向","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRNST","description":"预警状态","type":"varchar","primary":false,"length":"50","scale":""},{"name":"PAYLOADJSON","description":"展示数据内容，JSON格式","type":"text","primary":false,"length":"65535","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180643366276672","label":"表输出组件","type":"91","x":400,"y":270,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ADS"},{"name":"数据表","value":"ADS_AP_API_APP_RV_WTROV_A"},{"name":"字段","value":"ID, SCENECD, SCENENM, MODCD, MODNM, CARDCD, CARDNM, DISPORD, STATDT, STTPRD, BGNTM, ENDTM, OBJTP, OBJCD, OBJNM, METRICCD, METRICNM, METRICVAL, METRICUNIT, TRDDIR, WRNST, PAYLOADJSON, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, Z, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SCENECD","description":"应用场景编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"SCENENM","description":"应用场景名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"MODCD","description":"展示模块编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"MODNM","description":"展示模块名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"CARDCD","description":"卡片编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"CARDNM","description":"卡片名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DISPORD","description":"展示顺序","type":"int","primary":false,"length":"10","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICCD","description":"指标编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"METRICNM","description":"指标名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICVAL","description":"指标值","type":"decimal","primary":false,"length":"18","scale":"4"},{"name":"METRICUNIT","description":"指标单位","type":"varchar","primary":false,"length":"50","scale":""},{"name":"TRDDIR","description":"趋势方向","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRNST","description":"预警状态","type":"varchar","primary":false,"length":"50","scale":""},{"name":"PAYLOADJSON","description":"展示数据内容，JSON格式","type":"text","primary":false,"length":"65535","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"SCENECD","target":"SCENECD"},{"source":"SCENENM","target":"SCENENM"},{"source":"MODCD","target":"MODCD"},{"source":"MODNM","target":"MODNM"},{"source":"CARDCD","target":"CARDCD"},{"source":"CARDNM","target":"CARDNM"},{"source":"DISPORD","target":"DISPORD"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"METRICCD","target":"METRICCD"},{"source":"METRICNM","target":"METRICNM"},{"source":"METRICVAL","target":"METRICVAL"},{"source":"METRICUNIT","target":"METRICUNIT"},{"source":"TRDDIR","target":"TRDDIR"},{"source":"WRNST","target":"WRNST"},{"source":"PAYLOADJSON","target":"PAYLOADJSON"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"Z","target":"Z"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180643343962688","target":"180643366276672"}]}',''),
 (180732715957504,'吴同','','2026-08-05 18:53:14','2026-08-05 18:54:13','{"nodes":[{"id":"180638398249536","label":"表输入组件","type":"1","x":230,"y":160,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_day_s"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180638458847808","label":"表输出组件","type":"91","x":370,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWS"},{"name":"数据表","value":"DWS_RH_HIST_ST_Z_DAY_S"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"AVGZ","target":"AVGZ"},{"source":"MAXZ","target":"MAXZ"},{"source":"MINZ","target":"MINZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180638398249536","target":"180638458847808"}]}',''),
 (180732707736000,'吴同','','2026-08-05 18:53:06','2026-08-05 18:54:20','{"nodes":[{"id":"180644232485440","label":"表输入组件","type":"1","x":400,"y":200,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180644247765568","label":"表输出组件","type":"91","x":560,"y":310,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[]}',''),
 (180732465720672,'吴同','','2026-08-05 18:49:10','2026-08-05 18:51:04','{"nodes":[{"id":"180634940345920","label":"表输入组件","type":"1","x":280,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634974291520","label":"转换组件","type":"31","x":400,"y":180,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"水位范围清洗","field":"Z","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180635019662912","label":"表输出组件","type":"91","x":490,"y":260,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180634940345920","target":"180634974291520"},{"source":"180634974291520","target":"180635019662912"}]}',''),
 (180732152368224,'吴同','','2026-08-05 18:44:04','2026-08-05 18:45:02','{"nodes":[{"id":"180634814063168","label":"表输入组件","type":"1","x":220,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_his_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634825788992","label":"表输出组件","type":"91","x":440,"y":190,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180634814063168","target":"180634825788992"}]}',''),
 (180732146972000,'吴同','','2026-08-05 18:43:58','2026-08-05 18:45:30','{"nodes":[{"id":"180629980610112","label":"表输入组件","type":"1","x":230,"y":230,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180630021398080","label":"表输出组件","type":"91","x":380,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180629980610112","target":"180630021398080"}]}',''),
 (180655924056416,'吴同','','2026-08-04 22:03:22','2026-08-04 22:04:34','{"nodes":[{"id":"180644232485440","label":"表输入组件","type":"1","x":400,"y":200,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180644247765568","label":"表输出组件","type":"91","x":560,"y":310,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[]}',''),
 (180653625275712,'吴同','','2026-08-04 21:25:57','2026-08-04 21:27:35','{"nodes":[{"id":"180638398249536","label":"表输入组件","type":"1","x":230,"y":160,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_day_s"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180638458847808","label":"表输出组件","type":"91","x":370,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWS"},{"name":"数据表","value":"DWS_RH_HIST_ST_Z_DAY_S"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"AVGZ","target":"AVGZ"},{"source":"MAXZ","target":"MAXZ"},{"source":"MINZ","target":"MINZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180638398249536","target":"180638458847808"}]}',''),
 (180653525968992,'吴同','','2026-08-04 21:24:20','2026-08-04 21:25:09','{"nodes":[{"id":"180634940345920","label":"表输入组件","type":"1","x":280,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634974291520","label":"转换组件","type":"31","x":400,"y":180,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"水位范围清洗","field":"Z","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180635019662912","label":"表输出组件","type":"91","x":490,"y":260,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180634940345920","target":"180634974291520"},{"source":"180634974291520","target":"180635019662912"}]}',''),
 (180653379000928,'吴同','','2026-08-04 21:21:57','2026-08-04 21:22:34','{"nodes":[{"id":"180629980610112","label":"表输入组件","type":"1","x":230,"y":230,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180630021398080","label":"表输出组件","type":"91","x":380,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180629980610112","target":"180630021398080"}]}',''),
 (180653379012224,'吴同','','2026-08-04 21:21:57','2026-08-04 21:23:20','{"nodes":[{"id":"180634814063168","label":"表输入组件","type":"1","x":220,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_his_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634825788992","label":"表输出组件","type":"91","x":440,"y":190,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180634814063168","target":"180634825788992"}]}',''),
 (180643952638912,'吴同','','2026-08-04 18:48:31','2026-08-04 18:50:45','{"nodes":[{"id":"180638398249536","label":"表输入组件","type":"1","x":230,"y":160,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_day_s"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180638458847808","label":"表输出组件","type":"91","x":370,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWS"},{"name":"数据表","value":"DWS_RH_HIST_ST_Z_DAY_S"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"AVGZ","target":"AVGZ"},{"source":"MAXZ","target":"MAXZ"},{"source":"MINZ","target":"MINZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180638398249536","target":"180638458847808"}]}',''),
 (180643589517120,'吴同','','2026-08-04 18:42:37','2026-08-04 18:43:02','{"nodes":[{"id":"180638398249536","label":"表输入组件","type":"1","x":230,"y":160,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_day_s"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180638458847808","label":"表输出组件","type":"91","x":370,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWS"},{"name":"数据表","value":"DWS_RH_HIST_ST_Z_DAY_S"},{"name":"字段","value":"ID, OBJTP, OBJCD, OBJNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, STATDT, STTPRD, BGNTM, ENDTM, AVGZ, MAXZ, MINZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"AVGZ","description":"平均水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MAXZ","description":"最高水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"MINZ","description":"最低水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"AVGZ","target":"AVGZ"},{"source":"MAXZ","target":"MAXZ"},{"source":"MINZ","target":"MINZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180638398249536","target":"180638458847808"}]}',''),
 (180643432345632,'吴同','','2026-08-04 18:40:03','2026-08-04 18:40:36','{"nodes":[{"id":"180643343962688","label":"表输入组件","type":"1","x":270,"y":190,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"app_rv_wtrov_a"},{"name":"字段","value":"ID, SCENECD, SCENENM, MODCD, MODNM, CARDCD, CARDNM, DISPORD, STATDT, STTPRD, BGNTM, ENDTM, OBJTP, OBJCD, OBJNM, METRICCD, METRICNM, METRICVAL, METRICUNIT, TRDDIR, WRNST, PAYLOADJSON, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, Z, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SCENECD","description":"应用场景编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"SCENENM","description":"应用场景名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"MODCD","description":"展示模块编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"MODNM","description":"展示模块名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"CARDCD","description":"卡片编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"CARDNM","description":"卡片名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DISPORD","description":"展示顺序","type":"int","primary":false,"length":"10","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICCD","description":"指标编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"METRICNM","description":"指标名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICVAL","description":"指标值","type":"decimal","primary":false,"length":"18","scale":"4"},{"name":"METRICUNIT","description":"指标单位","type":"varchar","primary":false,"length":"50","scale":""},{"name":"TRDDIR","description":"趋势方向","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRNST","description":"预警状态","type":"varchar","primary":false,"length":"50","scale":""},{"name":"PAYLOADJSON","description":"展示数据内容，JSON格式","type":"text","primary":false,"length":"65535","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180643366276672","label":"表输出组件","type":"91","x":400,"y":270,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ADS"},{"name":"数据表","value":"ADS_AP_API_APP_RV_WTROV_A"},{"name":"字段","value":"ID, SCENECD, SCENENM, MODCD, MODNM, CARDCD, CARDNM, DISPORD, STATDT, STTPRD, BGNTM, ENDTM, OBJTP, OBJCD, OBJNM, METRICCD, METRICNM, METRICVAL, METRICUNIT, TRDDIR, WRNST, PAYLOADJSON, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, Z, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SCENECD","description":"应用场景编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"SCENENM","description":"应用场景名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"MODCD","description":"展示模块编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"MODNM","description":"展示模块名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"CARDCD","description":"卡片编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"CARDNM","description":"卡片名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DISPORD","description":"展示顺序","type":"int","primary":false,"length":"10","scale":""},{"name":"STATDT","description":"统计日期","type":"date","primary":false,"length":"","scale":""},{"name":"STTPRD","description":"统计周期","type":"varchar","primary":false,"length":"20","scale":""},{"name":"BGNTM","description":"开始时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"ENDTM","description":"结束时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"OBJTP","description":"业务对象类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"OBJCD","description":"业务对象编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"OBJNM","description":"业务对象名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICCD","description":"指标编码","type":"varchar","primary":false,"length":"100","scale":""},{"name":"METRICNM","description":"指标名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"METRICVAL","description":"指标值","type":"decimal","primary":false,"length":"18","scale":"4"},{"name":"METRICUNIT","description":"指标单位","type":"varchar","primary":false,"length":"50","scale":""},{"name":"TRDDIR","description":"趋势方向","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRNST","description":"预警状态","type":"varchar","primary":false,"length":"50","scale":""},{"name":"PAYLOADJSON","description":"展示数据内容，JSON格式","type":"text","primary":false,"length":"65535","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"SCENECD","target":"SCENECD"},{"source":"SCENENM","target":"SCENENM"},{"source":"MODCD","target":"MODCD"},{"source":"MODNM","target":"MODNM"},{"source":"CARDCD","target":"CARDCD"},{"source":"CARDNM","target":"CARDNM"},{"source":"DISPORD","target":"DISPORD"},{"source":"STATDT","target":"STATDT"},{"source":"STTPRD","target":"STTPRD"},{"source":"BGNTM","target":"BGNTM"},{"source":"ENDTM","target":"ENDTM"},{"source":"OBJTP","target":"OBJTP"},{"source":"OBJCD","target":"OBJCD"},{"source":"OBJNM","target":"OBJNM"},{"source":"METRICCD","target":"METRICCD"},{"source":"METRICNM","target":"METRICNM"},{"source":"METRICVAL","target":"METRICVAL"},{"source":"METRICUNIT","target":"METRICUNIT"},{"source":"TRDDIR","target":"TRDDIR"},{"source":"WRNST","target":"WRNST"},{"source":"PAYLOADJSON","target":"PAYLOADJSON"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"Z","target":"Z"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180643343962688","target":"180643366276672"}]}',''),
 (180640747487296,'吴同','','2026-08-04 17:56:21','2026-08-04 17:57:34','{"nodes":[{"id":"180640656332352","label":"表输入组件","type":"1","x":180,"y":150,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640673126976","label":"表输出组件","type":"91","x":310,"y":280,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640656332352","target":"180640673126976"}]}',''),
 (180640703308992,'吴同','','2026-08-04 17:55:38','2026-08-04 17:56:11','{"nodes":[{"id":"180640620423744","label":"表输入组件","type":"1","x":300,"y":150,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_SEC_B"},{"name":"字段","value":"ID, SECCD, SECNM, RVCD, RVNM, RSCD, RSNM, ADDVCD, ADDVNM, SECTP, WRZ, GRZ, DFLZ, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECTP","description":"断面类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"GRZ","description":"保证水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"DFLZ","description":"设计洪水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640628469312","label":"表输出组件","type":"91","x":610,"y":320,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_SEC_B"},{"name":"字段","value":"ID, SECCD, SECNM, RVCD, RVNM, RSCD, RSNM, ADDVCD, ADDVNM, SECTP, WRZ, GRZ, DFLZ, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECTP","description":"断面类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"GRZ","description":"保证水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"DFLZ","description":"设计洪水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"SECTP","target":"SECTP"},{"source":"WRZ","target":"WRZ"},{"source":"GRZ","target":"GRZ"},{"source":"DFLZ","target":"DFLZ"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640620423744","target":"180640628469312"}]}',''),
 (180640641835936,'吴同','','2026-08-04 17:54:39','2026-08-04 17:55:22','{"nodes":[{"id":"180640027438656","label":"表输入组件","type":"1","x":190,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"180640061972032","label":"转换组件","type":"31","x":300,"y":250,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"清洗降雨量为负数的值","field":"DRP","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180640116290112","label":"表输出组件","type":"91","x":530,"y":330,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"180640027438656","target":"180640061972032"},{"source":"180640061972032","target":"180640116290112"}]}',''),
 (180640631146592,'吴同','','2026-08-04 17:54:28','2026-08-04 17:55:55','{"nodes":[{"id":"180640530184768","label":"表输入组件","type":"1","x":440,"y":200,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_RCH_B"},{"name":"字段","value":"ID, RSCD, RSNM, RVCD, RVNM, RSTP, RSLEN, UPSECCD, UPSECNM, DNSECCD, DNSECNM, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSTP","description":"河段类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSLEN","description":"河段长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"UPSECCD","description":"上游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"UPSECNM","description":"上游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DNSECCD","description":"下游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"DNSECNM","description":"下游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640554352192","label":"表输出组件","type":"91","x":600,"y":320,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_RCH_B"},{"name":"字段","value":"ID, RSCD, RSNM, RVCD, RVNM, RSTP, RSLEN, UPSECCD, UPSECNM, DNSECCD, DNSECNM, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSTP","description":"河段类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSLEN","description":"河段长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"UPSECCD","description":"上游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"UPSECNM","description":"上游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"DNSECCD","description":"下游断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"DNSECNM","description":"下游断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSTP","target":"RSTP"},{"source":"RSLEN","target":"RSLEN"},{"source":"UPSECCD","target":"UPSECCD"},{"source":"UPSECNM","target":"UPSECNM"},{"source":"DNSECCD","target":"DNSECCD"},{"source":"DNSECNM","target":"DNSECNM"},{"source":"GEOMJSON","target":"GEOMJSON"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180640530184768","target":"180640554352192"}]}',''),
 (180640537941088,'吴同','','2026-08-04 17:52:57','2026-08-04 17:54:04','{"nodes":[{"id":"180640318418496","label":"表输入组件","type":"1","x":350,"y":270,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_RV_B"},{"name":"字段","value":"ID, RVCD, RVNM, BASCD, BSNM, ADDVCD, ADDVNM, RVTP, RVGRD, RVLEN, SRCLC, ESTLC, LGTD, LTTD, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"BASCD","description":"流域编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"BSNM","description":"流域名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVTP","description":"河流类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVGRD","description":"河流等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVLEN","description":"河流长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"SRCLC","description":"河流起点位置","type":"varchar","primary":false,"length":"300","scale":""},{"name":"ESTLC","description":"河流终点位置","type":"varchar","primary":false,"length":"300","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180640372906560","label":"表输出组件","type":"91","x":570,"y":370,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DIM"},{"name":"数据表","value":"DIM_RH_BO_ST_RV_B"},{"name":"字段","value":"ID, RVCD, RVNM, BASCD, BSNM, ADDVCD, ADDVNM, RVTP, RVGRD, RVLEN, SRCLC, ESTLC, LGTD, LTTD, GEOMJSON, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"bigint","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"BASCD","description":"流域编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"BSNM","description":"流域名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVTP","description":"河流类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVGRD","description":"河流等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVLEN","description":"河流长度，单位：千米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"SRCLC","description":"河流起点位置","type":"varchar","primary":false,"length":"300","scale":""},{"name":"ESTLC","description":"河流终点位置","type":"varchar","primary":false,"length":"300","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"GEOMJSON","description":"空间几何信息，GeoJSON格式","type":"varchar","primary":false,"length":"2147483643","scale":""},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"BASCD","target":"BASCD"},{"source":"BSNM","target":"BSNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"RVTP","target":"RVTP"},{"source":"RVGRD","target":"RVGRD"},{"source":"RVLEN","target":"RVLEN"},{"source":"SRCLC","target":"SRCLC"},{"source":"ESTLC","target":"ESTLC"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"GEOMJSON","target":"GEOMJSON"},{"source":"USFL","target":"USFL"}]}],"edges":[]}',''),
 (180640182706240,'吴同','','2026-08-04 17:47:10','2026-08-04 17:48:27','{"nodes":[{"id":"180639954870848","label":"表输入组件","type":"1","x":130,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_pptn_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}]},{"id":"180639964181056","label":"表输出组件","type":"91","x":220,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_RTW_ST_PPTN_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, DRP"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"DRP","description":"降雨量，单位：毫米","type":"decimal","primary":false,"length":"10","scale":"2"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"DRP","target":"DRP"}]}],"edges":[{"source":"180639954870848","target":"180639964181056"}]}',''),
 (180639963656000,'吴同','','2026-08-04 17:43:36','2026-08-04 17:44:49','{"nodes":[{"id":"180639866575424","label":"表输入组件","type":"1","x":340,"y":180,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_pp_stbprp_b"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180639896659520","label":"表输出组件","type":"91","x":510,"y":270,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_PP_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[{"source":"180639866575424","target":"180639896659520"}]}',''),
 (180638766873344,'吴同','','2026-08-04 17:24:07','2026-08-04 17:25:22','{"nodes":[{"id":"180638664062528","label":"表输入组件","type":"1","x":340,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_q_his_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Q"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Q","description":"流量，单位：立方米每秒","type":"decimal","primary":false,"length":"14","scale":"3"}]},{"id":"180638682468928","label":"表输出组件","type":"91","x":480,"y":220,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_HIST_ST_Q_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Q"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Q","description":"流量，单位：立方米每秒","type":"decimal","primary":false,"length":"14","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Q","target":"Q"}]}],"edges":[{"source":"180638664062528","target":"180638682468928"}]}',''),
 (180636244087904,'吴同','','2026-08-04 16:43:03','2026-08-04 16:44:26','{"nodes":[{"id":"180634940345920","label":"表输入组件","type":"1","x":280,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634974291520","label":"转换组件","type":"31","x":400,"y":180,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"水位范围清洗","field":"Z","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180635019662912","label":"表输出组件","type":"91","x":490,"y":260,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180634940345920","target":"180634974291520"},{"source":"180634974291520","target":"180635019662912"}]}',''),
 (180635913626528,'吴同','','2026-08-04 16:37:41','2026-08-04 16:39:07','{"nodes":[{"id":"180634940345920","label":"表输入组件","type":"1","x":280,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634974291520","label":"转换组件","type":"31","x":400,"y":180,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"水位范围清洗","field":"Z","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180635019662912","label":"表输出组件","type":"91","x":490,"y":260,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[]}',''),
 (180635696217920,'吴同','','2026-08-04 16:34:08','2026-08-04 16:35:27','{"nodes":[{"id":"180634940345920","label":"表输入组件","type":"1","x":280,"y":110,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634974291520","label":"转换组件","type":"31","x":400,"y":180,"publicConfig":[{"name":"节点名称","value":"转换组件"},{"name":"组件类型","value":"转换组件"},{"name":"字段","value":""},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"}],"publicRules":[{"name":"水位范围清洗","field":"Z","rule":"数值边界调整","description":"","dimension":"异常值修正类"}]},{"id":"180635019662912","label":"表输出组件","type":"91","x":490,"y":260,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"DWD"},{"name":"数据表","value":"DWD_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[]}',''),
 (180634892677312,'吴同','','2026-08-04 16:21:04','2026-08-04 16:22:24','{"nodes":[{"id":"180634814063168","label":"表输入组件","type":"1","x":220,"y":130,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"st_z_his_r"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}]},{"id":"180634825788992","label":"表输出组件","type":"91","x":440,"y":190,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_MON_ST_Z_HIS_R"},{"name":"字段","value":"ID, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, STCD, STNM, TM, Z, WRZ, ZRR"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"TM","description":"监测时间","type":"datetime","primary":false,"length":"","scale":""},{"name":"Z","description":"水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"WRZ","description":"警戒水位，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"ZRR","description":"水位上涨速率，单位：米每小时","type":"decimal","primary":false,"length":"10","scale":"3"}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"TM","target":"TM"},{"source":"Z","target":"Z"},{"source":"WRZ","target":"WRZ"},{"source":"ZRR","target":"ZRR"}]}],"edges":[{"source":"180634814063168","target":"180634825788992"}]}',''),
 (180634455840000,'吴同','','2026-08-04 16:13:57','2026-08-04 16:15:22','{"nodes":[{"id":"180629980610112","label":"表输入组件","type":"1","x":230,"y":230,"publicConfig":[{"name":"节点名称","value":"表输入组件"},{"name":"组件类型","value":"表输入组件"},{"name":"数据源类型","value":"MySql"},{"name":"数据库","value":"RIVER_WATER"},{"name":"数据表","value":"ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"读取方式","value":"全量"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}]},{"id":"180630021398080","label":"表输出组件","type":"91","x":380,"y":250,"publicConfig":[{"name":"节点名称","value":"表输出组件"},{"name":"组件类型","value":"表输出组件"},{"name":"数据源类型","value":"Doris"},{"name":"数据库","value":"ODS"},{"name":"数据表","value":"ODS_RH_BO_ST_Z_STBPRP_B"},{"name":"字段","value":"ID, STCD, STNM, RVCD, RVNM, RSCD, RSNM, SECCD, SECNM, ADDVCD, ADDVNM, STTP, STLVL, MNTITEM, COMMODE, LGTD, LTTD, EL, USFL"},{"name":"过滤条件","value":""},{"name":"批次大小","value":"1024"},{"name":"写入方式","value":"追加"}],"publicFields":[{"name":"ID","description":"记录ID","type":"bigint","primary":false,"length":"19","scale":""},{"name":"STCD","description":"测站编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STNM","description":"测站名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RVCD","description":"河流编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RVNM","description":"河流名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"RSCD","description":"河段编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"RSNM","description":"河段名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"SECCD","description":"断面编码","type":"varchar","primary":false,"length":"50","scale":""},{"name":"SECNM","description":"断面名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"ADDVCD","description":"行政区划编码","type":"varchar","primary":false,"length":"20","scale":""},{"name":"ADDVNM","description":"行政区划名称","type":"varchar","primary":false,"length":"200","scale":""},{"name":"STTP","description":"测站类型","type":"varchar","primary":false,"length":"50","scale":""},{"name":"STLVL","description":"测站等级","type":"varchar","primary":false,"length":"50","scale":""},{"name":"MNTITEM","description":"监测项目","type":"varchar","primary":false,"length":"500","scale":""},{"name":"COMMODE","description":"通信方式","type":"varchar","primary":false,"length":"100","scale":""},{"name":"LGTD","description":"经度","type":"decimal","primary":false,"length":"11","scale":"8"},{"name":"LTTD","description":"纬度","type":"decimal","primary":false,"length":"10","scale":"8"},{"name":"EL","description":"高程，单位：米","type":"decimal","primary":false,"length":"10","scale":"3"},{"name":"USFL","description":"启用标识","type":"varchar","primary":false,"length":"10","scale":""}],"publicMappings":[{"source":"ID","target":"ID"},{"source":"STCD","target":"STCD"},{"source":"STNM","target":"STNM"},{"source":"RVCD","target":"RVCD"},{"source":"RVNM","target":"RVNM"},{"source":"RSCD","target":"RSCD"},{"source":"RSNM","target":"RSNM"},{"source":"SECCD","target":"SECCD"},{"source":"SECNM","target":"SECNM"},{"source":"ADDVCD","target":"ADDVCD"},{"source":"ADDVNM","target":"ADDVNM"},{"source":"STTP","target":"STTP"},{"source":"STLVL","target":"STLVL"},{"source":"MNTITEM","target":"MNTITEM"},{"source":"COMMODE","target":"COMMODE"},{"source":"LGTD","target":"LGTD"},{"source":"LTTD","target":"LTTD"},{"source":"EL","target":"EL"},{"source":"USFL","target":"USFL"}]}],"edges":[]}',''))
UPDATE dh_dataforge_integration_record r
SET creator_name=s.creator_name,description=s.description,source_create_time=s.create_time::timestamp,
    source_update_time=s.update_time::timestamp,flow_json=s.flow_json::jsonb,log_content=s.log_content,
    owner_name=r.executor_name
FROM source_details s WHERE r.id=s.id AND r.source_create_time IS NULL;

-- 源详情尚未单独采集的实例沿用源运行时间及任务图；后续受控导入可精确补齐。
UPDATE dh_dataforge_integration_record r
SET owner_name=t.owner_name,creator_name=t.creator_name,
    source_create_time=coalesce(r.start_time,r.create_time),source_update_time=coalesce(r.end_time,r.start_time,r.update_time),
    flow_json=t.flow_json,description='',log_content=''
FROM dh_dataforge_integration_task t
WHERE t.id=r.task_id AND r.source_create_time IS NULL AND r.creator='migration:0215';

LOCK TABLE system_menu,system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE directory_id bigint; page_id bigint; new_id bigint; high_id bigint; parent bigint; after_sort integer; entry record;
BEGIN
 SELECT parent_id,sort INTO parent,after_sort FROM system_menu WHERE id=5987 AND path='integrate' AND deleted=0;
 IF parent IS NULL THEN RAISE EXCEPTION '0217 数据接入父菜单缺失'; END IF;
 SELECT id INTO directory_id FROM system_menu WHERE parent_id=parent AND path='operations' AND deleted=0 LIMIT 1;
 IF directory_id IS NULL THEN
  UPDATE system_menu SET sort=sort+1 WHERE parent_id=parent AND sort>after_sort AND deleted=0;
  SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO directory_id FROM system_menu;
  INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,status,visible,keep_alive,always_show,creator,updater,deleted)
  VALUES(directory_id,'运维管理','',1,after_sort+1,parent,'operations','carbon:operations-field',0,true,true,true,'migration:0217','migration:0217',0);
 END IF;
 FOR entry IN SELECT * FROM (VALUES
  ('数据集成实例','integration','dataforge/ops/integration/index','DataforgeOpsIntegration',1),
  ('数据开发实例','development','dataforge/ops/development/index','DataforgeOpsDevelopment',2)
 ) pages(label,path,component,component_name,sort) LOOP
  SELECT id INTO page_id FROM system_menu WHERE component=entry.component AND deleted=0 LIMIT 1;
  IF page_id IS NULL THEN
   SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO page_id FROM system_menu;
   INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
   VALUES(page_id,entry.label,'dataforge:ops-instance:query',2,entry.sort,directory_id,entry.path,'carbon:task-approved',entry.component,entry.component_name,0,true,true,true,'migration:0217','migration:0217',0);
  END IF;
  IF NOT EXISTS(SELECT 1 FROM system_menu WHERE parent_id=page_id AND permission='dataforge:ops-instance:export' AND deleted=0) THEN
   SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO new_id FROM system_menu;
   INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
   VALUES(new_id,'下载日志','dataforge:ops-instance:export',3,1,page_id,'',0,true,true,true,'migration:0217','migration:0217',0);
  END IF;
 END LOOP;
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
 PERFORM setval('system_menu_seq',high_id); PERFORM setval('system_menu_id_seq',high_id);
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater,deleted)
 SELECT high_id+row_number() OVER(ORDER BY r.id,r.tenant_id,m.id),r.id,m.id,r.tenant_id,'migration:0217','migration:0217',0
 FROM system_role r CROSS JOIN system_menu m
 WHERE r.code='super_admin' AND r.deleted=0 AND m.deleted=0 AND
 (m.id=directory_id OR m.parent_id=directory_id OR m.permission='dataforge:ops-instance:export')
 AND NOT EXISTS(SELECT 1 FROM system_role_menu old WHERE old.role_id=r.id AND old.tenant_id=r.tenant_id AND old.menu_id=m.id AND old.deleted=0);
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 PERFORM setval('system_role_menu_seq',high_id);
END $menus$;
COMMIT;
