package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskGenerateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskProgressRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTaskService;
import cn.iocoder.yudao.module.dataforge.service.fill.FillWorkItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-采集任务 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报采集任务")
@RestController
@RequestMapping("/dataforge/fill-task")
@Validated
public class FillTaskController {

    @Resource
    private FillTaskService taskService;

    @Resource
    private FillWorkItemService workItemService;

    /**
     * 获得采集任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 采集任务分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得采集任务分页")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-task:query')")
    public CommonResult<PageResult<FillTaskRespVO>> getTaskPage(@Valid FillTaskPageReqVO pageReqVO) {
        return success(taskService.getTaskPage(pageReqVO));
    }

    /**
     * 获得采集任务。
     *
     * @param id 记录编号
     * @return 填报任务响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得采集任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-task:query')")
    public CommonResult<FillTaskRespVO> getTask(@RequestParam("id") Long id) {
        return success(taskService.getTask(id));
    }

    /**
     * 获得任务进度（各状态数量、逾期数、完成率）。
     *
     * @param id 记录编号
     * @return 填报任务进度响应数据
     */
    @GetMapping("/progress")
    @Operation(summary = "获得任务进度（各状态数量、逾期数、完成率）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-task:query')")
    public CommonResult<FillTaskProgressRespVO> getTaskProgress(@RequestParam("id") Long id) {
        return success(taskService.getTaskProgress(id));
    }

    /**
     * 手工生成/补生成任务。
     *
     * @param reqVO 请求参数
     * @return 填报任务
     */
    @PostMapping("/generate")
    @LogRecord(type = "数据开发-在线填报采集任务", subType = "手工生成/补生成任务", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "为采集计划【{{(#reqVO.planId) ?: '未设置'}}】发起了任务生成，指定生成时点【{{(#reqVO.generateTime) ?: '未设置'}}】，任务编号【{{(#_ret.data) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "手工生成/补生成任务", description = "一次性任务重新生成；调度停机后按历史生成时点补生成周期任务")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-task:create')")
    public CommonResult<Long> generateTask(@Valid @RequestBody FillTaskGenerateReqVO reqVO) {
        return success(taskService.manualGenerate(reqVO));
    }

    /**
     * 把任务冻结的审批流程切换到当前生效版本。
     *
     * @param id 记录编号
     * @return 切换后的流程定义编号
     */
    @PutMapping("/switch-process-definition")
    @LogRecord(type = "数据开发-在线填报采集任务", subType = "切换审批流程版本", bizNo = "{{(#id) ?: 0}}",
            success = "将填报采集任务【{{(#operateName) ?: '未设置'}}】的审批流程切换到当前版本【{{(#_ret.data) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "切换到当前审批流程版本",
            description = "冻结的流程版本被新部署停用后，由管理员确认切换；等待启动审批的提交将立即按新版本重新发起，已发起的审批不受影响")
    @Parameter(name = "id", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-task:create')")
    public CommonResult<String> switchProcessDefinition(@RequestParam("id") Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = taskService.getTask(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);
        return success(taskService.switchProcessDefinition(id));
    }

    /**
     * 任务级一键催办。
     *
     * @param id 记录编号
     * @return 填报任务
     */
    @PostMapping("/urge")
    @LogRecord(type = "数据开发-在线填报采集任务", subType = "任务级一键催办", bizNo = "{{(#id) ?: 0}}",
            success = "对填报采集任务【{{(#operateName) ?: '未设置'}}】发起了催办，通知处理数【{{(#_ret.data) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "任务级一键催办", description = "对全部未完成组织实例发送催办；1 小时频控内的自动跳过")
    @Parameter(name = "id", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-task:urge')")
    public CommonResult<Integer> urgeTask(@RequestParam("id") Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = taskService.getTask(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        return success(workItemService.urgeByTask(id));
    }

}
