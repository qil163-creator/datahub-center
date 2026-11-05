package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketFavoriteReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketResourceRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketStatisticsRespVO;
import cn.iocoder.yudao.module.asset.service.market.MarketMartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据集市")
@RestController
@RequestMapping("/asset/market/mart")
@Validated
public class MarketMartController {

    @Resource
    private MarketMartService marketMartService;

    /**
     * 启用的顶级市场专题及其已发布资产数。
     */
    @GetMapping("/topics")
    @Operation(summary = "启用的顶级市场专题及其已发布资产数")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<java.util.List<cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketTopicRespVO>> getTopics() {
        return success(marketMartService.getTopics());
    }

    /**
     * 读取市场展示信息供发布表单回显，不修改发布状态。
     */
    @GetMapping("/listing")
    @Operation(summary = "读取市场展示信息供发布表单回显，不修改发布状态")
    @PreAuthorize("@ss.hasAnyPermissions('asset:market:query','asset:data-asset:query','asset:data-asset:publish')")
    public CommonResult<cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketListingRespVO> getListing(
            @RequestParam("assetId") Long assetId) {
        return success(marketMartService.getListing(assetId));
    }

    /**
     * 保存市场展示配置并沿资产目录原状态机发布。
     */
    @BusinessOperateLog(type = "数据资产-数据集市", bizId = "#reqVO.assetId")
    @PutMapping("/publish")
    @Operation(summary = "保存市场展示配置并沿资产目录原状态机发布")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:publish')")
    public CommonResult<Boolean> publish(
            @Valid @RequestBody cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPublishReqVO reqVO) {
        marketMartService.publish(reqVO);
        return success(true);
    }

    /**
     * 检索已发布资产，收藏与授权均绑定当前登录用户。
     */
    @GetMapping("/page")
    @Operation(summary = "检索已发布资产，收藏与授权均绑定当前登录用户")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<PageResult<MarketResourceRespVO>> getPage(@Valid MarketPageReqVO reqVO) {
        return success(marketMartService.getPage(reqVO));
    }

    /**
     * 已发布总数及当前用户授权、收藏、在途申请资产数。
     */
    @GetMapping("/statistics")
    @Operation(summary = "已发布总数及当前用户授权、收藏、在途申请资产数")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<MarketStatisticsRespVO> getStatistics() {
        return success(marketMartService.getStatistics());
    }

    /**
     * 设置当前用户收藏状态，重复设置保持幂等。
     */
    @BusinessOperateLog(type = "数据资产-数据集市", bizId = "#reqVO.assetId")
    @PutMapping("/favorite")
    @Operation(summary = "设置当前用户收藏状态，重复设置保持幂等")
    @PreAuthorize("@ss.hasPermission('asset:market:favorite')")
    public CommonResult<Boolean> setFavorite(@Valid @RequestBody MarketFavoriteReqVO reqVO) {
        marketMartService.setFavorite(reqVO);
        return success(true);
    }
}
