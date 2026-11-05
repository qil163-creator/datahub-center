package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityTaskService;
import cn.iocoder.yudao.module.governance.service.quality.QualityTaskEvaluationService;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-质量任务 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-质量任务")
@RestController
@RequestMapping("/governance/quality-task")
@Validated
public class QualityTaskController {

    @Resource
    private QualityTaskService taskService;

    @Resource
    private QualityTaskEvaluationService evaluationService;

    /**
     * 获得任务稽查规则目录。
     */
    @GetMapping("/rule-catalog")
    @Operation(summary = "获得任务稽查规则目录")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:query')")
    public CommonResult<List<QualityTaskEvaluationService.CatalogItem>> catalog() {
        return success(evaluationService.catalog());
    }

    /**
     * 对未保存评测进行样例监测。
     */
    @PostMapping("/sample")
    @Operation(summary = "对未保存评测进行样例监测")
    @PreAuthorize("@ss.hasAnyPermissions('governance:quality-task:create','governance:quality-task:update','governance:quality-task:query')")
    public CommonResult<QualityTaskEvaluationService.SampleResult> sample(
            @Valid @RequestBody QualityTaskProbeReqVO request) {
        return success(evaluationService.sample(request));
    }

    /**
     * 在当前用户的数据权限下抽查评测。
     */
    @PostMapping("/spot-check")
    @Operation(summary = "在当前用户的数据权限下抽查评测")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:query')")
    public CommonResult<QualityTaskEvaluationService.SpotResult> spotCheck(
            @Valid @RequestBody QualityTaskProbeReqVO request) {
        return success(evaluationService.spotCheck(request));
    }

    public record SuggestRequest(@Valid @jakarta.validation.constraints.Size(max = 100)
                                 List<QualityTaskObjectSaveReqVO> objects) {

    }

    /**
     * 按已确认标准落标生成评测候选。
     */
    @PostMapping("/suggest-rules")
    @Operation(summary = "按已确认标准落标生成评测候选")
    @PreAuthorize("@ss.hasAnyPermissions('governance:quality-task:create','governance:quality-task:update')")
    public CommonResult<List<QualityTaskEvaluationSaveReqVO>> suggest(@Valid @RequestBody SuggestRequest request) {
        return success(evaluationService.suggest(request.objects()));
    }

    /**
     * 创建质量任务。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-数据质量-质量任务", subType = "创建质量任务",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了质量任务【{{(#createReqVO.taskName) ?: '未设置'}}】，编码【{{(#createReqVO.taskCode) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建质量任务")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:create')")
    public CommonResult<Long> createTask(@Valid @RequestBody QualityTaskSaveReqVO createReqVO) {
        return success(taskService.createTask(createReqVO));
    }

    /**
     * 更新质量任务。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-数据质量-质量任务", subType = "更新质量任务",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了质量任务，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.taskName) ?: '未设置'}}】，提交编码【{{(#updateReqVO.taskCode) ?: '未设置'}}】，目标状态【{{(#updateReqVO.taskStatus) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新质量任务")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:update')")
    public CommonResult<Boolean> updateTask(@Valid @RequestBody QualityTaskSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        taskService.updateTask(updateReqVO);
        return success(true);
    }

    /**
     * 更新任务状态（上线/下线）
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-数据质量-质量任务", subType = "更新质量任务状态（上线/下线）",
            bizNo = "{{(#id) ?: 0}}", success = "调整了质量任务【{{(#auditObjectName) ?: '编号' + #id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#taskStatus) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新质量任务状态（上线/下线）")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:update')")
    public CommonResult<Boolean> updateTaskStatus(@RequestParam("id") Long id,
            @RequestParam("taskStatus") Integer taskStatus) {
        captureAuditContext(id);
        taskService.updateTaskStatus(id, taskStatus);
        return success(true);
    }

    /**
     * 删除质量任务。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-质量任务", subType = "删除质量任务",
            bizNo = "{{(#id) ?: 0}}", success = "删除了质量任务【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除质量任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task:delete')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
        captureAuditContext(id);
        taskService.deleteTask(id);
        return success(true);
    }

    /**
     * 获得质量任务详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得质量任务详情")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task:query')")
    public CommonResult<QualityTaskRespVO> getTask(@RequestParam("id") Long id) {
        return success(taskService.getTaskDetail(id));
    }

    /**
     * 获得质量任务分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得质量任务分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:query')")
    public CommonResult<PageResult<QualityTaskRespVO>> getTaskPage(@Valid QualityTaskPageReqVO pageReqVO) {
        return success(taskService.getTaskPage(pageReqVO));
    }

    /**
     * 获得质量任务精简列表。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得质量任务精简列表")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:query')")
    public CommonResult<List<QualityTaskRespVO>> getTaskSimpleList() {
        return success(BeanUtils.toBean(taskService.getTaskSimpleList(), QualityTaskRespVO.class));
    }

    /**
     * 手动执行一次
     *
     *  @return 任务实例编号
     */
    @PostMapping("/execute")
    @LogRecord(type = "数据治理-数据质量-质量任务", subType = "手动执行一次质量任务",
            bizNo = "{{(#id) ?: 0}}", success = "发起了质量任务【{{(#auditObjectName) ?: '编号' + #id}}】的一次手动检测，检测实例编号【{{(#_ret.data) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "手动执行一次质量任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task:execute')")
    public CommonResult<Long> executeTask(@RequestParam("id") Long id) {
        captureAuditContext(id);
        return success(taskService.executeTask(id));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = taskService.getTask(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getTaskName() == null
                        ? "编号" + id : auditBefore.getTaskName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getTaskStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getTaskStatus()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
