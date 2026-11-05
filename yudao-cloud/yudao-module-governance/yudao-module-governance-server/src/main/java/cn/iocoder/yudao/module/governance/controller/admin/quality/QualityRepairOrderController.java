package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRepairOrderDO;
import cn.iocoder.yudao.module.governance.service.quality.QualityRepairOrderService;
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
 * 数据治理-数据质量-修复工单 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-修复工单")
@RestController
@RequestMapping("/governance/quality-repair-order")
@Validated
public class QualityRepairOrderController {

    @Resource
    private QualityRepairOrderService repairOrderService;

    /**
     * 创建修复工单。
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-数据质量-修复工单", bizId = "#result")
    @Operation(summary = "创建修复工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:create')")
    public CommonResult<Long> createRepairOrder(@Valid @RequestBody QualityRepairOrderSaveReqVO createReqVO) {
        return success(repairOrderService.createRepairOrder(createReqVO));
    }

    /**
     * 更新修复工单。
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-数据质量-修复工单", bizId = "#updateReqVO.id")
    @Operation(summary = "更新修复工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:update')")
    public CommonResult<Boolean> updateRepairOrder(@Valid @RequestBody QualityRepairOrderSaveReqVO updateReqVO) {
        repairOrderService.updateRepairOrder(updateReqVO);
        return success(true);
    }

    /**
     * 删除修复工单。
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-数据质量-修复工单", bizId = "#id")
    @Operation(summary = "删除修复工单")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:delete')")
    public CommonResult<Boolean> deleteRepairOrder(@RequestParam("id") Long id) {
        repairOrderService.deleteRepairOrder(id);
        return success(true);
    }

    /**
     * 获得修复工单。
     */
    @GetMapping("/get")
    @Operation(summary = "获得修复工单")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:query')")
    public CommonResult<QualityRepairOrderRespVO> getRepairOrder(@RequestParam("id") Long id) {
        QualityRepairOrderDO order = repairOrderService.getRepairOrder(id);
        return success(BeanUtils.toBean(order, QualityRepairOrderRespVO.class));
    }

    /**
     * 获得修复工单分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得修复工单分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:query')")
    public CommonResult<PageResult<QualityRepairOrderRespVO>> getRepairOrderPage(
            @Valid QualityRepairOrderPageReqVO pageReqVO) {
        PageResult<QualityRepairOrderDO> pageResult = repairOrderService.getRepairOrderPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, QualityRepairOrderRespVO.class));
    }

    /**
     * 按修复配置生成 SQL 预览（不落库）
     */
    @PostMapping("/preview-sql")
    @Operation(summary = "预览修复SQL（按修复配置生成，不落库）")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:query')")
    public CommonResult<String> previewRepairSql(@Valid @RequestBody QualityRepairOrderSaveReqVO reqVO) {
        return success(repairOrderService.previewRepairSql(reqVO));
    }

    /**
     * 审批：通过/拒绝
     */
    @PutMapping("/approve")
    @BusinessOperateLog(type = "数据治理-数据质量-修复工单", bizId = "#id")
    @Operation(summary = "审批修复工单（通过/拒绝）")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:approve')")
    public CommonResult<Boolean> approveRepairOrder(@RequestParam("id") Long id,
            @RequestParam("approveStatus") String approveStatus) {
        repairOrderService.approveRepairOrder(id, approveStatus);
        return success(true);
    }

    /**
     * 执行修复：真实回源执行修复 SQL，回写执行状态与结果
     */
    @PostMapping("/execute")
    @BusinessOperateLog(type = "数据治理-数据质量-修复工单", bizId = "#id")
    @Operation(summary = "执行修复（真实回源执行修复SQL）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:execute')")
    public CommonResult<Boolean> executeRepairOrder(@RequestParam("id") Long id) {
        repairOrderService.executeRepairOrder(id);
        return success(true);
    }

    /**
     * 内容或物理目标变更后，由编辑人员撤销旧批准并重新送审。
     */
    @PostMapping("/resubmit")
    @BusinessOperateLog(type = "数据治理-数据质量-修复工单", bizId = "#id")
    @Operation(summary = "撤销未执行修复单的旧审批并重新送审")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:update')")
    public CommonResult<Boolean> resubmitRepairOrder(@RequestParam("id") Long id) {
        repairOrderService.resubmitRepairOrder(id);
        return success(true);
    }

    /**
     * 验证：验证通过/验证未通过
     */
    @PutMapping("/verify")
    @BusinessOperateLog(type = "数据治理-数据质量-修复工单", bizId = "#id")
    @Operation(summary = "验证修复结果（验证通过/验证未通过）")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:update')")
    public CommonResult<Boolean> verifyRepairOrder(@RequestParam("id") Long id,
            @RequestParam("verifyStatus") String verifyStatus) {
        repairOrderService.verifyRepairOrder(id, verifyStatus);
        return success(true);
    }

}
