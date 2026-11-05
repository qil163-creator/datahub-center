package cn.iocoder.yudao.module.dataforge.controller.admin.push;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.*;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushTaskService;
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

@Tag(name = "管理后台 - 数据推送任务")
@RestController
@RequestMapping("/dataforge/data-push-task")
@Validated
public class DataPushTaskController {

    @Resource
    private DataPushTaskService taskService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.push.DataPushRunReviewService reviewService;

    /**
     * 人工核查未知推送（仅未生效或完整补偿，停用排期且不改水位）。
     *
     * @param request 请求参数
     * @return 数据推送任务日志响应数据
     */
    @PostMapping("/resolve-command")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#request.recordId")
    @Operation(summary = "人工核查未知推送（仅未生效或完整补偿，停用排期且不改水位）")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:resolve')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<DataPushTaskLogRespVO> resolve(@Valid @RequestBody DataPushResolveReqVO request) {
        return success(reviewService.resolve(request));
    }

    /**
     * 创建推送任务。
     *
     * @param createReqVO 创建参数
     * @return 数据推送任务
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#result")
    @Operation(summary = "创建推送任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:create')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false)
    public CommonResult<Long> createTask(@Valid @RequestBody DataPushTaskSaveReqVO createReqVO) {
        return success(taskService.createTask(createReqVO));
    }

    /**
     * 更新推送任务。
     *
     * @param updateReqVO 更新参数
     * @return 数据推送任务
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#updateReqVO.id")
    @Operation(summary = "更新推送任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:update')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false)
    public CommonResult<Boolean> updateTask(@Valid @RequestBody DataPushTaskSaveReqVO updateReqVO) {
        taskService.updateTask(updateReqVO);
        return success(true);
    }

    /**
     * 删除推送任务。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#id")
    @Operation(summary = "删除推送任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:delete')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
        taskService.deleteTask(id);
        return success(true);
    }

    /**
     * 获得推送任务。
     *
     * @param id 记录编号
     * @return 数据推送任务响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得推送任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<DataPushTaskRespVO> getTask(@RequestParam("id") Long id) {
        return success(taskService.getTask(id));
    }

    /**
     * 获得推送任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 推送任务分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得推送任务分页")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<PageResult<DataPushTaskRespVO>> getTaskPage(@Valid DataPushTaskPageReqVO pageReqVO) {
        return success(taskService.getTaskPage(pageReqVO));
    }

    /**
     * 发布任务。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    @PutMapping("/release")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#id")
    @Operation(summary = "发布任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:release')")
    public CommonResult<Boolean> releaseTask(@RequestParam("id") Long id) {
        taskService.releaseTask(id);
        return success(true);
    }

    /**
     * 取消发布。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    @PutMapping("/cancel-release")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#id")
    @Operation(summary = "取消发布")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:release')")
    public CommonResult<Boolean> cancelTask(@RequestParam("id") Long id) {
        taskService.cancelTask(id);
        return success(true);
    }

    /**
     * 手动执行一次。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    @PutMapping("/hand-run")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#id", action = "提交数据推送手动执行请求")
    @Operation(summary = "手动执行一次")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:run')")
    public CommonResult<Boolean> handRun(@RequestParam("id") Long id) {
        taskService.handRun(id);
        return success(true);
    }

    /**
     * 停止正在运行的任务。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    @PutMapping("/stop")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#id", action = "提交数据推送停止执行请求")
    @Operation(summary = "停止正在运行的任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:run')")
    public CommonResult<Boolean> stopTask(@RequestParam("id") Long id) {
        taskService.stopTask(id);
        return success(true);
    }

    /**
     * 获得执行记录分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 执行记录分页结果
     */
    @GetMapping("/log-page")
    @Operation(summary = "获得执行记录分页")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<PageResult<DataPushTaskLogRespVO>> getLogPage(@Valid DataPushTaskLogPageReqVO pageReqVO) {
        return success(taskService.getLogPage(pageReqVO));
    }

    /**
     * 获得执行记录详情。
     *
     * @param id 记录编号
     * @return 数据推送任务日志响应数据
     */
    @GetMapping("/log-get")
    @Operation(summary = "获得执行记录详情")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<DataPushTaskLogRespVO> getLog(@RequestParam("id") Long id) {
        return success(taskService.getLog(id));
    }

    /**
     * 获得源表列清单。
     *
     * @param sourceDatabaseId 来源数据源编号
     * @param tableName 表名
     * @return 源表列清单结果集合
     */
    @GetMapping("/table-columns")
    @Operation(summary = "获得源表列清单")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<List<DataPushColumnRespVO>> getTableColumns(
            @RequestParam("sourceDatabaseId") Long sourceDatabaseId,
            @RequestParam("tableName") String tableName) {
        return success(taskService.getTableColumns(sourceDatabaseId, tableName));
    }

    /**
     * 解析 SQL 列清单。
     *
     * @param reqVO 请求参数
     * @return 解析 SQL 列清单结果集合
     */
    @PostMapping("/parse-sql-columns")
    @Operation(summary = "解析 SQL 列清单")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<List<DataPushColumnRespVO>> parseSqlColumns(
            @Valid @RequestBody DataPushParseSqlReqVO reqVO) {
        return success(taskService.parseSqlColumns(reqVO.getSourceDatabaseId(), reqVO.getSqlText()));
    }

    /**
     * 获得增量水位。
     *
     * @param id 记录编号
     * @return 数据推送水位响应数据
     */
    @GetMapping("/watermark")
    @Operation(summary = "获得增量水位")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    public CommonResult<DataPushWatermarkRespVO> getWatermark(@RequestParam("id") Long id) {
        return success(taskService.getWatermark(id));
    }

    /**
     * 重置增量水位。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    @PutMapping("/reset-watermark")
    @Operation(summary = "重置增量水位")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:run')")
    public CommonResult<Boolean> resetWatermark(@RequestParam("id") Long id) {
        taskService.resetWatermark(id);
        return success(true);
    }

    /**
     * 复制推送任务。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    @PostMapping("/copy")
    @BusinessOperateLog(type = "数据开发-数据推送任务", bizId = "#result")
    @Operation(summary = "复制推送任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:create')")
    public CommonResult<Long> copyTask(@RequestParam("id") Long id) {
        return success(taskService.copyTask(id));
    }

    /**
     * 试跑预览前 N 行（只读源端，不推送）。
     *
     * @param reqVO 请求参数
     * @return 数据推送预览响应数据
     */
    @PostMapping("/preview")
    @Operation(summary = "试跑预览前 N 行（只读源端，不推送）")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:query')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false)
    public CommonResult<DataPushPreviewRespVO> preview(@Valid @RequestBody DataPushPreviewReqVO reqVO) {
        return success(taskService.preview(reqVO));
    }

}
