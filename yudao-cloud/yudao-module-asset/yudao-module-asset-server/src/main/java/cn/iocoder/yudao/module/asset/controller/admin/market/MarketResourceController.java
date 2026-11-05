package cn.iocoder.yudao.module.asset.controller.admin.market;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.MarketConsumerDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetFacetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEndpointBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketEndpointRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketResourceRespVO;
import cn.iocoder.yudao.module.asset.service.market.MarketResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 集市共享资源")
@RestController
@RequestMapping("/asset/market/resource")
@Validated
public class MarketResourceController {

    @Resource
    private MarketResourceService marketResourceService;

    /**
     * 已发布资产分页（集市）。
     */
    @GetMapping("/page")
    @Operation(summary = "已发布资产分页（集市）")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<PageResult<MarketResourceRespVO>> getPublishedPage(@Valid DataAssetPageReqVO reqVO) {
        return success(marketResourceService.getPublishedPage(reqVO));
    }

    /**
     * 已发布资产详情（集市消费视图，不重复计浏览）。
     */
    @GetMapping("/get")
    @Operation(summary = "已发布资产详情（集市消费视图，不重复计浏览）")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<MarketConsumerDetailRespVO> getPublishedDetail(@RequestParam("id") Long id) {
        return success(marketResourceService.getConsumerDetail(id));
    }

    /**
     * 集市资产分组统计。
     */
    @GetMapping("/facets")
    @Operation(summary = "集市资产分组统计")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<DataAssetFacetRespVO> getPublishedFacets() {
        return success(marketResourceService.getPublishedFacets());
    }

    /**
     * 绑定资源可交付 API。
     */
    @LogRecord(type = "数据资产-集市共享资源", subType = "绑定资源可交付 API", bizNo = "{{(#reqVO.assetId) ?: 0}}",
            success = "绑定资源可交付 API", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/bind-api")
    @Operation(summary = "绑定资源可交付 API")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Boolean> bindApi(@Valid @RequestBody MarketEndpointBindReqVO reqVO) {
        marketResourceService.bindApi(reqVO);
        return success(true);
    }

    /**
     * 查询资源已绑定交付端点。
     */
    @GetMapping("/endpoints")
    @Operation(summary = "查询资源已绑定交付端点")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<List<MarketEndpointRespVO>> listEndpoints(@RequestParam("assetId") Long assetId) {
        return success(marketResourceService.listEndpoints(assetId));
    }

}
