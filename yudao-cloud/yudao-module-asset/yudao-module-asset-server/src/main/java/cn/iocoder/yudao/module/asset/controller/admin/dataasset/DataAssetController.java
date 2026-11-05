package cn.iocoder.yudao.module.asset.controller.admin.dataasset;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetReviewTicketDO;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.dataasset.DataAssetService;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.CategoryDataAssetRespVO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据资产")
@RestController
@RequestMapping("/asset/data-asset")
@Validated
public class DataAssetController {

    @Resource
    private DataAssetService dataAssetService;

    @Resource
    private AssetCatalogService catalogService;

    /**
     * 资产地图的平台管理分类导航，不开放类目维护权限。
     */
    @GetMapping("/navigation-categories")
    @Operation(summary = "资产地图的平台管理分类导航，不开放类目维护权限")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<List<CategoryDataAssetRespVO>> navigationCategories() {
        return success(dataAssetService.getNavigationCategories());
    }

    /** 资产地图读取平台共享的已启用数据板块。 */
    /**
     * 获得数据资产分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据资产分页")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<PageResult<DataAssetRespVO>> getAssetPage(
            @Valid DataAssetMapPageReqVO reqVO) {
        return success(dataAssetService.getAssetPage(reqVO));
    }

    /**
     * 左侧分组树统计：类目 / 主题域 / 数仓分层
     */
    @GetMapping("/facets")
    @Operation(summary = "获得资产分组统计（业务分类 / 主题域 / 数仓分层）")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<DataAssetFacetRespVO> getAssetFacets() {
        return success(dataAssetService.getAssetFacets());
    }

    /**
     * 获得资产注册与维护可选的分类及主题域，沿用当前登录人的可见范围。
     */
    @GetMapping("/registration-options")
    @Operation(summary = "获得资产注册与维护可选的分类及主题域，沿用当前登录人的可见范围")
    @PreAuthorize("@ss.hasAnyPermissions('asset:data-asset:create', 'asset:data-asset:update')")
    public CommonResult<DataAssetFacetRespVO> registrationOptions() {
        return success(dataAssetService.getAssetFacets());
    }

    /**
     * 获得待编目的已发布元数据分页。
     */
    @GetMapping("/candidate-page")
    @Operation(summary = "获得待编目的已发布元数据分页")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:create')")
    public CommonResult<PageResult<MetadataTableRespDTO>> getCandidatePage(
            @Valid DataAssetCandidatePageReqVO reqVO) {
        return success(dataAssetService.getCandidatePage(reqVO));
    }

    /**
     * 批量编目数据资产。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#result")
    @PostMapping("/batch-register")
    @Operation(summary = "批量编目数据资产")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:create')")
    public CommonResult<List<Long>> batchRegisterAsset(
            @Valid @RequestBody DataAssetBatchRegisterReqVO reqVO) {
        return success(dataAssetService.batchRegisterAsset(reqVO));
    }

    /**
     * 维护数据资产。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#reqVO.id")
    @PutMapping("/update")
    @Operation(summary = "维护数据资产")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Boolean> updateAsset(@Valid @RequestBody DataAssetUpdateReqVO reqVO) {
        dataAssetService.updateAsset(reqVO);
        return success(true);
    }

    /**
     * 读取资产范围内当前字段的治理属性及修订号。
     */
    @GetMapping("/column-get")
    @Operation(summary = "读取资产范围内当前字段的治理属性及修订号")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<DataAssetColumnRespVO> getAssetColumn(@RequestParam("assetId") Long assetId,
            @RequestParam("id") Long id) {
        return success(dataAssetService.getAssetColumn(assetId, id));
    }

