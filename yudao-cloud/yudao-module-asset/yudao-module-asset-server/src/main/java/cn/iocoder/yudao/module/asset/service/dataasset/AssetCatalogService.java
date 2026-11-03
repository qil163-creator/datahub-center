package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetCatalogVersionCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetReviewTicketPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetReviewTicketSaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetTechLinkSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetCatalogVersionDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetReviewTicketDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetTechLinkDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;

import java.util.List;

public interface AssetCatalogService {

    /**
     * 创建草稿。
     */
    Long createDraft(String assetName, Long categoryId, String description);

    /**
     * 添加技术关联。
     */
    Long addTechLink(AssetTechLinkSaveReqVO reqVO);

    /**
     * 查询技术关联。
     */
    List<AssetTechLinkDO> listTechLinks(Long assetId);

    /**
     * 查询资产目录字段。
     */
    List<MetadataColumnRespDTO> listColumns(Long assetId, Long snapshotId);

    /**
     * 校验资产目录的单个表兼容性，不满足约束时拒绝当前操作。
     */
    void assertSingleTableCompatible(DataAssetDO asset);

    /**
     * 提交目录版本。
     */
    Long submitCatalogVersion(Long assetId, Long expectedRevision);

    /**
     * 检查资产目录的目录版本，不满足约束时拒绝当前操作。
     */
    void checkCatalogVersion(AssetCatalogVersionCheckReqVO reqVO);

    /**
     * 发布目录版本。
     */
    void publishCatalogVersion(Long versionId);

    /** 兼容旧发布入口，仅发布当前内容对应的最新已审核版本。 */
    void publishLatestApproved(Long assetId);

    /** 调用方先预取身份及权限，再在持锁事务中复用同一发布状态机。 */
    void publishLatestApproved(Long assetId, AssetMaintenanceAuthorization authorization);

    /**
     * 准备资产目录的维护授权。
     */
    AssetMaintenanceAuthorization prepareMaintenanceAuthorization();

    /**
     * 核验当前用户对资产目录的维护权限。
     */
    void assertCanMaintain(DataAssetDO asset, AssetMaintenanceAuthorization authorization);

    /** 市场消费冻结发布内容；没有可信发布快照时拒绝回退草稿。 */
    DataAssetDO publishedAsset(DataAssetDO asset);

    /**
     * 判断资产目录是否具有完成已发布快照。
     */
    boolean hasCompletePublishedSnapshot(DataAssetDO asset);

    /**
     * 汇总资产目录发布前置条件与未满足项。
     */
    cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetPublicationReadinessRespVO publicationReadiness(
            Long assetId);

    /**
     * 校验资产目录的完成已发布资产，不满足约束时拒绝当前操作。
     */
    DataAssetDO requireCompletePublishedAsset(DataAssetDO asset);

    /**
     * 查询目录版本。
     */
    List<AssetCatalogVersionDO> listCatalogVersions(Long assetId);

    /**
     * 创建工单。
     */
    Long createTicket(AssetReviewTicketSaveReqVO reqVO);

    /**
     * 领取工单。
     */
    void claimTicket(Long id);

    /**
     * 提交资产目录校核工单反馈并更新处理状态。
     */
    void feedbackTicket(Long id, String conclusion);

    /**
     * 复核工单。
     */
    void reviewTicket(Long id, boolean pass, String conclusion);

    /**
     * 读取工单分页。
     */
    PageResult<AssetReviewTicketDO> getTicketPage(AssetReviewTicketPageReqVO reqVO);

    /**
     * 核验当前用户对资产目录的维护权限。
     */
    void assertCanMaintain(DataAssetDO asset);

    /**
     * 同步主对象投影。
     */
    void syncPrimaryProjection(Long assetId);

    /**
     * 校验资产目录的技术可修改，不满足约束时拒绝当前操作。
     */
    void assertTechnicalMutable(DataAssetDO asset);

    /**
     * 读取资产目录发布快照中的标签。
     */
    java.util.List<cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagRespVO> publishedTags(
            DataAssetDO asset);
}
