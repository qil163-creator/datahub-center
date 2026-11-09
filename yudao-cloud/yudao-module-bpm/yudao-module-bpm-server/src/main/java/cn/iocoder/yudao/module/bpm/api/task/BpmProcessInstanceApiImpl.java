package cn.iocoder.yudao.module.bpm.api.task;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCancelReqVO;
import cn.iocoder.yudao.module.bpm.dal.dataobject.task.BpmProcessInstanceCopyDO;
import cn.iocoder.yudao.module.bpm.dal.mysql.task.BpmProcessInstanceCopyMapper;
import cn.iocoder.yudao.module.bpm.service.definition.BpmProcessDefinitionService;
import cn.iocoder.yudao.module.bpm.framework.flowable.core.util.FlowableUtils;
import cn.iocoder.yudao.module.bpm.service.task.BpmProcessInstanceService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * Flowable 流程实例 Api 实现类
 *
 * @author 芋道源码
 * @author jason
 */
@RestController
@Validated
public class BpmProcessInstanceApiImpl implements BpmProcessInstanceApi {

    @org.springframework.beans.factory.annotation.Value("${yudao.tenant.enable:true}")
    private boolean tenantEnabled = true;

    @Resource
    private BpmProcessInstanceService processInstanceService;
    @Resource
    private HistoryService historyService;
    @Resource
    private BpmProcessDefinitionService processDefinitionService;
    @Resource
    private BpmProcessInstanceCopyMapper processInstanceCopyMapper;

    @Override
    public CommonResult<String> createProcessInstance(Long userId, @Valid BpmProcessInstanceCreateReqDTO reqDTO) {
        return success(processInstanceService.createProcessInstance(userId, reqDTO));
    }

    @Override
    public CommonResult<Boolean> cancelProcessInstanceByStartUser(
            Long userId, String processInstanceId, String reason) {
        BpmProcessInstanceCancelReqVO cancelReqVO = new BpmProcessInstanceCancelReqVO();
        cancelReqVO.setId(processInstanceId);
        cancelReqVO.setReason(reason);
        processInstanceService.cancelProcessInstanceByStartUser(userId, cancelReqVO);
        return success(true);
    }

    @Override
    public CommonResult<String> getProcessInstanceIdByBusinessKey(String processDefinitionKey, String businessKey) {
        // 历史表含运行中与已结束实例，覆盖"已创建但响应丢失"的幂等启动检查场景
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(requiredTenant())
                .processDefinitionKey(processDefinitionKey)
                .processInstanceBusinessKey(businessKey)
                .orderByProcessInstanceStartTime().asc()
                .list().stream().findFirst().orElse(null);
        return success(instance != null ? instance.getId() : null);
    }

    @Override
    public CommonResult<String> getActiveProcessDefinitionId(String processDefinitionKey) {
        ProcessDefinition definition = processDefinitionService.getActiveProcessDefinition(processDefinitionKey);
        return success(definition == null || definition.isSuspended() ? null : definition.getId());
    }

    @Override
    public CommonResult<Boolean> isProcessInstanceParticipant(String processInstanceId, Long userId) {
        if (processInstanceId == null || userId == null) {
            return success(false);
        }
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(requiredTenant())
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance == null) {
            return success(false);
        }
        String user = String.valueOf(userId);
        if (user.equals(instance.getStartUserId())) {
            return success(true);
        }
        // 历史任务覆盖运行中与已完成任务；分别检查审批人、拥有人及候选/参与身份链接。
        boolean taskParticipant = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).taskAssignee(user).count() > 0
                || historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).taskOwner(user).count() > 0
                || historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).taskInvolvedUser(user).count() > 0
                || historyService.getHistoricIdentityLinksForProcessInstance(processInstanceId).stream()
                .anyMatch(link -> user.equals(link.getUserId()));
        if (taskParticipant) {
            return success(true);
        }
        return success(processInstanceCopyMapper.selectCount(new LambdaQueryWrapperX<BpmProcessInstanceCopyDO>()
                .eq(BpmProcessInstanceCopyDO::getProcessInstanceId, processInstanceId)
                .eq(BpmProcessInstanceCopyDO::getUserId, userId)) > 0);
    }

    @Override
    public CommonResult<Integer> getProcessInstanceStatus(String processInstanceId) {
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(requiredTenant())
                .processInstanceId(processInstanceId)
                .includeProcessVariables()
                .singleResult();
        return success(instance != null ? FlowableUtils.getProcessInstanceStatus(instance) : null);
    }

    private String requiredTenant() {
        return FlowableUtils.getRequiredTenantId(tenantEnabled);
    }

}
