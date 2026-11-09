package cn.iocoder.yudao.module.asset.controller.admin.monitor;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetInventoryRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationTrendRespVO;
import cn.iocoder.yudao.module.asset.service.monitor.AssetOperationService;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 资产运营：使用情况（热度/价值）与资产盘点报告。
 */
@Tag(name = "管理后台 - 资产运营")
@RestController
@RequestMapping("/asset/operation")
@Validated
public class AssetOperationController {

    @Resource
    private AssetOperationService operationService;

    /**
     * 获得资产运营指标分页。
     *
     * @param reqVO 分页与筛选
     * @return 分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得资产运营指标分页")
    @PreAuthorize("@ss.hasPermission('asset:operation:query')")
    public CommonResult<PageResult<AssetOperationRespVO>> getPage(@Valid AssetOperationPageReqVO reqVO) {
        return success(operationService.getPage(reqVO));
    }

    /**
     * 获得单个资产的使用与评分趋势。
     *
     * @param assetId 资产编号
     * @param days 天数
     * @return 趋势
     */
    @GetMapping("/trend")
    @Operation(summary = "获得资产使用与评分趋势")
    @Parameter(name = "assetId", description = "资产编号", required = true)
    @Parameter(name = "days", description = "天数（7-180）", example = "30")
    @PreAuthorize("@ss.hasPermission('asset:operation:query')")
    public CommonResult<AssetOperationTrendRespVO> getTrend(@RequestParam("assetId") Long assetId,
            @RequestParam(value = "days", defaultValue = "30") Integer days) {
        return success(operationService.getTrend(assetId, days));
    }

    /**
     * 获得资产盘点报告。
     *
     * @param statDate 统计日期，默认最近一次快照
     * @return 盘点报告
     */
    @GetMapping("/inventory")
    @Operation(summary = "获得资产盘点报告")
    @Parameter(name = "statDate", description = "统计日期，默认最近一次快照", example = "2026-11-09")
    @PreAuthorize("@ss.hasPermission('asset:operation:query')")
    public CommonResult<AssetInventoryRespVO> getInventory(
            @RequestParam(value = "statDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate statDate) {
        return success(operationService.getInventory(statDate));
    }

    /**
     * 立即重新计算今天的运营指标。
     *
     * @return 写入的资产数
     */
    @PostMapping("/refresh")
    @LogRecord(type = "数据资产-资产运营", subType = "刷新运营指标", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "重新计算了资产运营指标，共【{{(#_ret.data) ?: 0}}】个资产", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0}}")
    @Operation(summary = "立即重新计算今天的运营指标")
    @PreAuthorize("@ss.hasPermission('asset:operation:refresh')")
    public CommonResult<Integer> refresh() {
        return success(operationService.refresh(LocalDate.now()));
    }

    /**
     * 导出资产盘点清单（运营指标）Excel。
     *
     * @param reqVO 筛选条件
     * @param response 响应
     * @throws IOException 写出失败
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出资产盘点清单 Excel")
    @PreAuthorize("@ss.hasPermission('asset:operation:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportExcel(@Valid AssetOperationPageReqVO reqVO, HttpServletResponse response) throws IOException {
        ExcelUtils.write(response, "资产盘点清单.xls", "资产盘点", AssetOperationRespVO.class,
                operationService.getExportList(reqVO));
    }

}
