-- Migration: 0261_dataforge_clear-local-development-history
-- Purpose: 按用户要求清空指定本地数据集的离线、实时开发任务和四类历史实例；仅逻辑删除备份清单中的固定编号。
-- Depends on: 0260_dataforge_analytical-category-label.sql；已核实 datahub-center 当前结构，清理前须备份清单对应记录并停止等待运行。
-- Target: 仅用户指定的已有开发库；必须在同一会话显式 SET datahub.cleanup_development_history = '20261102-231657'；其他环境默认跳过。
-- Repeatable: yes；固定编号、逻辑删除且不重置序列，重复执行不会清理新建记录。
-- Rollback: 事务内失败自动回滚；提交后可用本次私密 JSON 备份按原编号恢复。恢复时须保持调度关闭和运行终态，不得重新入队旧等待记录；禁止无备份执行或重置序列。
-- 仅清理页面任务、版本与实例，不删除类目、目录、数据连接、作业定义、数据接入定义或外部业务表。
BEGIN;
SET LOCAL search_path TO "datahub-center", public;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $cleanup$
BEGIN
    IF current_setting('datahub.cleanup_development_history', true) IS DISTINCT FROM '20261102-231657' THEN
        RAISE NOTICE '0261 未显式启用本地数据清理，已跳过';
        RETURN;
    END IF;
    IF current_database() <> 'datahub' THEN
        RAISE EXCEPTION '0261 仅允许对已核实的 datahub 开发库执行';
    END IF;
    LOCK TABLE dh_dataforge_job_node_run, dh_dataforge_job_record, dh_dataforge_access_task_detail, dh_dataforge_access_task, dh_dataforge_ops_aux_snapshot, dh_dataforge_ops_development_snapshot, dh_dataforge_dev_record, dh_dataforge_dev_task_version, dh_dataforge_dev_task, dh_dataforge_integration_record, dh_dataforge_integration_task, dh_dataforge_job, dh_dataforge_access, dh_dataforge_dev_schedule IN SHARE ROW EXCLUSIVE MODE;
    -- 防止仍受后台维护或引擎管理的运行被直接隐藏。
    IF EXISTS (SELECT 1 FROM dh_dataforge_integration_task WHERE deleted=0 AND schedule_status=1)
        OR EXISTS (SELECT 1 FROM dh_dataforge_dev_task WHERE deleted=0 AND schedule_status=1)
        OR EXISTS (SELECT 1 FROM dh_dataforge_dev_schedule WHERE deleted=0 AND (desired_enabled OR synced_enabled))
        OR EXISTS (SELECT 1 FROM dh_dataforge_job WHERE deleted=0 AND schedule_status=1)
        OR EXISTS (SELECT 1 FROM dh_dataforge_access WHERE deleted=0 AND task_type IN (2,3) AND next_run_time IS NOT NULL)
        OR EXISTS (SELECT 1 FROM dh_dataforge_integration_record WHERE deleted=0 AND ds_project_code IS NOT NULL AND status IN ('RUNNING','WAITING'))
        OR EXISTS (SELECT 1 FROM dh_dataforge_dev_record WHERE deleted=0 AND status IN ('WAITING','SUBMITTING','RUNNING','UNKNOWN','CANCEL_REQUESTED'))
        OR EXISTS (SELECT 1 FROM dh_dataforge_job_record WHERE deleted=0 AND status IN ('WAITING','SUBMITTING','RUNNING','UNKNOWN','CANCEL_REQUESTED'))
        OR EXISTS (SELECT 1 FROM dh_dataforge_access_task r JOIN dh_dataforge_access t ON t.id=r.data_access_id
                   WHERE r.deleted=0 AND t.deleted=0 AND t.task_type IN (2,3) AND r.run_status IN (1,2,5)) THEN
        RAISE EXCEPTION '0261 仍有启用调度或未终结的真实运行，请先停止并核实后重试';
    END IF;
    -- 清理本次备份中的 4 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_job_node_run SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (1,2,3,4);
    -- 清理本次备份中的 5 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_job_record SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (1,2,3,4,5);
    -- 清理本次备份中的 1 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_access_task_detail SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (1);
    -- 清理本次备份中的 2 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_access_task SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (1,2);
    -- 清理本次备份中的 3 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_ops_aux_snapshot SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (1,2,3);
    -- 清理本次备份中的 24 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_ops_development_snapshot SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (601,604,608,613,618,626,634,655,657,658,661,671,699,700,863,864,865,866,867,868,869,1232,1237,1239);
    -- 清理本次备份中的 11 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_dev_record SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (1,2,3,4,5,6,7,8,9,10,11);
    -- 清理本次备份中的 20 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_dev_task_version SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20);
    -- 清理本次备份中的 14 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_dev_task SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP, status=0, schedule_status=0, next_run_time=NULL
    WHERE deleted=0 AND id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14);
    -- 清理本次备份中的 67 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_integration_record SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP
    WHERE deleted=0 AND id IN (1267,1268,1269,1271,180634455840000,180634892677312,180635696217920,180635913626528,180636244087904,180638766873344,180639963656000,180640182706240,180640537941088,180640631146592,180640641835936,180640703308992,180640747487296,180643432345632,180643589517120,180643952638912,180653379000928,180653379012224,180653525968992,180653625275712,180655924056416,180732146972000,180732152368224,180732465720672,180732707736000,180732715957504,180733273618976,180734797282592,180734807399776,180734876660416,180734923026784,180734941784448,180828134271424,180828138342976,180828143077664,180828144655136,180828152475584,180828163325472,180828176013376,180828185599040,180828188917568,180828190953728,180828225404608,180828227551104,180828231566624,180828281719584,180828790549248,180828802092064,180828817622144,180828841586528,180828987542560,180829090983840,180829707866400,180829751976928,180829751977008,180829751977009,180829751977010,180829751977014,180829751977015,180829751977016,180829751977017,180829751977018,180829751977019);
    -- 清理本次备份中的 25 条记录，保留原编号和审计内容。
    UPDATE dh_dataforge_integration_task SET deleted=1, updater='cleanup:0261', update_time=LOCALTIMESTAMP, status=0, schedule_status=0
    WHERE deleted=0 AND id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,48,49,50,1001,1002,1003,1004);
END $cleanup$;
COMMIT;
