package cn.iocoder.yudao.module.governance.job.metadata;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectTaskMapper;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectExecutor;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 元数据采集定时调度 Job
 *
 * 建议在 XXL-Job 控制台按每分钟执行配置：扫描已上线且调度开启的采集任务，
 * 到达下次执行时间的先推进 next_run_time（CAS 防重复触发），再提交执行；
 * 调度选型见设计文档 3.3：默认 XXL-Job，DolphinScheduler 为预留适配位
 *
 * @author Li
 */
@Component
@Slf4j
public class MetaCollectTaskJob {

    @Resource
    private MetaCollectTaskMapper taskMapper;

    @Resource
    private MetaCollectExecutor collectExecutor;

    /**
     * 执行元数据采集任务处理流程，并维护相应运行结果。
     */
    @XxlJob("metaCollectTaskJob")
    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        collectExecutor.recoverAndDispatch();
        List<MetaCollectTaskDO> dueList = taskMapper.selectDueList(now);
        int triggered = 0;
        for (MetaCollectTaskDO task : dueList) {
            // 推进 next_run_time 与持久入队在同一事务内提交，避免崩溃后漏触发
            LocalDateTime next = calculateNext(task.getCron(), now);
            try {
                if (collectExecutor.submitScheduled(task.getId(), task.getNextRunTime(), next)) {
                    triggered++;
                } else {
                    log.info("[execute][采集任务({}) 上一次执行尚未结束，本次跳过]", task.getId());
                }
            } catch (RuntimeException ex) {
                log.warn("[execute][采集任务({}) 提交失败，其余任务继续]", task.getId());
            }
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个到期采集任务，触发执行 %s 个",
                dueList.size(), triggered));
    }

    /**
     * 计算下一个。
     */
    private LocalDateTime calculateNext(String cron, LocalDateTime now) {
        if (StrUtil.isBlank(cron) || !CronExpression.isValidExpression(cron)) {
            return null;
        }
        return CronExpression.parse(cron).next(now);
    }

}
