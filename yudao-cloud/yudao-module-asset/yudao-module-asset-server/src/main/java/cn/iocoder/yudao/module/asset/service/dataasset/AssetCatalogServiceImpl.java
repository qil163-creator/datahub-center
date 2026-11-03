package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetCatalogVersionCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetReviewTicketPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetReviewTicketSaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetTechLinkSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetCatalogVersionDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetReviewTicketDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetTechLinkDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetCatalogVersionMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetReviewTicketMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetTechLinkMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Slf4j
@Service
@Validated
public class AssetCatalogServiceImpl implements AssetCatalogService {

    @Resource
    private AssetImportSnapshotService importSnapshots;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private AssetTechLinkMapper techLinkMapper;

    @Resource
    private AssetCatalogVersionMapper versionMapper;

    @Resource
    private AssetReviewTicketMapper ticketMapper;

    @Resource
    private MetadataApi metadataApi;

    @Resource
    private PermissionApi permissionApi;

    @Resource
    private AssetCatalogLinkService catalogLinkService;

    @Resource
    private cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetSupplyStopMapper stopMapper;

    @Resource
    private cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder audit;

    @Resource
    private cn.iocoder.yudao.module.asset.service.assettag.AssetTagService tagService;

    /**
     * 创建草稿。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDraft(String assetName, Long categoryId, String description) {
        if (StrUtil.isBlank(assetName)) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "名称");
        }
        DataAssetDO asset = DataAssetDO.builder()
                .assetCode("TMP")
                .catalogItemUrn("tmp")
                .assetName(assetName)
                .assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .categoryId(categoryId)
                .description(description)
                .status(DataAssetDO.STATUS_UNPUBLISHED)
                .shareAttribute(DataAssetDO.SHARE_PENDING)
                .shareAttr(DataAssetDO.SHARE_PENDING)
                .techAvailable(0)
                .catalogRevision(0L)
                .ownerDeptId(SecurityFrameworkUtils.getLoginUserDeptId())
                .providerDeptId(SecurityFrameworkUtils.getLoginUserDeptId())
                .viewCount(0L)
                .build();
        assetMapper.insert(asset);
        String code = "GOV-" + asset.getId();
        assetMapper.update(null, Wrappers.lambdaUpdate(DataAssetDO.class)
                .set(DataAssetDO::getAssetCode, code)
                .set(DataAssetDO::getCatalogItemUrn, "urn:datahub:asset:catalog:" + asset.getId())
                .eq(DataAssetDO::getId, asset.getId()));
        return asset.getId();
    }

    /**
     * 添加技术关联。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addTechLink(AssetTechLinkSaveReqVO reqVO) {
        DataAssetDO asset = requireAsset(reqVO.getAssetId());
        assertCanMaintain(asset);
        if (techLinkMapper.selectByAssetAndMeta(reqVO.getAssetId(), reqVO.getMetaTableId()) != null) {
            throw exception(DATA_ASSET_TECH_LINK_DUPLICATE);
        }
        MetadataTableRespDTO metadata = metadataApi.getTable(reqVO.getMetaTableId()).getCheckedData();
        if (metadata == null) {
            throw exception(DATA_ASSET_META_NOT_EXISTS);
        }
        if (!Objects.equals(metadata.getPublishStatus(), 1)
                || metadata.getPublishedSnapshotId() == null
                || StrUtil.isBlank(metadata.getPublishedVersion())) {
            throw exception(DATA_ASSET_META_NOT_PUBLISHED);
        }
        asset = assetMapper.selectForCatalogUpdate(asset.getId());
        assertTechnicalMutable(asset);
        boolean first = techLinkMapper.selectListByAssetId(reqVO.getAssetId()).isEmpty();
        AssetTechLinkDO link = AssetTechLinkDO.builder()
                .assetId(reqVO.getAssetId())
                .providerModule("governance")
                .resourceUrn(metadata.getResourceUrn())
                .datasourceId(metadata.getDatasourceId())
                .schemaName(metadata.getSchemaName())
                .objectName(metadata.getTableName())
                .metaTableId(metadata.getId())
                .snapshotId(metadata.getPublishedSnapshotId())
                .primaryFlag(first || Objects.equals(reqVO.getPrimaryFlag(), 1) ? 1 : 0)
                .build();
        techLinkMapper.insert(link);
        if (Objects.equals(link.getPrimaryFlag(), 1)) {
            techLinkMapper.update(null, Wrappers.lambdaUpdate(AssetTechLinkDO.class)
                    .set(AssetTechLinkDO::getPrimaryFlag, 0)
                    .eq(AssetTechLinkDO::getAssetId, reqVO.getAssetId())
                    .ne(AssetTechLinkDO::getId, link.getId()));
        }
        syncPrimaryProjection(reqVO.getAssetId());
        casRevision(asset, null);
        catalogLinkService.enqueue(asset, metadata.getId(), true);
        return link.getId();
    }

    /**
     * 查询技术关联。
     */
    @Override
    public List<AssetTechLinkDO> listTechLinks(Long assetId) {
        assertCanMaintain(requireAsset(assetId));
        return techLinkMapper.selectListByAssetId(assetId);
    }

