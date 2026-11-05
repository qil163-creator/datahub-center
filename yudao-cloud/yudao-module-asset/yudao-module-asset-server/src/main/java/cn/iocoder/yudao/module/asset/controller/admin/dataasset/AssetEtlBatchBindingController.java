package cn.iocoder.yudao.module.asset.controller.admin.dataasset;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetEtlBatchBindingService;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchRowsRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
@RequestMapping("/asset/data-asset/etl-batch")
@Tag(name = "管理后台 - 资产 ETL 批次关联")
public class AssetEtlBatchBindingController {

    @Resource
    private AssetEtlBatchBindingService service;

    /**
     * 读取当前资产关联的具体批次及实时准入摘要。
     */
    @GetMapping("/get")
    @Operation(summary = "读取当前资产关联的具体批次及实时准入摘要")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:query')")
    public CommonResult<AssetEtlBatchBindingRespVO> get(@RequestParam("assetId") @Positive Long assetId) {
        return success(service.get(assetId));
    }

    /**
     * 与资产详情同一对象范围；只读历史，远端批次失效不抹掉过去的关联事实。
     */
    @GetMapping("/history")
    @Operation(summary = "按修订倒序读取资产批次关联历史，不代表当前准入")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:query')")
    public CommonResult<AssetEtlBatchHistoryRespVO> history(@RequestParam("assetId") @Positive Long assetId,
            @RequestParam(value = "beforeRevision", required = false) @Min(0) Integer beforeRevision,
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(50) int limit) {
        return success(service.history(assetId, beforeRevision, limit));
    }

    /**
     * 绑定明确批次并追加关联修订历史。
     */
    @LogRecord(type = "数据资产-资产 ETL 批次关联", subType = "绑定明确批次并追加关联修订历史", bizNo = "{{(#request.assetId) ?: 0}}",
            success = "将资产【编号{{#request.assetId}}】绑定到ETL成果批次【{{#request.batchId}}】，原关联修订【{{#request.expectedRevision}}】，新修订【{{#_ret.data}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/bind")
    @Operation(summary = "绑定明确批次并追加关联修订历史")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Integer> bind(@Valid @RequestBody AssetEtlBatchBindingReqVO request) {
        return success(service.bind(request));
    }

    /**
     * 读取所绑定批次的准入数据，实时复核源对象权限。
     */
    @GetMapping("/rows")
    @Operation(summary = "读取所绑定批次的准入数据，实时复核源对象权限")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:query')")
    public CommonResult<EtlTargetBatchRowsRespDTO> rows(@RequestParam("assetId") @Positive Long assetId,
            @RequestParam("expectedRevision") @Min(0) Integer expectedRevision,
            @RequestParam(value = "afterRow", defaultValue = "0") @Min(0) Long afterRow,
            @RequestParam(value = "limit", defaultValue = "50") @Min(1) @Max(200) Integer limit) {
        return success(service.rows(assetId, expectedRevision, afterRow, limit));
    }
}
