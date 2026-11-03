package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessService;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据集市授权查询")
@RestController
@RequestMapping("/asset/market/access")
@Validated
public class MarketAccessController {

    @Resource
    private MarketAccessService marketAccessService;

    @Resource
    private cn.iocoder.yudao.module.supply.api.app.SupplyQuotaApi quotaApi;

    /**
     * 当前用户最近100次额度消费回执（不包含数据内容）。
     */
    @GetMapping("/consumption-history")
    @Operation(summary = "当前用户最近100次额度消费回执（不包含数据内容）")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<java.util.List<cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Receipt>> consumptionHistory() {
        return success(quotaApi.history(SecurityFrameworkUtils.getLoginUserId()).getCheckedData());
    }

    /**
     * 当前用户已发生消费的批准来源余额。
     */
    @GetMapping("/consumption-balances")
    @Operation(summary = "当前用户已发生消费的批准来源余额")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<java.util.List<cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Balance>> consumptionBalances() {
        return success(quotaApi.balances(SecurityFrameworkUtils.getLoginUserId()).getCheckedData());
    }

    /**
     * 按稳定请求键查询本人的历史消费回执。
     */
    @GetMapping("/consumption-receipt")
    @Operation(summary = "按稳定请求键查询本人的历史消费回执")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Receipt> consumptionReceipt(
            @jakarta.validation.constraints.Positive @RequestParam("assetId") Long assetId,
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{7,127}") @RequestParam("requestKey") String requestKey) {
        long actor = SecurityFrameworkUtils.getLoginUserId();
        return success(quotaApi.receipt("PORTAL:" + actor + ":" + assetId, requestKey, actor).getCheckedData());
    }

    /**
     * 当前用户是否拥有该资产的有效集市授权。
     */
    @GetMapping("/check")
    @Operation(summary = "当前用户是否拥有该资产的有效集市授权")
    @Parameter(name = "assetId", required = true)
    @PreAuthorize("isAuthenticated()")
    public CommonResult<Boolean> checkAccess(@RequestParam("assetId") Long assetId) {
        return success(marketAccessService.hasEffectiveAccess(
                SecurityFrameworkUtils.getLoginUserId(), assetId));
    }

    /**
     * 按有效集市授权预览库表（收回/过期立即不可用）。
     */
    @GetMapping("/preview")
    @Operation(summary = "按有效集市授权预览库表（收回/过期立即不可用）")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<DatabasePreviewRespDTO> previewAuthorized(
            @RequestParam("assetId") Long assetId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return success(marketAccessService.previewAuthorizedAsset(assetId, limit));
    }

}
