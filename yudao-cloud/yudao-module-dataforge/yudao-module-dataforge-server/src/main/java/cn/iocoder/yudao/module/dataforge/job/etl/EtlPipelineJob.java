package cn.iocoder.yudao.module.dataforge.job.etl;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineExecutor;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics;

import static cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ETL;
import static cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Phase.*;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 全局控制面有界扫描，逐项处理业务记录；受理与时间推进由领域事务负责。 */
@Component
@Slf4j
public class EtlPipelineJob {

    @Resource
    private EtlPipelineMapper pipelineMapper;

    @Resource
    private EtlPipelineService pipelineService;

    @Resource
    private EtlPipelineExecutor executor;

    @Resource
    private RunExecutionProperties executionProperties;

    @Resource
    private RunMetrics metrics;

    /**
     * 执行ETL 流程任务处理。
     */
    @XxlJob("etlPipelineJob")

    public void execute() {
        int failures = scanPhase(RECOVER, executor::recoverExpired);
        failures += scanPhase(RECONCILE, executor::reconcileQuality);
        List<EtlPipelineDO> dueList = List.of();
        try {
            dueList = pipelineMapper.selectDueList(executionProperties.getScanBatch(),
                    executionProperties.getPerTaskPending());
        } catch (RuntimeException failure) {
            failures++;
            log.warn("ETL 到期扫描失败 phase=scheduled causeType={}", failure.getClass().getSimpleName());
            metrics.scanFailed(ETL, SCHEDULED, 1);
        }
        AtomicInteger accepted = new AtomicInteger();
        for (EtlPipelineDO initial : dueList) {
            try {
                {
                    EtlPipelineDO observed = initial;
                    for (int i = 0; i < executionProperties.getPerTaskCatchUp(); i++) {
                        if (observed == null || observed.getNextRunTime() == null) {
                            break;
                        }
                        var receipt = pipelineService.runPipelineScheduled(observed.getId(),
                                observed.getScheduleGeneration(), observed.getNextRunTime());
                        if (receipt == null || receipt.replayed()) {
                            break;
                        }
                        accepted.incrementAndGet();
                        observed = pipelineMapper.selectById(observed.getId());
                    }
                }
                ;
            } catch (RuntimeException failure) {
                failures++;
                metrics.scanFailed(ETL, SCHEDULED, 1);
                log.warn("ETL 到期受理失败，未受理计划仍可重试 phase=scheduled pipelineId={} causeType={}",
                        initial.getId(), failure.getClass().getSimpleName());
            }
        }
        failures += scanPhase(DISPATCH, executor::dispatchWaiting);
        String result = String.format("扫描 %s 个到期流程，持久受理 %s 次；失败项或阶段 %s", dueList.size(),
                accepted.get(), failures);
        if (failures > 0) {
            XxlJobHelper.handleFail(result);
        } else {
            XxlJobHelper.handleSuccess(result);
        }
    }

    /**
     * 获取ETL 流程任务的扫描阶段。
     *
     * @param phase 阶段
     * @param scan 扫描
     * @return 扫描阶段数值
     */
    private int scanPhase(RunMetrics.Phase phase, Runnable scan) {
        try {
            scan.run();
            return 0;
        } catch (RuntimeException failure) {
            log.warn("ETL 扫描阶段失败 phase={} causeType={}", phase, failure.getClass().getSimpleName());
            metrics.scanFailed(ETL, phase, 1);
            return 1;
        }
    }
}
