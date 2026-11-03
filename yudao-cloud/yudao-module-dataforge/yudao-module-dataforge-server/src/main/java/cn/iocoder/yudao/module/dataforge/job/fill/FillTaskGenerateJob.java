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
        for (FillPlanDO plan : plans) {
            generated += catchUp(plan, now);
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个到期计划，生成任务 %s 个", plans.size(), generated));
    }

    /**
     * 补齐向上。
     *
     * @param plan 计划
     * @param now 当前时间
     * @return 补齐向上数值
     */
    private int catchUp(FillPlanDO plan, LocalDateTime now) {
        FillPeriodConfig config = FillPeriodCalculator.parseConfig(plan.getPeriodConfig());
        int generated = 0;
        LocalDateTime generateTime = plan.getNextGenerateTime();
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
            LocalDateTime finalGenerateTime = generateTime;
            try {
                // 生成动作按计划执行，保留任务、工作项与填报人的关联
                fillTaskService.generateForPlan(plan, finalGenerateTime);
                generated++;
            } catch (Exception ex) {
                // 位点已推进，失败期次可通过"任务补生成"按历史时点补建
                log.error("[catchUp][计划({}) 期次({}) 生成失败]", plan.getId(), period.periodKey(), ex);
            }
            generateTime = next;
        }
        return generated;
    }

}
