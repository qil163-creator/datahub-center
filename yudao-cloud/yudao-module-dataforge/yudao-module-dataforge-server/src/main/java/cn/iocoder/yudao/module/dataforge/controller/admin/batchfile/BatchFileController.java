package cn.iocoder.yudao.module.dataforge.controller.admin.batchfile;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum;
import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.service.batchfile.BatchFileService;
import cn.iocoder.yudao.module.dataforge.service.batchfile.BatchFileRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - 批量文件入库")
@RestController
@RequestMapping("/dataforge/batch-file")
@Validated
public class BatchFileController {

    @Resource
    private BatchFileService service;

    @Resource
    private BatchFileRunService runs;

    /**
     * 获得平台批量文件任务分页。
     *
     * @param request 请求参数
     * @return 平台批量文件任务分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得平台批量文件任务分页")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:query')")
    public CommonResult<PageResult<Task>> page(@Valid Page request) {
        return success(service.page(request));
    }

    /**
     * 获得入库实例统计。
     *
     * @return 汇总
     */
    @GetMapping("/summary")
    @Operation(summary = "获得入库实例统计")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:query')")
    public CommonResult<Summary> summary() {
        return success(service.summary());
    }

    /**
     * 获得任务详情。
     *
     * @param id 记录编号
     * @return 任务
     */
    @GetMapping("/get")
    @Operation(summary = "获得任务详情")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:query')")
    public CommonResult<Task> get(@RequestParam Long id) {
        return success(service.get(id));
    }

    /**
     * 获得目标数据源、负责人及文件限额。
     *
     * @return 选项
     */
    @GetMapping("/options")
    @Operation(summary = "获得目标数据源、负责人及文件限额")
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:batch-file:query','dataforge:batch-file:create','dataforge:batch-file:update')")
    public CommonResult<Options> options() {
        return success(service.options());
    }

