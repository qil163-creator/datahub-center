package cn.iocoder.yudao.module.dataforge.controller.admin.dev;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordRespVO;
import cn.iocoder.yudao.module.dataforge.service.dev.DevRecordService;
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
 * 数据开发-任务执行实例 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据开发任务实例")
@RestController
@RequestMapping("/dataforge/dev-record")
@Validated
public class DevRecordController {

    @Resource
    private DevRecordService devRecordService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.dev.DevDependencyService dependencyService;

    /**
     * 获得任务实例分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 任务实例分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得任务实例分页")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<PageResult<DevRecordRespVO>> getRecordPage(@Valid DevRecordPageReqVO pageReqVO) {
        return success(BeanUtils.toBean(devRecordService.getRecordPage(pageReqVO), DevRecordRespVO.class));
    }

    /**
     * 获得当前平台任务执行实例统计。
     *
     * @param taskId 任务编号
     * @return 开发记录统计响应数据
     */
    @GetMapping("/statistics")
    @Operation(summary = "获得当前平台任务执行实例统计")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordStatisticsRespVO> getStatistics(
            @RequestParam(value = "taskId", required = false) Long taskId) {
        return success(devRecordService.getStatistics(taskId));
    }

    /**
     * 下载当前平台任务实例的已脱敏日志（有界）。
     *
     * @param id 记录编号
     * @return 响应实体字节
     */
    @GetMapping("/task-log-download")
    @Operation(summary = "下载当前平台任务实例的已脱敏日志（有界）")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(operateType = cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT)
    public org.springframework.http.ResponseEntity<byte[]> downloadTaskLog(@RequestParam("id") Long id) {
        byte[] body = devRecordService.downloadTaskLog(id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=dev-task-" + id + ".log")
                .contentType(new org.springframework.http.MediaType("text", "plain",
                        java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    /**
     * 获得任务实例（含日志，供运行中轮询）。
     *
     * @param id 记录编号
     * @return 开发记录响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得任务实例（含日志，供运行中轮询）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<DevRecordRespVO> getRecord(@RequestParam("id") Long id) {
        var record = devRecordService.getRecord(id);
        var response = BeanUtils.toBean(record, DevRecordRespVO.class);
        response.setDependencies(dependencyService.inspect(record));
        if ("WAITING".equals(record.getStatus())) {
            response.setWaitReason(
                    response.getDependencies().stream().anyMatch(item -> !Boolean.TRUE.equals(item.getSatisfied()))
                            ? "等待上游依赖，详见下方阻塞清单"
                            : "依赖已满足，等待执行资源或调度派发");
        }
        return success(response);
    }

    /**
     * 停止运行中的任务实例。
     *
     * @param id 记录编号
     * @return 开发记录
     */
    @PutMapping("/stop")
    @LogRecord(type = "数据开发-数据开发任务实例", subType = "停止运行中的任务实例", bizNo = "{{(#id) ?: 0}}",
            success = "对开发任务执行实例【{{(#id) ?: '未设置'}}】提交了停止请求", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "停止运行中的任务实例")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:run')")
    public CommonResult<Boolean> stopRecord(@RequestParam("id") Long id) {
        devRecordService.stopRecord(id);
        return success(true);
    }

    /**
     * 分页读取当前平台任务实例的已脱敏调度器日志。
     *
     * @param req 请求参数
     * @return 开发任务日志分页响应数据
     */
    @GetMapping("/task-log")
    @Operation(summary = "分页读取当前平台任务实例的已脱敏调度器日志")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageRespVO> getTaskLog(
            @Valid cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageReqVO req) {
        return success(devRecordService.getTaskLog(req));
    }

    /**
     * 删除任务实例。
     *
     * @param id 记录编号
     * @return 开发记录
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-数据开发任务实例", subType = "删除任务实例", bizNo = "{{(#id) ?: 0}}",
            success = "删除了开发任务执行实例【{{(#id) ?: '未设置'}}】及其执行记录", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除任务实例")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:delete')")
    public CommonResult<Boolean> deleteRecord(@RequestParam("id") Long id) {
        devRecordService.deleteRecord(id);
        return success(true);
    }

    /**
     * 记录未知实例的人工核查结论。
     *
     * @param req 请求参数
     * @return 开发记录
     */
    @PostMapping("/resolve-unknown")
    @LogRecord(type = "数据开发-数据开发任务实例", subType = "记录未知实例的人工核查结论", bizNo = "{{(#req.id) ?: 0}}",
            success = "人工核查了开发实例【{{(#req.id) ?: '未设置'}}】，状态从【{{(#req.expectedStatus) ?: '未设置'}}】收敛为【{{(#req.status) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "记录未知实例的人工核查结论")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:run')")
    public CommonResult<Boolean> resolveUnknown(@Valid @RequestBody
    cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordResolveReqVO req) {
        devRecordService.resolveUnknown(req);
        return success(true);
    }

    /**
     * 核实原质检已结束，将未知质量保守收敛为失败。
     *
     * @param req 请求参数
     * @return 开发记录
     */
    @PostMapping("/resolve-quality")
    @LogRecord(type = "数据开发-数据开发任务实例", subType = "核实原质检已结束，将未知质量保守收敛为失败", bizNo = "{{(#req.id) ?: 0}}",
            success = "核查了开发实例【{{(#req.id) ?: '未设置'}}】的未知质量状态【{{(#req.expectedStatus) ?: '未设置'}}】，按保守策略收敛为失败", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "核实原质检已结束，将未知质量保守收敛为失败")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:run')")
    public CommonResult<Boolean> resolveQuality(@Valid @RequestBody
    cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevQualityResolveReqVO req) {
        devRecordService.resolveQuality(req);
        return success(true);
    }

}
