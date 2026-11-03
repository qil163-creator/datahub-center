package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.*;
import cn.iocoder.yudao.module.dataforge.enums.fill.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

/**
 * 在线填报-BPM 状态事件处理实现类
 *
 * 回调链路契约：只做校验 + 状态迁移 + 建合并批次，绝不在回调线程执行目标库批量写入
 * （合并由 fillMergeJob 异步执行）
 *
 * @author Li
 */
@Slf4j
@Service
public class FillBpmEventServiceImpl implements FillBpmEventService {

    @Resource
    private FillEventInboxMapper inboxMapper;

    @Resource
    private FillSubmissionMapper submissionMapper;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillMergeService mergeService;

    @Resource
    private FillNotifyService notifyService;

    @Resource
    private FillCollabService collabService;

    /**
     * 处理 BPM 流程实例状态事件（通过/驳回/取消）
     *
     *  幂等：去重键 {processInstanceId}:{status} 唯一约束；重复、乱序、找不到业务的
     *  事件只记录不执行副作用；处理异常记录 Inbox 失败，不向 BPM 抛错（避免影响 BPM 事件链）
     *
     * @param event 事件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleStatusEvent(BpmProcessInstanceStatusEvent event) {
        // 1. Inbox 幂等：事件信封无全局 eventId，用 {processInstanceId}:{status} 确定性构造
        String eventId = event.getId() + ":" + event.getStatus();
        FillEventInboxDO inbox = FillEventInboxDO.builder()
                .eventId(eventId)
                .eventType(resolveEventType(event.getStatus()))
                .source("BPM")
                .payload(JsonUtils.toJsonString(event))
                .status(FillInboxStatusEnum.RECEIVED.getStatus())
                .build();
        try {
            inboxMapper.insert(inbox);
        } catch (DuplicateKeyException ex) {
            log.info("[handleStatusEvent][事件({}) 重复投递，忽略]", eventId);
            return;
        }

        // 2. 处理：异常只记录 Inbox 失败，不向 BPM 抛错
        try {
            String result = process(event);
            updateInbox(inbox.getId(), FillInboxStatusEnum.PROCESSED, result);
        } catch (Exception ex) {
            log.error("[handleStatusEvent][事件({}) 处理失败]", eventId, ex);
            updateInbox(inbox.getId(), FillInboxStatusEnum.FAILED, StrUtil.brief(ex.getMessage(), 500));
        }
    }

    /**
     * 处理填报BPM事件。
     *
     * @param event 事件
     * @return 处理填报BPM事件文本
     */
    private String process(BpmProcessInstanceStatusEvent event) {
        // 定位提交：优先流程实例编号，回退 businessKey（= submissionId）
        FillSubmissionDO submission = submissionMapper.selectByProcessInstanceId(event.getId());
        if (submission == null && StrUtil.isNotBlank(event.getBusinessKey())) {
            submission = submissionMapper.selectById(Long.parseLong(event.getBusinessKey()));
        }
        if (submission == null) {
            return "IGNORED：找不到对应提交记录";
        }
        // 校验流程实例与提交绑定一致，拒绝伪造/错绑事件
        if (submission.getProcessInstanceId() != null
                && !submission.getProcessInstanceId().equals(event.getId())) {
            return "IGNORED：流程实例与提交绑定不一致";
        }

        if (BpmProcessInstanceStatusEnum.APPROVE.getStatus().equals(event.getStatus())) {
            return handleApproved(submission, event);
        }
        if (BpmProcessInstanceStatusEnum.REJECT.getStatus().equals(event.getStatus())) {
            return handleRejected(submission, event);
        }
        if (BpmProcessInstanceStatusEnum.CANCEL.getStatus().equals(event.getStatus())) {
            return handleCancelled(submission, event);
        }
        return "IGNORED：无需处理的状态 " + event.getStatus();
    }

    /**
     * 处理已批准。
     *
     * @param submission 提交
     * @param event 事件
     * @return 处理已批准文本
     */
    private String handleApproved(FillSubmissionDO submission, BpmProcessInstanceStatusEvent event) {
        if (FillApprovalStatusEnum.APPROVED.getStatus().equals(submission.getApprovalStatus())) {
            return "IGNORED：已是通过状态（重复事件）";
        }
        markFinished(submission, FillApprovalStatusEnum.APPROVED, event.getReason());
        // 审批通过 → 待合并；只建批次，合并由 Job 异步执行（每组织独立合并，不等其他部门）
        workItemMapper.compareAndSetStatus(submission.getWorkItemId(),
                FillWorkItemStatusEnum.APPROVING.getStatus(), FillWorkItemStatusEnum.WAIT_MERGE.getStatus());
        mergeService.createBatchForSubmission(submission.getId());
        notifyAssignees(submission, FillNotifyTypeEnum.APPROVED, null);
        broadcastStatus(submission.getWorkItemId(), FillWorkItemStatusEnum.WAIT_MERGE.getStatus());
        return "PROCESSED：已建合并批次";
    }

