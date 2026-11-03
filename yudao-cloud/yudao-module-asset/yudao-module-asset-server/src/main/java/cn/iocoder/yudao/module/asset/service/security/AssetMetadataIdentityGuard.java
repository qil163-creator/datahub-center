package cn.iocoder.yudao.module.asset.service.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_TECH_UNAVAILABLE;

/** 消费前核验冻结技术身份；物理同名不能替代已删除的库表或字段。 */
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AssetMetadataIdentityGuard {

    private static final int MAX_ASSETS = 2000;

    private static final int MAX_COLUMNS = 10000;

    @Resource
    private MetadataApi metadataApi;

    /**
     * 同一批次只核验一次同一资产；不跨请求缓存成功结果。
     */
    public void assertCurrent(DataAssetDO asset) {
        if (asset == null) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        if (!Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
            return;
        }
        if (asset.getMetaTableId() == null || asset.getPublishedSnapshotId() == null
                || asset.getDatasourceId() == null || !stableUrn(asset.getResourceUrn())
                || StrUtil.isBlank(asset.getSchemaName()) || StrUtil.isBlank(asset.getTableName())) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        var table = metadataApi.getTable(asset.getMetaTableId()).getCheckedData();
        if (table == null || !Objects.equals(table.getObjectStatus(), 1)
                || !Objects.equals(table.getId(), asset.getMetaTableId())
                || !Objects.equals(table.getResourceUrn(), asset.getResourceUrn())
                || !Objects.equals(table.getDatasourceId(), asset.getDatasourceId())
                || !Objects.equals(table.getDbName(), asset.getDbName())
                || !Objects.equals(table.getSchemaName(), asset.getSchemaName())
                || !Objects.equals(table.getTableName(), asset.getTableName())) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        var frozen = metadataApi.getColumnListBySnapshot(asset.getPublishedSnapshotId()).getCheckedData();
        var current = metadataApi.getColumnList(asset.getMetaTableId()).getCheckedData();
        if (frozen == null || frozen.isEmpty() || frozen.size() > MAX_COLUMNS
                || current == null || current.size() > MAX_COLUMNS) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        var byId = new HashMap<Long, MetadataColumnRespDTO>();
        for (var column : current) {
            if (column == null || column.getId() == null || byId.putIfAbsent(column.getId(), column) != null) {
                throw exception(DATA_ASSET_TECH_UNAVAILABLE);
            }
        }
        var seen = new HashSet<Long>();
        for (var column : frozen) {
            if (column == null || column.getId() == null || !seen.add(column.getId())
                    || !stableUrn(column.getResourceUrn()) || StrUtil.isBlank(column.getColumnName())
                    || !Objects.equals(column.getTableId(), asset.getMetaTableId())) {
                throw exception(DATA_ASSET_TECH_UNAVAILABLE);
            }
            var live = byId.get(column.getId());
            if (live == null || !Objects.equals(live.getObjectStatus(), 1)
                    || !Objects.equals(live.getTableId(), column.getTableId())
                    || !Objects.equals(live.getResourceUrn(), column.getResourceUrn())
                    || !Objects.equals(live.getColumnName(), column.getColumnName())) {
                throw exception(DATA_ASSET_TECH_UNAVAILABLE);
            }
        }
    }

    /** 同一批次只核验一次同一资产；不跨请求缓存成功结果。 */
    public void assertCurrent(Collection<DataAssetDO> assets) {
        if (assets == null || assets.size() > MAX_ASSETS) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        var unique = new LinkedHashMap<Long, DataAssetDO>();
        for (var asset : assets) {
            if (asset == null || asset.getId() == null) {
                throw exception(DATA_ASSET_TECH_UNAVAILABLE);
            }
            var previous = unique.putIfAbsent(asset.getId(), asset);
            if (previous != null && (!Objects.equals(previous.getMetaTableId(), asset.getMetaTableId())
                    || !Objects.equals(previous.getPublishedSnapshotId(), asset.getPublishedSnapshotId())
                    || !Objects.equals(previous.getResourceUrn(), asset.getResourceUrn()))) {
                throw exception(DATA_ASSET_TECH_UNAVAILABLE);
            }
        }
        unique.values().forEach(this::assertCurrent);
    }

    /**
     * 判断元数据 URN 是否可用作稳定对象标识。
     */
    private static boolean stableUrn(String value) {
        return StrUtil.isNotBlank(value) && !value.startsWith("pending:");
    }
}
