package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderCommandReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderStatisticsRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRespVO;
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

    /**
     * 创建问题工单。
     */
    @PostMapping("/create")
    @Operation(summary = "创建问题工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:create')")
    public CommonResult<Long> createWorkOrder(@Valid @RequestBody QualityWorkOrderSaveReqVO createReqVO) {
        return success(workOrderService.createWorkOrder(createReqVO));
    }

    /**
     * 更新问题工单。
     */
    @PutMapping("/update")
    @Operation(summary = "更新问题工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:update')")
    public CommonResult<Boolean> updateWorkOrder(@Valid @RequestBody QualityWorkOrderSaveReqVO updateReqVO) {
        workOrderService.updateWorkOrder(updateReqVO);
        return success(true);
    }

    /**
     * 删除问题工单。
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除问题工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:delete')")
    public CommonResult<Boolean> deleteWorkOrder(@RequestParam("id") Long id) {
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
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:assign')")
    public CommonResult<QualityWorkOrderRespVO> assign(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.assign(reqVO));
    }

    /**
     * 领取待处理的质量工单任务或记录。
     */
    @PostMapping("/claim")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:claim')")
    public CommonResult<QualityWorkOrderRespVO> claim(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.claim(reqVO));
    }

    /**
     * 将质量工单转交给指定处理人，并保留操作轨迹。
     */
    @PostMapping("/transfer")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> transfer(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.transfer(reqVO));
    }

    /**
     * 提交处理结果。
     */
    @PostMapping("/submit-resolution")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> submitResolution(
            @Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.submitResolution(reqVO));
    }

    /**
     * 受理质量工单复检请求并关联本次提交记录。
     */
    @PostMapping("/recheck")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> recheck(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.recheck(reqVO));
    }

    /**
     * 校验质量工单处理证据后记录确认结论。
     */
    @PostMapping("/confirm")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:confirm')")
    public CommonResult<QualityWorkOrderRespVO> confirm(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.confirm(reqVO));
    }

    /**
     * 撤销质量工单当前提交或处理状态。
     */
    @PostMapping("/withdraw-submission")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> withdraw(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.withdraw(reqVO));
    }

    /**
     * 延长截止时间。
     */
    @PostMapping("/extend-deadline")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderRespVO> extendDeadline(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.extendDeadline(reqVO));
    }

    /**
     * 重新打开已结束的质量工单。
     */
    @PostMapping("/reopen")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:reopen')")
    public CommonResult<QualityWorkOrderRespVO> reopen(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
        return success(workOrderService.reopen(reqVO));
    }

    /**
     * 记录质量工单协调信息并更新相关处理安排。
     */
    @PostMapping("/request-coordination")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:coordinate')")
    public CommonResult<QualityWorkOrderRespVO> coordinate(@Valid @RequestBody QualityWorkOrderCommandReqVO reqVO) {
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
    @Operation(summary = "基于人工未送达结论显式授权重试")
    @PreAuthorize("@ss.hasPermission('governance:quality-work-order:process')")
    public CommonResult<QualityWorkOrderNotifyRespVO> authorizeNotifyRetry(
            @Valid @RequestBody QualityWorkOrderNotifyRetryReqVO reqVO) {
        return success(workOrderService.authorizeNotifyRetry(reqVO));
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

}
