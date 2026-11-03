package cn.iocoder.yudao.module.dataforge.job.fill;

import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.service.fill.FillBpmAdapter;
import cn.iocoder.yudao.module.dataforge.service.fill.FillBpmEventService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 在线填报-BPM 状态对账 Job
 *
 * 建议在 XXL-Job 控制台按每 30 分钟执行配置。
 * 扫描长时间停留在"审批中"的提交，主动查询 BPM 实例实际状态；发现流程已结束而
 * 本地未收到回调（HTTP 回调丢失）时，构造状态事件走原有 Inbox 幂等链路补偿处理，
 * 与正常回调重复到达时由 Inbox 事件号去重，不会重复建合并批次
 *
 * @author Li
 */
@Component
@Slf4j
public class FillBpmReconcileJob {

    private static final int SCAN_LIMIT = 100;

    /** 提交后至少经过该时长才参与对账（给正常回调留足时间） */
    private static final int MIN_APPROVING_HOURS = 2;

    @Resource
    private FillSubmissionMapper submissionMapper;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillBpmAdapter bpmAdapter;

    @Resource
    private FillBpmEventService bpmEventService;

    /**
     * 执行填报BPM对账任务处理。
     */
    @XxlJob("fillBpmReconcileJob")

    public void execute() {
        List<FillSubmissionDO> submissions = submissionMapper.selectApprovingBefore(
                LocalDateTime.now().minusHours(MIN_APPROVING_HOURS), SCAN_LIMIT);
        int compensated = 0;
        for (FillSubmissionDO submission : submissions) {
            try {
                FillWorkItemDO item = workItemMapper.selectById(submission.getWorkItemId());
                if (item == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(reconcile(submission))) {
                    compensated++;
                }
            } catch (Exception ex) {
                log.error("[execute][提交({}) BPM 状态对账失败]", submission.getId(), ex);
            }
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个审批中提交，补偿 %s 个", submissions.size(), compensated));
    }

    /** @return true = 发现状态不一致并已补偿 */
    private boolean reconcile(FillSubmissionDO submission) {
        Integer bpmStatus = bpmAdapter.getProcessInstanceStatus(submission.getProcessInstanceId());
        if (bpmStatus == null) {
            log.warn("[reconcile][提交({}) 的流程实例({}) 在 BPM 不存在，需人工核查]",
                    submission.getId(), submission.getProcessInstanceId());
            return false;
        }
        if (Objects.equals(bpmStatus, BpmProcessInstanceStatusEnum.RUNNING.getStatus())) {
            return false;
        }
        // 流程已结束而本地仍审批中 → 走原有事件链路补偿（Inbox 幂等去重）
        FillTaskDO task = taskMapper.selectById(submission.getTaskId());
        BpmProcessInstanceStatusEvent event = new BpmProcessInstanceStatusEvent();
        event.setId(submission.getProcessInstanceId());
        event.setProcessDefinitionKey(task != null ? task.getProcessDefinitionKey() : "");
        event.setStatus(bpmStatus);
        event.setBusinessKey(String.valueOf(submission.getId()));
        event.setReason("状态对账补偿（回调丢失）");
        bpmEventService.handleStatusEvent(event);
        log.info("[reconcile][提交({}) 流程({}) 实际状态({})，已补偿处理]",
                submission.getId(), submission.getProcessInstanceId(), bpmStatus);
        return true;
    }

}
