package cn.iocoder.yudao.module.dataforge.dal.mysql.execution;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunMetricsSnapshot;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/**
 * 只读内部控制面汇总，在平台范围内；不返回标识/配置，不用于管理端业务查询。
 * 每条查询最多等待五秒；保留已软删除但仍占位的异常行，避免监控隐藏阻塞。
 */
@Mapper
public interface RunMetricsMapper {

    String AGGREGATE = """
            ), state AS (
              SELECT count(*) FILTER (WHERE queued) AS queued,
                COALESCE(GREATEST(EXTRACT(EPOCH FROM (statement_timestamp()::timestamp
                  - min(create_time) FILTER (WHERE queued))),0),0) AS queue_oldest_age,
                count(*) FILTER (WHERE occupies_slot) AS slots,
                count(*) FILTER (WHERE unknown) AS unknown,
                COALESCE(GREATEST(EXTRACT(EPOCH FROM (statement_timestamp()::timestamp
                  - min(create_time) FILTER (WHERE unknown))),0),0) AS unknown_oldest_run_age,
                count(*) FILTER (WHERE needs_review) AS review,
                count(*) FILTER (WHERE stop_pending) AS stop_pending,
                COALESCE(GREATEST(EXTRACT(EPOCH FROM (statement_timestamp()::timestamp
                  - min(stop_requested_at) FILTER (WHERE stop_pending))),0),0) AS stop_oldest_age,
                count(*) FILTER (WHERE occupies_slot AND run_status IN (1,2)
                  AND lease_until <= statement_timestamp()) AS expired,
                count(*) FILTER (WHERE quality_pending) AS quality_pending
              FROM runs
            ) SELECT state.*, due.* FROM state CROSS JOIN due
            """;

    /**
     * 查询运行指标的ETL。
     *
     * @return 运行指标快照
     */
    @Select("""
            WITH runs AS (
              SELECT create_time, stop_requested_at, lease_until, occupies_slot, run_status,
                run_status=1 AND NOT occupies_slot AND NOT stop_requested AND deleted=0 AS queued,
                run_status=6 AS unknown,
                review_required OR (occupies_slot AND quality_status='UNKNOWN') AS needs_review,
                stop_requested AND run_status IN (1,2,6) AS stop_pending,
                occupies_slot AND run_status=3 AND quality_status IN ('PENDING','UNKNOWN') AS quality_pending
              FROM dh_dataforge_etl_record
              WHERE occupies_slot OR (deleted=0 AND (run_status IN (1,6) OR review_required))
            ), due AS (
              SELECT count(*) AS due,
                COALESCE(GREATEST(EXTRACT(EPOCH FROM (statement_timestamp()::timestamp-min(next_run_time))),0),0) AS overdue_age
              FROM dh_dataforge_etl_pipeline
              WHERE deleted=0 AND status=1 AND schedule_status=1 AND task_type=2
                AND next_run_time <= statement_timestamp()
            """ + AGGREGATE)
    @Options(timeout = 5, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @InterceptorIgnore(dataPermission = "true")
    RunMetricsSnapshot selectEtl();

    /**
     * 查询运行指标的访问。
     *
     * @return 运行指标快照
     */
    @Select("""
            WITH runs AS (
              SELECT create_time, stop_requested_at, lease_until, occupies_slot, run_status,
                run_status=1 AND NOT occupies_slot AND NOT stop_requested AND deleted=0 AS queued,
                run_status=5 AS unknown, review_required AS needs_review,
                stop_requested AND run_status IN (1,2,5) AS stop_pending,
                false AS quality_pending
              FROM dh_dataforge_access_task
              WHERE occupies_slot OR (deleted=0 AND (run_status IN (1,5) OR review_required))
            ), due AS (
              SELECT count(*) AS due,
                COALESCE(GREATEST(EXTRACT(EPOCH FROM (statement_timestamp()::timestamp-min(next_run_time))),0),0) AS overdue_age
              FROM dh_dataforge_access
              WHERE deleted=0 AND status=1 AND task_type=3 AND next_run_time <= statement_timestamp()
            """ + AGGREGATE)
    @Options(timeout = 5, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @InterceptorIgnore(dataPermission = "true")
    RunMetricsSnapshot selectAccess();
}