    /**
     * 查询资产目录字段。
     */
    @Override
    public List<MetadataColumnRespDTO> listColumns(Long assetId, Long snapshotId) {
        DataAssetDO asset = requireAsset(assetId);
        if (snapshotId == null && AssetImportSnapshotService.supports(asset)) {
            return importSnapshots.columns(assetId);
        }
        if (snapshotId != null) {
            return metadataApi.getColumnListBySnapshot(snapshotId).getCheckedData();
        }
        AssetCatalogVersionDO published = asset.getPublishedCatalogVersionId() == null ? null
                : versionMapper.selectById(asset.getPublishedCatalogVersionId());
        if (published != null && published.getTechSnapshotId() != null) {
            return metadataApi.getColumnListBySnapshot(published.getTechSnapshotId()).getCheckedData();
        }
        if (asset.getMetaTableId() == null) {
            return List.of();
        }
        return metadataApi.getPublishedColumnList(asset.getMetaTableId()).getCheckedData();
    }

    /**
     * 校验资产目录的单个表兼容性，不满足约束时拒绝当前操作。
     */
    @Override
    public void assertSingleTableCompatible(DataAssetDO asset) {
        List<AssetTechLinkDO> links = techLinkMapper.selectListByAssetId(asset.getId());
        if (links.isEmpty() && asset.getMetaTableId() == null) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        if (links.size() > 1) {
            throw exception(DATA_ASSET_MULTI_TABLE_AMBIGUOUS);
        }
    }

