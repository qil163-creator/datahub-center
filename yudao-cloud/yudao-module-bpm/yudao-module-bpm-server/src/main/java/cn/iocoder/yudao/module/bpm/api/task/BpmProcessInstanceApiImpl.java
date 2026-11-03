package cn.iocoder.yudao.module.bpm.api.task;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCancelReqVO;
import cn.iocoder.yudao.module.bpm.framework.flowable.core.util.FlowableUtils;
import cn.iocoder.yudao.module.bpm.service.task.BpmProcessInstanceService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
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
