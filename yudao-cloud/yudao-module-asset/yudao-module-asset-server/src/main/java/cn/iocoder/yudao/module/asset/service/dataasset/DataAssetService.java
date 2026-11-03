package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;

import java.util.List;

public interface DataAssetService {

    /**
     * 获得数据资产分页。
     */
    PageResult<DataAssetRespVO> getAssetPage(DataAssetPageReqVO reqVO);

    /**
     * 读取已发布资产分页。
     */
    PageResult<DataAssetRespVO> getPublishedAssetPage(DataAssetPageReqVO reqVO);

    /**
     * 读取已发布市场分页。
     */
    PageResult<DataAssetRespVO> getPublishedMarketPage(
            cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPageReqVO reqVO,
            Long userId, java.util.Collection<Long> authorizedIds, java.util.Collection<Long> marketCategoryIds);

    /**
     * 读取已发布资产详情。
     */
    DataAssetDetailRespVO getPublishedAssetDetail(Long id);

    /**
     * 左侧分组树统计：类目 / 主题域 / 数仓分层
     */
    DataAssetFacetRespVO getAssetFacets();

    /**
     * 读取导航分类。
     */
    java.util.List<cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.CategoryDataAssetRespVO> getNavigationCategories();

    /**
     * 读取已发布资产分面。
     */
    DataAssetFacetRespVO getPublishedAssetFacets();

    /**
     * 获得待编目的已发布元数据分页。
     */
    PageResult<MetadataTableRespDTO> getCandidatePage(DataAssetCandidatePageReqVO reqVO);

    /**
     * 批量编目数据资产。
     */
    List<Long> batchRegisterAsset(DataAssetBatchRegisterReqVO reqVO);

    /**
     * 维护数据资产。
     */
    void updateAsset(DataAssetUpdateReqVO reqVO);

    /**
     * 维护资产字段的业务与标准属性。
     */
    void updateAssetColumn(DataAssetColumnUpdateReqVO reqVO);

    /**
     * 读取资产范围内当前字段的治理属性及修订号。
     */
    DataAssetColumnRespVO getAssetColumn(Long assetId, Long id);

    /**
     * 刷新资产引用的元数据定版与运行指标。
     */
    void refreshAsset(Long id);

    /**
     * 发布数据资产。
     */
    void publishAsset(Long id);

    /**
     * 停止新申请并保留既有授权。
     */
    void unpublishAsset(Long id);

    /** false 表示元数据解绑已排队，目录保留到回执确认后再次完成删除。 */
    boolean deleteAsset(Long id);

    /**
     * 获得数据资产详情。
     */
    DataAssetDetailRespVO getAssetDetail(Long id);

    /**
     * 读取资产详情。
     */
    DataAssetDetailRespVO getAssetDetail(Long id, boolean countView);

    /**
     * 只读预览资产数据。
     */
    DatabasePreviewRespDTO previewAsset(Long id, Integer limit);

    /**
     * 带过滤条件的只读预览
     */
    DatabasePreviewRespDTO previewAsset(DataAssetPreviewReqVO reqVO);

    /**
     * 停止供数并持久触发全部存量授权撤销。
     */
    void stopSupply(cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetCatalogActionReqVO req);

    /**
     * 重查未知回执或明确拒绝后受控重投。
     */
    void handleCatalogDelivery(cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetCatalogActionReqVO req);
}
