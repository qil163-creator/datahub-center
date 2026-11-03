package cn.iocoder.yudao.module.governance.job.statictask;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskMapper;
import cn.iocoder.yudao.module.governance.service.statictask.SafetyStaticTaskService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 静态脱敏任务定时调度 Job
 *
 * 建议在 XXL-Job 控制台按每分钟执行配置：
 * 扫描上线且配置了 cron 的任务，到期触发执行；执行器内部有防同任务并发保护。
 *
 * @author Li
 */
@Component
@Slf4j
public class SafetyStaticTaskJob {

    @Resource
    private SafetyStaticTaskMapper taskMapper;

    @Resource
    private SafetyStaticTaskService taskService;

    /**
     * 执行静态脱敏任务处理流程，并维护相应运行结果。
     */
    @XxlJob("governanceSafetyStaticTaskJob")
    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes(1);
        List<SafetyStaticTaskDO> tasks = taskMapper.selectTimerOnlineList();
        int triggered = 0;
        for (SafetyStaticTaskDO task : tasks) {
            if (!isDue(task.getCronExpression(), windowStart, now)) {
                continue;
            }
            try {
                // 全局扫描发现工作项，由领域服务处理互斥和执行状态。
                taskService.executeTaskBySchedule(task.getId());
                triggered++;
            } catch (Exception ex) {
                log.warn("[execute][静态脱敏任务({}) 调度触发失败：{}]", task.getId(), ex.getMessage());
            }
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个定时任务，触发 %s 个", tasks.size(), triggered));
    }

    /** cron 的下一次触发时间落在 (windowStart, now] 区间内则视为到期 */
    private boolean isDue(String cron, LocalDateTime windowStart, LocalDateTime now) {
        if (StrUtil.isBlank(cron) || !CronExpression.isValidExpression(cron)) {
            return false;
        }
        LocalDateTime next = CronExpression.parse(cron).next(windowStart);
        return next != null && !next.isAfter(now);
    }

}
