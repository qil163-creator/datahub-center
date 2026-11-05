package cn.iocoder.yudao.module.dataforge.controller.admin.dev;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.debug.DevTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.*;
import cn.iocoder.yudao.module.dataforge.service.dev.DevTaskService;
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
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

/**
 * 数据开发-开发任务 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据开发任务")
@RestController
@RequestMapping("/dataforge/dev-task")
@Validated
public class DevTaskController {

    @Resource
    private DevTaskService taskService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.dev.DevRecordService devRecordService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.dev.DevNativeRuntimeSupport nativeRuntimeSupport;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.dev.DevScheduleCoordinator scheduleCoordinator;

    /**
     * 获得当前平台可用的受管原生运行环境。
     *
     * @return 当前平台可用的受管原生运行环境结果集合
     */
    @GetMapping("/runtime-profiles")
    @Operation(summary = "获得当前平台可用的受管原生运行环境")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<List<DevRuntimeProfileRespVO>> runtimeProfiles() {
        return success(nativeRuntimeSupport.profiles());
    }

    /**
     * 创建开发任务。
     *
     * @param createReqVO 创建参数
     * @return 开发任务
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-数据开发任务", subType = "创建开发任务", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建开发任务", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建开发任务")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:save')")
    public CommonResult<Long> createTask(@Valid @RequestBody DevTaskSaveReqVO createReqVO) {
        return success(taskService.createTask(createReqVO));
    }

    /**
     * 保存开发任务并立即生成当前运行版本。
     *
     * @param updateReqVO 更新参数
     * @return 开发任务
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-数据开发任务", subType = "保存开发任务并立即生成当前运行版本", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "保存开发任务并立即生成当前运行版本", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "保存开发任务并立即生成当前运行版本")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:save')")
    public CommonResult<Boolean> updateTask(@Valid @RequestBody DevTaskSaveReqVO updateReqVO) {
        taskService.updateTask(updateReqVO);
        return success(true);
    }

    /**
     * 删除开发任务。
     *
     * @param id 记录编号
     * @return 开发任务
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-数据开发任务", subType = "删除开发任务", bizNo = "{{(#id) ?: 0}}",
            success = "删除开发任务", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除开发任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:delete')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
        taskService.deleteTask(id);
        return success(true);
    }

    /**
     * 获得开发任务。
     *
     * @param id 记录编号
     * @return 开发任务响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得开发任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<DevTaskRespVO> getTask(@RequestParam("id") Long id) {
        var response = toResponse(taskService.getTask(id));
        var latest = devRecordService.getLatestRecords(List.of(id)).get(id);
        response.setLatestRecord(BeanUtils.toBean(latest,
                cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordRespVO.class));
        return success(response);
    }

    /**
     * 获得开发任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 开发任务分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得开发任务分页")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<PageResult<DevTaskRespVO>> getTaskPage(@Valid DevTaskPageReqVO pageReqVO) {
        PageResult<cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO> page = taskService.getTaskPage(
                pageReqVO);
        var latest = devRecordService.getLatestRecords(convertList(page.getList(),
                cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO::getId));
        var responses = convertList(page.getList(), this::toResponse);
        responses.forEach(response -> response.setLatestRecord(BeanUtils.toBean(latest.get(response.getId()),
                cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordRespVO.class)));
        return success(new PageResult<>(responses, page.getTotal()));
    }

    /**
     * 获得当前平台的数据开发运行统计。
     *
     * @return 统计
     */
    @GetMapping("/statistics")
    @Operation(summary = "获得当前平台的数据开发运行统计")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<DevTaskStatisticsRespVO> getStatistics(
            @RequestParam(value = "workloadMode", required = false)
            @jakarta.validation.constraints.Pattern(regexp = "BATCH|STREAMING") String workloadMode) {
        return success(taskService.getStatistics(workloadMode));
    }

    /**
     * 预览 Cron 接下来五次运行时间。
     *
     * @param cronExpression Cron表达式
     * @return  Cron 接下来五次运行时间预览结果集合
     */
    @GetMapping("/cron-preview")
    @Operation(summary = "预览 Cron 接下来五次运行时间")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<List<java.time.LocalDateTime>> previewCron(
            @RequestParam("cronExpression") String cronExpression) {
        return success(taskService.previewCron(cronExpression));
    }

    /**
     * 按目录获得开发任务列表（资源树用，含子目录）。
     *
     * @param dirId 目录编号
     * @return 按目录获得开发任务列表（资源树用，含子目录）结果集合
     */
    @GetMapping("/list-by-dir")
    @Operation(summary = "按目录获得开发任务列表（资源树用，含子目录）")
    @Parameter(name = "dirId", description = "目录编号，空则查询全部")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<List<DevTaskRespVO>> getTaskListByDir(
            @RequestParam(value = "dirId", required = false) Long dirId,
            @RequestParam(value = "workloadMode", required = false)
            @jakarta.validation.constraints.Pattern(regexp = "BATCH|STREAMING") String workloadMode) {
        return success(convertList(taskService.getTaskListByDir(dirId, workloadMode), this::toResponse));
    }

    /**
     * 更新已保存任务的启用状态。
     *
     * @param id 记录编号
     * @param status 目标状态
     * @return 开发任务
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据开发-数据开发任务", subType = "更新已保存任务的启用状态", bizNo = "{{(#id) ?: 0}}",
            success = "更新已保存任务的启用状态", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新已保存任务的启用状态")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:save')")
    public CommonResult<Boolean> updateTaskStatus(@RequestParam("id") Long id,
            @RequestParam("status") Integer status) {
        taskService.updateTaskStatus(id, status);
        return success(true);
    }

    /**
     * 更新任务调度设置。
     *
     * @param reqVO 请求参数
     * @return 开发任务
     */
    @PutMapping("/update-schedule")
    @LogRecord(type = "数据开发-数据开发任务", subType = "更新任务调度设置", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "更新任务调度设置", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新任务调度设置")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:run')")
    public CommonResult<Boolean> updateTaskSchedule(@Valid @RequestBody DevTaskScheduleReqVO reqVO) {
        taskService.updateTaskSchedule(reqVO);
        return success(true);
    }

    /**
     * 手动运行任务（生成执行实例）。
     *
     * @param reqVO 请求参数
     * @return 开发任务
     */
    @PostMapping("/run")
    @LogRecord(type = "数据开发-数据开发任务", subType = "手动运行任务（生成执行实例）", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "手动运行任务（生成执行实例）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "手动运行任务（生成执行实例）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:run')")
    public CommonResult<Long> runTask(@Valid @RequestBody DevTaskRunReqVO reqVO) {
        return success(taskService.runTask(reqVO));
    }

    /**
     * 获得表字段元数据（编辑器补全用）。
     *
     * @param databaseId 数据源编号
     * @param tableName 表名
     * @return 表字段元数据（编辑器补全用）结果集合
     */
    @GetMapping("/column-list")
    @Operation(summary = "获得表字段元数据（编辑器补全用）")
    @Parameter(name = "databaseId", description = "数据源编号", required = true)
    @Parameter(name = "tableName", description = "表名", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<List<DevTableColumnRespVO>> getTableColumns(
            @RequestParam("databaseId") Long databaseId,
            @RequestParam("tableName") String tableName) {
        return success(taskService.getTableColumns(databaseId, tableName));
    }

    /**
     * 获得任务版本列表。
     *
     * @param taskId 任务编号
     * @return 任务版本列表结果集合
     */
    @GetMapping("/version/list")
    @Operation(summary = "获得任务版本列表")
    @Parameter(name = "taskId", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<List<DevTaskVersionRespVO>> getTaskVersionList(@RequestParam("taskId") Long taskId) {
        return success(convertList(taskService.getTaskVersionList(taskId),
                version -> BeanUtils.toBean(version, DevTaskVersionRespVO.class)));
    }

    /**
     * 回滚到指定版本。
     *
     * @param versionId 版本编号
     * @param expectedRevision 预期修订号
     * @return 开发任务
     */
    @PutMapping("/version/rollback")
    @LogRecord(type = "数据开发-数据开发任务", subType = "回滚到指定版本", bizNo = "{{(#versionId) ?: 0}}",
            success = "回滚到指定版本", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "回滚到指定版本")
    @Parameter(name = "versionId", description = "版本编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:save')")
    public CommonResult<Boolean> rollbackVersion(@RequestParam("versionId") Long versionId,
            @RequestParam("expectedRevision") Integer expectedRevision) {
        taskService.rollbackVersion(versionId, expectedRevision);
        return success(true);
    }

    /**
     * 转换为响应。
     *
     * @param task 任务记录
     * @return 开发任务响应数据
     */
    private DevTaskRespVO toResponse(cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO task) {
        DevTaskRespVO response = BeanUtils.toBean(task, DevTaskRespVO.class);
        var type = cn.iocoder.yudao.module.dataforge.service.dev.DevTaskDefinition.requireType(task.getTaskType());
        response.setEngine(type.getEngine());
        response.setProgramType(type.getProgramType());
        response.setProgramConfig(cn.iocoder.yudao.module.dataforge.service.dev.DevTaskDefinition.readConfig(
                task.getProgramConfigJson()));
        response.setExecutionConfig(cn.iocoder.yudao.module.dataforge.service.dev.DevTaskDefinition.readExecutionConfig(
                task.getExecutionConfigJson()));
        response.setWorkloadMode(java.util.Objects.requireNonNullElse(response.getExecutionConfig().getWorkloadMode(),
                response.getProgramConfig() == null || response.getProgramConfig().getWorkloadMode() == null
                        ? "BATCH" : response.getProgramConfig().getWorkloadMode()));
        if (response.getScheduler() == null) {
            response.setScheduler("DOLPHIN_SCHEDULER");
        }
        response.setExecutionCapability(
                cn.iocoder.yudao.module.dataforge.service.dev.DevNativeRuntimeSupport.isNative(task)
                        ? nativeRuntimeSupport.capability(
                        task) : cn.iocoder.yudao.module.dataforge.service.dev.DevTaskDefinition.capability(task));
        if ("NOT_READY".equals(response.getExecutionCapability())) {
            response.setExecutionCapabilityReason("请完善任务内容和受管运行配置；配置未就绪时不能运行");
        }
        response.setDependencyTaskIds(cn.hutool.core.util.StrUtil.isBlank(task.getDependencyTaskIdsJson())
                ? List.of() : cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(
                task.getDependencyTaskIdsJson(), Long.class));
        var scheduleState = scheduleCoordinator.state(task.getId());
        response.setScheduleSyncStatus(scheduleState.status());
        response.setScheduleSyncReason(scheduleState.reason());
        return response;
    }

}
