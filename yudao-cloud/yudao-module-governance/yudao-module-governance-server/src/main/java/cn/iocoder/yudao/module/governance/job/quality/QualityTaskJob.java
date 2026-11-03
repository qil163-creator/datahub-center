package cn.iocoder.yudao.module.governance.job.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskTemplateDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskTemplateMapper;
import cn.iocoder.yudao.module.governance.service.quality.QualityTaskService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量任务定时调度 Job
 *
 * 建议在 XXL-Job 控制台按每分钟执行配置：
 * 1. 扫描已上线且配置了 cron 的定时任务，到期触发执行；
 * 2. 扫描启用中的定时任务模板，到期后批量触发该模板物化出的全部上线任务
 *    （调度挂在模板上，物化任务本身是手动的，避免海量任务各自注册定时器）。
 * 执行器内部有防同任务并发保护
 *
 * @author Li
 */
@Component
@Slf4j
public class QualityTaskJob {

    /** 模板物化任务的编码前缀（与 QualityTaskTemplateServiceImpl 保持一致） */
    private static final String TASK_CODE_PREFIX = "TPL-";

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityTaskTemplateMapper templateMapper;

    @Resource
    private QualityTaskService taskService;

    /**
     * 执行质量任务处理流程，并维护相应运行结果。
     */
    @XxlJob("governanceQualityTaskJob")
    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes(1);
        // 1. 定时任务
        List<QualityTaskDO> tasks = taskMapper.selectTimerOnlineList();
        int triggered = 0;
        for (QualityTaskDO task : tasks) {
            if (!isDue(task.getCronExpression(), windowStart, now)) {
                continue;
            }
            try {
                taskService.executeTaskBySchedule(task.getId());
                triggered++;
            } catch (Exception ex) {
                log.warn("[execute][质量任务({}) 调度触发失败：{}]", task.getId(), ex.getMessage());
            }
        }
        // 2. 定时模板：到期后批量触发模板物化出的上线任务
        List<QualityTaskTemplateDO> templates = templateMapper.selectTimerEnabledList();
        int templateTriggered = 0;
        for (QualityTaskTemplateDO template : templates) {
            if (!isDue(template.getCronExpression(), windowStart, now)) {
                continue;
            }
            List<QualityTaskDO> generatedTasks = taskMapper.selectListByCodePrefix(
                    TASK_CODE_PREFIX + template.getId() + "-");
            for (QualityTaskDO task : generatedTasks) {
                try {
                    taskService.executeTaskBySchedule(task.getId());
                    templateTriggered++;
                } catch (Exception ex) {
                    log.warn("[execute][模板({}) 物化任务({}) 调度触发失败：{}]",
                            template.getId(), task.getId(), ex.getMessage());
                }
            }
        }
        XxlJobHelper.handleSuccess(String.format(
                "扫描到 %s 个定时任务（触发 %s 个）、%s 个定时模板（触发物化任务 %s 个）",
                tasks.size(), triggered, templates.size(), templateTriggered));
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