    /**
     * 提交目录版本。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitCatalogVersion(Long assetId, Long expectedRevision) {
        DataAssetDO asset = requireAsset(assetId);
        assertCanMaintain(asset);
        asset = assetMapper.selectForCatalogUpdate(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (Boolean.TRUE.equals(asset.getRetirementRequested())) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "目录已进入退役，请完成解绑删除");
        }
        catalogLinkService.assertSettled(assetId);
        casRevision(asset, expectedRevision);
        Map<String, Object> content = snapshotContent(asset);
        int nextNo = versionMapper.selectMaxVersionNo(assetId) + 1;
        AssetTechLinkDO primary = techLinkMapper.selectPrimary(assetId);
        AssetCatalogVersionDO version = AssetCatalogVersionDO.builder()
                .assetId(assetId)
                .versionNo(nextNo)
                .contentJson(JsonUtils.toJsonString(content))
                .contentHash(DigestUtil.sha256Hex(JsonUtils.toJsonString(content)))
                .techSnapshotId(primary == null ? asset.getPublishedSnapshotId() : primary.getSnapshotId())
                .status(AssetCatalogVersionDO.STATUS_SUBMITTED)
                .submitUserId(SecurityFrameworkUtils.getLoginUserId())
                .submitTime(LocalDateTime.now())
                .build();
        versionMapper.insert(version);
        audit.record("CATALOG_SUBMIT", "CATALOG", assetId, null, version.getStatus(), "版本 #" + nextNo, "OK");
        return version.getId();
    }

    /**
     * 检查资产目录的目录版本，不满足约束时拒绝当前操作。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkCatalogVersion(AssetCatalogVersionCheckReqVO reqVO) {
        AssetCatalogVersionDO version = versionMapper.selectById(reqVO.getId());
        if (version == null) {
            throw exception(DATA_ASSET_VERSION_NOT_EXISTS);
        }
        if (!Objects.equals(version.getStatus(), AssetCatalogVersionDO.STATUS_SUBMITTED)) {
            throw exception(DATA_ASSET_VERSION_STATUS_DENY);
        }
        DataAssetDO asset = requireAsset(version.getAssetId());
        assertCanMaintain(asset);
        boolean pass = Objects.equals(reqVO.getStatus(), AssetCatalogVersionDO.STATUS_APPROVED);
        if (versionMapper.update(null, Wrappers.lambdaUpdate(AssetCatalogVersionDO.class)
                .set(AssetCatalogVersionDO::getStatus, pass
                        ? AssetCatalogVersionDO.STATUS_APPROVED : AssetCatalogVersionDO.STATUS_RETURNED)
                .set(AssetCatalogVersionDO::getCheckUserId, SecurityFrameworkUtils.getLoginUserId())
                .set(AssetCatalogVersionDO::getCheckTime, LocalDateTime.now())
                .set(AssetCatalogVersionDO::getCheckNote, reqVO.getCheckNote())
                .eq(AssetCatalogVersionDO::getId, version.getId())
                .eq(AssetCatalogVersionDO::getStatus, AssetCatalogVersionDO.STATUS_SUBMITTED)) != 1) {
            throw exception(DATA_ASSET_VERSION_STATUS_DENY);
        }
        audit.record("CATALOG_CHECK", "CATALOG", asset.getId(), version.getStatus(), reqVO.getStatus(),
                reqVO.getCheckNote(), "OK");
    }

    /**
     * 发布目录版本。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishCatalogVersion(Long versionId) {
        publishCatalogVersion(versionId, null);
    }

    /**
     * 发布目录版本。
     */
    private void publishCatalogVersion(Long versionId, AssetMaintenanceAuthorization authorization) {
        AssetCatalogVersionDO version = versionMapper.selectById(versionId);
        if (version == null) {
            throw exception(DATA_ASSET_VERSION_NOT_EXISTS);
        }
        if (!Objects.equals(version.getStatus(), AssetCatalogVersionDO.STATUS_APPROVED)
                && !Objects.equals(version.getStatus(), AssetCatalogVersionDO.STATUS_PUBLISHED)) {
            throw exception(DATA_ASSET_VERSION_STATUS_DENY);
        }
        if (authorization == null) {
            assertCanMaintain(requireAsset(version.getAssetId()));
        }
        DataAssetDO asset = assetMapper.selectForCatalogUpdate(version.getAssetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (authorization != null) {
            assertCanMaintain(asset, authorization);
        }
        if (Boolean.TRUE.equals(asset.getRetirementRequested())) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "目录已进入退役，不能重新发布");
        }
        boolean resume = Objects.equals(version.getStatus(), AssetCatalogVersionDO.STATUS_PUBLISHED);
        if (resume && !Objects.equals(asset.getPublishedCatalogVersionId(), versionId)) {
            throw exception(DATA_ASSET_VERSION_STATUS_DENY);
        }
        if (Objects.equals(asset.getStatus(), DataAssetDO.STATUS_OFFLINE)
                && stopMapper.pending(asset.getId()) > 0) {
            throw exception(DATA_ASSET_RETIRE_PENDING);
        }
        DataAssetDO frozen = AssetPublishedSnapshot.read(version.getContentJson(), asset.getId());

