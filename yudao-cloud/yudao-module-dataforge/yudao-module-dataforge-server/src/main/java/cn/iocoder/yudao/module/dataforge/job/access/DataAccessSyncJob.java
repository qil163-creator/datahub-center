package cn.iocoder.yudao.module.dataforge.job.access;

import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.access.AccessRunCoordinator;
import cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 全局发现有数量上限；协调器在每次操作前校验业务记录。 */
@Component
@Slf4j
public class DataAccessSyncJob {

    @Resource
    private AccessRunCoordinator coordinator;

    @Resource
    private RunExecutionProperties properties;

    @Resource
    private RunMetrics metrics;

    /**
     * 执行数据同步任务同步任务处理。
     */
    @XxlJob("dataAccessSyncJob")
    public void execute() {
        int failures = 0;
        int limit = properties.getScanBatch();
        Runnable[] phases = {
                () -> coordinator.recoverExpired(limit),
                () -> coordinator.reconcileDue(limit),
                () -> coordinator.scanScheduled(limit),
                () -> coordinator.dispatchWaiting(limit)
        };
        for (int phase = 0; phase < phases.length; phase++) {
            try {
                phases[phase].run();
            } catch (RuntimeException failure) {
                failures++;
                metrics.scanFailed(RunMetrics.Domain.ACCESS, RunMetrics.Phase.values()[phase], 1);
                log.warn("[execute][ACCESS phase {} failed: {}]", phase, failure.getClass().getSimpleName());
            }
        }
        if (failures > 0) {
            XxlJobHelper.handleFail("ACCESS scan phases failed: " + failures);
        } else {
            XxlJobHelper.handleSuccess("ACCESS bounded scheduling and recovery completed");
        }
    }
}
