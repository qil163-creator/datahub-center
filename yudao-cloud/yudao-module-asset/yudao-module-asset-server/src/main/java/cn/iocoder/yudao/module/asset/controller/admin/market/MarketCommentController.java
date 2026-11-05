package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentReplyReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentSaveReqVO;
import cn.iocoder.yudao.module.asset.service.market.MarketCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据集市资源评价")
@RestController
@RequestMapping("/asset/market/comment")
@Validated
public class MarketCommentController {

    @Resource
    private MarketCommentService marketCommentService;

    /**
     * 发表评价。
     */
    @BusinessOperateLog(type = "数据资产-数据集市资源评价", bizId = "#result")
    @PostMapping("/create")
    @Operation(summary = "发表评价")
    @PreAuthorize("@ss.hasPermission('asset:market:comment')")
    public CommonResult<Long> createComment(@Valid @RequestBody MarketCommentSaveReqVO reqVO) {
        return success(marketCommentService.createComment(reqVO));
    }

    /**
     * 修改自己的评价。
     */
    @BusinessOperateLog(type = "数据资产-数据集市资源评价", bizId = "#reqVO.id")
    @PutMapping("/update")
    @Operation(summary = "修改自己的评价")
    @PreAuthorize("@ss.hasPermission('asset:market:comment')")
    public CommonResult<Boolean> updateComment(@Valid @RequestBody MarketCommentSaveReqVO reqVO) {
        marketCommentService.updateComment(reqVO);
        return success(true);
    }

    /**
     * 删除自己的评价。
     */
    @BusinessOperateLog(type = "数据资产-数据集市资源评价", bizId = "#id")
    @DeleteMapping("/delete")
    @Operation(summary = "删除自己的评价")
    @PreAuthorize("@ss.hasPermission('asset:market:comment')")
    public CommonResult<Boolean> deleteComment(@RequestParam("id") Long id) {
        marketCommentService.deleteComment(id);
        return success(true);
    }

    /**
     * 资产方回复评价。
     */
    @BusinessOperateLog(type = "数据资产-数据集市资源评价", bizId = "#reqVO.id")
    @PutMapping("/reply")
    @Operation(summary = "资产方回复评价")
    @PreAuthorize("@ss.hasPermission('asset:market:check')")
    public CommonResult<Boolean> replyComment(@Valid @RequestBody MarketCommentReplyReqVO reqVO) {
        marketCommentService.replyComment(reqVO);
        return success(true);
    }

    /**
     * 评价分页。
     */
    @GetMapping("/page")
    @Operation(summary = "评价分页")
    @PreAuthorize("@ss.hasPermission('asset:market:query')")
    public CommonResult<PageResult<MarketCommentRespVO>> getCommentPage(@Valid MarketCommentPageReqVO reqVO) {
        return success(marketCommentService.getCommentPage(reqVO));
    }

}
