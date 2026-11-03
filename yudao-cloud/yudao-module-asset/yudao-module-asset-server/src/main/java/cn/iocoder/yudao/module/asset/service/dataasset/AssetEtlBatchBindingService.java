package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard;
import cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 资产拥有关联历史；批次状态、目标读数及数据权限由 DataForge 的正式 API 证明。 */
@Service
public class AssetEtlBatchBindingService {

    @Resource
    private DataAssetMapper assets;

    @Resource
    private AssetEtlBatchBindingMapper bindings;

    @Resource
    private AssetCatalogService catalog;

    @Resource
    private AssetAccessGuard guard;

    @Resource
    private EtlTargetBatchApi batches;

    @Resource
    private AssetEtlBatchBindingWriter writer;

    /**
     * 绑定明确批次并追加关联修订历史。
     */
    public Integer bind(AssetEtlBatchBindingReqVO request) {
        var asset = require(request.getAssetId());
        catalog.assertCanMaintain(asset);
        catalog.assertSingleTableCompatible(asset);
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        var descriptor = describe(request.getBatchId(), actor);
        if (!Boolean.TRUE.equals(descriptor.getAvailable())) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        requireIdentity(asset, descriptor.getDatabaseId(), descriptor.getSchemaName(), descriptor.getTableName());
        var binding = new AssetEtlBatchBindingDO();
        binding.setAssetId(asset.getId());
        binding.setBatchId(request.getBatchId());
        binding.setRecordId(descriptor.getRecordId());
        binding.setPayloadHash(descriptor.getPayloadHash());
        binding.setDatabaseId(descriptor.getDatabaseId());
        binding.setSchemaName(descriptor.getSchemaName());
        binding.setTableName(descriptor.getTableName());
        binding.setActorId(actor);
        binding.setReason(request.getReason().trim());
        return writer.append(asset, request.getExpectedRevision(), binding);
    }

    /**
     * 读取当前资产关联的具体批次及实时准入摘要。
     */
    public AssetEtlBatchBindingRespVO get(Long assetId) {
        var asset = readable(assetId);
        var binding = bindings.latest(assetId);
        if (binding == null) {
            return null;
        }
        requireIdentity(asset, binding.getDatabaseId(), binding.getSchemaName(), binding.getTableName());
        var descriptor = describe(binding.getBatchId(), SecurityFrameworkUtils.getLoginUserId());
        if (!Objects.equals(binding.getPayloadHash(), descriptor.getPayloadHash()) || !Objects.equals(
                binding.getRecordId(), descriptor.getRecordId())
                || !Objects.equals(binding.getDatabaseId(), descriptor.getDatabaseId())
                || !Objects.equals(binding.getSchemaName(), descriptor.getSchemaName()) || !Objects.equals(
                binding.getTableName(), descriptor.getTableName())) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        var result = BeanUtils.toBean(binding, AssetEtlBatchBindingRespVO.class);
        result.setBatch(descriptor);
        return result;
    }

    /** 与资产详情同一对象范围；只读历史，远端批次失效不抹掉过去的关联事实。 */
    public AssetEtlBatchHistoryRespVO history(Long assetId, Integer beforeRevision, int limit) {
        if (assetId == null || assetId <= 0 || limit < 1 || limit > 50
                || (beforeRevision != null && beforeRevision < 0)) {
            throw new IllegalArgumentException("Bounded binding history required");
        }
        var asset = require(assetId);
        guard.assertPublishedHistoryReadable(asset, SecurityFrameworkUtils.getLoginUserId());
        catalog.assertSingleTableCompatible(asset);
        var rows = bindings.history(assetId, beforeRevision, limit + 1);
        boolean hasMore = rows.size() > limit;
        var items = rows.stream().limit(limit)
                .map(row -> BeanUtils.toBean(row, AssetEtlBatchHistoryRespVO.Item.class)).toList();
        return new AssetEtlBatchHistoryRespVO(items,
                hasMore ? items.get(items.size() - 1).getRevision() : null, hasMore);
    }

    /**
     * 读取所绑定批次的准入数据，实时复核源对象权限。
     */
    public EtlTargetBatchRowsRespDTO rows(Long assetId, Integer expectedRevision, Long afterRow, Integer limit) {
        var asset = readable(assetId);
        var binding = bindings.latest(assetId);
        if (binding == null) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        if (!Objects.equals(binding.getRevision(), expectedRevision)) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        requireIdentity(asset, binding.getDatabaseId(), binding.getSchemaName(), binding.getTableName());
        var request = new EtlTargetBatchReadReqDTO();
        request.setBatchId(binding.getBatchId());
        request.setExpectedPayloadHash(binding.getPayloadHash());
        request.setExpectedDatabaseId(binding.getDatabaseId());
        request.setExpectedSchemaName(binding.getSchemaName());
        request.setExpectedTableName(binding.getTableName());
        request.setExecutionUserId(SecurityFrameworkUtils.getLoginUserId());
        request.setAfterRow(afterRow);
        request.setLimit(limit);
        return batches.read(request).getCheckedData();
    }

    /** 旧资产预览入口也必须经过已绑定批次的准入，不能退回整表读取。 */
    public cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO previewIfBound(
            DataAssetPreviewReqVO request) {
        if (bindings.latest(request.getId()) == null) {
            return null;
        }
        if (request.getFilters() != null && !request.getFilters().isEmpty()) {
            throw exception(DATA_ASSET_ETL_FILTER_UNSUPPORTED);
        }
        var binding = get(request.getId());
        var page = rows(request.getId(), binding.getRevision(), 0L,
                request.getLimit() == null ? 20 : Math.max(1, Math.min(100, request.getLimit())));
        if (page == null || page.getRows() == null) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        var result = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO();
        result.setRows(page.getRows());
        result.setTruncated(page.getHasMore());
        result.setColumns(binding.getBatch().getFields().stream().map(name ->
                        new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO.Column(name, name, ""))
                .toList());
        return result;
    }

    /**
     * 组装资产ETL批次绑定的描述信息。
     */
    private EtlTargetBatchDescribeRespDTO describe(Long batchId, Long actor) {
        if (actor == null) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        var request = new EtlTargetBatchDescribeReqDTO();
        request.setBatchId(batchId);
        request.setExecutionUserId(actor);
        var result = batches.describe(request).getCheckedData();
        if (result == null || !Objects.equals(result.getBatchId(), batchId) || result.getPayloadHash() == null
                || !result.getPayloadHash().matches("[0-9a-f]{64}") || result.getRecordId() == null) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        return result;
    }

    /**
     * 判断资产ETL批次绑定当前状态及授权是否允许读取。
     */
    private DataAssetDO readable(Long assetId) {
        var asset = require(assetId);
        guard.assertPublishedReadable(asset, SecurityFrameworkUtils.getLoginUserId());
        catalog.assertSingleTableCompatible(asset);
        return asset;
    }

    /**
     * 读取并校验必要的资产ETL批次绑定上下文或记录。
     */
    private DataAssetDO require(Long id) {
        var asset = assets.selectById(id);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (!Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
            throw exception(DATA_ASSET_PREVIEW_UNSUPPORTED);
        }
        return asset;
    }

    /**
     * 校验资产ETL批次绑定的身份，不满足约束时拒绝当前操作。
     */
    private static void requireIdentity(DataAssetDO asset, Long database, String schema, String table) {
        if (schema == null || table == null || !Objects.equals(asset.getDatasourceId(), database)
                || !Objects.equals(asset.getSchemaName(), schema) || !Objects.equals(asset.getTableName(), table)) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
    }
}
