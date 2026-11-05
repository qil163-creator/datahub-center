package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.service.market.MarketDemandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 供需对接")
@RestController
@RequestMapping("/asset/market/demand")
@Validated
public class MarketDemandController {

    @Resource
    private MarketDemandService marketDemandService;

    /**
     * 创建数据需求。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#result")
    @PostMapping("/create")
    @Operation(summary = "创建数据需求")
    @PreAuthorize("@ss.hasPermission('asset:demand:create')")
    public CommonResult<Long> createDemand(@Valid @RequestBody MarketDemandSaveReqVO reqVO) {
        return success(marketDemandService.createDemand(reqVO));
    }

    /**
     * 修改数据需求。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#reqVO.id")
    @PutMapping("/update")
    @Operation(summary = "修改数据需求")
    @PreAuthorize("@ss.hasPermission('asset:demand:create')")
    public CommonResult<Boolean> updateDemand(@Valid @RequestBody MarketDemandSaveReqVO reqVO) {
        marketDemandService.updateDemand(reqVO);
        return success(true);
    }

    /**
     * 删除草稿需求。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#id")
    @DeleteMapping("/delete")
    @Operation(summary = "删除草稿需求")
    @PreAuthorize("@ss.hasPermission('asset:demand:create')")
    public CommonResult<Boolean> deleteDemand(@RequestParam("id") Long id) {
        marketDemandService.deleteDemand(id);
        return success(true);
    }

    /**
     * 发布到需求大厅。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#id")
    @PutMapping("/publish")
    @Operation(summary = "发布到需求大厅")
    @PreAuthorize("@ss.hasPermission('asset:demand:submit')")
    public CommonResult<Boolean> publishDemand(@RequestParam("id") Long id) {
        marketDemandService.publishDemand(id);
        return success(true);
    }

    /**
     * 关闭、撤销或驳回需求。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#reqVO.id")
    @PutMapping("/close")
    @Operation(summary = "关闭、撤销或驳回需求")
    @PreAuthorize("@ss.hasAnyPermissions('asset:demand:close', 'asset:demand:match')")
    public CommonResult<Boolean> closeDemand(@Valid @RequestBody MarketDemandCloseReqVO reqVO) {
        marketDemandService.closeDemand(reqVO);
        return success(true);
    }

    /**
     * 需求详情。
     */
    @GetMapping("/get")
    @Operation(summary = "需求详情")
    @PreAuthorize("@ss.hasPermission('asset:demand:query')")
    public CommonResult<MarketDemandRespVO> getDemand(@RequestParam("id") Long id) {
        return success(marketDemandService.getDemand(id));
    }

    /**
     * 需求分页。
     */
    @GetMapping("/page")
    @Operation(summary = "需求分页")
    @PreAuthorize("@ss.hasPermission('asset:demand:query')")
    public CommonResult<PageResult<MarketDemandRespVO>> getDemandPage(@Valid MarketDemandPageReqVO reqVO) {
        return success(marketDemandService.getDemandPage(reqVO));
    }

    /**
     * 响应需求。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#reqVO.demandId")
    @PostMapping("/reply")
    @Operation(summary = "响应需求")
    @PreAuthorize("@ss.hasPermission('asset:demand:match')")
    public CommonResult<Long> replyDemand(@Valid @RequestBody MarketDemandReplySaveReqVO reqVO) {
        return success(marketDemandService.replyDemand(reqVO));
    }

    /**
     * 把已有资产匹配到需求。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#reqVO.demandId")
    @PostMapping("/match")
    @Operation(summary = "把已有资产匹配到需求")
    @PreAuthorize("@ss.hasPermission('asset:demand:match')")
    public CommonResult<Long> matchDemand(@Valid @RequestBody MarketDemandMatchSaveReqVO reqVO) {
        return success(marketDemandService.matchDemand(reqVO));
    }

    /**
     * 需求方确认匹配。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#reqVO.id")
    @PutMapping("/match/confirm")
    @Operation(summary = "需求方确认匹配")
    @PreAuthorize("@ss.hasPermission('asset:demand:submit')")
    public CommonResult<Boolean> confirmMatch(@Valid @RequestBody MarketDemandMatchConfirmReqVO reqVO) {
        marketDemandService.confirmMatch(reqVO);
        return success(true);
    }

    /**
     * 验收已交付需求，已匹配不等于已解决。
     */
    @BusinessOperateLog(type = "数据资产-供需对接", bizId = "#id")
    @PutMapping("/accept")
    @Operation(summary = "验收已交付需求，已匹配不等于已解决")
    @PreAuthorize("@ss.hasPermission('asset:demand:submit')")
    public CommonResult<Boolean> acceptDemand(@RequestParam("id") Long id) {
        marketDemandService.acceptDemand(id);
        return success(true);
    }

    /**
     * 按需求推荐已发布资产。
     */
    @GetMapping("/suggest")
    @Operation(summary = "按需求推荐已发布资产")
    @PreAuthorize("@ss.hasPermission('asset:demand:query')")
    public CommonResult<List<MarketResourceRespVO>> suggestAssets(@RequestParam("demandId") Long demandId) {
        return success(marketDemandService.suggestAssets(demandId));
    }

}
