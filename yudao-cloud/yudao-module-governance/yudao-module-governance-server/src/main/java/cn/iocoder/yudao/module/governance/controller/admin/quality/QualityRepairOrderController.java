package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
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
    @LogRecord(type = "数据治理-数据质量-修复工单", subType = "创建修复工单",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了质量修复工单【{{(#createReqVO.orderTitle) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建修复工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:create')")
    public CommonResult<Long> createRepairOrder(@Valid @RequestBody QualityRepairOrderSaveReqVO createReqVO) {
        return success(repairOrderService.createRepairOrder(createReqVO));
    }

    /**
     * 更新修复工单。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-数据质量-修复工单", subType = "更新修复工单",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了质量修复工单，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.orderTitle) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新修复工单")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:update')")
    public CommonResult<Boolean> updateRepairOrder(@Valid @RequestBody QualityRepairOrderSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        repairOrderService.updateRepairOrder(updateReqVO);
        return success(true);
    }

    /**
     * 删除修复工单。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-修复工单", subType = "删除修复工单",
            bizNo = "{{(#id) ?: 0}}", success = "删除了质量修复工单【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除修复工单")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:delete')")
    public CommonResult<Boolean> deleteRepairOrder(@RequestParam("id") Long id) {
        captureAuditContext(id);
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
    @LogRecord(type = "数据治理-数据质量-修复工单", subType = "审批修复工单（通过/拒绝）",
            bizNo = "{{(#id) ?: 0}}", success = "审批了修复工单【{{(#auditObjectName) ?: '编号' + #id}}】，审批结论【{{(#approveStatus) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "审批修复工单（通过/拒绝）")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:approve')")
    public CommonResult<Boolean> approveRepairOrder(@RequestParam("id") Long id,
            @RequestParam("approveStatus") String approveStatus) {
        captureAuditContext(id);
        repairOrderService.approveRepairOrder(id, approveStatus);
        return success(true);
    }

    /**
     * 执行修复：真实回源执行修复 SQL，回写执行状态与结果
     */
    @PostMapping("/execute")
    @LogRecord(type = "数据治理-数据质量-修复工单", subType = "执行修复（真实回源执行修复SQL）",
            bizNo = "{{(#id) ?: 0}}", success = "发起了修复工单【{{(#auditObjectName) ?: '编号' + #id}}】的回源修复，具体执行结果见修复回执",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "执行修复（真实回源执行修复SQL）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:execute')")
    public CommonResult<Boolean> executeRepairOrder(@RequestParam("id") Long id) {
        captureAuditContext(id);
        repairOrderService.executeRepairOrder(id);
        return success(true);
    }

    /**
     * 内容或物理目标变更后，由编辑人员撤销旧批准并重新送审。
     */
    @PostMapping("/resubmit")
    @LogRecord(type = "数据治理-数据质量-修复工单", subType = "撤销未执行修复单的旧审批并重新送审",
            bizNo = "{{(#id) ?: 0}}", success = "将修复工单【{{(#auditObjectName) ?: '编号' + #id}}】重新送审，旧审批已撤销",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "撤销未执行修复单的旧审批并重新送审")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:update')")
    public CommonResult<Boolean> resubmitRepairOrder(@RequestParam("id") Long id) {
        captureAuditContext(id);
        repairOrderService.resubmitRepairOrder(id);
        return success(true);
    }

    /**
     * 验证：验证通过/验证未通过
     */
    @PutMapping("/verify")
    @LogRecord(type = "数据治理-数据质量-修复工单", subType = "验证修复结果（验证通过/验证未通过）",
            bizNo = "{{(#id) ?: 0}}", success = "核验了修复工单【{{(#auditObjectName) ?: '编号' + #id}}】，验证结论【{{(#verifyStatus) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "验证修复结果（验证通过/验证未通过）")
    @PreAuthorize("@ss.hasPermission('governance:quality-repair-order:update')")
    public CommonResult<Boolean> verifyRepairOrder(@RequestParam("id") Long id,
            @RequestParam("verifyStatus") String verifyStatus) {
        captureAuditContext(id);
        repairOrderService.verifyRepairOrder(id, verifyStatus);
        return success(true);
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = repairOrderService.getRepairOrder(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getOrderTitle() == null
                        ? "编号" + id : auditBefore.getOrderTitle());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getApproveStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getApproveStatus()));
                LogRecordContext.putVariable("auditBeforeRevision", auditBefore.getContentVersion() == null
                        ? "未设置" : String.valueOf(auditBefore.getContentVersion()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