    /**
     * 维护资产字段的业务与标准属性。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#reqVO.assetId")
    @PutMapping("/update-column")
    @Operation(summary = "维护资产字段的业务与标准属性")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Boolean> updateAssetColumn(
            @Valid @RequestBody DataAssetColumnUpdateReqVO reqVO) {
        dataAssetService.updateAssetColumn(reqVO);
        return success(true);
    }

    /**
     * 刷新资产引用的元数据定版与运行指标。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#id")
    @PutMapping("/refresh")
    @Operation(summary = "刷新资产引用的元数据定版与运行指标")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Boolean> refreshAsset(@RequestParam("id") Long id) {
        dataAssetService.refreshAsset(id);
        return success(true);
    }

    /**
     * 发布数据资产。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#id")
    @PutMapping("/publish")
    @Operation(summary = "发布数据资产")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:publish')")
    public CommonResult<Boolean> publishAsset(@RequestParam("id") Long id) {
        dataAssetService.publishAsset(id);
        return success(true);
    }

    /**
     * 停止新申请并保留既有授权。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#id")
    @PutMapping("/unpublish")
    @Operation(summary = "停止新申请并保留既有授权")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:publish')")
    public CommonResult<Boolean> unpublishAsset(@RequestParam("id") Long id) {
        dataAssetService.unpublishAsset(id);
        return success(true);
    }

    /**
     * 停止供数并持久触发全部存量授权撤销。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#req.assetId")
    @PutMapping("/stop-supply")
    @Operation(summary = "停止供数并持久触发全部存量授权撤销")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:stop-supply')")
    public CommonResult<Boolean> stopSupply(@Valid @RequestBody AssetCatalogActionReqVO req) {
        dataAssetService.stopSupply(req);
        return success(true);
    }

    /**
     * 重查未知回执或明确拒绝后受控重投。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", action = "处置目录交付", bizId = "#req.assetId")
    @PutMapping("/catalog-delivery/handle")
    @Operation(summary = "重查未知回执或明确拒绝后受控重投")
    @PreAuthorize("@ss.hasPermission('asset:catalog-delivery:handle')")
    public CommonResult<Boolean> handleCatalogDelivery(@Valid @RequestBody AssetCatalogActionReqVO req) {
        dataAssetService.handleCatalogDelivery(req);
        return success(true);
    }

    /**
     * false 表示元数据解绑已排队，目录保留到回执确认后再次完成删除。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#id", recordOnFalse = true,
            action = "提交数据资产退役删除请求（完成状态以业务回执为准）")
    @DeleteMapping("/delete")
    @Operation(summary = "删除未发布的数据资产")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:delete')")
    public CommonResult<Boolean> deleteAsset(@RequestParam("id") Long id) {
        return success(dataAssetService.deleteAsset(id));
    }

    /**
     * 获得数据资产详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据资产详情")
    @Parameter(name = "id", description = "资产编号", required = true)
    @PreAuthorize("isAuthenticated()")
    public CommonResult<DataAssetDetailRespVO> getAssetDetail(@RequestParam("id") Long id) {
        return success(dataAssetService.getAssetDetail(id));
    }

    /**
     * 只读预览资产数据。
     */
    @GetMapping("/preview")
    @Operation(summary = "只读预览资产数据")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:preview')")
    public CommonResult<DatabasePreviewRespDTO> previewAsset(
            @RequestParam("id") Long id,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return success(dataAssetService.previewAsset(id, limit));
    }

    /**
     * 带过滤条件的只读预览资产数据。
     */
    @PostMapping("/preview")
    @Operation(summary = "带过滤条件的只读预览资产数据")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:preview')")
    public CommonResult<DatabasePreviewRespDTO> previewAssetWithFilters(
            @Valid @RequestBody DataAssetPreviewReqVO reqVO) {
        return success(dataAssetService.previewAsset(reqVO));
    }

    /**
     * 创建未挂接技术资源的政务目录草稿。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#result")
    @PostMapping("/draft")
    @Operation(summary = "创建未挂接技术资源的政务目录草稿")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:create')")
    public CommonResult<Long> createCatalogDraft(@Valid @RequestBody AssetCatalogDraftReqVO reqVO) {
        return success(catalogService.createDraft(reqVO.getAssetName(), reqVO.getCategoryId(),
                reqVO.getDescription()));
    }

    /**
     * 挂接技术资源。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#reqVO.assetId")
    @PostMapping("/tech-link")
    @Operation(summary = "挂接技术资源")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Long> addTechLink(@Valid @RequestBody AssetTechLinkSaveReqVO reqVO) {
        return success(catalogService.addTechLink(reqVO));
    }

    /**
     * 查询技术资源关联。
     */
    @GetMapping("/tech-link/list")
    @Operation(summary = "查询技术资源关联")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:query')")
    public CommonResult<List<AssetTechLinkRespVO>> listTechLinks(@RequestParam("assetId") Long assetId) {
        return success(BeanUtils.toBean(catalogService.listTechLinks(assetId), AssetTechLinkRespVO.class));
    }