        if (!Objects.equals(asset.getPublishedCatalogVersionId(), versionId)
                && (stopMapper.openApplications(asset.getId()) > 0 || stopMapper.pending(asset.getId()) > 0
                || stopMapper.unclosedSources(asset.getId()) > 0)) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY,
                    "目录合同版本将变化，请先处置旧申请并确认显式撤权，不能将存量授权迁移到新版本");
        }
        if (!Objects.equals(asset.getMetaTableId(), frozen.getMetaTableId())
                || !Objects.equals(asset.getDatasourceId(), frozen.getDatasourceId())
                || !Objects.equals(asset.getSchemaName(), frozen.getSchemaName())
                || !Objects.equals(asset.getTableName(), frozen.getTableName())
                || !Objects.equals(asset.getPublishedSnapshotId(), frozen.getPublishedSnapshotId())) {
            assertTechnicalMutable(asset);
        }
        catalogLinkService.assertSettled(asset.getId());
        if (frozen.getCategoryId() == null) {
            throw exception(DATA_ASSET_PUBLISH_CATEGORY_REQUIRED);
        }
        if (frozen.resolveShareAttr() == DataAssetDO.SHARE_PENDING) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "共享属性");
        }
        if (!resume && versionMapper.update(null, Wrappers.lambdaUpdate(AssetCatalogVersionDO.class)
                .set(AssetCatalogVersionDO::getStatus, AssetCatalogVersionDO.STATUS_PUBLISHED)
                .set(AssetCatalogVersionDO::getPublishTime, LocalDateTime.now())
                .eq(AssetCatalogVersionDO::getId, versionId)
                .eq(AssetCatalogVersionDO::getStatus, AssetCatalogVersionDO.STATUS_APPROVED)) != 1) {
            throw exception(DATA_ASSET_VERSION_STATUS_DENY);
        }
        assetMapper.update(null, Wrappers.lambdaUpdate(DataAssetDO.class)
                .set(DataAssetDO::getStatus, DataAssetDO.STATUS_PUBLISHED)
                .set(DataAssetDO::getPublishedCatalogVersionId, versionId)
                .set(DataAssetDO::getMetaTableId, frozen.getMetaTableId())
                .set(DataAssetDO::getDatasourceId, frozen.getDatasourceId())
                .set(DataAssetDO::getSchemaName, frozen.getSchemaName())
                .set(DataAssetDO::getTableName, frozen.getTableName())
                .set(DataAssetDO::getResourceUrn, frozen.getResourceUrn())
                .set(DataAssetDO::getPublishedSnapshotId, frozen.getPublishedSnapshotId())
                .set(DataAssetDO::getPublishedVersion, frozen.getPublishedVersion())
                .set(DataAssetDO::getStopSupplyUserId, null)
                .set(DataAssetDO::getStopSupplyDeptId, null)
                .set(DataAssetDO::getStopSupplyReason, null)
                .set(DataAssetDO::getStopSupplyTime, null)
                .set(DataAssetDO::getPublishTime, LocalDateTime.now())
                .set(DataAssetDO::getPublisher, String.valueOf(SecurityFrameworkUtils.getLoginUserId()))
                .eq(DataAssetDO::getId, asset.getId()));
        audit.record("CATALOG_PUBLISH", "CATALOG", asset.getId(), asset.getStatus(), DataAssetDO.STATUS_PUBLISHED,
                "审核版本 #" + version.getVersionNo(), "OK");
    }

    /**
     * 兼容旧发布入口，仅发布当前内容对应的最新已审核版本。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishLatestApproved(Long assetId) {
        publishLatestApproved(assetId, null);
    }

    /**
     * 调用方先预取身份及权限，再在持锁事务中复用同一发布状态机。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishLatestApproved(Long assetId, AssetMaintenanceAuthorization authorization) {
        AssetCatalogVersionDO latest = versionMapper.selectListByAssetId(assetId).stream()
                .filter(item -> Objects.equals(item.getStatus(), AssetCatalogVersionDO.STATUS_APPROVED))
                .findFirst().orElse(null);
        if (latest == null) {
            DataAssetDO asset = requireAsset(assetId);
            latest = asset.getPublishedCatalogVersionId() == null ? null : versionMapper.selectById(
                    asset.getPublishedCatalogVersionId());
        }
        if (latest == null) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "已审核目录版本");
        }
        publishCatalogVersion(latest.getId(), authorization); // 当前入口已有真实事务；复用唯一发布不变量。
    }

    /**
     * 市场消费冻结发布内容；没有可信发布快照时拒绝回退草稿。
     */
    @Override
    public DataAssetDO publishedAsset(DataAssetDO current) {
        if (current == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        AssetCatalogVersionDO version = current.getPublishedCatalogVersionId() == null ? null
                : versionMapper.selectById(current.getPublishedCatalogVersionId());
        if (version != null && (!Objects.equals(version.getAssetId(), current.getId())
                || !Objects.equals(version.getStatus(), AssetCatalogVersionDO.STATUS_PUBLISHED))) {
            version = null;
        }
        DataAssetDO frozen;
        try {
            frozen = version == null ? null : AssetPublishedSnapshot.read(version.getContentJson(), current.getId());
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException legacy) {
            frozen = null;
        }
        if (frozen == null) {
            frozen = AssetPublishedSnapshot.historical(version == null ? null : version.getContentJson(),
                    current.getId());
        }
        frozen.setStatus(current.getStatus());
        frozen.setPublishedCatalogVersionId(version == null ? null : version.getId());
        frozen.setPublishTime(version == null ? null : version.getPublishTime());
        frozen.setPublishedSnapshotId(version == null ? null : version.getTechSnapshotId());
        frozen.setViewCount(current.getViewCount());
        return frozen;
    }

    /**
     * 判断资产目录是否具有完成已发布快照。
     */
    @Override
    public boolean hasCompletePublishedSnapshot(DataAssetDO asset) {
        try {
            requireCompletePublishedAsset(asset);
            return true;
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException legacy) {
            return false;
        }
    }

    /**
     * 汇总资产目录发布前置条件与未满足项。
     */
    @Override
    public cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetPublicationReadinessRespVO publicationReadiness(
            Long assetId) {
        DataAssetDO asset = requireAsset(assetId);
        assertCanMaintain(asset); // 责任人或有维护职责的人员；不能以传入资产编号跳过范围。

        var result = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetPublicationReadinessRespVO();
        result.setAssetId(assetId);
        result.setCatalogRevision(asset.getCatalogRevision());
        result.setPublishedEvidenceComplete(hasCompletePublishedSnapshot(asset));
        result.setOpenApplications(stopMapper.openApplications(assetId));
        result.setPendingRevocations(stopMapper.pending(assetId));
        result.setUnclosedSources(stopMapper.unclosedSources(assetId));
        var missing = new java.util.ArrayList<String>();
        if (!result.isPublishedEvidenceComplete()) {
            missing.add("完整的已审核目录发布快照");
        }
        if (asset.getCategoryId() == null) {
            missing.add("资产分类");
        }
        if (asset.resolveShareAttr() == DataAssetDO.SHARE_PENDING) {
            missing.add("确认共享属性");
        }
        if (Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
            if (asset.getMetaTableId() == null || asset.getDatasourceId() == null || StrUtil.isBlank(
                    asset.getSchemaName()) || StrUtil.isBlank(asset.getTableName())) {
                missing.add("数据源、模式、表的精确技术绑定");
            }
            if (asset.getPublishedSnapshotId() == null || StrUtil.isBlank(asset.getPublishedVersion())) {
                missing.add("已发布元数据快照与版本");
            }
        }
        try {
            catalogLinkService.assertSettled(assetId);
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException unsettled) {
            missing.add("目录挂接尚未全部确认，请稍后刷新或联系管理员核查");
        }
        if (Boolean.TRUE.equals(asset.getRetirementRequested())) {
            missing.add("目录正在退役，不能提交新发布");
        }
        result.setMissingItems(List.copyOf(missing));
        var steps = new java.util.ArrayList<String>();
        if (result.getOpenApplications() > 0 || result.getPendingRevocations() > 0 || result.getUnclosedSources() > 0) {
            steps.add("先逐项核对在途申请和授权；需要换发目录合同时，显式停止供数并完成申请处置、撤权回执确认");
        }
        steps.add("在资产主档补齐分类、共享属性和责任信息；技术绑定变更必须先满足停止供数条件");
        steps.add("核对已发布元数据快照及挂接交付，提交新的目录版本，保存本次完整冻结内容");
        steps.add("审核人核对冻结内容并填写意见，通过后显式发布；旧审批记录不回填、不迁移到新版本");
        steps.add("刷新本评估确认发布证据完整，再以新目录版本提交共享申请");
        result.setSteps(List.copyOf(steps));
        return result;
    }

    /**
     * 校验资产目录的完成已发布资产，不满足约束时拒绝当前操作。
     */
    @Override
    public DataAssetDO requireCompletePublishedAsset(DataAssetDO asset) {
        AssetCatalogVersionDO version = asset == null || asset.getPublishedCatalogVersionId() == null ? null
                : versionMapper.selectById(asset.getPublishedCatalogVersionId());
        if (version == null || !Objects.equals(version.getAssetId(), asset.getId())
                || !Objects.equals(version.getStatus(), AssetCatalogVersionDO.STATUS_PUBLISHED)) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "完整发布证据，请提供方重新提交审核");
        }
        AssetPublishedSnapshot.read(version.getContentJson(), asset.getId());
        return publishedAsset(asset);
    }

    /**
     * 读取资产目录发布快照中的标签。
     */
    @Override
    public List<cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagRespVO> publishedTags(
            DataAssetDO asset) {
        if (asset == null || asset.getPublishedCatalogVersionId() == null) {
            return List.of();
        }
        var version = versionMapper.selectById(asset.getPublishedCatalogVersionId());
        if (version == null || !Objects.equals(version.getAssetId(), asset.getId())
                || !Objects.equals(version.getStatus(), AssetCatalogVersionDO.STATUS_PUBLISHED)) {
            return List.of();
        }
        try {
            var snapshot = JsonUtils.parseObject(version.getContentJson(), AssetPublishedSnapshot.class);
            return snapshot == null || snapshot.getTags() == null ? List.of() : snapshot.getTags();
        } catch (RuntimeException legacy) {
            return List.of();
        }
    }

    /**
     * 查询目录版本。
     */
    @Override
    public List<AssetCatalogVersionDO> listCatalogVersions(Long assetId) {
        assertCanMaintain(requireAsset(assetId));
        return versionMapper.selectListByAssetId(assetId);
    }

    /**
     * 创建工单。
     */
    @Override
    public Long createTicket(AssetReviewTicketSaveReqVO reqVO) {
        requireAsset(reqVO.getAssetId());
        AssetReviewTicketDO ticket = AssetReviewTicketDO.builder()
                .assetId(reqVO.getAssetId())
                .catalogVersionId(reqVO.getCatalogVersionId())
                .issueType(reqVO.getIssueType())
                .evidence(reqVO.getEvidence())
                .demandDeptId(SecurityFrameworkUtils.getLoginUserDeptId())
                .status(AssetReviewTicketDO.STATUS_SUBMITTED)
                .build();
        ticketMapper.insert(ticket);
        return ticket.getId();
    }

    /**
     * 领取工单。
     */
    @Override
    public void claimTicket(Long id) {
        AssetReviewTicketDO ticket = requireTicket(id);
        if (!Objects.equals(ticket.getStatus(), AssetReviewTicketDO.STATUS_SUBMITTED)) {
            throw exception(DATA_ASSET_TICKET_STATUS_DENY);
        }
        ticketMapper.update(null, Wrappers.lambdaUpdate(AssetReviewTicketDO.class)
                .set(AssetReviewTicketDO::getStatus, AssetReviewTicketDO.STATUS_CLAIMED)
                .set(AssetReviewTicketDO::getHandleDeptId, SecurityFrameworkUtils.getLoginUserDeptId())
                .eq(AssetReviewTicketDO::getId, id)
                .eq(AssetReviewTicketDO::getStatus, AssetReviewTicketDO.STATUS_SUBMITTED));
    }

    /**
     * 提交资产目录校核工单反馈并更新处理状态。
     */
    @Override
    public void feedbackTicket(Long id, String conclusion) {
        AssetReviewTicketDO ticket = requireTicket(id);
        if (ticket.getStatus() < AssetReviewTicketDO.STATUS_CLAIMED
                || ticket.getStatus() > AssetReviewTicketDO.STATUS_FIXING) {
            throw exception(DATA_ASSET_TICKET_STATUS_DENY);
        }
        ticketMapper.update(null, Wrappers.lambdaUpdate(AssetReviewTicketDO.class)
                .set(AssetReviewTicketDO::getStatus, AssetReviewTicketDO.STATUS_FEEDBACK)
                .set(AssetReviewTicketDO::getConclusion, conclusion)
                .eq(AssetReviewTicketDO::getId, id));
    }

    /**
     * 复核工单。
     */
    @Override
    public void reviewTicket(Long id, boolean pass, String conclusion) {
        AssetReviewTicketDO ticket = requireTicket(id);
        if (!Objects.equals(ticket.getStatus(), AssetReviewTicketDO.STATUS_FEEDBACK)
                && !Objects.equals(ticket.getStatus(), AssetReviewTicketDO.STATUS_REVIEW)) {
            throw exception(DATA_ASSET_TICKET_STATUS_DENY);
        }
        ticketMapper.update(null, Wrappers.lambdaUpdate(AssetReviewTicketDO.class)
                .set(AssetReviewTicketDO::getStatus, pass
                        ? AssetReviewTicketDO.STATUS_DONE : AssetReviewTicketDO.STATUS_RETURNED)
                .set(AssetReviewTicketDO::getConclusion, conclusion)
                .eq(AssetReviewTicketDO::getId, id));
    }

    /**
     * 读取工单分页。
     */
    @Override
    public PageResult<AssetReviewTicketDO> getTicketPage(AssetReviewTicketPageReqVO reqVO) {
        return ticketMapper.selectPage(reqVO);
    }

    /**
     * 核验当前用户对资产目录的维护权限。
     */
    @Override
    public void assertCanMaintain(DataAssetDO asset) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        Long deptId = SecurityFrameworkUtils.getLoginUserDeptId();
        if (userId == null) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        Boolean superAdmin = permissionApi.hasAnyRoles(userId, "super_admin").getCheckedData();
        if (Boolean.TRUE.equals(superAdmin)) {
            return;
        }
        if (Objects.equals(asset.getTechLeaderUserId(), userId)
                || Objects.equals(asset.getBusinessLeaderUserId(), userId)
                || (deptId != null && (Objects.equals(asset.getOwnerDeptId(), deptId)
                || Objects.equals(asset.getProviderDeptId(), deptId)))
                || Objects.equals(String.valueOf(userId), asset.getCreator())) {
            return;
        }
        Boolean operator = permissionApi.hasAnyPermissions(userId,
                "asset:data-asset:update", "asset:data-asset:publish").getCheckedData();
        if (!Boolean.TRUE.equals(operator)) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
    }

    /**
     * 准备资产目录的维护授权。
     */
    @Override
    public AssetMaintenanceAuthorization prepareMaintenanceAuthorization() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        Long deptId = SecurityFrameworkUtils.getLoginUserDeptId();
        if (userId == null) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        boolean superAdmin = Boolean.TRUE.equals(permissionApi.hasAnyRoles(userId, "super_admin").getCheckedData());
        boolean operator = !superAdmin && Boolean.TRUE.equals(permissionApi.hasAnyPermissions(userId,
                "asset:data-asset:update", "asset:data-asset:publish").getCheckedData());
        return new AssetMaintenanceAuthorization(userId, deptId, superAdmin, operator);
    }

    /**
     * 核验当前用户对资产目录的维护权限。
     */
    @Override
    public void assertCanMaintain(DataAssetDO asset, AssetMaintenanceAuthorization authorization) {
        if (authorization == null || authorization.getUserId() == null || authorization.getUserId() <= 0 || asset == null
                || !Objects.equals(authorization.getUserId(), SecurityFrameworkUtils.getLoginUserId())
                || !Objects.equals(authorization.getDeptId(), SecurityFrameworkUtils.getLoginUserDeptId())) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        if (authorization.canMaintain(asset)) {
            return;
        }
        throw exception(DATA_ASSET_FORBIDDEN);
    }

    /**
     * 同步主对象投影。
     */
    @Override
    public void syncPrimaryProjection(Long assetId) {
        List<AssetTechLinkDO> links = techLinkMapper.selectListByAssetId(assetId);
        AssetTechLinkDO primary = links.stream()
                .filter(item -> Objects.equals(item.getPrimaryFlag(), 1))
                .findFirst()
                .orElse(links.size() == 1 ? links.getFirst() : null);
        boolean available = primary != null && primary.getDatasourceId() != null
                && StrUtil.isNotBlank(primary.getObjectName());
        assetMapper.update(null, Wrappers.lambdaUpdate(DataAssetDO.class)
                .set(DataAssetDO::getMetaTableId, primary == null ? null : primary.getMetaTableId())
                .set(DataAssetDO::getDatasourceId, primary == null ? null : primary.getDatasourceId())
                .set(DataAssetDO::getSchemaName, primary == null ? null : primary.getSchemaName())
                .set(DataAssetDO::getTableName, primary == null ? null : primary.getObjectName())
                .set(DataAssetDO::getResourceUrn, primary == null ? null : primary.getResourceUrn())
                .set(DataAssetDO::getPublishedSnapshotId, primary == null ? null : primary.getSnapshotId())
                .set(DataAssetDO::getTechAvailable, available ? 1 : 0)
                .eq(DataAssetDO::getId, assetId));
    }

    /**
     * 校验资产目录的技术可修改，不满足约束时拒绝当前操作。
     */
    @Override
    public void assertTechnicalMutable(DataAssetDO asset) {
        if (Boolean.TRUE.equals(asset.getRetirementRequested())) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "目录已进入退役，不能再挂接资源");
        }

        if (asset.getPublishedCatalogVersionId() == null && !Objects.equals(asset.getStatus(),
                DataAssetDO.STATUS_PUBLISHED)
                && stopMapper.openApplications(asset.getId()) == 0 && stopMapper.pending(asset.getId()) == 0
                && stopMapper.unclosedSources(asset.getId()) == 0) {
            return;
        }
        if (!Objects.equals(asset.getStatus(), DataAssetDO.STATUS_OFFLINE)
                || stopMapper.openApplications(asset.getId()) > 0 || stopMapper.pending(asset.getId()) > 0
                || stopMapper.unclosedSources(asset.getId()) > 0) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "技术绑定变更需先停止供数并完成申请处置与撤权");
        }
    }

    /**
     * 汇总资产目录版本的冻结内容，供后续审批、发布与一致性核验使用。
     */
    private Map<String, Object> snapshotContent(DataAssetDO asset) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", AssetPublishedSnapshot.FORMAT);
        content.put("asset", asset);
        content.put("tags",
                tagService.getTagsByAssetIds(List.of(asset.getId())).getOrDefault(asset.getId(), List.of()));
        content.put("assetName", asset.getAssetName());
        content.put("description", asset.getDescription());
        content.put("shareAttr", asset.getShareAttr());
        content.put("shareCondition", asset.getShareCondition());
        content.put("shareDenyReason", asset.getShareDenyReason());
        content.put("providerDeptId", asset.getProviderDeptId());
        content.put("sourceDeptId", asset.getSourceDeptId());
        content.put("dutyBasis", asset.getDutyBasis());
        content.put("updateRule", asset.getUpdateRule());
        content.put("techLinks", techLinkMapper.selectListByAssetId(asset.getId()));
        return content;
    }

    /**
     * 按预期修订号更新资产目录版本，拒绝并发冲突。
     */
    private void casRevision(DataAssetDO asset, Long expectedRevision) {
        if (expectedRevision != null && !Objects.equals(expectedRevision, asset.getCatalogRevision())) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        int updated = assetMapper.update(null, Wrappers.lambdaUpdate(DataAssetDO.class)
                .setSql("catalog_revision = COALESCE(catalog_revision, 0) + 1")
                .eq(DataAssetDO::getId, asset.getId())
                .eq(expectedRevision != null, DataAssetDO::getCatalogRevision, expectedRevision));
        if (expectedRevision != null && updated == 0) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
    }

    /**
     * 校验资产目录的资产，不满足约束时拒绝当前操作。
     */
    private DataAssetDO requireAsset(Long id) {
        DataAssetDO asset = assetMapper.selectById(id);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        return asset;
    }

    /**
     * 校验资产目录的工单，不满足约束时拒绝当前操作。
     */
    private AssetReviewTicketDO requireTicket(Long id) {
        AssetReviewTicketDO ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw exception(DATA_ASSET_TICKET_NOT_EXISTS);
        }
        return ticket;
    }

}
