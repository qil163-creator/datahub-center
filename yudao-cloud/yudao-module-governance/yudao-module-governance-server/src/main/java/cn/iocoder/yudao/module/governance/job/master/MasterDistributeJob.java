package cn.iocoder.yudao.module.governance.job.master;

import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDistributeExecutor;
import cn.iocoder.yudao.module.governance.service.master.MasterDistributeRunCoordinator;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 主数据派发周期任务调度 Job
 *
 * 建议在 XXL-Job 控制台按每分钟执行配置：扫描已发布的周期派发任务，
 * 全局有界发现，逐条处理；持久意图与排期同事务，含手动等待记录恢复
 *
 * @author Li
 */
@Component
@Slf4j
public class MasterDistributeJob {

    @Resource
    private MasterDistributeMapper distributeMapper;

    @Resource
    private MasterDistributeExecutor distributeExecutor;

    @Resource
    private MasterDistributeLogMapper logMapper;

    @Resource
    private MasterDistributeRunCoordinator coordinator;

    /**
     * 执行主数据分发任务处理流程，并维护相应运行结果。
     */
    @XxlJob("masterDistributeJob")
    public void execute() {
        int failed = 0;
        for (var run : logMapper.recoverable()) {
            try {

                distributeExecutor.dispatch(run);
            } catch (Exception ex) {
                failed++;
                log.warn("派发恢复失败 runId={} type={}", run.getId(), ex.getClass().getSimpleName());
            }
        }
        for (var task : distributeMapper.dueCandidates()) {
            try {

                distributeExecutor.dispatch(coordinator.scheduled(task));
            } catch (Exception ex) {
                failed++;
                log.warn("派发受理失败 taskId={} type={}", task.getId(), ex.getClass().getSimpleName());
            }
        }
        if (failed > 0) {
            XxlJobHelper.handleFail("主数据派发扫描失败条数=" + failed);
        } else {
            XxlJobHelper.handleSuccess("主数据派发恢复及排期扫描完成");
        }
    }

}
