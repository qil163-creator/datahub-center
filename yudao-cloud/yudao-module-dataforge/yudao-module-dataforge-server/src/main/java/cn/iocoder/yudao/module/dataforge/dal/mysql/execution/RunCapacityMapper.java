package cn.iocoder.yudao.module.dataforge.dal.mysql.execution;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** 持久化 DataForge 运行的共享准入策略，不是本地工作线程池的容量限制。 */
public interface RunCapacityMapper {

    /** 仅提供扫描提示；以下事务中的抢占操作才是权威依据。 */
    // 仅展开一次活跃槽位，不展开每个排队中的资源组合。作用域未知的历史运行
    // 占用每个数据源的容量，包括两个领域中已软删除或处于 UNKNOWN 状态的运行。
    String RUN_CAPACITY_CTE = "WITH active_slots AS MATERIALIZED ("
            + "SELECT resource_ids FROM dh_dataforge_etl_record WHERE occupies_slot UNION ALL "
            + "SELECT resource_ids FROM dh_dataforge_access_task WHERE occupies_slot), "
            + "occupied AS (SELECT count(*) AS slots,"
            + "count(*) FILTER (WHERE resource_ids IS NULL) AS unknown_slots FROM active_slots ), "
            + "source_slots AS (SELECT s.resource_id,count(*) AS slots FROM active_slots a "
            + "CROSS JOIN LATERAL jsonb_array_elements(a.resource_ids) s(resource_id) GROUP BY s.resource_id), "
            + "saturated_sources AS (SELECT s.resource_id FROM source_slots s "
            + "JOIN occupied o ON 1=1 WHERE s.slots+o.unknown_slots>=#{dataSourceCapacity}) ";

    /** 使用尝试加锁，避免在另一任务或运行记录已被锁定时等待。 */

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended('dataforge:run-capacity', 0))")
    boolean tryLockPlatformCapacity();

    /** 未知、过期及已软删除的运行保留其槽位，直到显式完成对账。 */

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT (SELECT count(*) FROM dh_dataforge_etl_record WHERE occupies_slot)"
            + " + (SELECT count(*) FROM dh_dataforge_access_task WHERE occupies_slot)")
    long countPlatformSlots();

    /** NULL 是旧版本/未知资源，占用任一数据源预算，不能按无资源运行放行。 */

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT (SELECT count(*) FROM dh_dataforge_etl_record WHERE occupies_slot"
            + " AND (resource_ids IS NULL OR resource_ids @> CAST(#{resourceJson} AS jsonb)))"
            + " + (SELECT count(*) FROM dh_dataforge_access_task WHERE occupies_slot"
            + " AND (resource_ids IS NULL OR resource_ids @> CAST(#{resourceJson} AS jsonb)))")
    long countDataSourceSlots(@Param("resourceJson") String resourceJson);

    /** 调用方必须在同一个 READ COMMITTED 事务内获取并持久化该槽位。 */
    default boolean hasPlatformCapacity(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("平台执行容量必须为正数");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run admission requires an active transaction");
        }
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != java.sql.Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Run admission requires READ COMMITTED isolation");
        }
        return tryLockPlatformCapacity() && countPlatformSlots() < limit;
    }

    /** 在同一平台事务级锁下同时检查总容量和每个实际数据源；领取与占位写入必须同事务提交。 */
    default boolean hasCapacity(int platformLimit, int dataSourceLimit, List<Long> resourceIds) {
        if (dataSourceLimit < 1 || resourceIds == null) {
            throw new IllegalArgumentException("Invalid data source capacity");
        }
        if (!hasPlatformCapacity(platformLimit)) {
            return false;
        }
        for (Long resourceId : resourceIds) {
            if (resourceId == null || resourceId <= 0) {
                throw new IllegalArgumentException("Invalid data source identity");
            }
            if (countDataSourceSlots("[" + resourceId + "]") >= dataSourceLimit) {
                return false;
            }
        }
        return true;
    }
}