    /**
     * 处理已拒绝。
     *
     * @param submission 提交
     * @param event 事件
     * @return 处理已拒绝文本
     */
    private String handleRejected(FillSubmissionDO submission, BpmProcessInstanceStatusEvent event) {
        if (FillApprovalStatusEnum.REJECTED.getStatus().equals(submission.getApprovalStatus())) {
            return "IGNORED：已是驳回状态（重复事件）";
        }
        markFinished(submission, FillApprovalStatusEnum.REJECTED, event.getReason());
        // 驳回 → 整改中：原快照与 BPM 历史保持不变，草稿重新开放给当前有效填报人
        workItemMapper.compareAndSetStatus(submission.getWorkItemId(),
                FillWorkItemStatusEnum.APPROVING.getStatus(), FillWorkItemStatusEnum.REWORKING.getStatus());
        notifyAssignees(submission, FillNotifyTypeEnum.REJECTED,
                Map.of("reason", StrUtil.nullToDefault(event.getReason(), "-")));
        broadcastStatus(submission.getWorkItemId(), FillWorkItemStatusEnum.REWORKING.getStatus());
        return "PROCESSED：已转整改";
    }

    /**
     * 处理已取消。
     *
     * @param submission 提交
     * @param event 事件
     * @return 处理已取消文本
     */
    private String handleCancelled(FillSubmissionDO submission, BpmProcessInstanceStatusEvent event) {
        if (FillApprovalStatusEnum.WITHDRAWN.getStatus().equals(submission.getApprovalStatus())
                || FillApprovalStatusEnum.TERMINATED.getStatus().equals(submission.getApprovalStatus())) {
            return "IGNORED：已是撤回/终止状态（重复事件）";
        }
        // 撤回与管理员终止统一按"恢复整改"处理；业务取消由任务取消流程另行处理
        markFinished(submission, FillApprovalStatusEnum.WITHDRAWN, event.getReason());
        workItemMapper.compareAndSetStatus(submission.getWorkItemId(),
                FillWorkItemStatusEnum.APPROVING.getStatus(), FillWorkItemStatusEnum.REWORKING.getStatus());
        broadcastStatus(submission.getWorkItemId(), FillWorkItemStatusEnum.REWORKING.getStatus());
        return "PROCESSED：已按撤回转整改";
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 标记已完成。
     *
     * @param submission 提交
     * @param status 目标状态
     * @param reason 操作原因
     */
    private void markFinished(FillSubmissionDO submission, FillApprovalStatusEnum status, String reason) {
        FillSubmissionDO update = new FillSubmissionDO();
        update.setId(submission.getId());
        update.setApprovalStatus(status.getStatus());
        update.setApprovalEndTime(LocalDateTime.now());
        update.setApprovalReason(reason);
        submissionMapper.updateById(update);
    }

    /**
     * 通知填报人。
     *
     * @param submission 提交
     * @param type 类型标识
     * @param extraParams 附加参数
     */
    private void notifyAssignees(FillSubmissionDO submission, FillNotifyTypeEnum type,
            Map<String, Object> extraParams) {
        FillWorkItemDO item = workItemMapper.selectById(submission.getWorkItemId());
        FillTaskDO task = taskMapper.selectById(submission.getTaskId());
        List<Long> userIds = convertList(
                assigneeMapper.selectValidListByWorkItemId(submission.getWorkItemId()),
                FillAssigneeDO::getUserId);
        notifyService.sendToUsers(type, task, item, userIds, extraParams);
    }

    /**
     * 广播填报BPM事件的状态。
     *
     * @param workItemId 工作项编号
     * @param status 目标状态
     */
    private void broadcastStatus(Long workItemId, Integer status) {
        try {
            collabService.broadcast(workItemId, FillSubmissionServiceImpl.WS_STATUS_CHANGED,
                    Map.of("workItemId", workItemId, "status", status));
        } catch (Exception ex) {
            log.warn("[broadcastStatus][工作项({}) 广播失败：{}]", workItemId, ex.getMessage());
        }
    }

    /**
     * 更新收件箱。
     *
     * @param inboxId 收件箱编号
     * @param status 目标状态
     * @param result 处理结果
     */
    private void updateInbox(Long inboxId, FillInboxStatusEnum status, String result) {
        FillEventInboxDO update = new FillEventInboxDO();
        update.setId(inboxId);
        update.setStatus(status.getStatus());
        update.setProcessResult(result);
        inboxMapper.updateById(update);
    }

    /**
     * 解析事件类型。
     *
     * @param bpmStatus BPM状态
     * @return 解析事件类型文本
     */
    private String resolveEventType(Integer bpmStatus) {
        if (BpmProcessInstanceStatusEnum.APPROVE.getStatus().equals(bpmStatus)) {
            return FillEventTypeEnum.FILL_BPM_APPROVED.getType();
        }
        if (BpmProcessInstanceStatusEnum.REJECT.getStatus().equals(bpmStatus)) {
            return FillEventTypeEnum.FILL_BPM_REJECTED.getType();
        }
        if (BpmProcessInstanceStatusEnum.CANCEL.getStatus().equals(bpmStatus)) {
            return FillEventTypeEnum.FILL_BPM_CANCELLED.getType();
        }
        return FillEventTypeEnum.FILL_BPM_STARTED.getType();
    }

}
