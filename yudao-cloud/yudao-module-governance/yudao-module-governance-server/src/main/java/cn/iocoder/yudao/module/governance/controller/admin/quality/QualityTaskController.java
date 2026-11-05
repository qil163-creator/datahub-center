package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
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
    @BusinessOperateLog(type = "数据治理-数据质量-质量任务", bizId = "#result")
    @Operation(summary = "创建质量任务")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:create')")
    public CommonResult<Long> createTask(@Valid @RequestBody QualityTaskSaveReqVO createReqVO) {
        return success(taskService.createTask(createReqVO));
    }

    /**
     * 更新质量任务。
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-数据质量-质量任务", bizId = "#updateReqVO.id")
    @Operation(summary = "更新质量任务")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:update')")
    public CommonResult<Boolean> updateTask(@Valid @RequestBody QualityTaskSaveReqVO updateReqVO) {
        taskService.updateTask(updateReqVO);
        return success(true);
    }

    /**
     * 更新任务状态（上线/下线）
     */
    @PutMapping("/update-status")
    @BusinessOperateLog(type = "数据治理-数据质量-质量任务", bizId = "#id")
    @Operation(summary = "更新质量任务状态（上线/下线）")
    @PreAuthorize("@ss.hasPermission('governance:quality-task:update')")
    public CommonResult<Boolean> updateTaskStatus(@RequestParam("id") Long id,
            @RequestParam("taskStatus") Integer taskStatus) {
        taskService.updateTaskStatus(id, taskStatus);
        return success(true);
    }

    /**
     * 删除质量任务。
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-数据质量-质量任务", bizId = "#id")
    @Operation(summary = "删除质量任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task:delete')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
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
    @BusinessOperateLog(type = "数据治理-数据质量-质量任务", bizId = "#id")
    @Operation(summary = "手动执行一次质量任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-task:execute')")
    public CommonResult<Long> executeTask(@RequestParam("id") Long id) {
        return success(taskService.executeTask(id));
    }

}
