package cn.iocoder.yudao.module.dataforge.controller.admin.access;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.*;
import cn.iocoder.yudao.module.dataforge.service.access.DataAccessService;
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

@Tag(name = "管理后台 - 数据同步")
@RestController
@RequestMapping("/dataforge/access")
@Validated
public class DataAccessController {

    @Resource
    private DataAccessService dataAccessService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.access.AccessRunCoordinator runCoordinator;

    /**
     * 持久受理运行命令。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @PostMapping("/run-command")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#request.taskId")
    @Operation(summary = "持久受理运行命令", description = "返回原始记录身份；排队受理不等于开始执行")
    @PreAuthorize("@ss.hasPermission('dataforge:access:run')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> runCommand(
            @Valid @RequestBody AccessRunCommandReqVO request) {
        return success(runCoordinator.enqueueManual(request.getTaskId(), request.getRequestId()));
    }

    /**
     * 持久受理记录级停止。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @PostMapping("/stop-command")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#request.recordId")
    @Operation(summary = "持久受理记录级停止", description = "stopRequested=true 不表示资源已经停止；应查询该记录的状态")
    @PreAuthorize("@ss.hasPermission('dataforge:access:run')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> stopCommand(
            @Valid @RequestBody AccessStopCommandReqVO request) {
        return success(runCoordinator.requestStop(request.getRecordId(), request.getRequestId(), request.getReason()));
    }

    /**
     * 人工核查未知执行或部分输出。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @PostMapping("/resolve-command")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#request.recordId")
    @Operation(summary = "人工核查未知执行或部分输出", description = "要求独立核查权限、停止与输出证据以及当前行版本")
    @PreAuthorize("@ss.hasPermission('dataforge:access:resolve')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> resolveCommand(
            @Valid @RequestBody AccessResolveCommandReqVO request) {
        return success(
                runCoordinator.resolve(request.getRecordId(), request.getRequestId(), request.getExpectedVersion(),
                        request.getOutcome(), request.getConfirmedStopped(), request.getOutputReviewed(),
                        request.getReason(), request.getEvidenceRef()));
    }

    /**
     * 查看数据组恢复状态。
     *
     * @param recordId 记录编号
     * @return 访问分组恢复响应数据
     */
    @GetMapping("/group-recovery/get")
    @Operation(summary = "查看数据组恢复状态", description = "仅检查当前平台的平台恢复证据，不访问外部目标；状态提示不替代命令受理校验")
    @PreAuthorize("@ss.hasPermission('dataforge:access:resolve')")
    public CommonResult<AccessGroupRecoveryRespVO> groupRecoveryStatus(@RequestParam("recordId") Long recordId) {
        return success(runCoordinator.groupRecoveryStatus(recordId));
    }

    /**
     * 恢复中断的数据组。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @PostMapping("/group-recover-command")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#request.recordId")
    @Operation(summary = "恢复中断的数据组", description = "服务端确认上一执行器已退出后受理；组恢复完成仍须核查原同步输出")
    @PreAuthorize("@ss.hasPermission('dataforge:access:resolve')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> groupRecoverCommand(
            @Valid @RequestBody AccessGroupRecoverCommandReqVO request) {
        return success(runCoordinator.recoverGroups(request.getRecordId(), request.getRequestId(),
                request.getExpectedVersion(), request.getReason(), request.getEvidenceRef()));
    }

    /**
     * 创建数据同步任务。
     *
     * @param createReqVO 创建参数
     * @return 数据同步任务
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#result")
    @Operation(summary = "创建数据同步任务")
    @ApiAccessLog(requestEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:access:create')")
    public CommonResult<Long> createDataAccess(@Valid @RequestBody DataAccessSaveReqVO createReqVO) {
        return success(dataAccessService.createDataAccess(createReqVO));
    }

    /**
     * 更新数据同步任务。
     *
     * @param updateReqVO 更新参数
     * @return 数据同步任务
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#updateReqVO.id")
    @Operation(summary = "更新数据同步任务")
    @ApiAccessLog(requestEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:access:update')")
    public CommonResult<Boolean> updateDataAccess(@Valid @RequestBody DataAccessSaveReqVO updateReqVO) {
        dataAccessService.updateDataAccess(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据同步任务。
     *
     * @param id 记录编号
     * @return 数据同步任务
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#id")
    @Operation(summary = "删除数据同步任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:access:delete')")
    public CommonResult<Boolean> deleteDataAccess(@RequestParam("id") Long id) {
        dataAccessService.deleteDataAccess(id);
        return success(true);
    }

    /**
     * 获得数据同步任务（含配置回显）。
     *
     * @param id 记录编号
     * @return 数据同步任务响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据同步任务（含配置回显）")
    @ApiAccessLog(responseEnable = false)
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('dataforge:access:query')")
    public CommonResult<DataAccessRespVO> getDataAccess(@RequestParam("id") Long id) {
        return success(dataAccessService.getDataAccess(id));
    }

    /**
     * 获得数据同步任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 数据同步任务分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据同步任务分页")
    @ApiAccessLog(responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:access:query')")
    public CommonResult<PageResult<DataAccessRespVO>> getDataAccessPage(@Valid DataAccessPageReqVO pageReqVO) {
        return success(dataAccessService.getDataAccessPage(pageReqVO));
    }

    /**
     * 预览表名映射。
     *
     * @param reqVO 请求参数
     * @return 表名映射预览结果集合
     */
    @PostMapping("/preview-table-map")
    @Operation(summary = "预览表名映射")
    @PreAuthorize("@ss.hasPermission('dataforge:access:query')")
    public CommonResult<List<PreviewNameMapperRespVO>> previewTableMap(
            @Valid @RequestBody DataAccessPreviewReqVO reqVO) {
        return success(dataAccessService.previewTableMap(reqVO));
    }