    /**
     * 上传并解析当前用户的文件。
     *
     * @param file 文件
     * @return 文件
     */
    @PostMapping("/upload")
    @LogRecord(type = "数据开发-批量文件入库", subType = "上传并解析当前用户的文件", bizNo = "{{(#_ret.data.id) ?: 0}}",
            success = "上传并登记了批量入库文件【{{(#_ret.data.name) ?: '未设置'}}】，素材编号【{{(#_ret.data.id) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "上传并解析当前用户的文件")
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:batch-file:create','dataforge:batch-file:update')")
    @ApiAccessLog(operateType = OperateTypeEnum.IMPORT, requestEnable = false)
    public CommonResult<File> upload(@RequestParam("file") MultipartFile file) {
        return success(service.upload(file, getLoginUserId()));
    }

    /**
     * 选择已授权的系统文件并解析。
     *
     * @param request 请求参数
     * @return 文件
     */
    @PostMapping("/system-file")
    @LogRecord(type = "数据开发-批量文件入库", subType = "选择已授权的系统文件并解析", bizNo = "{{(#_ret.data.id) ?: 0}}",
            success = "从文件连接【{{(#request.connectionId) ?: '未设置'}}】选择并登记了入库文件【{{(#_ret.data.name) ?: '未设置'}}】，素材编号【{{(#_ret.data.id) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "选择已授权的系统文件并解析")
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:batch-file:create','dataforge:batch-file:update')")
    @ApiAccessLog(operateType = OperateTypeEnum.IMPORT, requestEnable = false)
    public CommonResult<File> systemFile(@Valid @RequestBody SystemFile request) {
        return success(service.systemFile(request, getLoginUserId()));
    }

    /**
     * 移除本人未绑定素材。
     *
     * @param id 记录编号
     * @return 批次文件
     */
    @DeleteMapping("/upload")
    @LogRecord(type = "数据开发-批量文件入库", subType = "移除本人未绑定素材", bizNo = "{{(#id) ?: 0}}",
            success = "移除了本人尚未绑定任务的入库素材，素材编号【{{(#id) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "移除本人未绑定素材")
    @PreAuthorize("@ss.hasAnyPermissions('dataforge:batch-file:create','dataforge:batch-file:update')")
    public CommonResult<Boolean> removeUpload(@RequestParam Long id) {
        service.removeUpload(id, getLoginUserId());
        return success(true);
    }

    /**
     * 保存四步入库配置，不执行数据写入。
     *
     * @param request 请求参数
     * @return 批次文件
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-批量文件入库", subType = "保存四步入库配置，不执行数据写入", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了批量文件入库任务【{{(#request.taskName) ?: '未设置'}}】，目标数据源【{{(#request.targetDatabaseId) ?: '未设置'}}】，文件数【{{(#request.files.size()) ?: '未设置'}}】，目标表数【{{(#request.targets.size()) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "保存四步入库配置，不执行数据写入")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:create')")
    public CommonResult<Long> create(@Valid @RequestBody Save request) {
        return success(service.save(request, getLoginUserId(), true));
    }

    /**
     * 修改已下线且无活动运行的任务。
     *
     * @param request 请求参数
     * @return 批次文件
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-批量文件入库", subType = "修改已下线且无活动运行的任务", bizNo = "{{(#request.id) ?: 0}}",
            success = "修改了批量文件入库任务【{{(#operateName) ?: '未设置'}}】，名称从【{{(#operateName) ?: '未设置'}}】改为【{{(#request.taskName) ?: '未设置'}}】，目标数据源【{{(#request.targetDatabaseId) ?: '未设置'}}】，文件数【{{(#request.files.size()) ?: '未设置'}}】，目标表数【{{(#request.targets.size()) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改已下线且无活动运行的任务")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody Save request) {
        String operateName = "编号" + request.getId();
        try {
            var operateObject = service.get(request.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getTaskName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        service.save(request, getLoginUserId(), false);
        return success(true);
    }

    /**
     * 删除已下线任务。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @return 批次文件
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-批量文件入库", subType = "删除已下线任务", bizNo = "{{(#id) ?: 0}}",
            success = "删除了批量文件入库任务【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除已下线任务")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:delete')")
    public CommonResult<Boolean> delete(@RequestParam Long id, @RequestParam Long revision) {
        String operateName = "编号" + id;
        try {
            var operateObject = service.get(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getTaskName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        service.delete(id, revision, getLoginUserId());
        return success(true);
    }

    /**
     * 修改上线状态。
     *
     * @param request 请求参数
     * @return 批次文件
     */
    @PutMapping("/status")
    @LogRecord(type = "数据开发-批量文件入库", subType = "修改上线状态", bizNo = "{{(#request.id) ?: 0}}",
            success = "将批量文件入库任务【{{(#operateName) ?: '未设置'}}】的上线状态从【{{(#operateOnline) ?: '未设置'}}】改为【{{(#request.online) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "修改上线状态")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:update')")
    public CommonResult<Boolean> status(@Valid @RequestBody Status request) {
        String operateName = "编号" + request.getId();
        LogRecordContext.putVariable("operateOnline", "未读取");
        try {
            var operateObject = service.get(request.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getTaskName(), operateName);
                LogRecordContext.putVariable("operateOnline", operateObject.getOnline());
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        service.status(request, getLoginUserId());
        return success(true);
    }

    /**
     * 幂等排队执行一次。
     *
     * @param request 请求参数
     * @return 回执
     */
    @PostMapping("/execute")
    @LogRecord(type = "数据开发-批量文件入库", subType = "幂等排队执行一次", bizNo = "{{(#request.id) ?: 0}}",
            success = "提交了批量文件入库任务【{{(#operateName) ?: '未设置'}}】的执行请求，返回实例【{{(#_ret.data.id) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "幂等排队执行一次")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:execute')")
    public CommonResult<Receipt> execute(@Valid @RequestBody Execute request) {
        String operateName = "编号" + request.getId();
        try {
            var operateObject = service.get(request.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getTaskName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        return success(runs.execute(request, getLoginUserId()));
    }

    /**
     * 获得任务运行实例分页。
     *
     * @param request 请求参数
     * @return 任务运行实例分页结果
     */
    @GetMapping("/run-page")
    @Operation(summary = "获得任务运行实例分页")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:query')")
    public CommonResult<PageResult<Run>> runPage(@Valid RunPage request) {
        return success(runs.page(request));
    }

    /**
     * 获得实例及逐文件结果。
     *
     * @param id 记录编号
     * @return 运行
     */
    @GetMapping("/run")
    @Operation(summary = "获得实例及逐文件结果")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:query')")
    public CommonResult<Run> run(@RequestParam Long id) {
        return success(runs.get(id));
    }

    /**
     * 人工核查未知结果并解除任务占用。
     *
     * @param request 请求参数
     * @return 批次文件
     */
    @PostMapping("/resolve")
    @LogRecord(type = "数据开发-批量文件入库", subType = "人工核查未知结果并解除任务占用", bizNo = "{{(#request.id) ?: 0}}",
            success = "人工核查了批量文件入库实例【{{(#request.id) ?: '未设置'}}】，确认执行已停止【{{(#request.confirmedStopped) ?: '未设置'}}】，确认输出已核查【{{(#request.outputReviewed) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "人工核查未知结果并解除任务占用")
    @PreAuthorize("@ss.hasPermission('dataforge:batch-file:execute')")
    public CommonResult<Boolean> resolve(@Valid @RequestBody Resolve request) {
        runs.resolve(request, getLoginUserId());
        return success(true);
    }
}

