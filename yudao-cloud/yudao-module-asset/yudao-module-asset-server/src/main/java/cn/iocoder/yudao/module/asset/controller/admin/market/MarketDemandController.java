package cn.iocoder.yudao.module.asset.controller.admin.market;

import com.mzt.logapi.starter.annotation.LogRecord;
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
    @LogRecord(type = "数据资产-供需对接", subType = "创建数据需求", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了数据需求【{{#reqVO.title}}】，编号【{{#_ret.data}}】，需求类型【{{#reqVO.demandType}}】，发布标识【{{#reqVO.publish}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建数据需求")
    @PreAuthorize("@ss.hasPermission('asset:demand:create')")
    public CommonResult<Long> createDemand(@Valid @RequestBody MarketDemandSaveReqVO reqVO) {
        return success(marketDemandService.createDemand(reqVO));
    }

    /**
     * 修改数据需求。
     */
    @LogRecord(type = "数据资产-供需对接", subType = "修改数据需求", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "修改了数据需求【{{#reqVO.title}}】（编号{{#reqVO.id}}），需求类型【{{#reqVO.demandType}}】，发布标识【{{#reqVO.publish}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据资产-供需对接", subType = "删除草稿需求", bizNo = "{{(#id) ?: 0}}",
            success = "删除了草稿数据需求【编号{{#id}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据资产-供需对接", subType = "发布到需求大厅", bizNo = "{{(#id) ?: 0}}",
            success = "将数据需求【编号{{#id}}】发布到了需求大厅", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据资产-供需对接", subType = "关闭、撤销或驳回需求", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "关闭了数据需求【编号{{#reqVO.id}}】，目标状态【{{#reqVO.status}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据资产-供需对接", subType = "响应需求", bizNo = "{{(#reqVO.demandId) ?: 0}}",
            success = "响应了数据需求【编号{{#reqVO.demandId}}】，生成响应记录【{{#_ret.data}}】，响应类型【{{#reqVO.replyType}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/reply")
    @Operation(summary = "响应需求")
    @PreAuthorize("@ss.hasPermission('asset:demand:match')")
    public CommonResult<Long> replyDemand(@Valid @RequestBody MarketDemandReplySaveReqVO reqVO) {
        return success(marketDemandService.replyDemand(reqVO));
    }

    /**
     * 把已有资产匹配到需求。
     */
    @LogRecord(type = "数据资产-供需对接", subType = "把已有资产匹配到需求", bizNo = "{{(#reqVO.demandId) ?: 0}}",
            success = "为数据需求【编号{{#reqVO.demandId}}】匹配了资产【{{#reqVO.assetId}}】，匹配记录【{{#_ret.data}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/match")
    @Operation(summary = "把已有资产匹配到需求")
    @PreAuthorize("@ss.hasPermission('asset:demand:match')")
    public CommonResult<Long> matchDemand(@Valid @RequestBody MarketDemandMatchSaveReqVO reqVO) {
        return success(marketDemandService.matchDemand(reqVO));
    }

    /**
     * 需求方确认匹配。
     */
    @LogRecord(type = "数据资产-供需对接", subType = "需求方确认匹配", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "确认了需求匹配记录【编号{{#reqVO.id}}】，匹配状态【{{#reqVO.status}}】，创建申请标识【{{#reqVO.createApply}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
    @LogRecord(type = "数据资产-供需对接", subType = "验收已交付需求，已匹配不等于已解决", bizNo = "{{(#id) ?: 0}}",
            success = "验收了已交付的数据需求【编号{{#id}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
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
