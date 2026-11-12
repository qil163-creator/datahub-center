package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.CategoryDataAssetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.AssetTagRelDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorydataasset.CategoryDataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetTechLinkDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.AssetTagRelMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.categorydataasset.CategoryDataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetTechLinkMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetRegistrationSourceMapper;
import cn.iocoder.yudao.module.asset.service.assettag.AssetTagService;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.market.MarketFieldScope;
import cn.iocoder.yudao.module.asset.service.market.MarketQuerySnapshot;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.asset.service.quality.AssetQualityTaskService;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessService;
import cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard;
import cn.iocoder.yudao.module.asset.service.term.AssetTermService;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnUpdateReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.governance.api.standard.StandardElementApi;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardElementRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Slf4j
@Service
@Validated
public class DataAssetServiceImpl implements DataAssetService {

    @Resource
    private cn.iocoder.yudao.module.asset.service.market.MarketConsumptionService consumptionService;

    /** 解析 jdbc:xxx://host:port/db 形式连接串中的主机部分 */
    private static final Pattern JDBC_HOST_PATTERN = Pattern.compile("//([^/;?]+)");

    @Resource
    private AssetRegistrationSourceMapper registrationSourceMapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private AssetTechLinkMapper techLinkMapper;

    @Resource
    private AssetCatalogService catalogService;

    @Resource
    private AssetCatalogLinkService catalogLinkService;

    @Resource
    private AssetSupplyStopService supplyStopService;

    @Resource
    private cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder catalogAudit;

    @Resource
    private CategoryDataAssetMapper categoryMapper;

    @Resource
    private AssetTagRelMapper tagRelMapper;

    @Resource
    private AssetTagService tagService;

    @Resource
    private AssetQualityTaskService qualityTaskService;

    @Resource
    private AssetTermService termService;

    @Resource
    private AssetUserNameResolver userNameResolver;

    @Resource
    private AssetMonitorService monitorService;

    @Resource
    private AssetAccessGuard assetAccessGuard;

    @Resource
    private AssetEtlBatchBindingService etlBatchBindings;

    @Resource
    private MarketAccessService marketAccessService;

    @Resource
    private MetadataApi metadataApi;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private StandardElementApi standardElementApi;

    @Resource
    private AssetRegistrationWriter registrationWriter;

    @Resource
    private AssetRegistrationConfig registrationConfig;

    @Resource
    private AssetImportSnapshotService importSnapshots;

    /**
     * 获得数据资产分页。
     */
    @Override
    public PageResult<DataAssetRespVO> getAssetPage(DataAssetPageReqVO reqVO) {
        AssetMaintenanceAuthorization authorization = mapReadAuthorization();
        Collection<Long> categoryIds = null;
        if (reqVO.getCategoryId() != null) {
            categoryIds = collectCategoryAndDescendantIds(reqVO.getCategoryId());
        }
        Collection<Long> assetIds = null;
        if (reqVO.getTagId() != null) {
            assetIds = tagRelMapper.selectListByTagId(reqVO.getTagId()).stream()
                    .map(AssetTagRelDO::getAssetId).distinct().toList();
            if (assetIds.isEmpty()) {
                return PageResult.empty();
            }
        }
        PageResult<DataAssetDO> page = authorization.isSuperAdmin() || authorization.isOperator()
                ? assetMapper.selectPage(reqVO, categoryIds, assetIds)
                : assetMapper.selectMapPage(reqVO, categoryIds, assetIds, authorization.getUserId(),
                authorization.getDeptId());
        List<DataAssetRespVO> list = BeanUtils.toBean(page.getList(), DataAssetRespVO.class);
        for (int i = 0; i < list.size(); i++) {
            fillRegistrationConfig(list.get(i), page.getList().get(i));
        }
        fillDisplayInfo(list);
        for (int i = 0; i < list.size(); i++) {
            fillPublicationState(list.get(i), page.getList().get(i));
        }
        return new PageResult<>(list, page.getTotal());
    }

    /**
     * 映射读取授权。
     */
    private AssetMaintenanceAuthorization mapReadAuthorization() {
        AssetMaintenanceAuthorization authorization = catalogService.prepareMaintenanceAuthorization();
        if (authorization == null || authorization.getUserId() == null || authorization.getUserId() <= 0
                || !Objects.equals(authorization.getUserId(), SecurityFrameworkUtils.getLoginUserId())
                || !Objects.equals(authorization.getDeptId(), SecurityFrameworkUtils.getLoginUserDeptId())) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        return authorization;
    }

    /**
     * 读取已发布资产分页。
     */
    @Override
    public PageResult<DataAssetRespVO> getPublishedAssetPage(DataAssetPageReqVO reqVO) {
        Collection<Long> categories = reqVO.getCategoryId() == null ? null
                : collectCategoryAndDescendantIds(reqVO.getCategoryId());
        Collection<Long> ids = null;
        PageResult<DataAssetDO> page = assetMapper.selectPublishedPage(reqVO, categories, ids);
        return publishedPage(page);
    }

    /**
     * 读取已发布市场分页。
     */
    @Override
    public PageResult<DataAssetRespVO> getPublishedMarketPage(
            cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPageReqVO reqVO,
            Long userId, Collection<Long> authorizedIds, Collection<Long> marketCategoryIds) {
        return publishedPage(assetMapper.selectMarketPage(reqVO, userId, authorizedIds, marketCategoryIds));
    }

    /**
     * 组装已发布资产分页并补充目录展示信息。
     */
    private PageResult<DataAssetRespVO> publishedPage(PageResult<DataAssetDO> page) {
        List<DataAssetRespVO> views = page.getList().stream()
                .map(catalogService::publishedAsset)
                .map(asset -> BeanUtils.toBean(asset, DataAssetRespVO.class)).toList();
        fillDisplayInfo(views);
        for (int i = 0; i < views.size(); i++) {
            fillPublicationState(views.get(i), page.getList().get(i));
            views.get(i).setTags(catalogService.publishedTags(page.getList().get(i)));
        }
        return new PageResult<>(views, page.getTotal());
    }

