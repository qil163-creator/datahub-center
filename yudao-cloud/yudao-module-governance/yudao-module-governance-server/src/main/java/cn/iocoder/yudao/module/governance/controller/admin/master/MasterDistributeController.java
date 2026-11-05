package cn.iocoder.yudao.module.governance.controller.admin.master;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDistributeService;
import cn.iocoder.yudao.module.governance.service.master.MasterDistributeRunReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据派发")
@RestController
@RequestMapping("/governance/master-distribute")
@Validated
public class MasterDistributeController {

    @Resource
    private MasterDistributeService distributeService;

    @Resource
    private MasterDistributeRunReviewService reviewService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.master.MasterModelService modelService;

    /**
     * 获得可推送的已发布模型及字段。
     */
    @GetMapping("/model")
    @Operation(summary = "获得可推送的已发布模型及字段")
    @PreAuthorize("@ss.hasAnyPermissions('governance:master-distribute:query', 'governance:master-distribute:create', 'governance:master-distribute:update')")
    public CommonResult<MasterModelRespVO> model(@RequestParam("id") Long id) {
        return success(modelService.getMaintenanceModel(id));
    }

    /**
     * 获得可推送的已发布模型列表。
     */
    @GetMapping("/model-list")
    @Operation(summary = "获得可推送的已发布模型列表")
    @PreAuthorize("@ss.hasAnyPermissions('governance:master-distribute:query', 'governance:master-distribute:create', 'governance:master-distribute:update')")
    public CommonResult<java.util.List<MasterModelRespVO>> models() {
        return success(modelService.getReleasedModelList());
    }

    /**
     * 启用或停用 REST 推送（不发送数据）。
     */
    @PutMapping("/update-status")
    @LogRecord(type = "数据治理-主数据派发", subType = "启用或停用 REST 推送（不发送数据）",
            bizNo = "{{(#id) ?: 0}}", success = "调整了主数据派发任务【{{(#auditObjectName) ?: '编号' + #id}}】的状态，从【{{(#auditBeforeStatus) ?: '未设置'}}】变为【{{(#status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "启用或停用 REST 推送（不发送数据）")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:release')")
    public CommonResult<Boolean> updateStatus(@RequestParam("id") Long id, @RequestParam("status") Integer status) {
        captureAuditContext(id);
        distributeService.updateStatus(id, status);
        return success(true);
    }

    /**
     * 重试最近一次发送前失败的推送。
     */
    @PutMapping("/retry")
    @LogRecord(type = "数据治理-主数据派发", subType = "重试最近一次发送前失败的推送",
            bizNo = "{{(#id) ?: 0}}", success = "提交了派发任务【{{(#auditObjectName) ?: '编号' + #id}}】最近失败运行的重试请求",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "重试最近一次发送前失败的推送")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:run')")
    public CommonResult<Boolean> retry(@RequestParam("id") Long id) {
        captureAuditContext(id);
        distributeService.retry(id);
        return success(true);
    }

    /**
     * 人工核查未知派发结果并停用排期（不发送数据）。
     */
    @PutMapping("/resolve")
    @LogRecord(type = "数据治理-主数据派发", subType = "人工核查未知派发结果并停用排期（不发送数据）",
            bizNo = "{{(#request.recordId) ?: 0}}", success = "核查了派发运行【{{(#request.recordId) ?: '未设置'}}】的未知结果，结论【{{(#request.outcome) ?: '未设置'}}】，并停用了该任务排期",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "人工核查未知派发结果并停用排期（不发送数据）")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:resolve')")
    public CommonResult<MasterDistributeLogRespVO> resolve(@Valid @RequestBody MasterDistributeResolveReqVO request) {
        return success(reviewService.resolve(request));
    }

