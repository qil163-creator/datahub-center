package cn.iocoder.yudao.module.dataforge.job.fill;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillNotifyRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.fill.FillNotifyService;
import cn.iocoder.yudao.module.dataforge.service.fill.FillRemindConfig;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;

/**
 * 在线填报-临近截止提醒 Job
 *
 * 建议在 XXL-Job 控制台按每 30 分钟执行配置。
 * 提醒提前量取计划 remindConfig.hoursBeforeDeadline（缺省 24 小时，上限 168 小时）：
 * 先按上限窗口、截止时间升序扫描尚未提交完成的工作项，再按各自计划的提前量过滤；
 * 以 fill_notify_record 为频控依据，同一截止时间的提醒窗口内只提醒一次（延期后按新截止时间重新提醒）
 *
 * @author Li
 */
@Component
@Slf4j
public class FillRemindJob {

    private static final int SCAN_LIMIT = 500;

    /** 单次执行最多扫描的页数（每页 SCAN_LIMIT 条） */
    private static final int MAX_PAGES = 40;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillPlanMapper planMapper;

    @Resource
    private FillNotifyRecordMapper notifyRecordMapper;

    @Resource
    private FillNotifyService notifyService;

    /**
     * 执行填报提醒任务处理。
     */
    @XxlJob("fillRemindJob")

    public void execute() {
        int reminded = 0;
        int scanned = 0;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = now.plusHours(FillRemindConfig.MAX_HOURS_BEFORE_DEADLINE);
        // 按（截止时间，编号）游标逐页扫描：已提醒过的工作项仍在候选里，只取第一页会让排在后面的永远轮不到
        LocalDateTime afterDeadline = null;
        Long afterId = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<FillWorkItemDO> candidates = workItemMapper.selectRemindCandidates(
                    now, until, afterDeadline, afterId, SCAN_LIMIT);
            if (candidates.isEmpty()) {
                break;
            }
            scanned += candidates.size();
            Map<Long, Integer> hoursByPlan = resolveHoursByPlan(candidates);
            for (FillWorkItemDO item : candidates) {
                try {
                    int hours = hoursByPlan.getOrDefault(item.getPlanId(),
                            FillRemindConfig.DEFAULT_HOURS_BEFORE_DEADLINE);
                    if (remind(item, hours, now)) {
                        reminded++;
                    }
                } catch (Exception ex) {
                    log.error("[execute][工作项({}) 临近截止提醒失败]", item.getId(), ex);
                }
            }
            if (candidates.size() < SCAN_LIMIT) {
                break;
            }
            FillWorkItemDO last = candidates.get(candidates.size() - 1);
            afterDeadline = last.getDeadlineTime();
            afterId = last.getId();
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个待截止工作项，提醒 %s 个", scanned, reminded));
    }

    /** 每个计划的提醒提前量（小时）；计划缺失时按默认值 */
    private Map<Long, Integer> resolveHoursByPlan(List<FillWorkItemDO> candidates) {
        if (CollUtil.isEmpty(candidates)) {
            return Collections.emptyMap();
        }
        List<FillPlanDO> plans = planMapper.selectByIds(convertSet(candidates, FillWorkItemDO::getPlanId));
        return convertMap(plans, FillPlanDO::getId,
                plan -> FillRemindConfig.parse(plan.getRemindConfig()).resolveHoursBeforeDeadline());
    }

    /**
     * @param item 工作项
     * @param hoursBeforeDeadline 计划配置的提醒提前量（小时）
     * @param now 本次扫描时间
     * @return false = 尚未进入提醒窗口，或本窗口内已提醒过
     */
    private boolean remind(FillWorkItemDO item, int hoursBeforeDeadline, LocalDateTime now) {
        LocalDateTime windowStart = item.getDeadlineTime().minusHours(hoursBeforeDeadline);
        if (now.isBefore(windowStart)) {
            return false;
        }
        long sent = notifyRecordMapper.selectCountSince(item.getId(),
                FillNotifyTypeEnum.DEADLINE_APPROACHING.getType(), windowStart);
        if (sent > 0) {
            return false;
        }
        List<FillAssigneeDO> assignees = assigneeMapper.selectValidListByWorkItemId(item.getId());
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        notifyService.sendToUsers(FillNotifyTypeEnum.DEADLINE_APPROACHING, task, item,
                convertList(assignees, FillAssigneeDO::getUserId), null);
        return true;
    }

}
