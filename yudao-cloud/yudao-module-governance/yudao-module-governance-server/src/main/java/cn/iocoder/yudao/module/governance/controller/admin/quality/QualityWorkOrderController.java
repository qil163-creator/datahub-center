package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderCommandReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderStatisticsRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityExpiryFailureRetryReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityExpiryFailureRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityExpiryFailurePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyReviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRetryReqVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityWorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据质量-问题工单")
@RestController
@RequestMapping("/governance/quality-work-order")
@Validated
public class QualityWorkOrderController {

    @Resource
    private QualityWorkOrderService workOrderService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.quality.QualityExceptionExpiryFailureService expiryFailureService;

    /**
     * 创建问题工单。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "创建问题工单",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了质量问题工单【{{(#createReqVO.orderName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建问题工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:create')")
    public CommonResult<Long> createWorkOrder(@Valid @RequestBody QualityWorkOrderSaveReqVO createReqVO) {
        return success(workOrderService.createWorkOrder(createReqVO));
    }

    /**
     * 更新问题工单。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "更新问题工单",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了质量问题工单，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.orderName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新问题工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:update')")
    public CommonResult<Boolean> updateWorkOrder(@Valid @RequestBody QualityWorkOrderSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        workOrderService.updateWorkOrder(updateReqVO);
        return success(true);
    }

    /**
     * 删除问题工单。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "删除问题工单",
            bizNo = "{{(#id) ?: 0}}", success = "删除了质量问题工单【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除问题工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:delete')")
    public CommonResult<Boolean> deleteWorkOrder(@RequestParam("id") Long id) {
        captureAuditContext(id);
        workOrderService.deleteWorkOrder(id);
        return success(true);
    }

    /**
     * 获得问题工单。
     */
    @GetMapping("/get")
    @Operation(summary = "获得问题工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:query')")
    public CommonResult<QualityWorkOrderRespVO> getWorkOrder(@RequestParam("id") Long id) {
        return success(workOrderService.getWorkOrder(id));
    }

    /**
     * 获得问题工单分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得问题工单分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:query')")
    public CommonResult<PageResult<QualityWorkOrderRespVO>> getWorkOrderPage(
            @Valid QualityWorkOrderPageReqVO pageReqVO) {
        return success(workOrderService.getWorkOrderPage(pageReqVO));
    }

    /**
     * 获得我的待办工单列表。
     */
    @GetMapping("/my-todo")
    @Operation(summary = "获得我的待办工单列表")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:query')")
    public CommonResult<List<QualityWorkOrderRespVO>> getMyTodoList() {
        return success(workOrderService.getMyTodoList());
    }

