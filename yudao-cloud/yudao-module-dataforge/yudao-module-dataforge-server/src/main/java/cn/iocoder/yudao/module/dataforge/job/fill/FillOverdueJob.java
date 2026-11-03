package cn.iocoder.yudao.module.dataforge.job.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
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
 * 在线填报-逾期扫描 Job
 *
 * 建议在 XXL-Job 控制台按每 5 分钟执行配置。
 * 将超过截止时间且仍可编辑的工作项标记为已逾期（软逾期：只标记不锁定；
 * 硬截止的编辑/提交拦截由草稿与提交服务按 overduePolicy 执行），并通知当前有效填报人
 *
 * @author Li
 */
@Component
@Slf4j
public class FillOverdueJob {

    private static final int SCAN_LIMIT = 500;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillNotifyService notifyService;

    /**
     * 执行填报逾期任务处理。
     */
    @XxlJob("fillOverdueJob")

    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        List<FillWorkItemDO> candidates = workItemMapper.selectOverdueCandidates(now, SCAN_LIMIT);
        int marked = 0;
        for (FillWorkItemDO item : candidates) {
            try {
                markOverdue(item);
                marked++;
            } catch (Exception ex) {
                log.error("[execute][工作项({}) 逾期标记失败]", item.getId(), ex);
            }
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个逾期工作项，标记 %s 个", candidates.size(), marked));
    }

    /**
     * 标记逾期。
     *
     * @param item 当前条目
     */
    private void markOverdue(FillWorkItemDO item) {
        FillWorkItemDO update = new FillWorkItemDO();
        update.setId(item.getId());
        update.setOverdue(1);
        workItemMapper.updateById(update);

        List<FillAssigneeDO> assignees = assigneeMapper.selectValidListByWorkItemId(item.getId());
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        notifyService.sendToUsers(FillNotifyTypeEnum.OVERDUE, task, item,
                convertList(assignees, FillAssigneeDO::getUserId), null);
    }

}