    /**
     * 读取已发布资产详情。
     */
    @Override
    public DataAssetDetailRespVO getPublishedAssetDetail(Long id) {
        DataAssetDO current = validateAssetExists(id);
        if (!Objects.equals(current.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
            throw exception(MARKET_APPLY_ASSET_NOT_PUBLISHED);
        }
        return buildAssetDetail(catalogService.publishedAsset(current), false, true);
    }

    /**
     * 停止供数并持久触发全部存量授权撤销。
     */
    @Override
    public void stopSupply(AssetCatalogActionReqVO req) {
        supplyStopService.request(req.getAssetId(), req.getReason());
    }

    /**
     * 重查未知回执或明确拒绝后受控重投。
     */
    @Override
    public void handleCatalogDelivery(AssetCatalogActionReqVO req) {
        catalogService.assertCanMaintain(validateAssetExists(req.getAssetId()));
        if (req.getDeliveryId() == null) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "交付编号");
        }
        if (Boolean.TRUE.equals(req.getRetryRejected())) {
            catalogLinkService.retryRejected(req.getAssetId(), req.getDeliveryId(), req.getReason());
        } else {
            catalogLinkService.recheck(req.getAssetId(), req.getDeliveryId(), req.getReason());
        }
    }

    /**
     * 读取已发布资产分面。
     */
    @Override
    public DataAssetFacetRespVO getPublishedAssetFacets() {
        var rows = assetMapper.selectPublishedFacetCounts();
        var result = new DataAssetFacetRespVO();
        result.setTotal(assetMapper.selectCount(
                Wrappers.<DataAssetDO>lambdaQuery().eq(DataAssetDO::getStatus, DataAssetDO.STATUS_PUBLISHED)));
        Map<String, Long> categories = toCountMap(
                rows.stream().filter(row -> "category".equals(rowValue(row, "kind"))).toList());
        result.setCategories(
                categoryMapper.selectList().stream().filter(category -> Objects.equals(category.getStatus(), (short) 0))
                        .map(category -> new DataAssetFacetRespVO.Facet(String.valueOf(category.getId()),
                                category.getCategoryName(),
                                String.valueOf(category.getParentId()),
                                categories.getOrDefault(String.valueOf(category.getId()), 0L))).toList());
        result.setSubjectDomains(rows.stream().filter(row -> "subject".equals(rowValue(row, "kind")))
                .map(row -> new DataAssetFacetRespVO.Facet(String.valueOf(rowValue(row, "group_key")),
                        Objects.toString(rowValue(row, "group_label"), "未命名主题域"), null,
                        toLong(rowValue(row, "cnt")))).toList());
        result.setLayers(rows.stream().filter(row -> "layer".equals(rowValue(row, "kind")))
                .map(row -> new DataAssetFacetRespVO.Facet(String.valueOf(rowValue(row, "group_key")),
                        String.valueOf(rowValue(row, "group_label")), null, toLong(rowValue(row, "cnt")))).toList());
        return result;
    }

    /**
     * 左侧分组树统计：类目 / 主题域 / 数仓分层
     */
    @Override
    public DataAssetFacetRespVO getAssetFacets() {
        AssetMaintenanceAuthorization authorization = mapReadAuthorization();
        if (!authorization.isSuperAdmin() && !authorization.isOperator()) {
            return scopedFacets(assetMapper.selectMapFacetCounts(authorization.getUserId(), authorization.getDeptId()));
        }
        DataAssetFacetRespVO result = new DataAssetFacetRespVO();
        result.setTotal(assetMapper.selectCount());

        Map<String, Long> categoryCounts = toCountMap(assetMapper.selectCountGroupByCategory());
        result.setCategories(categoryMapper.selectList().stream()
                .filter(category -> Objects.equals(category.getStatus(), (short) 0))
                .sorted(Comparator.comparing(CategoryDataAssetDO::getSort,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CategoryDataAssetDO::getId))
                .map(category -> new DataAssetFacetRespVO.Facet(String.valueOf(category.getId()),
                        category.getCategoryName(), String.valueOf(category.getParentId()),
                        categoryCounts.getOrDefault(String.valueOf(category.getId()), 0L)))
                .toList());

        result.setSubjectDomains(assetMapper.selectCountGroupBySubjectDomain().stream()
                .map(row -> new DataAssetFacetRespVO.Facet(
                        String.valueOf(rowValue(row, "group_key")),
                        StrUtil.blankToDefault(Objects.toString(rowValue(row, "group_label"), null),
                                "主题域 #" + rowValue(row, "group_key")),
                        null, toLong(rowValue(row, "cnt"))))
                .sorted(Comparator.comparing(DataAssetFacetRespVO.Facet::getCount).reversed())
                .toList());

        result.setLayers(assetMapper.selectCountGroupByLayer().stream()
                .map(row -> new DataAssetFacetRespVO.Facet(
                        String.valueOf(rowValue(row, "group_key")), String.valueOf(rowValue(row, "group_key")),
                        null, toLong(rowValue(row, "cnt"))))
                .sorted(Comparator.comparingInt(facet -> layerOrder(facet.getKey())))
                .toList());
        return result;
    }

    /**
     * 在当前可见范围内汇总资产分类分面。
     */
    private DataAssetFacetRespVO scopedFacets(List<Map<String, Object>> rows) {
        var result = new DataAssetFacetRespVO();
        result.setTotal(rows.stream().filter(row -> "total".equals(rowValue(row, "kind")))
                .mapToLong(row -> toLong(rowValue(row, "cnt"))).findFirst().orElse(0));
        var counts = toCountMap(rows.stream().filter(row -> "category".equals(rowValue(row, "kind"))).toList());
        var categories = categoryMapper.selectList();
        var byId = categories.stream().collect(Collectors.toMap(CategoryDataAssetDO::getId, Function.identity()));
        Set<Long> visible = new HashSet<>();
        for (String key : counts.keySet()) {
            Long id = Long.valueOf(key);
            Set<Long> chain = new HashSet<>();
            while (id != null && byId.containsKey(id) && chain.add(id)) {
                visible.add(id);
                id = byId.get(id).getParentId();
            }
        }
        result.setCategories(categories.stream().filter(item -> visible.contains(item.getId())
                        && Objects.equals(item.getStatus(), (short) 0))
                .map(item -> new DataAssetFacetRespVO.Facet(String.valueOf(item.getId()), item.getCategoryName(),
                        String.valueOf(item.getParentId()), counts.getOrDefault(String.valueOf(item.getId()), 0L)))
                .toList());
        result.setSubjectDomains(rows.stream().filter(row -> "subject".equals(rowValue(row, "kind")))
                .map(row -> new DataAssetFacetRespVO.Facet(String.valueOf(rowValue(row, "group_key")),
                        Objects.toString(rowValue(row, "group_label"), "未命名主题域"), null,
                        toLong(rowValue(row, "cnt")))).toList());
        result.setLayers(rows.stream().filter(row -> "layer".equals(rowValue(row, "kind")))
                .map(row -> new DataAssetFacetRespVO.Facet(String.valueOf(rowValue(row, "group_key")),
                        String.valueOf(rowValue(row, "group_label")), null, toLong(rowValue(row, "cnt")))).toList());
        return result;
    }

    /**
     * 读取导航分类。
     */
    @Override
    public List<CategoryDataAssetRespVO> getNavigationCategories() {
        Set<Long> visible = getAssetFacets().getCategories().stream().map(item -> Long.valueOf(item.getKey()))
                .collect(Collectors.toSet());
        return BeanUtils.toBean(categoryMapper.selectList().stream().filter(item -> visible.contains(item.getId()))
                .sorted(Comparator.comparing(CategoryDataAssetDO::getSort,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CategoryDataAssetDO::getId)).toList(), CategoryDataAssetRespVO.class);
    }

    /**
     * 获得待编目的已发布元数据分页。
     */
    @Override
    public PageResult<MetadataTableRespDTO> getCandidatePage(DataAssetCandidatePageReqVO reqVO) {
        boolean onlyUnlinked = reqVO.getOnlyUnlinked() == null || Boolean.TRUE.equals(reqVO.getOnlyUnlinked());
        return metadataApi.getPublishedTablePage(reqVO.getPageNo(), reqVO.getPageSize(),
                reqVO.getKeyword(), onlyUnlinked).getCheckedData();
    }

    /**
     * 批量编目数据资产。
     */
    @Override
    public List<Long> batchRegisterAsset(DataAssetBatchRegisterReqVO reqVO) {
        List<DataAssetDO> assets = new ArrayList<>();
        Set<Long> metaTableIds = new HashSet<>();
        for (DataAssetRegisterReqVO item : reqVO.getItems()) {
            if (!metaTableIds.add(item.getMetaTableId())) {
                throw exception(DATA_ASSET_META_DUPLICATE);
            }
            MetadataTableRespDTO metadata = validateMetadataForRegister(item.getMetaTableId());
            validateCategory(item.getCategoryId());
            assets.add(buildAsset(item, metadata));
        }
        // 远端元数据和命名上下文已全部核验，进入本地事务后不再调用远端。
        try {
            return registrationWriter.persistTables(assets);
        } catch (org.springframework.dao.DuplicateKeyException failure) {
            throw exception(DATA_ASSET_META_DUPLICATE);
        }
    }

    /**
     * 维护数据资产。
     */
    @Override
    public void updateAsset(DataAssetUpdateReqVO reqVO) {
        DataAssetDO asset = validateAssetExists(reqVO.getId());
        catalogService.assertCanMaintain(asset);
        validateCategory(reqVO.getCategoryId());
        var resolvedConfig = reqVO.getRegistrationConfig() == null ? null
                : registrationConfig.resolve(reqVO.getRegistrationConfig(), reqVO.getSubjectDomainId());
        String subjectDomainName = reqVO.getRegistrationConfig() != null && reqVO.getRegistrationConfig()
                .getDataDomainId() != null
                ? resolvedConfig.subjectDomainName() : resolveSubjectDomainName(reqVO.getSubjectDomainId(),
                asset.getSubjectDomainId(), asset.getSubjectDomainName());
        Integer share = reqVO.getShareAttribute() == null
                ? DataAssetDO.SHARE_PENDING : reqVO.getShareAttribute();
        int updated = assetMapper.update(null, Wrappers.lambdaUpdate(DataAssetDO.class)
                .set(DataAssetDO::getAssetName, reqVO.getAssetName())
                .set(DataAssetDO::getCategoryId, reqVO.getCategoryId())
                .set(DataAssetDO::getSubjectDomainId, reqVO.getSubjectDomainId())
                .set(DataAssetDO::getSubjectDomainName, subjectDomainName)
                .set(DataAssetDO::getDescription, reqVO.getDescription())
                .set(reqVO.getRegistrationConfig() != null, DataAssetDO::getRegistrationConfigJson,
                        resolvedConfig == null ? null : resolvedConfig.json())
                .set(DataAssetDO::getTechLeaderUserId, reqVO.getTechLeaderUserId())
                .set(DataAssetDO::getTechLeaderPhone, reqVO.getTechLeaderPhone())
                .set(DataAssetDO::getBusinessLeaderUserId, reqVO.getBusinessLeaderUserId())
                .set(DataAssetDO::getBusinessLeaderPhone, reqVO.getBusinessLeaderPhone())
                .set(DataAssetDO::getOwnerDeptId, reqVO.getOwnerDeptId())
                .set(DataAssetDO::getRemark, reqVO.getRemark())
                .set(DataAssetDO::getShareAttribute, share)
                .set(DataAssetDO::getShareAttr, share)
                .set(DataAssetDO::getShareCondition, reqVO.getShareCondition())
                .set(DataAssetDO::getShareDenyReason, reqVO.getShareDenyReason())
                .set(DataAssetDO::getUpdateCycle, reqVO.getUpdateCycle())
                .set(DataAssetDO::getBusinessPurpose, reqVO.getBusinessPurpose())
                .set(DataAssetDO::getContactName, reqVO.getContactName())
                .set(DataAssetDO::getContactPhone, reqVO.getContactPhone())
                .set(DataAssetDO::getProviderDeptId, reqVO.getProviderDeptId())
                .set(DataAssetDO::getSourceDeptId, reqVO.getSourceDeptId())
                .set(DataAssetDO::getDutyBasis, reqVO.getDutyBasis())
                .set(DataAssetDO::getUpdateRule, reqVO.getUpdateRule())
                .setSql("catalog_revision = COALESCE(catalog_revision, 0) + 1")
                .eq(DataAssetDO::getId, reqVO.getId())
                .eq(reqVO.getExpectedRevision() != null, DataAssetDO::getCatalogRevision,
                        reqVO.getExpectedRevision()));
        if (reqVO.getExpectedRevision() != null && updated == 0) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
    }

    /**
     * 维护资产字段的业务与标准属性。
     */
    @Override
    public void updateAssetColumn(DataAssetColumnUpdateReqVO reqVO) {
        DataAssetDO asset = validateAssetExists(reqVO.getAssetId());
        if (AssetImportSnapshotService.supports(asset)) {
            catalogService.assertCanMaintain(asset);
            if (reqVO.getStandardElementId() != null && standardElementApi.getElementListByIds(
                            List.of(reqVO.getStandardElementId().longValue())).getCheckedData().stream()
                    .noneMatch(element -> Objects.equals(element.getId(), reqVO.getStandardElementId())
                            && Objects.equals(element.getStatus(), (short) 0))) {
                throw exception(DATA_ASSET_IMPORT_INPUT_INVALID);
            }
            importSnapshots.updateColumn(reqVO, mapReadAuthorization());
            return;
        }
        getAssetColumn(reqVO.getAssetId(), reqVO.getId());
        // 保留调用方读取时的修订号，由元数据提供方执行 CAS；不得改成这里回读到的新修订号。
        metadataApi.updateColumnBiz(
                BeanUtils.toBean(reqVO, MetadataColumnUpdateReqDTO.class)).getCheckedData();
    }

    /**
     * 读取资产范围内当前字段的治理属性及修订号。
     */
    @Override
    public DataAssetColumnRespVO getAssetColumn(Long assetId, Long id) {
        DataAssetDO asset = validateAssetExists(assetId);
        catalogService.assertCanMaintain(asset);
        if (AssetImportSnapshotService.supports(asset)) {
            return importSnapshots.column(assetId, id);
        }
        catalogService.assertSingleTableCompatible(asset);
        List<MetadataColumnRespDTO> frozen = catalogService.listColumns(assetId, null);
        MetadataColumnRespDTO member = id == null || frozen == null ? null : frozen.stream()
                .filter(column -> Objects.equals(column.getId(), id)
                        && Objects.equals(column.getTableId(), asset.getMetaTableId()))
                .findFirst().orElse(null);
        if (member == null) {
            throw exception(ASSET_QUALITY_COLUMN_INVALID);
        }
        MetadataColumnRespDTO current = metadataApi.getColumn(id).getCheckedData();
        if (current == null || !Objects.equals(current.getId(), member.getId())
                || !Objects.equals(current.getTableId(), asset.getMetaTableId())
                || !Objects.equals(member.getColumnName(), current.getColumnName())
                || (StrUtil.isNotBlank(member.getResourceUrn())
                && !Objects.equals(member.getResourceUrn(), current.getResourceUrn()))
                || Objects.equals(current.getObjectStatus(), 3)) {
            throw exception(ASSET_QUALITY_COLUMN_INVALID);
        }
        return BeanUtils.toBean(current, DataAssetColumnRespVO.class);
    }

    /**
     * 刷新资产引用的元数据定版与运行指标。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshAsset(Long id) {
        DataAssetDO asset = validateAssetExists(id);
        catalogService.assertCanMaintain(asset);
        MetadataTableRespDTO metadata = getMetadata(asset.getMetaTableId());
        validateMetadataPublished(metadata);
        asset = assetMapper.selectForCatalogUpdate(id);
        catalogService.assertTechnicalMutable(asset);
        assetMapper.update(null, Wrappers.lambdaUpdate(DataAssetDO.class)
                .set(DataAssetDO::getResourceUrn, metadata.getResourceUrn())
                .set(DataAssetDO::getPublishedSnapshotId, metadata.getPublishedSnapshotId())
                .set(DataAssetDO::getPublishedVersion, metadata.getPublishedVersion())
                .set(DataAssetDO::getDatasourceId, metadata.getDatasourceId())
                .set(DataAssetDO::getSourceSystemName, metadata.getSourceSystemName())
                .set(DataAssetDO::getDbName, metadata.getDbName())
                .set(DataAssetDO::getSchemaName, metadata.getSchemaName())
                .set(DataAssetDO::getTableName, metadata.getTableName())
                .set(DataAssetDO::getTableType, metadata.getTableType())
                .set(DataAssetDO::getLayer, metadata.getLayer())
                .set(DataAssetDO::getSubjectDomainId, metadata.getSubjectDomainId())
                .set(DataAssetDO::getSubjectDomainName, metadata.getSubjectDomainName())
                .set(DataAssetDO::getRowCount, metadata.getRowCount())
                .set(DataAssetDO::getFieldCount, metadata.getColumnCount())
                .set(DataAssetDO::getStorageSize, metadata.getStorageSize())
                .set(DataAssetDO::getHealthScore, metadata.getHealthScore())
                .setSql("catalog_revision = COALESCE(catalog_revision, 0) + 1")
                .eq(DataAssetDO::getId, id));
        var primary = techLinkMapper.selectPrimary(id);
        if (primary != null) {
            techLinkMapper.update(null, Wrappers.<AssetTechLinkDO>lambdaUpdate()
                    .set(AssetTechLinkDO::getSnapshotId, metadata.getPublishedSnapshotId())
                    .set(AssetTechLinkDO::getDatasourceId, metadata.getDatasourceId())
                    .set(AssetTechLinkDO::getSchemaName, metadata.getSchemaName())
                    .set(AssetTechLinkDO::getObjectName, metadata.getTableName())
                    .set(AssetTechLinkDO::getResourceUrn, metadata.getResourceUrn())
                    .eq(AssetTechLinkDO::getId, primary.getId()));
        }
    }

    /**
     * 发布数据资产。
     */
    @Override
    public void publishAsset(Long id) {
        catalogService.publishLatestApproved(id);
    }

    /**
     * 停止新申请并保留既有授权。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unpublishAsset(Long id) {
        DataAssetDO asset = validateAssetExists(id);
        catalogService.assertCanMaintain(asset);
        if (Objects.equals(asset.getStatus(), DataAssetDO.STATUS_OFFLINE)) {
            return;
        }
        assetMapper.update(null, Wrappers.lambdaUpdate(DataAssetDO.class)
                .set(DataAssetDO::getStatus, DataAssetDO.STATUS_UNPUBLISHED)
                // 仅停止新申请；历史版本及既有供给合同继续保留。
                .ne(DataAssetDO::getStatus, DataAssetDO.STATUS_OFFLINE)
                .eq(DataAssetDO::getId, id));
        catalogAudit.record("CATALOG_STOP_NEW_APPLY", "CATALOG", id, asset.getStatus(),
                DataAssetDO.STATUS_UNPUBLISHED, "停止新增申请，保留既有授权", "OK");
    }

    /**
     * false 表示元数据解绑已排队，目录保留到回执确认后再次完成删除。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteAsset(Long id) {
        DataAssetDO asset = validateAssetExists(id);
        catalogService.assertCanMaintain(asset);
        asset = assetMapper.selectForCatalogUpdate(id);
        if (Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
            throw exception(DATA_ASSET_DELETE_PUBLISHED);
        }
        supplyStopService.assertDeletable(id);
        if (!qualityTaskService.getTaskList(id).isEmpty()) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "请先在资产质量任务页面解除关联并确认远端删除");
        }
        assetMapper.update(null, Wrappers.<DataAssetDO>lambdaUpdate()
                .set(DataAssetDO::getRetirementRequested, true).eq(DataAssetDO::getId, id));
        java.util.Set<Long> tables = new java.util.LinkedHashSet<>();
        techLinkMapper.selectListByAssetId(id).forEach(link -> tables.add(link.getMetaTableId()));
        tables.add(asset.getMetaTableId());
        boolean detached = true;
        for (Long table : tables) {
            detached &= catalogLinkService.prepareUnlink(asset, table);
        }
        if (!detached) {
            return false;
        }
        catalogLinkService.assertSettled(id);
        tagRelMapper.deleteByAssetId(id);
        termService.deleteByAssetId(id);
        assetMapper.deleteById(id);
        catalogAudit.record("CATALOG_DELETE", "CATALOG", id, asset.getStatus(), null, "资产退役，元数据解绑回执已确认",
                "OK");
        return true;
    }

    /**
     * 获得数据资产详情。
     */
    @Override
    public DataAssetDetailRespVO getAssetDetail(Long id) {
        return getAssetDetail(id, true);
    }

    /**
     * 读取资产详情。
     */
    @Override
    public DataAssetDetailRespVO getAssetDetail(Long id, boolean countView) {
        AssetMaintenanceAuthorization authorization = mapReadAuthorization();
        DataAssetDO asset = validateAssetExists(id);
        if (!authorization.canMaintain(asset)) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        return buildAssetDetail(asset, countView, false);
    }

    /**
     * 构造数据资产的资产详情。
     */
    private DataAssetDetailRespVO buildAssetDetail(DataAssetDO asset, boolean countView, boolean published) {
        Long id = asset.getId();
        if (countView) {
            assetMapper.update(null, Wrappers.lambdaUpdate(DataAssetDO.class)
                    .setSql("view_count = COALESCE(view_count, 0) + 1")
                    .eq(DataAssetDO::getId, id));
            asset.setViewCount(Optional.ofNullable(asset.getViewCount()).orElse(0L) + 1);
            monitorService.recordUsageLog(asset, AssetUsageLogDO.ACTION_BROWSE);
        }
        DataAssetRespVO assetVO = BeanUtils.toBean(asset, DataAssetRespVO.class);
        fillRegistrationConfig(assetVO, asset);
        fillDisplayInfo(List.of(assetVO));
        fillOverviewInfo(assetVO);
        fillPublicationState(assetVO, asset);
        if (published) {
            assetVO.setTags(catalogService.publishedTags(asset));
        }

        List<MetadataColumnRespDTO> columns = List.of();
        try {
            columns = published ? (asset.getPublishedSnapshotId() == null ? List.of()
                    : metadataApi.getColumnListBySnapshot(asset.getPublishedSnapshotId()).getCheckedData())
                    : catalogService.listColumns(id, null);
            if (!published && asset.getMetaTableId() != null) {
                MetadataTableRespDTO metadata = getMetadata(asset.getMetaTableId());
                fillMetadataState(assetVO, metadata);
            }
        } catch (RuntimeException ex) {
            log.warn("[getAssetDetail][元数据服务不可用，资产({})按编目快照降级展示：{}]",
                    id, ex.getMessage());
        }

        DataAssetDetailRespVO result = new DataAssetDetailRespVO();
        result.setAsset(assetVO);
        result.setColumns(buildColumns(id, columns));
        result.setQualityTasks(qualityTaskService.getTaskList(id));
        if (!published && AssetImportSnapshotService.supports(asset)) {
            result.setImportedSnapshot(importSnapshots.describe(id));
        }
        return result;
    }

    /**
     * 补充数据资产的发布状态。
     */
    private void fillPublicationState(DataAssetRespVO view, DataAssetDO source) {
        if (source.getPublishedCatalogVersionId() != null || Objects.equals(source.getStatus(),
                DataAssetDO.STATUS_PUBLISHED)) {
            if (!catalogService.hasCompletePublishedSnapshot(source)) {
                view.setPublicationNotice("发布证据待补齐，暂停新增申请；请在目录版本提交完整快照审核，既有授权保留");
            }
        }
        view.setPendingGrantRevocations(supplyStopService.pending(source.getId()));
    }

    /**
     * 只读预览资产数据。
     */
    @Override
    public DatabasePreviewRespDTO previewAsset(Long id, Integer limit) {
        DataAssetPreviewReqVO reqVO = new DataAssetPreviewReqVO();
        reqVO.setId(id);
        reqVO.setLimit(limit);
        return previewAsset(reqVO);
    }

    /**
     * 带过滤条件的只读预览
     */
    @Override
    public DatabasePreviewRespDTO previewAsset(DataAssetPreviewReqVO reqVO) {
        DatabasePreviewRespDTO batchPreview = etlBatchBindings.previewIfBound(reqVO);
        if (batchPreview != null) {
            return batchPreview;
        }
        DataAssetDO imported = validateAssetExists(reqVO.getId());
        if (AssetImportSnapshotService.supports(imported)) {
            AssetMaintenanceAuthorization authorization = mapReadAuthorization();
            if (!authorization.canMaintain(imported)) {
                throw exception(DATA_ASSET_FORBIDDEN);
            }
            if (!Objects.equals(imported.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
                throw exception(DATA_ASSET_PREVIEW_UNSUPPORTED);
            }
            DatabasePreviewRespDTO preview = importSnapshots.preview(imported, reqVO);
            if (!authorization.canMaintain(assetMapper.selectById(imported.getId()))) {
                throw exception(DATA_ASSET_FORBIDDEN);
            }
            return preview;
        }
        DataAssetDO asset = imported;
        if (!Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
            throw exception(DATA_ASSET_PREVIEW_UNSUPPORTED);
        }
        catalogService.assertSingleTableCompatible(asset);
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        assetAccessGuard.assertPublishedReadable(asset, userId);
        DatabasePreviewReqDTO request = new DatabasePreviewReqDTO();
        request.setDatabaseId(asset.getDatasourceId());
        request.setSchemaName(asset.getSchemaName());
        request.setTableName(asset.getTableName());
        Integer limit = reqVO.getLimit();
        request.setLimit(limit == null ? 20 : Math.max(1, Math.min(limit, 100)));
        if (CollUtil.isNotEmpty(reqVO.getFilters())) {
            request.setFilters(reqVO.getFilters().stream()
                    .filter(filter -> StrUtil.isNotBlank(filter.getColumn()))
                    .map(filter -> BeanUtils.toBean(filter, DatabasePreviewReqDTO.Filter.class))
                    .toList());
        }
        request.setMaskScene("ASSET_PREVIEW");
        MarketResourceApplyDO apply = marketAccessService.findEffectiveApply(userId, asset.getId());
        MarketQuerySnapshot authorization = apply == null ? null
                : marketAccessService.requireQuerySnapshot(userId, apply.getId());
        MarketFieldScope scope = authorization == null ? null : authorization.fieldScope();
        if (apply != null) {
            request.setDatabaseId(authorization.databaseId());
            request.setSchemaName(authorization.schemaName());
            request.setTableName(authorization.tableName());
            MarketFieldScope.applyToPreviewRequest(request, scope);
        } else {
            assetAccessGuard.assertPublishedReadableWithoutApply(asset, userId);
        }
        return cn.iocoder.yudao.module.asset.service.market.MarketConsumptionService.read(
                consumptionService, authorization, request, request.getLimit(), allowedLimit -> {
                    request.setLimit(allowedLimit);
                    DatabasePreviewRespDTO preview = databaseApi.previewTable(request).getCheckedData();
                    if (apply != null) {
                        marketAccessService.filterPreview(preview, scope);
                    }
                    monitorService.recordUsageLog(asset, AssetUsageLogDO.ACTION_USAGE);
                    if (authorization != null) {
                        marketAccessService.assertStillCurrent(authorization);
                    } else {
                        assetAccessGuard.assertPublishedReadableWithoutApply(asset, userId);
                    }
                    return preview;
                }, preview -> preview.getRows() == null ? 0 : preview.getRows().size());
    }

    /**
     * 字段列表：元数据技术属性 + 资产侧术语绑定 + 标准数据元名称
     */
    private List<DataAssetColumnRespVO> buildColumns(Long assetId, List<MetadataColumnRespDTO> columns) {
        if (CollUtil.isEmpty(columns)) {
            return List.of();
        }
        List<DataAssetColumnRespVO> result = BeanUtils.toBean(columns, DataAssetColumnRespVO.class);
        Map<Long, AssetTermRespVO> terms = termService.getColumnTermMap(assetId);
        Set<Long> elementIds = result.stream().map(DataAssetColumnRespVO::getStandardElementId)
                .filter(Objects::nonNull).map(Integer::longValue)
                .collect(Collectors.toSet());
        Map<Long, StandardElementRespDTO> elements = new HashMap<>();
        if (!elementIds.isEmpty()) {
            try {
                standardElementApi.getElementListByIds(elementIds).getCheckedData()
                        .forEach(element -> elements.put(element.getId().longValue(), element));
            } catch (RuntimeException ex) {
                log.warn("[buildColumns][标准数据元服务不可用，仅展示编号：{}]", ex.getMessage());
            }
        }
        result.forEach(column -> {
            AssetTermRespVO term = terms.get(column.getId());
            if (term != null) {
                column.setTermId(term.getId());
                column.setTermName(term.getName());
            }
            StandardElementRespDTO element = column.getStandardElementId() == null ? null
                    : elements.get(column.getStandardElementId().longValue());
            if (element != null) {
                column.setStandardElementCode(element.getCode());
                column.setStandardElementName(element.getName());
            }
        });
        result.sort(Comparator.comparing(DataAssetColumnRespVO::getOrdinalPosition,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }


    /**
     * 构造数据资产的资产。
     */
    private DataAssetDO buildAsset(DataAssetRegisterReqVO reqVO, MetadataTableRespDTO metadata) {
        String assetName = firstNotBlank(reqVO.getAssetName(), metadata.getBusinessName(),
                metadata.getCustomComment(), metadata.getSourceComment(), metadata.getTableName());
        String description = firstNotBlank(reqVO.getDescription(), metadata.getBusinessDefinition(),
                metadata.getCustomComment(), metadata.getSourceComment());
        Long subjectDomainId = reqVO.getSubjectDomainId() == null
                && (reqVO.getRegistrationConfig() == null || reqVO.getRegistrationConfig().getDataDomainId() == null)
                ? metadata.getSubjectDomainId() : reqVO.getSubjectDomainId();
        var resolvedConfig = reqVO.getRegistrationConfig() == null ? null
                : registrationConfig.resolve(reqVO.getRegistrationConfig(), subjectDomainId);
        String subjectDomainName = reqVO.getRegistrationConfig() != null && reqVO.getRegistrationConfig()
                .getDataDomainId() != null
                ? resolvedConfig.subjectDomainName() : resolveSubjectDomainName(subjectDomainId,
                metadata.getSubjectDomainId(), metadata.getSubjectDomainName());
        return DataAssetDO.builder()
                .assetCode("DBT-" + metadata.getId())
                .catalogItemUrn("urn:datahub:asset:table:" + metadata.getDatasourceId()
                        + ":" + metadata.getId())
                .assetName(assetName)
                .assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .categoryId(reqVO.getCategoryId())
                .metaTableId(metadata.getId())
                .resourceUrn(metadata.getResourceUrn())
                .publishedSnapshotId(metadata.getPublishedSnapshotId())
                .publishedVersion(metadata.getPublishedVersion())
                .datasourceId(metadata.getDatasourceId())
                .sourceSystemName(metadata.getSourceSystemName())
                .dbName(metadata.getDbName())
                .schemaName(metadata.getSchemaName())
                .tableName(metadata.getTableName())
                .tableType(metadata.getTableType())
                .layer(metadata.getLayer())
                .subjectDomainId(subjectDomainId)
                .subjectDomainName(subjectDomainName)
                .rowCount(metadata.getRowCount())
                .fieldCount(Optional.ofNullable(metadata.getColumnCount()).orElse(0))
                .storageSize(metadata.getStorageSize())
                .healthScore(metadata.getHealthScore())
                .description(description)
                .registrationConfigJson(resolvedConfig == null ? null : resolvedConfig.json())
                .status(DataAssetDO.STATUS_UNPUBLISHED)
                .techLeaderUserId(metadata.getTechLeaderUserId())
                .techLeaderPhone(metadata.getTechLeaderPhone())
                .businessLeaderUserId(metadata.getBusinessLeaderUserId())
                .businessLeaderPhone(metadata.getBusinessLeaderPhone())
                .ownerDeptId(metadata.getOwnerDeptId())
                .viewCount(0L)
                .shareAttribute(DataAssetDO.SHARE_PENDING)
                .shareAttr(DataAssetDO.SHARE_PENDING)
                .techAvailable(1)
                .catalogRevision(0L)
                .providerDeptId(metadata.getOwnerDeptId())
                .remark(reqVO.getRemark())
                .build();
    }

    /**
     * 新增主对象技术关联。
     */
    private void insertPrimaryTechLink(DataAssetDO asset, MetadataTableRespDTO metadata) {
        if (metadata == null || metadata.getId() == null) {
            return;
        }
        if (techLinkMapper.selectByAssetAndMeta(asset.getId(), metadata.getId()) != null) {
            return;
        }
        techLinkMapper.insert(AssetTechLinkDO.builder()
                .assetId(asset.getId())
                .providerModule("governance")
                .resourceUrn(metadata.getResourceUrn())
                .datasourceId(metadata.getDatasourceId())
                .schemaName(metadata.getSchemaName())
                .objectName(metadata.getTableName())
                .metaTableId(metadata.getId())
                .snapshotId(metadata.getPublishedSnapshotId())
                .primaryFlag(1)
                .build());
    }

    /**
     * 校验数据资产的元数据用于登记，不满足约束时拒绝当前操作。
     */
    private MetadataTableRespDTO validateMetadataForRegister(Long metaTableId) {
        if (assetMapper.selectByMetaTableId(metaTableId) != null) {
            throw exception(DATA_ASSET_META_DUPLICATE);
        }
        MetadataTableRespDTO metadata = getMetadata(metaTableId);
        validateMetadataPublished(metadata);
        return metadata;
    }

    /**
     * 读取元数据。
     */
    private MetadataTableRespDTO getMetadata(Long metaTableId) {
        MetadataTableRespDTO metadata = metadataApi.getTable(metaTableId).getCheckedData();
        if (metadata == null) {
            throw exception(DATA_ASSET_META_NOT_EXISTS);
        }
        return metadata;
    }

    /**
     * 校验数据资产的元数据已发布，不满足约束时拒绝当前操作。
     */
    private void validateMetadataPublished(MetadataTableRespDTO metadata) {
        if (!Objects.equals(metadata.getPublishStatus(), 1)
                || metadata.getPublishedSnapshotId() == null
                || StrUtil.isBlank(metadata.getPublishedVersion())) {
            throw exception(DATA_ASSET_META_NOT_PUBLISHED);
        }
    }

    /**
     * 校验资产记录存在，不存在时抛出业务异常。
     */
    private DataAssetDO validateAssetExists(Long id) {
        DataAssetDO asset = assetMapper.selectById(id);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        return asset;
    }

    /**
     * 解析数据资产的主体域名称。
     */
    private String resolveSubjectDomainName(Long id, Long currentId, String currentName) {
        if (id == null) {
            return null;
        }
        if (Objects.equals(id, currentId)) {
            return currentName;
        }
        return getAssetFacets().getSubjectDomains().stream()
                .filter(facet -> String.valueOf(id).equals(facet.getKey()))
                .map(DataAssetFacetRespVO.Facet::getLabel).findFirst()
                .orElseThrow(() -> exception(DATA_ASSET_CATALOG_NOT_READY, "主题域不存在或当前不可见"));
    }

    /**
     * 校验数据资产的分类，不满足约束时拒绝当前操作。
     */
    private void validateCategory(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        CategoryDataAssetDO category = categoryMapper.selectById(categoryId);
        if (category == null || !Objects.equals(category.getStatus(), (short) 0)) {
            throw exception(CATEGORY_DATA_ASSET_NOT_EXISTS);
        }
    }

    /**
     * 收集分类与后代编号。
     */
    private Collection<Long> collectCategoryAndDescendantIds(Long categoryId) {
        validateCategory(categoryId);
        List<CategoryDataAssetDO> categories = categoryMapper.selectList();
        Map<Long, List<Long>> children = categories.stream()
                .collect(Collectors.groupingBy(CategoryDataAssetDO::getParentId,
                        Collectors.mapping(CategoryDataAssetDO::getId, Collectors.toList())));
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            Long current = queue.removeFirst();
            if (result.add(current)) {
                queue.addAll(children.getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    /**
     * 补充数据资产的登记配置。
     */
    private void fillRegistrationConfig(DataAssetRespVO view, DataAssetDO asset) {
        if (StrUtil.isNotBlank(asset.getRegistrationConfigJson())) {
            view.setRegistrationConfig(
                    JsonUtils.parseObject(asset.getRegistrationConfigJson(), AssetRegistrationConfigVO.class));
        }
    }

    /**
     * 补充数据资产的展示信息。
     */
    private void fillDisplayInfo(List<DataAssetRespVO> assets) {
        if (CollUtil.isEmpty(assets)) {
            return;
        }
        List<Long> assetIds = assets.stream().map(DataAssetRespVO::getId).toList();
        if (registrationSourceMapper != null) {
            var sources = registrationSourceMapper.selectBatchIds(assetIds).stream().collect(Collectors.toMap(
                    cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetRegistrationSourceDO::getAssetId,
                    Function.identity()));
            for (var view : assets) {
                var source = sources.get(view.getId());
                if (source != null && Objects.equals(view.getAssetType(), DataAssetDO.TYPE_FILE)) {
                    view.setFileName(source.getSourceName());
                    view.setFileType(source.getFileType());
                    view.setFilePath(source.getSourcePath());
                }
            }
        }
        Set<Long> categoryIds = assets.stream().map(DataAssetRespVO::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, CategoryDataAssetDO> categories = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(CategoryDataAssetDO::getId, Function.identity()));
        Map<Long, List<AssetTagRespVO>> tags = tagService.getTagsByAssetIds(assetIds);
        Map<Long, Integer> qualityCounts = qualityTaskService.getTaskCounts(assetIds);
        Set<String> userTexts = new HashSet<>();
        assets.forEach(asset -> {
            userTexts.add(asset.getCreator());
            userTexts.add(asset.getUpdater());
        });
        Map<String, String> userNames = userNameResolver.resolveByTexts(userTexts);
        assets.forEach(asset -> {
            CategoryDataAssetDO category = asset.getCategoryId() == null ? null : categories.get(asset.getCategoryId());
            asset.setCategoryName(category == null ? null : category.getCategoryName());
            asset.setTags(tags.getOrDefault(asset.getId(), List.of()));
            asset.setQualityTaskCount(qualityCounts.getOrDefault(asset.getId(), 0));
            asset.setCreatorName(userNameResolver.nameOf(userNames, asset.getCreator()));
            asset.setUpdaterName(userNameResolver.nameOf(userNames, asset.getUpdater()));
            if (asset.getExternalCode() != null && (asset.getExternalCode().startsWith("qdata:market:")
                    || asset.getExternalCode().startsWith("qdata:asset:"))) {
                importSnapshots.fillDisplay(asset);
                asset.setMapDisplayScore("93.33");
            }
            if (asset.getShareAttribute() == null) {
                asset.setShareAttribute(asset.getShareAttr());
            }
            if (asset.getShareAttr() == null) {
                asset.setShareAttr(asset.getShareAttribute());
            }
        });
    }

    /**
     * 概览 Tab 需要的负责人姓名与数据连接信息；外部服务失败仅记录日志
     */
    private void fillOverviewInfo(DataAssetRespVO asset) {
        Map<Long, String> leaderNames = userNameResolver.resolve(
                Arrays.asList(asset.getTechLeaderUserId(), asset.getBusinessLeaderUserId()));
        asset.setTechLeaderName(leaderNames.get(asset.getTechLeaderUserId()));
        asset.setBusinessLeaderName(leaderNames.get(asset.getBusinessLeaderUserId()));
        if (asset.getDatasourceId() == null) {
            return;
        }
        try {
            databaseApi.getDatabaseList(null).getCheckedData().stream()
                    .filter(item -> Objects.equals(item.getId(), asset.getDatasourceId()))
                    .findFirst()
                    .ifPresent(datasource -> {
                        asset.setDatasourceName(datasource.getName());
                        asset.setDatasourceType(datasource.getProductType());
                        asset.setDatasourceHost(parseJdbcHost(datasource));
                    });
        } catch (RuntimeException ex) {
            log.warn("[fillOverviewInfo][数据源服务不可用，资产({})不展示连接信息：{}]",
                    asset.getId(), ex.getMessage());
        }
    }

    /**
     * 解析数据资产的JDBC主机。
     */
    private String parseJdbcHost(DatabaseConnectionRespDTO datasource) {
        if (StrUtil.isBlank(datasource.getJdbcUrl())) {
            return null;
        }
        Matcher matcher = JDBC_HOST_PATTERN.matcher(datasource.getJdbcUrl());
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 补充数据资产的元数据状态。
     */
    private void fillMetadataState(DataAssetRespVO asset, MetadataTableRespDTO metadata) {
        asset.setCurrentMetadataVersion(metadata.getPublishedVersion());
        asset.setCurrentMetadataPublishStatus(metadata.getPublishStatus());
        asset.setMetadataDrifted(!Objects.equals(asset.getPublishedSnapshotId(),
                metadata.getPublishedSnapshotId()));
    }

    /**
     * 转换数量映射。
     */
    private Map<String, Long> toCountMap(List<Map<String, Object>> rows) {
        Map<String, Long> result = new HashMap<>();
        rows.forEach(row -> result.put(String.valueOf(rowValue(row, "group_key")),
                toLong(rowValue(row, "cnt"))));
        return result;
    }

    /** selectMaps 的列别名在不同数据库下大小写不同，统一按忽略大小写取值 */
    private Object rowValue(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /**
     * 转换长整数。
     */
    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 将模型层级转换为展示排序值。
     */
    private int layerOrder(String layer) {
        List<String> order = List.of("ODS", "DIM", "DWD", "DWS", "DWM", "ADS", "APP");
        int index = order.indexOf(StrUtil.nullToEmpty(layer).toUpperCase(Locale.ROOT));
        return index < 0 ? order.size() : index;
    }

    /**
     * 读取当前登录用户编号，拒绝缺失的登录身份。
     */
    private String currentUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId == null ? "" : String.valueOf(userId);
    }

    /**
     * 按顺序返回首个非空白字符串。
     */
    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

}