    /**
     * 预览字段名映射。
     *
     * @param reqVO 请求参数
     * @return 字段名映射预览结果集合
     */
    @PostMapping("/preview-column-map")
    @Operation(summary = "预览字段名映射")
    @PreAuthorize("@ss.hasPermission('dataforge:access:query')")
    public CommonResult<List<PreviewNameMapperRespVO>> previewColumnMap(
            @Valid @RequestBody DataAccessPreviewReqVO reqVO) {
        return success(dataAccessService.previewColumnMap(reqVO));
    }

    /**
     * 预览表源跨产品类型映射。
     *
     * @param reqVO 请求参数
     * @return 访问类型映射计划响应数据
     */
    @PostMapping("/preview-type-map")
    @Operation(summary = "预览表源跨产品类型映射", description = "按最终物理目标表/列输出损失与不支持原因；不自动确认，不执行 DDL 或搬数")
    @PreAuthorize("@ss.hasPermission('dataforge:access:query')")
    public CommonResult<AccessTypeMappingPlanRespVO> previewTypeMap(
            @Valid @RequestBody AccessTypeMappingPreviewReqVO reqVO) {
        return success(dataAccessService.previewTypeMap(reqVO));
    }

    /**
     * 发布任务。
     *
     * @param id 记录编号
     * @return 数据同步任务
     */
    @PutMapping("/release")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#id")
    @Operation(summary = "发布任务", description = "周期任务进入调度，一次性任务立即执行一次")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:access:release')")
    public CommonResult<Boolean> releaseDataAccess(@RequestParam("id") Long id) {
        dataAccessService.releaseDataAccess(id);
        return success(true);
    }

    /**
     * 取消发布。
     *
     * @param id 记录编号
     * @return 数据同步任务
     */
    @PutMapping("/cancel")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#id")
    @Operation(summary = "取消发布")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:access:release')")
    public CommonResult<Boolean> cancelDataAccess(@RequestParam("id") Long id) {
        dataAccessService.cancelDataAccess(id);
        return success(true);
    }

    /**
     * 手动执行一次。
     *
     * @param id 记录编号
     * @return 数据同步任务
     */
    @PostMapping("/run")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#id", action = "提交数据同步手动执行请求")
    @Operation(summary = "手动执行一次")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:access:run')")
    public CommonResult<Boolean> runDataAccess(@RequestParam("id") Long id) {
        dataAccessService.runDataAccess(id);
        return success(true);
    }

    /**
     * 停止正在运行的任务。
     *
     * @param id 记录编号
     * @return 数据同步任务
     */
    @PostMapping("/stop")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#id", action = "提交数据同步停止执行请求")
    @Operation(summary = "停止正在运行的任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:access:run')")
    public CommonResult<Boolean> stopDataAccess(@RequestParam("id") Long id) {
        dataAccessService.stopDataAccess(id);
        return success(true);
    }

    // ==================== 执行记录 / 表级结果 ====================

    /**
     * 获得执行记录分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 执行记录分页结果
     */
    @GetMapping("/task/page")
    @Operation(summary = "获得执行记录分页")
    @PreAuthorize("@ss.hasPermission('dataforge:access:query')")
    public CommonResult<PageResult<DataAccessTaskRespVO>> getTaskPage(@Valid DataAccessTaskPageReqVO pageReqVO) {
        return success(dataAccessService.getTaskPage(pageReqVO));
    }

    /**
     * 获得执行记录（含实时日志，可轮询）。
     *
     * @param id 记录编号
     * @return 数据同步任务任务响应数据
     */
    @GetMapping("/task/get")
    @Operation(summary = "获得执行记录（含实时日志，可轮询）")
    @Parameter(name = "id", description = "执行记录编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:access:query')")
    public CommonResult<DataAccessTaskRespVO> getTask(@RequestParam("id") Long id) {
        return success(dataAccessService.getTask(id));
    }

    /**
     * 批量删除执行记录。
     *
     * @param ids 记录编号集合
     * @return 数据同步任务
     */
    @DeleteMapping("/task/delete-list")
    @BusinessOperateLog(type = "数据开发-数据同步", bizId = "#ids")
    @Operation(summary = "批量删除执行记录")
    @Parameter(name = "ids", description = "执行记录编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:access:delete')")
    public CommonResult<Boolean> deleteTaskList(@RequestParam("ids") List<Long> ids) {
        dataAccessService.deleteTaskList(ids);
        return success(true);
    }

    /**
     * 获得表级同步结果分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 表级同步结果分页结果
     */
    @GetMapping("/task-detail/page")
    @Operation(summary = "获得表级同步结果分页")
    @PreAuthorize("@ss.hasPermission('dataforge:access:query')")
    public CommonResult<PageResult<DataAccessTaskDetailRespVO>> getTaskDetailPage(
            @Valid DataAccessTaskDetailPageReqVO pageReqVO) {
        return success(dataAccessService.getTaskDetailPage(pageReqVO));
    }

}
