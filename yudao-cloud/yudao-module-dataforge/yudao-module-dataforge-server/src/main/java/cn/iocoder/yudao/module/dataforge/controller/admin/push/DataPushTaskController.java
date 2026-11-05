package cn.iocoder.yudao.module.dataforge.controller.admin.push;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
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
    @LogRecord(type = "数据开发-数据推送任务", subType = "人工核查未知推送（仅未生效或完整补偿，停用排期且不改水位）", bizNo = "{{(#request.recordId) ?: 0}}",
            success = "人工核查了推送实例【{{(#request.recordId) ?: '未设置'}}】，核查结论【{{(#request.outcome) ?: '未设置'}}】，确认执行停止【{{(#request.confirmedStopped) ?: '未设置'}}】，确认输出核查【{{(#request.outputReviewed) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据开发-数据推送任务", subType = "创建推送任务", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了推送任务【{{(#createReqVO.name) ?: '未设置'}}】，推送模式【{{(#createReqVO.pushMode) ?: '未设置'}}】，源数据源【{{(#createReqVO.sourceDatabaseId) ?: '未设置'}}】，批量大小【{{(#createReqVO.batchSize) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据开发-数据推送任务", subType = "更新推送任务", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了推送任务【{{(#operateName) ?: '未设置'}}】，名称从【{{(#operateName) ?: '未设置'}}】改为【{{(#updateReqVO.name) ?: '未设置'}}】，推送模式【{{(#updateReqVO.pushMode) ?: '未设置'}}】，源数据源【{{(#updateReqVO.sourceDatabaseId) ?: '未设置'}}】，批量大小【{{(#updateReqVO.batchSize) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新推送任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:update')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false)
    public CommonResult<Boolean> updateTask(@Valid @RequestBody DataPushTaskSaveReqVO updateReqVO) {
        String operateName = "编号" + updateReqVO.getId();
        try {
            var operateObject = taskService.getTask(updateReqVO.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

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
    @LogRecord(type = "数据开发-数据推送任务", subType = "删除推送任务", bizNo = "{{(#id) ?: 0}}",
            success = "删除了推送任务【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除推送任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:delete')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
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
    @LogRecord(type = "数据开发-数据推送任务", subType = "发布任务", bizNo = "{{(#id) ?: 0}}",
            success = "发布了推送任务【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发布任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:release')")
    public CommonResult<Boolean> releaseTask(@RequestParam("id") Long id) {
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
    @LogRecord(type = "数据开发-数据推送任务", subType = "取消发布", bizNo = "{{(#id) ?: 0}}",
            success = "取消发布了推送任务【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "取消发布")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:release')")
    public CommonResult<Boolean> cancelTask(@RequestParam("id") Long id) {
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
    @LogRecord(type = "数据开发-数据推送任务", subType = "提交数据推送手动执行请求", bizNo = "{{(#id) ?: 0}}",
            success = "提交了推送任务的手工运行请求【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "手动执行一次")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:run')")
    public CommonResult<Boolean> handRun(@RequestParam("id") Long id) {
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
    @LogRecord(type = "数据开发-数据推送任务", subType = "提交数据推送停止执行请求", bizNo = "{{(#id) ?: 0}}",
            success = "提交了推送任务的停止请求【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "停止正在运行的任务")
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:run')")
    public CommonResult<Boolean> stopTask(@RequestParam("id") Long id) {
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
    @LogRecord(type = "数据开发-数据推送任务", subType = "复制推送任务", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "复制了推送任务【{{(#operateName) ?: '未设置'}}】，新任务编号【{{(#_ret.data) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "复制推送任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:data-push:create')")
    public CommonResult<Long> copyTask(@RequestParam("id") Long id) {
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