    /**
     * 创建派发任务
     */
    @PostMapping("/create")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false)
    @LogRecord(type = "数据治理-主数据派发", subType = "创建主数据派发任务",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了主数据派发任务【{{(#createReqVO.name) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建主数据派发任务")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:create')")
    public CommonResult<Long> createDistribute(@Valid @RequestBody MasterDistributeSaveReqVO createReqVO) {
        return success(distributeService.createDistribute(createReqVO));
    }

    /**
     * 更新派发任务（已发布不可修改）
     */
    @PutMapping("/update")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false)
    @LogRecord(type = "数据治理-主数据派发", subType = "更新主数据派发任务",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了主数据派发任务，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.name) ?: '未设置'}}】，目标状态【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新主数据派发任务")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:update')")
    public CommonResult<Boolean> updateDistribute(@Valid @RequestBody MasterDistributeSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        distributeService.updateDistribute(updateReqVO);
        return success(true);
    }

    /**
     * 删除派发任务（已发布不可删除）
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-主数据派发", subType = "删除主数据派发任务",
            bizNo = "{{(#id) ?: 0}}", success = "删除了主数据派发任务【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除主数据派发任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:delete')")
    public CommonResult<Boolean> deleteDistribute(@RequestParam("id") Long id) {
        captureAuditContext(id);
        distributeService.deleteDistribute(id);
        return success(true);
    }

    /**
     * 获得派发任务
     */
    @GetMapping("/get")
    @Operation(summary = "获得主数据派发任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:query')")
    public CommonResult<MasterDistributeRespVO> getDistribute(@RequestParam("id") Long id) {
        return success(distributeService.getDistribute(id));
    }

    /**
     * 获得派发任务分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得主数据派发任务分页")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:query')")
    public CommonResult<PageResult<MasterDistributeRespVO>> getDistributePage(
            @Valid MasterDistributePageReqVO pageReqVO) {
        return success(distributeService.getDistributePage(pageReqVO));
    }

    /**
     * 发布：一次性任务立即执行一次，周期任务按 Cron 排期
     */
    @PutMapping("/release")
    @LogRecord(type = "数据治理-主数据派发", subType = "发布派发任务（一次性任务立即执行，周期任务开始排期）",
            bizNo = "{{(#id) ?: 0}}", success = "提交了主数据派发任务【{{(#auditObjectName) ?: '编号' + #id}}】的发布请求，一次性任务进入执行、周期任务进入排期",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发布派发任务（一次性任务立即执行，周期任务开始排期）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:release')")
    public CommonResult<Boolean> releaseDistribute(@RequestParam("id") Long id) {
        captureAuditContext(id);
        distributeService.releaseDistribute(id);
        return success(true);
    }

    /**
     * 取消发布：停止后续排期，发送前取消，发送后保留未知结果。
     */
    @PutMapping("/cancel-release")
    @LogRecord(type = "数据治理-主数据派发", subType = "取消发布派发任务",
            bizNo = "{{(#id) ?: 0}}", success = "取消发布了主数据派发任务【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "取消发布派发任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:release')")
    public CommonResult<Boolean> cancelDistribute(@RequestParam("id") Long id) {
        captureAuditContext(id);
        distributeService.cancelDistribute(id);
        return success(true);
    }

    /**
     * 手动执行一次（无论是否发布）
     */
    @PutMapping("/hand-run")
    @LogRecord(type = "数据治理-主数据派发", subType = "手动执行一次派发",
            bizNo = "{{(#id) ?: 0}}", success = "发起了主数据派发任务【{{(#auditObjectName) ?: '编号' + #id}}】的一次手动执行",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "手动执行一次派发")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:run')")
    public CommonResult<Boolean> handRun(@RequestParam("id") Long id) {
        captureAuditContext(id);
        distributeService.handRun(id);
        return success(true);
    }

    /**
     * 停止指定运行，发布及周期排期保持不变。平台内拥有运行权限的管理员可操作。
     */
    @PutMapping("/stop")
    @LogRecord(type = "数据治理-主数据派发", subType = "停止指定派发运行（不改变发布及排期，发送后结果仍须核查）",
            bizNo = "{{(#request.recordId) ?: 0}}", success = "提交了派发运行【{{(#request.recordId) ?: '未设置'}}】的停止请求，未更改任务发布状态与排期",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "停止指定派发运行（不改变发布及排期，发送后结果仍须核查）")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:run')")
    public CommonResult<MasterDistributeLogRespVO> stopRun(@Valid @RequestBody MasterDistributeStopReqVO request) {
        return success(distributeService.stopRun(request));
    }

    /**
     * 获得执行记录分页
     */
    @GetMapping("/log-page")
    @Operation(summary = "获得派发执行记录分页")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:query')")
    public CommonResult<PageResult<MasterDistributeLogRespVO>> getDistributeLogPage(
            @Valid MasterDistributeLogPageReqVO pageReqVO) {
        return success(distributeService.getDistributeLogPage(pageReqVO));
    }

    /**
     * 获得执行记录详情
     */
    @GetMapping("/log-get")
    @Operation(summary = "获得派发执行记录详情")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:query')")
    public CommonResult<MasterDistributeLogRespVO> getDistributeLog(@RequestParam("id") Long id) {
        return success(distributeService.getDistributeLog(id));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = distributeService.getDistribute(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getName() == null
                        ? "编号" + id : auditBefore.getName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
