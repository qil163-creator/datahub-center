package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityAuditLogPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityAuditLogRespVO;
import cn.iocoder.yudao.module.governance.service.quality.QualityAuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-检测结果 Controller（质量报告数据源）
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-检测结果")
@RestController
@RequestMapping("/governance/quality-audit-log")
@Validated
public class QualityAuditLogController {

    @Resource
    private QualityAuditLogService auditLogService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.quality.QualityReportService reportService;

    /**
     * 按执行实例查询质量报告分页。
     */
    @GetMapping("/report-page")
    @Operation(summary = "按执行实例查询质量报告分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-audit-log:query')")
    public CommonResult<PageResult<cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceRespVO>> reportPage(
            @Valid cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityReportPageReqVO request) {
        return success(reportService.page(request));
    }

    /**
     * 按冻结实例获得质量维度、趋势与评测结果。
     */
    @GetMapping("/report")
    @Operation(summary = "按冻结实例获得质量维度、趋势与评测结果")
    @PreAuthorize("@ss.hasPermission('governance:quality-audit-log:query')")
    public CommonResult<cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityReportRespVO> report(
            @RequestParam("id") Long id, @RequestParam(value = "days", required = false) Integer days) {
        return success(reportService.report(id, days));
    }

    /**
     * 获得检测结果详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得检测结果详情")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-audit-log:query')")
    public CommonResult<QualityAuditLogRespVO> getAuditLog(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(auditLogService.getAuditLog(id), QualityAuditLogRespVO.class));
    }

    /**
     * 获得检测结果分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得检测结果分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-audit-log:query')")
    public CommonResult<PageResult<QualityAuditLogRespVO>> getAuditLogPage(
            @Valid QualityAuditLogPageReqVO pageReqVO) {
        return success(auditLogService.getAuditLogPage(pageReqVO));
    }

    /**
     * 质量报告统计：检测总次数、通过、未通过、平均通过率
     */
    @GetMapping("/report-summary")
    @Operation(summary = "质量报告统计（检测总次数/通过/未通过/平均通过率）")
    @PreAuthorize("@ss.hasPermission('governance:quality-audit-log:query')")
    public CommonResult<Map<String, Object>> getReportSummary() {
        return success(auditLogService.getReportSummary());
    }

    /**
     * 六维质量维度统计（雷达图）：按规则的质量维度聚合最近检测结果的通过率
     */
    @GetMapping("/dimension-summary")
    @Operation(summary = "六维质量维度统计（雷达图：按维度聚合最近检测通过率）")
    @PreAuthorize("@ss.hasPermission('governance:quality-audit-log:query')")
    public CommonResult<List<Map<String, Object>>> getDimensionSummary() {
        return success(auditLogService.getDimensionSummary());
    }

    /**
     * 质量趋势（复合图表）：近 N 天按日统计检测次数与平均通过率
     */
    @GetMapping("/trend")
    @Operation(summary = "质量趋势（近 N 天按日统计检测次数与平均通过率）")
    @Parameter(name = "days", description = "统计天数（默认15，最大90）")
    @PreAuthorize("@ss.hasPermission('governance:quality-audit-log:query')")
    public CommonResult<List<Map<String, Object>>> getReportTrend(
            @RequestParam(value = "days", required = false) Integer days) {
        return success(auditLogService.getReportTrend(days));
    }

}
