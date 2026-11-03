package cn.iocoder.yudao.module.dataforge.job.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillNotifyRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.fill.FillNotifyService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

/**
 * 在线填报-临近截止提醒 Job
 *
 * 建议在 XXL-Job 控制台按每 30 分钟执行配置。
 * 对截止时间落在未来提醒窗口内、尚未提交完成的工作项提醒当前有效填报人；
 * 以 fill_notify_record 为频控依据，窗口内只提醒一次
 *
 * @author Li
 */
@Component
@Slf4j
public class FillRemindJob {

    private static final int SCAN_LIMIT = 500;

    /** 提醒窗口：截止前 24 小时 */
    private static final int REMIND_WINDOW_HOURS = 24;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillNotifyRecordMapper notifyRecordMapper;

    @Resource
    private FillNotifyService notifyService;

    /**
     * 执行填报提醒任务处理。
     */
    @XxlJob("fillRemindJob")

    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        List<FillWorkItemDO> candidates = workItemMapper.selectRemindCandidates(
                now, now.plusHours(REMIND_WINDOW_HOURS), SCAN_LIMIT);
        int reminded = 0;
        for (FillWorkItemDO item : candidates) {
            try {
                if (Boolean.TRUE.equals(remind(item))) {
                    reminded++;
                }
            } catch (Exception ex) {
                log.error("[execute][工作项({}) 临近截止提醒失败]", item.getId(), ex);
            }
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个临近截止工作项，提醒 %s 个", candidates.size(), reminded));
    }

    /** @return false = 本窗口内已提醒过 */
    private boolean remind(FillWorkItemDO item) {
        long sent = notifyRecordMapper.selectCountSince(item.getId(),
                FillNotifyTypeEnum.DEADLINE_APPROACHING.getType(),
                item.getDeadlineTime().minusHours(REMIND_WINDOW_HOURS));
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