    /**
     * 将质量工单分派给指定处理人，并记录分派信息。
     */
    @PostMapping("/assign")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "分派质量工单",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "分派了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】，处理人编号【{{(#reqVO.ownerUserId) ?: '未设置'}}】，处理部门编号【{{(#reqVO.handlingDeptId) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:assign')")
    public CommonResult<QualityWorkOrderRespVO> assign(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.assign(reqVO));
    }

    /**
     * 领取待处理的质量工单任务或记录。
     */
    @PostMapping("/claim")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "领取质量工单",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "领取了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】，处理版本【{{(#reqVO.expectedVersion) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:claim')")
    public CommonResult<QualityWorkOrderRespVO> claim(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.claim(reqVO));
    }

    /**
     * 将质量工单转交给指定处理人，并保留操作轨迹。
     */
    @PostMapping("/transfer")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "转交质量工单",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "转交了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】，新处理人编号【{{(#reqVO.ownerUserId) ?: '未设置'}}】，处理部门编号【{{(#reqVO.handlingDeptId) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> transfer(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.transfer(reqVO));
    }

    /**
     * 提交处理结果。
     */
    @PostMapping("/submit-resolution")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "提交质量工单处理结果",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "提交了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】的处理结果，处理类型【{{(#reqVO.submissionType) ?: '未设置'}}】，提交编号【{{(#_ret.data.currentSubmissionId) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> submitResolution(
            @Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.submitResolution(reqVO));
    }

    /**
     * 受理质量工单复检请求并关联本次提交记录。
     */
    @PostMapping("/recheck")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "提交质量工单复检",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "发起了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】的复检，提交编号【{{(#reqVO.submissionId) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> recheck(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.recheck(reqVO));
    }

    /**
     * 校验质量工单处理证据后记录确认结论。
     */
    @PostMapping("/confirm")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "确认质量工单处理结论",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "确认了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】的处理结论，确认动作【{{(#reqVO.confirmAction) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:confirm')")
    public CommonResult<QualityWorkOrderRespVO> confirm(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.confirm(reqVO));
    }

    /**
     * 撤销质量工单当前提交或处理状态。
     */
    @PostMapping("/withdraw-submission")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "撤销质量工单提交",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "撤销了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】的当前提交，处理版本【{{(#reqVO.expectedVersion) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> withdraw(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.withdraw(reqVO));
    }

    /**
     * 延长截止时间。
     */
    @PostMapping("/extend-deadline")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "延长质量工单截止时间",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "延长了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】的截止时间，新截止时间【{{(#reqVO.newDeadline) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> extendDeadline(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.extendDeadline(reqVO));
    }

    /**
     * 重新打开已结束的质量工单。
     */
    @PostMapping("/reopen")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "重新打开质量工单",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "重新打开了质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】，处理版本【{{(#reqVO.expectedVersion) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:reopen')")
    public CommonResult<QualityWorkOrderRespVO> reopen(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.reopen(reqVO));
    }

    /**
     * 记录质量工单协调信息并更新相关处理安排。
     */
    @PostMapping("/request-coordination")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "请求质量工单协调",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "为质量工单【{{(#auditObjectName) ?: '编号' + #reqVO.id}}】提交了协调请求，处理部门编号【{{(#reqVO.handlingDeptId) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:coordinate')")
    public CommonResult<QualityWorkOrderRespVO> coordinate(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        captureAuditContext(reqVO.getId());
        return success(workOrderService.coordinate(reqVO));
    }

    /**
     * 读取质量工单操作及复核历史。
     */
    @GetMapping("/history")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:query')")
    public CommonResult<List<QualityWorkOrderRespVO.QualityWorkOrderActionRespVO>> history(
            @RequestParam("id") Long id) {
        return success(workOrderService.getHistory(id));
    }

    /**
     * 查询工单通知投递记录。
     */
    @GetMapping("/notify-page")
    @Operation(summary = "查询工单通知投递记录")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:query')")
    public CommonResult<PageResult<QualityWorkOrderNotifyRespVO>> notifyPage(
            @Valid QualityWorkOrderNotifyPageReqVO reqVO) {
        return success(workOrderService.getNotifyPage(reqVO));
    }

    /**
     * 查询通知投递详情及人工核验历史。
     */
    @GetMapping("/notify-detail")
    @Operation(summary = "查询通知投递详情及人工核验历史")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:query')")
    public CommonResult<QualityWorkOrderNotifyRespVO> notifyDetail(@RequestParam("id") Long id) {
        return success(workOrderService.getNotifyDetail(id));
    }

    /**
     * 人工核验结果未知的通知。
     */
    @PostMapping("/notify-review")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "人工核验结果未知的通知",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "人工核验了工单通知【{{(#reqVO.id) ?: '未设置'}}】的投递结果，结论【{{(#reqVO.outcome) ?: '未设置'}}】，核验版本【{{(#reqVO.expectedResolutionVersion) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "人工核验结果未知的通知")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderNotifyRespVO> reviewNotify(
            @Valid @RequestBody QualityWorkOrderNotifyReviewReqVO reqVO) {
        return success(workOrderService.reviewNotify(reqVO));
    }

    /**
     * 基于人工未送达结论显式授权重试。
     */
    @PostMapping("/notify-authorize-retry")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "基于人工未送达结论显式授权重试",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "依据核验决定【{{(#reqVO.decisionId) ?: '未设置'}}】授权重试了工单通知【{{(#reqVO.id) ?: '未设置'}}】，核验版本【{{(#reqVO.expectedResolutionVersion) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "基于人工未送达结论显式授权重试")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderNotifyRespVO> authorizeNotifyRetry(
            @Valid @RequestBody QualityWorkOrderNotifyRetryReqVO reqVO) {
        return success(workOrderService.authorizeNotifyRetry(reqVO));
    }

    /**
     * 例外到期处理失败账本分页（退避重试中 / 次数耗尽待人工 / 已关闭）。
     */
    @GetMapping("/expiry-failure/page")
    @Operation(summary = "例外到期处理失败账本分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:coordinate')")
    public CommonResult<PageResult<QualityExpiryFailureRespVO>> getExpiryFailurePage(
            @Valid QualityExpiryFailurePageReqVO reqVO) {
        return success(expiryFailureService.getPage(reqVO));
    }

    /**
     * 人工排查后授权例外到期处理重试。
     */
    @PostMapping("/expiry-failure/retry")
    @LogRecord(type = "数据治理-数据质量-问题工单", subType = "授权例外到期处理重试",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "授权重试了例外到期处理失败记录【{{(#reqVO.id) ?: '未设置'}}】，账本版本【{{(#reqVO.expectedVersion) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "授权例外到期处理重试", description = "清零自动处理次数并立即到期；到期动作已生效时下一轮自动跳过，不会重复创建复核单")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:coordinate')")
    public CommonResult<QualityExpiryFailureRespVO> authorizeExpiryRetry(
            @Valid @RequestBody QualityExpiryFailureRetryReqVO reqVO) {
        return success(expiryFailureService.authorizeRetry(reqVO));
    }

    /**
     * 核验明细访问权限后读取工单原始问题数据。
     */
    @GetMapping("/raw-issues")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:raw-query')")
    public CommonResult<Map<String, Object>> rawIssues(@RequestParam("id") Long id) {
        return success(workOrderService.getRawIssues(id));
    }

    /**
     * 获得当前用户可见工单的趋势、状态与责任人统计。
     */
    @GetMapping("/statistics")
    @Operation(summary = "获得当前用户可见工单的趋势、状态与责任人统计")
    @Parameter(name = "days", description = "统计天数（默认30，最大90）")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:query')")
    public CommonResult<QualityWorkOrderStatisticsRespVO> getWorkOrderStatistics(
            @RequestParam(value = "days", required = false) Integer days) {
        return success(workOrderService.getWorkOrderStatistics(days));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = workOrderService.getWorkOrder(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getOrderName() == null
                        ? "编号" + id : auditBefore.getOrderName());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getOrderStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getOrderStatus()));
                LogRecordContext.putVariable("auditBeforeRevision", auditBefore.getLockVersion() == null
                        ? "未设置" : String.valueOf(auditBefore.getLockVersion()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
