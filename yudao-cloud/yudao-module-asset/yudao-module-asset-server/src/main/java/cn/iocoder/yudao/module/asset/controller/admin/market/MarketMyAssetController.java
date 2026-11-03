package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetApiDocumentRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyApplicationRespVO;
import cn.iocoder.yudao.module.asset.service.market.MarketMyAssetService;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 我的资产")
@RestController
@RequestMapping("/asset/market/my-assets")
@Validated
public class MarketMyAssetController {

    @Resource
    private MarketMyAssetService service;

    @GetMapping("/applications/page")
    @Operation(summary = "分页查看本人的申请记录")
    @PreAuthorize("@ss.hasPermission('asset:market:my-assets:query')")
    public CommonResult<PageResult<MarketMyApplicationRespVO>> applications(@Valid MarketApplyPageReqVO req) {
        return success(service.getApplications(req));
    }

    @GetMapping("/applications/get")
    @Operation(summary = "查看本人的申请详情")
    @PreAuthorize("@ss.hasPermission('asset:market:my-assets:query')")
    public CommonResult<MarketApplyRespVO> application(@RequestParam("id") @Positive Long id) {
        return success(service.getApplication(id));
    }

    /**
     * 本人的已批准资产，保留过期、暂停和撤销历史。
     */
    @GetMapping("/page")
    @Operation(summary = "本人的已批准资产，保留过期、暂停和撤销历史")
    @PreAuthorize("@ss.hasPermission('asset:market:my-assets:query')")
    public CommonResult<PageResult<MarketMyAssetRespVO>> page(@Valid MarketMyAssetPageReqVO req) {
        return success(service.getPage(req));
    }

    /**
     * 本人的批准详情；读取历史不恢复消费授权。
     */
    @GetMapping("/get")
    @Operation(summary = "本人的批准详情；读取历史不恢复消费授权")
    @PreAuthorize("@ss.hasPermission('asset:market:my-assets:query')")
    public CommonResult<MarketMyAssetDetailRespVO> get(@RequestParam("id") @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 按本人指定批准实时核验并预览库表。
     */
    @GetMapping("/preview")
    @Operation(summary = "按本人指定批准实时核验并预览库表")
    @PreAuthorize("@ss.hasPermission('asset:market:my-assets:query')")
    public CommonResult<DatabasePreviewRespDTO> preview(@RequestParam("id") @Positive Long id,
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(100) Integer limit) {
        return success(service.preview(id, limit));
    }

    /**
     * 实时核对本人批准及固定 API 合同后查看文档。
     */
    @GetMapping("/api-document")
    @Operation(summary = "实时核对本人批准及固定 API 合同后查看文档")
    @PreAuthorize("@ss.hasPermission('asset:market:my-assets:query')")
    public CommonResult<MarketMyAssetApiDocumentRespVO> apiDocument(@RequestParam("id") @Positive Long id) {
        return success(service.apiDocument(id));
    }
}
