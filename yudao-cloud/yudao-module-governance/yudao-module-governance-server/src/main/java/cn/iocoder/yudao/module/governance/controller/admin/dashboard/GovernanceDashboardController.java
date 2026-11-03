package cn.iocoder.yudao.module.governance.controller.admin.dashboard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.dashboard.vo.GovernanceDashboardRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.dashboard.vo.GovernanceSafetySummaryRespVO;
import cn.iocoder.yudao.module.governance.service.dashboard.GovernanceDashboardService;
import cn.iocoder.yudao.module.governance.service.dashboard.GovernanceSafetySummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 首页看板（数据治理）
 * <p>
 * 供工作台与治理总览调用。通用数量汇总登录后可访问，
 * 安全统计保留独立权限控制；明细仍由各功能接口自身的权限控制。
 *
 * @author Li
 */
@Tag(name = "管理后台 - 首页看板（数据治理）")
@RestController
@RequestMapping("/governance/dashboard")
@Validated
public class GovernanceDashboardController {

    @Resource
    private GovernanceDashboardService dashboardService;

    @Resource
    private GovernanceSafetySummaryService safetySummaryService;

    /**
     * 获得首页看板汇总：元数据概览、标准数据元统计、质量规则 / 检核 / 告警 / 工单汇总、主数据模型 / 派发任务数量
     *
     *  @return 汇总
     */
    @GetMapping("/summary")
    @Operation(summary = "获得首页看板汇总（元数据 / 数据标准 / 数据质量）")
    public CommonResult<GovernanceDashboardRespVO> getSummary() {
        return success(dashboardService.getSummary());
    }

    @GetMapping("/safety-summary")
    @Operation(summary = "获得治理总览安全统计")
    @PreAuthorize("@ss.hasPermission('governance:dashboard:safety-query')")
    public CommonResult<GovernanceSafetySummaryRespVO> getSafetySummary() {
        return success(safetySummaryService.getSummary());
    }

}
