package cn.iocoder.yudao.module.dataforge.job.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.service.fill.FillPeriodCalculator;
import cn.iocoder.yudao.module.dataforge.service.fill.FillPeriodConfig;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTaskService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线填报-周期任务生成 Job
 *
 * 建议在 XXL-Job 控制台按每分钟执行配置。
 * 扫描启用中且生成时点已到的采集计划：先以旧 nextGenerateTime 做 CAS 推进生成位点
 * （防多实例重复生成），成功抢占后按计划生成任务；
 * 调度停机跨过多个期次时逐期追赶补生成，(plan_id, period_key) 唯一约束兜底幂等
 *
 * @author Li
 */
@Component
@Slf4j
public class FillTaskGenerateJob {

    /** 单计划单次执行最多追赶的期次数，防止异常配置导致死循环 */
    private static final int MAX_CATCH_UP = 36;

    private static final int SCAN_LIMIT = 100;

    @Resource
    private FillPlanMapper planMapper;

    @Resource
    private FillTaskService fillTaskService;

    /**
     * 执行填报任务生成任务处理。
     */
    @XxlJob("fillTaskGenerateJob")

    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        List<FillPlanDO> plans = planMapper.selectListToGenerate(now, SCAN_LIMIT);
        int generated = 0;
        List<String> failures = new ArrayList<>();
        for (FillPlanDO plan : plans) {
            generated += catchUp(plan, now, failures);
        }
        // 已到开放时间的“待开始”任务转为进行中，并通知填报人任务开始
        int opened = 0;
        try {
            opened = fillTaskService.openDueTasks(now);
        } catch (Exception ex) {
            log.error("[execute][到期任务开放失败]", ex);
            failures.add("到期任务开放失败：" + ex.getMessage());
        }
        String summary = String.format("扫描到 %s 个到期计划，生成任务 %s 个，开放任务 %s 个",
                plans.size(), generated, opened);
        if (failures.isEmpty()) {
            XxlJobHelper.handleSuccess(summary);
        } else {
            // 生成失败的期次已退回生成时点，下一轮自动重试；这里把任务标记为失败以触发调度告警
            XxlJobHelper.handleFail(summary + "；失败 " + failures.size() + " 项：" + String.join("；", failures));
        }
    }

    /**
     * 按生成时点逐期补齐计划的任务。
     *
     * @param plan 计划
     * @param now 当前时间
     * @param failures 失败说明收集
     * @return 本次生成的任务数
     */
    private int catchUp(FillPlanDO plan, LocalDateTime now, List<String> failures) {
        FillPeriodConfig config = FillPeriodCalculator.parseConfig(plan.getPeriodConfig());
        int generated = 0;
        LocalDateTime generateTime = plan.getNextGenerateTime();
        String lastPeriodKey = plan.getLastPeriodKey();
        for (int i = 0; i < MAX_CATCH_UP && generateTime != null && !generateTime.isAfter(now); i++) {
            LocalDateTime next = FillPeriodCalculator.computeNextGenerateTime(
                    plan.getPeriodType(), config, generateTime);
            FillPeriodCalculator.PeriodInfo period = FillPeriodCalculator.buildPeriod(
                    plan.getId(), plan.getPeriodType(), config, generateTime);
            // CAS 推进生成位点：失败说明被其他实例抢占，本计划直接结束
            int updated = planMapper.compareAndSetGeneratePoint(
                    plan.getId(), generateTime, next, period.periodKey());
            if (updated == 0) {
                break;
            }
            try {
                // 生成动作按计划执行，保留任务、工作项与填报人的关联
                fillTaskService.generateForPlan(plan, generateTime);
                generated++;
            } catch (Exception ex) {
                // 生成失败：把位点退回本期，下一轮重试，不跳期；后续期次等本期成功后再补
                log.error("[catchUp][计划({}) 期次({}) 生成失败，已退回生成时点等待重试]",
                        plan.getId(), period.periodKey(), ex);
                planMapper.rollbackGeneratePoint(plan.getId(), next, generateTime, lastPeriodKey);
                failures.add("计划 " + plan.getId() + " 期次 " + period.periodKey());
                break;
            }
            lastPeriodKey = period.periodKey();
            generateTime = next;
        }
        return generated;
    }

}
