-- 0164 首次执行断言；旧周期不自动激活外部登记，历史 JSON 不变，原 Cron 原文转存只读备份。
SET search_path TO "datahub-center", pg_catalog;
DO $assert_external_schedule$
BEGIN
    IF EXISTS (SELECT 1 FROM dh_dataforge_dev_task WHERE scheduler <> 'DOLPHIN_SCHEDULER'
        OR schedule_status <> 0 OR next_run_time IS NOT NULL OR cron_expression IS NOT NULL)
       OR EXISTS (SELECT 1 FROM fixture_0164_before previous JOIN dh_dataforge_dev_task task ON task.id = previous.id
           WHERE previous.kind = 'task' AND (task.legacy_cron_expression IS DISTINCT FROM previous.row_value ->> 'cron_expression'
               OR coalesce(to_jsonb(task.current_version_id), 'null'::jsonb) IS DISTINCT FROM previous.row_value -> 'current_version_id'
               OR to_jsonb(task.revision) IS DISTINCT FROM previous.row_value -> 'revision')) THEN
        RAISE EXCEPTION '0164 旧周期未停用、旧 Cron 未原样备份或当前版本和修订号被重写';
    END IF;
    IF (SELECT count(*) FROM dh_dataforge_dev_schedule) <> 0 THEN
        RAISE EXCEPTION '0164 SQL 不得推断可信身份并制造外部调度绑定';
    END IF;
    IF EXISTS (SELECT 1 FROM fixture_0164_before previous LEFT JOIN dh_dataforge_dev_task_version historical_version ON historical_version.id = previous.id
        WHERE previous.kind = 'version' AND previous.row_value IS DISTINCT FROM to_jsonb(historical_version))
       OR EXISTS (SELECT 1 FROM fixture_0164_before previous LEFT JOIN dh_dataforge_dev_record record ON record.id = previous.id
           WHERE previous.kind = 'record' AND previous.row_value IS DISTINCT FROM to_jsonb(record)) THEN
        RAISE EXCEPTION '0164 历史版本或运行 JSON 原文变化';
    END IF;
END $assert_external_schedule$;
