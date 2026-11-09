package cn.iocoder.yudao.module.bpm.api.task;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.module.bpm.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.Valid;

@FeignClient(name = ApiConstants.NAME) // TODO 芋艿：fallbackFactory =
@Tag(name = "RPC 服务 - 流程实例")
public interface BpmProcessInstanceApi {

    String PREFIX = ApiConstants.PREFIX + "/process-instance";

    @PostMapping(PREFIX + "/create")
    @Operation(summary = "创建流程实例（提供给内部），返回实例编号")
    @Parameter(name = "userId", description = "用户编号", required = true, example = "1")
    CommonResult<String> createProcessInstance(@RequestParam("userId") Long userId,
                                               @Valid @RequestBody BpmProcessInstanceCreateReqDTO reqDTO);

    @PostMapping(PREFIX + "/cancel-by-start-user")
    @Operation(summary = "取消流程实例（提供给内部）")
    CommonResult<Boolean> cancelProcessInstanceByStartUser(
            @RequestParam("userId") Long userId,
            @RequestParam("processInstanceId") String processInstanceId,
            @RequestParam("reason") String reason);

    @GetMapping(PREFIX + "/get-id-by-business-key")
    @Operation(summary = "按流程定义与业务标识查询流程实例编号（提供给内部，业务方幂等启动用）",
            description = "含历史实例；不存在返回 null")
    CommonResult<String> getProcessInstanceIdByBusinessKey(
            @RequestParam("processDefinitionKey") String processDefinitionKey,
            @RequestParam("businessKey") String businessKey);

    @GetMapping(PREFIX + "/get-active-definition-id")
    @Operation(summary = "按流程定义标识查询当前生效的流程定义编号（提供给内部，业务方冻结流程版本用）",
            description = "不存在或已挂起时返回 null")
    CommonResult<String> getActiveProcessDefinitionId(
            @RequestParam("processDefinitionKey") String processDefinitionKey);

    @GetMapping(PREFIX + "/is-participant")
    @Operation(summary = "判断用户是否为流程实例参与者（提供给内部，业务方收紧审批材料可见范围用）",
            description = "发起人、任一任务的审批人/拥有人/候选人，以及抄送人均视为参与者；实例不存在返回 false")
    CommonResult<Boolean> isProcessInstanceParticipant(
            @RequestParam("processInstanceId") String processInstanceId,
            @RequestParam("userId") Long userId);

    @GetMapping(PREFIX + "/get-status")
    @Operation(summary = "查询流程实例状态（提供给内部，业务方状态对账用）",
            description = "返回 BpmProcessInstanceStatusEnum 的状态值；实例不存在返回 null")
    CommonResult<Integer> getProcessInstanceStatus(
            @RequestParam("processInstanceId") String processInstanceId);

}
