package cn.iocoder.yudao.module.dataforge.job.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillEventOutboxDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillEventOutboxMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillEventTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillOutboxStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.bpm.enums.ErrorCodeConstants;
import cn.iocoder.yudao.module.dataforge.service.fill.FillBpmAdapter;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTaskService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-可靠事件投递 Job
 *
 * 建议在 XXL-Job 控制台按每分钟执行配置。
 * 扫描待发送/到期重试的 Outbox 事件，CAS 抢占后按事件执行外部动作；
 * BPM 短暂不可用时提交快照不丢失，恢复后自动启动审批
 *
 * @author Li
 */
@Component
@Slf4j
public class FillOutboxRelayJob {

    private static final int SCAN_LIMIT = 100;

    private static final long RETRY_BASE_MINUTES = 2;

    private static final long RETRY_MAX_MINUTES = 120;

    @Resource
    private FillEventOutboxMapper outboxMapper;

    @Resource
    private FillSubmissionMapper submissionMapper;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillBpmAdapter bpmAdapter;

    @Resource
    private FillTaskService fillTaskService;

    /**
     * 执行填报发件箱转发任务处理。
     */
    @XxlJob("fillOutboxRelayJob")

    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        List<FillEventOutboxDO> events = outboxMapper.selectListToSend(now, SCAN_LIMIT);
        int success = 0;
        for (FillEventOutboxDO event : events) {
            // 投递抢占 CAS，多实例安全
            if (outboxMapper.compareAndSetSending(event.getId(), event.getStatus()) == 0) {
                continue;
            }
            try {
                dispatch(event);
                markSuccess(event);
                success++;
            } catch (Exception ex) {
                log.error("[execute][Outbox 事件({}/{}) 投递失败]", event.getId(), event.getEventType(), ex);
                markFailed(event, ex);
            }
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个待投递事件，成功 %s 个", events.size(), success));
    }

    /**
     * 派发填报发件箱转发任务。
     *
     * @param event 事件
     */
    private void dispatch(FillEventOutboxDO event) {
        if (FillEventTypeEnum.FILL_BPM_START_REQUESTED.getType().equals(event.getEventType())) {
            startBpmProcess(event.getBizId());
            return;
        }
        // 其他事件类型（通知等）当前无需外部投递，直接视为成功
        log.info("[dispatch][事件类型({}) 无需投递，跳过]", event.getEventType());
    }

    /**
     * 启动 BPM 审批：submission.processInstanceId 已存在则视为重放，只做状态对齐；
     * 为空时先按 businessKey 查询既有实例（"已创建但响应丢失"时复用，不创建第二个流程）
     */
    private void startBpmProcess(Long submissionId) {
        FillSubmissionDO submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            log.warn("[startBpmProcess][提交({}) 不存在，跳过]", submissionId);
            return;
        }
        FillWorkItemDO workItem = workItemMapper.selectById(submission.getWorkItemId());
        if (StrUtil.isBlank(submission.getProcessInstanceId())) {
            FillTaskDO task = taskMapper.selectById(submission.getTaskId());
            String processInstanceId = bpmAdapter.findProcessInstanceIdByBusinessKey(
                    task.getProcessDefinitionKey(), submission.getId());
            if (StrUtil.isBlank(processInstanceId)) {
                processInstanceId = startWithFrozenDefinition(submission, workItem, task);
            } else {
                log.info("[startBpmProcess][提交({}) 已存在流程实例({})，复用不重建]", submissionId, processInstanceId);
            }
            FillSubmissionDO update = new FillSubmissionDO();
            update.setId(submission.getId());
            update.setProcessInstanceId(processInstanceId);
            update.setApprovalStatus(FillApprovalStatusEnum.APPROVING.getStatus());
            submissionMapper.updateById(update);
        } else if (FillApprovalStatusEnum.WAIT_START.getStatus().equals(submission.getApprovalStatus())) {
            FillSubmissionDO update = new FillSubmissionDO();
            update.setId(submission.getId());
            update.setApprovalStatus(FillApprovalStatusEnum.APPROVING.getStatus());
            submissionMapper.updateById(update);
        }
        workItemMapper.compareAndSetStatus(submission.getWorkItemId(),
                FillWorkItemStatusEnum.WAIT_BPM_START.getStatus(), FillWorkItemStatusEnum.APPROVING.getStatus());
    }

    /**
     * 按任务冻结的流程版本启动审批：尚未冻结时先冻结当前生效版本；冻结版本已被新部署挂起时
     * 不回退到当前版本，保留失败事件并提示管理员在任务上切换版本（切换后事件立即重新投递）。
     */
    private String startWithFrozenDefinition(FillSubmissionDO submission, FillWorkItemDO workItem, FillTaskDO task) {
        if (StrUtil.isNotBlank(task.getProcessDefinitionKey()) && StrUtil.isBlank(task.getProcessDefinitionId())) {
            task.setProcessDefinitionId(fillTaskService.freezeProcessDefinition(
                    task.getId(), task.getProcessDefinitionKey()));
        }
        try {
            return bpmAdapter.startProcess(submission, workItem, task);
        } catch (ServiceException ex) {
            if (ErrorCodeConstants.PROCESS_DEFINITION_IS_SUSPENDED.getCode().equals(ex.getCode())
                    || ErrorCodeConstants.PROCESS_DEFINITION_NOT_EXISTS.getCode().equals(ex.getCode())) {
                throw new IllegalStateException("任务冻结的审批流程版本(" + task.getProcessDefinitionId()
                        + ")已停用或不存在，请管理员在采集任务上执行“切换到当前流程版本”", ex);
            }
            throw ex;
        }
    }

    /**
     * 标记成功。
     *
     * @param event 事件
     */
    private void markSuccess(FillEventOutboxDO event) {
        FillEventOutboxDO update = new FillEventOutboxDO();
        update.setId(event.getId());
        update.setStatus(FillOutboxStatusEnum.SUCCESS.getStatus());
        update.setErrorMessage(null);
        outboxMapper.updateById(update);
    }

    /**
     * 标记失败。
     *
     * @param event 事件
     * @param ex 异常信息
     */
    private void markFailed(FillEventOutboxDO event, Exception ex) {
        int retryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        long delayMinutes = Math.min(RETRY_BASE_MINUTES * (1L << Math.min(retryCount - 1, 10)), RETRY_MAX_MINUTES);
        FillEventOutboxDO update = new FillEventOutboxDO();
        update.setId(event.getId());
        update.setStatus(FillOutboxStatusEnum.FAILED.getStatus());
        update.setRetryCount(retryCount);
        update.setNextRetryTime(LocalDateTime.now().plusMinutes(delayMinutes));
        update.setErrorMessage(StrUtil.brief(ex.getMessage(), 2000));
        outboxMapper.updateById(update);
    }

}
