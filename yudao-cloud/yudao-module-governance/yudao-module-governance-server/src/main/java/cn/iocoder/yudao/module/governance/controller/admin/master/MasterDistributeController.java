package cn.iocoder.yudao.module.governance.controller.admin.master;

import com.mzt.logapi.starter.annotation.LogRecord;
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
            bizNo = "{{(#id) ?: 0}}", success = "启用或停用 REST 推送（不发送数据）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "启用或停用 REST 推送（不发送数据）")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:release')")
    public CommonResult<Boolean> updateStatus(@RequestParam("id") Long id, @RequestParam("status") Integer status) {
        distributeService.updateStatus(id, status);
        return success(true);
    }

    /**
     * 重试最近一次发送前失败的推送。
     */
    @PutMapping("/retry")
    @LogRecord(type = "数据治理-主数据派发", subType = "重试最近一次发送前失败的推送",
            bizNo = "{{(#id) ?: 0}}", success = "重试最近一次发送前失败的推送",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "重试最近一次发送前失败的推送")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:run')")
    public CommonResult<Boolean> retry(@RequestParam("id") Long id) {
        distributeService.retry(id);
        return success(true);
    }

    /**
     * 人工核查未知派发结果并停用排期（不发送数据）。
     */
    @PutMapping("/resolve")
    @LogRecord(type = "数据治理-主数据派发", subType = "人工核查未知派发结果并停用排期（不发送数据）",
            bizNo = "{{(#request.recordId) ?: 0}}", success = "人工核查未知派发结果并停用排期（不发送数据）",
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
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建主数据派发任务",
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
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新主数据派发任务",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新主数据派发任务")
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:update')")
    public CommonResult<Boolean> updateDistribute(@Valid @RequestBody MasterDistributeSaveReqVO updateReqVO) {
        distributeService.updateDistribute(updateReqVO);
        return success(true);
    }

    /**
     * 删除派发任务（已发布不可删除）
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-主数据派发", subType = "删除主数据派发任务",
            bizNo = "{{(#id) ?: 0}}", success = "删除主数据派发任务",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除主数据派发任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:delete')")
    public CommonResult<Boolean> deleteDistribute(@RequestParam("id") Long id) {
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
            bizNo = "{{(#id) ?: 0}}", success = "发布派发任务（一次性任务立即执行，周期任务开始排期）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发布派发任务（一次性任务立即执行，周期任务开始排期）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:release')")
    public CommonResult<Boolean> releaseDistribute(@RequestParam("id") Long id) {
        distributeService.releaseDistribute(id);
        return success(true);
    }

    /**
     * 取消发布：停止后续排期，发送前取消，发送后保留未知结果。
     */
    @PutMapping("/cancel-release")
    @LogRecord(type = "数据治理-主数据派发", subType = "取消发布派发任务",
            bizNo = "{{(#id) ?: 0}}", success = "取消发布派发任务",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "取消发布派发任务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:release')")
    public CommonResult<Boolean> cancelDistribute(@RequestParam("id") Long id) {
        distributeService.cancelDistribute(id);
        return success(true);
    }

    /**
     * 手动执行一次（无论是否发布）
     */
    @PutMapping("/hand-run")
    @LogRecord(type = "数据治理-主数据派发", subType = "手动执行一次派发",
            bizNo = "{{(#id) ?: 0}}", success = "手动执行一次派发",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "手动执行一次派发")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-distribute:run')")
    public CommonResult<Boolean> handRun(@RequestParam("id") Long id) {
        distributeService.handRun(id);
        return success(true);
    }

    /**
     * 停止指定运行，发布及周期排期保持不变。平台内拥有运行权限的管理员可操作。
     */
    @PutMapping("/stop")
    @LogRecord(type = "数据治理-主数据派发", subType = "停止指定派发运行（不改变发布及排期，发送后结果仍须核查）",
            bizNo = "{{(#request.recordId) ?: 0}}", success = "停止指定派发运行（不改变发布及排期，发送后结果仍须核查）",
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

}
