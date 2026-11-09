package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 在线填报-BPM 适配器（对 BPM 依赖的唯一出入口）
 *
 * 约定：流程发起人 = 实际提交人（撤回/我的流程/审批展示自然成立）；
 * businessKey = 提交快照编号（审批与合并只认快照）。
 *
 * 幂等与对账：Outbox 重试启动前先按 businessKey 查询既有实例（消除"已创建但
 * 响应丢失"的双实例窗口）；fillBpmReconcileJob 定期按实例状态对账补偿丢失回调
 *
 * @author Li
 */
@Component
public class FillBpmAdapter {

    @Resource
    private BpmProcessInstanceApi bpmProcessInstanceApi;

    /**
     * 以实际提交人身份启动审批流程
     *
     * @return BPM 流程实例编号
     */
    public String startProcess(FillSubmissionDO submission, FillWorkItemDO workItem, FillTaskDO task) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("taskId", task.getId());
        variables.put("taskName", task.getName());
        variables.put("periodKey", task.getPeriodKey());
        variables.put("workItemId", workItem.getId());
        variables.put("orgId", workItem.getReportingOrgId());
        variables.put("orgName", workItem.getOrgName());
        variables.put("submissionId", submission.getId());
        variables.put("attemptNo", submission.getAttemptNo());
        variables.put("rowCount", submission.getRowCount());
        variables.put("submitterUserId", submission.getSubmitterUserId());
        return bpmProcessInstanceApi.createProcessInstance(submission.getSubmitterUserId(),
                        new BpmProcessInstanceCreateReqDTO()
                                .setProcessDefinitionKey(task.getProcessDefinitionKey())
                                // 严格按任务冻结的版本发起；该版本已停用时 BPM 拒绝，不回退到当前版本
                                .setProcessDefinitionId(task.getProcessDefinitionId())
                                .setBusinessKey(String.valueOf(submission.getId()))
                                .setVariables(variables))
                .getCheckedData();
    }

    /**
     * 以提交人身份撤回（终止）流程实例
     */
    public void cancelProcessBySubmitter(Long submitterUserId, String processInstanceId, String reason) {
        bpmProcessInstanceApi.cancelProcessInstanceByStartUser(submitterUserId, processInstanceId, reason)
                .getCheckedData();
    }

    /**
     * 按流程定义 + 业务标识查询既有流程实例编号（幂等启动检查；不存在返回 null）
     */
    public String findProcessInstanceIdByBusinessKey(String processDefinitionKey, Long submissionId) {
        return bpmProcessInstanceApi.getProcessInstanceIdByBusinessKey(
                processDefinitionKey, String.valueOf(submissionId)).getCheckedData();
    }

    /**
     * 查询流程定义标识当前生效的版本编号（不存在或已挂起返回 null）
     */
    public String getActiveProcessDefinitionId(String processDefinitionKey) {
        return bpmProcessInstanceApi.getActiveProcessDefinitionId(processDefinitionKey).getCheckedData();
    }

    /**
     * 判断用户是否为流程实例参与者（发起人、审批人/候选人、抄送人）
     */
    public boolean isProcessInstanceParticipant(String processInstanceId, Long userId) {
        return Boolean.TRUE.equals(bpmProcessInstanceApi
                .isProcessInstanceParticipant(processInstanceId, userId).getCheckedData());
    }

    /**
     * 查询流程实例状态（BpmProcessInstanceStatusEnum 值；实例不存在返回 null）
     */
    public Integer getProcessInstanceStatus(String processInstanceId) {
        return bpmProcessInstanceApi.getProcessInstanceStatus(processInstanceId).getCheckedData();
    }

}
