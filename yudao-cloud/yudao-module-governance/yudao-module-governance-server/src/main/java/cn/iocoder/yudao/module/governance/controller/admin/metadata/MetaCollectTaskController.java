package cn.iocoder.yudao.module.governance.controller.admin.metadata;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.service.metadata.MetaCollectTaskService;
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
 * 管理后台 - 元数据采集任务
 *
 * @author Li
 */
@Tag(name = "管理后台 - 元数据采集任务")
@RestController
@RequestMapping("/governance/meta-collect-task")
@Validated
public class MetaCollectTaskController {

    @Resource
    private MetaCollectTaskService taskService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.metadata.MetaCollectDisplay display;

    /**
     * 创建采集任务（默认下线、调度停止，由发布/调度开关显式开启）
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-元数据采集任务", subType = "创建元数据采集任务",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建元数据采集任务",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建元数据采集任务")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:create')")
    public CommonResult<Long> createTask(@Valid @RequestBody MetaCollectTaskSaveReqVO createReqVO) {
        return success(taskService.createTask(createReqVO));
    }

    /**
     * 更新采集任务
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-元数据采集任务", subType = "更新元数据采集任务",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新元数据采集任务",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新元数据采集任务")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:update')")
    public CommonResult<Boolean> updateTask(@Valid @RequestBody MetaCollectTaskSaveReqVO updateReqVO) {
        taskService.updateTask(updateReqVO);
        return success(true);
    }

    /**
     * 删除采集任务（仅下线状态可删）
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-元数据采集任务", subType = "删除元数据采集任务",
            bizNo = "{{(#id) ?: 0}}", success = "删除元数据采集任务",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除元数据采集任务")
    @Parameter(name = "id", description = "任务编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:delete')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
        taskService.deleteTask(id);
        return success(true);
    }

    /**
     * 获取采集任务
     */
    @GetMapping("/get")
    @Operation(summary = "获得元数据采集任务")
    @Parameter(name = "id", description = "任务编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:query')")
    public CommonResult<MetaCollectTaskRespVO> getTask(@RequestParam("id") Long id) {
        return success(display.tasks(java.util.List.of(taskService.getTask(id))).getFirst());
    }

    /**
     * 采集任务分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得元数据采集任务分页")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:query')")
    public CommonResult<PageResult<MetaCollectTaskRespVO>> getTaskPage(
            @Valid MetaCollectTaskPageReqVO pageReqVO) {
        var page = taskService.getTaskPage(pageReqVO);
        return success(new PageResult<>(display.tasks(page.getList()), page.getTotal()));
    }

    /**
     * 任务上下线（发布/取消发布任务）
     */
    @PutMapping("/update-enabled")
    @LogRecord(type = "数据治理-元数据采集任务", subType = "任务上下线（发布/取消发布任务）",
            bizNo = "{{(#id) ?: 0}}", success = "任务上下线（发布/取消发布任务）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "任务上下线（发布/取消发布任务）")
    @Parameter(name = "id", description = "任务编号", required = true, example = "1")
    @Parameter(name = "enabled", description = "0-下线 1-上线", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:update')")
    public CommonResult<Boolean> updateEnabled(@RequestParam("id") Long id,
            @RequestParam("enabled") Integer enabled) {
        taskService.updateEnabled(id, enabled);
        return success(true);
    }

    /**
     * 调度启停（开启时校验 cron 并推进下次执行时间）
     */
    @PutMapping("/update-schedule-enabled")
    @LogRecord(type = "数据治理-元数据采集任务", subType = "调度启停",
            bizNo = "{{(#id) ?: 0}}", success = "调度启停",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "调度启停")
    @Parameter(name = "id", description = "任务编号", required = true, example = "1")
    @Parameter(name = "scheduleEnabled", description = "0-停止 1-启动", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:update')")
    public CommonResult<Boolean> updateScheduleEnabled(@RequestParam("id") Long id,
            @RequestParam("scheduleEnabled") Integer scheduleEnabled) {
        taskService.updateScheduleEnabled(id, scheduleEnabled);
        return success(true);
    }

    /**
     * 手动执行一次
     */
    @PostMapping("/run")
    @LogRecord(type = "数据治理-元数据采集任务", subType = "手动执行一次采集",
            bizNo = "{{(#id) ?: 0}}", success = "手动执行一次采集",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "手动执行一次采集")
    @Parameter(name = "id", description = "任务编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:run')")
    public CommonResult<Boolean> runOnce(@RequestParam("id") Long id) {
        taskService.runOnce(id);
        return success(true);
    }

    @Resource
    private cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectSources sources;

    /**
     * 获得可采集的连接与来源系统。
     */
    @GetMapping("/source-options")
    @Operation(summary = "获得可采集的连接与来源系统")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:query')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataSourceOptionsDTO> sourceOptions() {
        return success(sources.options());
    }

    /**
     * 读取当前连接的库表范围。
     */
    @GetMapping("/scope-list")
    @Operation(summary = "读取当前连接的库表范围")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:query')")
    public CommonResult<java.util.List<cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectScopeRespVO>> scopeList(
            @RequestParam("datasourceId") Long id) {
        return success(sources.scopeList(id));
    }

    /**
     * 批量删除采集任务。
     */
    @DeleteMapping("/delete-list")
    @LogRecord(type = "数据治理-元数据采集任务", subType = "批量删除采集任务",
            bizNo = "{{(#ids) ?: 0}}", success = "批量删除采集任务",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除采集任务")
    @PreAuthorize("@ss.hasPermission('governance:meta-collect-task:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam("ids") java.util.List<Long> ids) {
        taskService.deleteTasks(ids);
        return success(true);
    }

}