    /**
     * 提交目录版本审核。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#assetId")
    @PostMapping("/catalog-version/submit")
    @Operation(summary = "提交目录版本审核")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Long> submitCatalogVersion(@RequestParam("assetId") Long assetId,
            @RequestParam(value = "expectedRevision", required = false)
            Long expectedRevision) {
        return success(catalogService.submitCatalogVersion(assetId, expectedRevision));
    }

    /**
     * 审核目录版本。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#reqVO.id")
    @PutMapping("/catalog-version/check")
    @Operation(summary = "审核目录版本")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:publish')")
    public CommonResult<Boolean> checkCatalogVersion(@Valid @RequestBody AssetCatalogVersionCheckReqVO reqVO) {
        catalogService.checkCatalogVersion(reqVO);
        return success(true);
    }

    /**
     * 发布已审核通过的目录版本。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#id")
    @PutMapping("/catalog-version/publish")
    @Operation(summary = "发布已审核通过的目录版本")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:publish')")
    public CommonResult<Boolean> publishCatalogVersion(@RequestParam("id") Long id) {
        catalogService.publishCatalogVersion(id);
        return success(true);
    }

    /**
     * 查询目录业务版本。
     */
    @GetMapping("/catalog-version/list")
    @Operation(summary = "查询目录业务版本")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:query')")
    public CommonResult<List<AssetCatalogVersionRespVO>> listCatalogVersions(
            @RequestParam("assetId") Long assetId) {
        return success(BeanUtils.toBean(catalogService.listCatalogVersions(assetId),
                AssetCatalogVersionRespVO.class));
    }

    /**
     * 只读评估发布证据缺口及历史申请影响。
     */
    @GetMapping("/publication-readiness")
    @Operation(summary = "只读评估发布证据缺口及历史申请影响")
    @PreAuthorize("@ss.hasAnyPermissions('asset:data-asset:update', 'asset:data-asset:publish')")
    public CommonResult<cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetPublicationReadinessRespVO> publicationReadiness(
            @RequestParam("assetId") Long assetId) {
        return success(catalogService.publicationReadiness(assetId));
    }

    /**
     * 提交校核工单。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#result")
    @PostMapping("/ticket")
    @Operation(summary = "提交校核工单")
    @PreAuthorize("@ss.hasAnyPermissions('asset:data-asset:query', 'asset:market:query')")
    public CommonResult<Long> createTicket(@Valid @RequestBody AssetReviewTicketSaveReqVO reqVO) {
        return success(catalogService.createTicket(reqVO));
    }

    /**
     * 认领校核工单。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#id")
    @PutMapping("/ticket/claim")
    @Operation(summary = "认领校核工单")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Boolean> claimTicket(@RequestParam("id") Long id) {
        catalogService.claimTicket(id);
        return success(true);
    }

    /**
     * 反馈校核处理结果。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#id")
    @PutMapping("/ticket/feedback")
    @Operation(summary = "反馈校核处理结果")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Boolean> feedbackTicket(@RequestParam("id") Long id,
            @RequestParam("conclusion") String conclusion) {
        catalogService.feedbackTicket(id, conclusion);
        return success(true);
    }

    /**
     * 复核校核工单。
     */
    @BusinessOperateLog(type = "数据资产-数据资产", bizId = "#id")
    @PutMapping("/ticket/review")
    @Operation(summary = "复核校核工单")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Boolean> reviewTicket(@RequestParam("id") Long id,
            @RequestParam("pass") Boolean pass,
            @RequestParam(value = "conclusion", required = false)
            String conclusion) {
        catalogService.reviewTicket(id, Boolean.TRUE.equals(pass), conclusion);
        return success(true);
    }

    /**
     * 校核工单分页。
     */
    @GetMapping("/ticket/page")
    @Operation(summary = "校核工单分页")
    @PreAuthorize("@ss.hasAnyPermissions('asset:data-asset:query', 'asset:market:query')")
    public CommonResult<PageResult<AssetReviewTicketRespVO>> getTicketPage(
            @Valid AssetReviewTicketPageReqVO reqVO) {
        PageResult<AssetReviewTicketDO> page = catalogService.getTicketPage(reqVO);
        return success(new PageResult<>(BeanUtils.toBean(page.getList(), AssetReviewTicketRespVO.class),
                page.getTotal()));
    }

}
