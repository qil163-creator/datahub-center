package cn.iocoder.yudao.module.dataforge.controller.admin.job;
import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.OwnerResp;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.InstanceResp;
import cn.iocoder.yudao.module.dataforge.service.job.JobService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
/** 管理端作业编排接口，不接受外部调度器编号或执行身份。 */
@RestController @Validated @RequestMapping("/dataforge/job") @Tag(name="管理后台 - 作业管理")
public class JobController {
    @Resource private JobService service;
    @GetMapping("/page") @Operation(summary="查询作业分页") @PreAuthorize("@ss.hasPermission('dataforge:job:query')")
    public CommonResult<PageResult<TaskResp>> page(@Valid PageReq req) { return success(service.page(req)); }
    @GetMapping("/get") @Operation(summary="查询作业详情与配置") @PreAuthorize("@ss.hasPermission('dataforge:job:query')")
    public CommonResult<TaskResp> get(@RequestParam Long id) { return success(service.get(id)); }
    @PostMapping("/create") @LogRecord(type = "数据开发-作业管理", subType = "新增作业", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "新增作业", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="新增作业") @PreAuthorize("@ss.hasPermission('dataforge:job:create')")
    public CommonResult<Long> create(@RequestBody @Valid SaveReq req) { return success(service.create(req)); }
    @PutMapping("/update") @LogRecord(type = "数据开发-作业管理", subType = "保存作业配置", bizNo = "{{(#req.id) ?: 0}}",
            success = "保存作业配置", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="保存作业配置") @PreAuthorize("@ss.hasPermission('dataforge:job:update')")
    public CommonResult<Boolean> update(@RequestBody @Valid SaveReq req) { service.update(req);return success(true); }
    @PostMapping("/clone") @LogRecord(type = "数据开发-作业管理", subType = "克隆为未上线作业", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "克隆为未上线作业", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="克隆为未上线作业") @PreAuthorize("@ss.hasPermission('dataforge:job:create')")
    public CommonResult<Long> cloneTask(@RequestParam Long id) { return success(service.cloneTask(id)); }
    @DeleteMapping("/delete") @LogRecord(type = "数据开发-作业管理", subType = "删除已下线作业", bizNo = "{{(#id) ?: 0}}",
            success = "删除已下线作业", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="删除已下线作业") @PreAuthorize("@ss.hasPermission('dataforge:job:delete')")
    public CommonResult<Boolean> delete(@RequestParam Long id,@RequestParam Integer revision) { service.delete(id,revision);return success(true); }
    @PutMapping("/status") @LogRecord(type = "数据开发-作业管理", subType = "切换作业上线状态", bizNo = "{{(#req.id) ?: 0}}",
            success = "切换作业上线状态", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="切换作业上线状态") @PreAuthorize("@ss.hasPermission('dataforge:job:update')")
    public CommonResult<Boolean> status(@RequestBody @Valid StatusReq req) { service.status(req);return success(true); }
    @PutMapping("/schedule") @LogRecord(type = "数据开发-作业管理", subType = "配置受管调度", bizNo = "{{(#req.id) ?: 0}}",
            success = "配置受管调度", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="配置受管调度") @PreAuthorize("@ss.hasPermission('dataforge:job:update')")
    public CommonResult<Boolean> schedule(@RequestBody @Valid ScheduleReq req) { service.schedule(req);return success(true); }
    @GetMapping("/cron-preview") @Operation(summary="预览上海时区下次执行时间") @PreAuthorize("@ss.hasPermission('dataforge:job:query')")
    public CommonResult<List<String>> cronPreview(@RequestParam String cronExpression) { return success(service.cronPreview(cronExpression)); }
    @GetMapping("/categories") @Operation(summary="查询作业类目树") @PreAuthorize("@ss.hasPermission('dataforge:job:query')")
    public CommonResult<List<CategoryResp>> categories() { return success(service.categories()); }
    @PostMapping("/category/create") @LogRecord(type = "数据开发-作业管理", subType = "新增作业类目", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "新增作业类目", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="新增作业类目") @PreAuthorize("@ss.hasPermission('dataforge:job:create')")
    public CommonResult<Long> createCategory(@RequestBody @Valid CategoryReq req) { return success(service.category(req,false)); }
    @PutMapping("/category/update") @LogRecord(type = "数据开发-作业管理", subType = "编辑作业类目", bizNo = "{{(#req.id) ?: 0}}",
            success = "编辑作业类目", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="编辑作业类目") @PreAuthorize("@ss.hasPermission('dataforge:job:update')")
    public CommonResult<Boolean> updateCategory(@RequestBody @Valid CategoryReq req) { service.category(req,true);return success(true); }
    @DeleteMapping("/category/delete") @LogRecord(type = "数据开发-作业管理", subType = "删除空作业类目", bizNo = "{{(#id) ?: 0}}",
            success = "删除空作业类目", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="删除空作业类目") @PreAuthorize("@ss.hasPermission('dataforge:job:delete')")
    public CommonResult<Boolean> deleteCategory(@RequestParam Long id) { service.deleteCategory(id);return success(true); }
    @GetMapping("/owners") @Operation(summary="查询作业负责人") @PreAuthorize("@ss.hasPermission('dataforge:job:query')")
    public CommonResult<List<OwnerResp>> owners() { return success(service.owners()); }
    @GetMapping("/task-options") @Operation(summary="查询可编排的集成与开发任务") @PreAuthorize("@ss.hasPermission('dataforge:job:query')")
    public CommonResult<List<TaskOption>> taskOptions() { return success(service.taskOptions()); }
    @PostMapping("/check") @Operation(summary="检查流程结构与可执行能力") @PreAuthorize("@ss.hasPermission('dataforge:job:query')")
    public CommonResult<CheckResp> check(@RequestBody @Valid FlowReq req) { return success(service.checkFlow(req.getFlowJson())); }
    @PostMapping("/run") @LogRecord(type = "数据开发-作业管理", subType = "提交受管作业并保留真实执行记录", bizNo = "{{(#req.id) ?: 0}}",
            success = "提交受管作业并保留真实执行记录", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="提交受管作业并保留真实执行记录") @PreAuthorize("@ss.hasPermission('dataforge:job:run')")
    public CommonResult<Long> run(@RequestBody @Valid RunReq req) { return success(service.run(req)); }
    @PostMapping("/stop") @LogRecord(type = "数据开发-作业管理", subType = "请求停止本平台作业实例", bizNo = "{{(#id) ?: 0}}",
            success = "请求停止本平台作业实例", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary="请求停止本平台作业实例") @PreAuthorize("@ss.hasPermission('dataforge:job:run')")
    public CommonResult<Boolean> stop(@RequestParam Long id) { service.stop(id);return success(true); }
    @GetMapping("/records") @Operation(summary="查询作业历史与真实执行实例") @PreAuthorize("@ss.hasPermission('dataforge:job:query')")
    public CommonResult<PageResult<InstanceResp>> records(@Valid RecordPageReq req) { return success(service.recordPage(req)); }
}
