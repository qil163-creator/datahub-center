package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetRegistrationSourceDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetTechLinkDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetRegistrationSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetTechLinkMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 注册写入仅操作资产本地状态，远端候选核验在进入事务前完成。 */
@Service
public class AssetRegistrationWriter {

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private AssetTechLinkMapper linkMapper;

    @Resource
    private AssetRegistrationSourceMapper sourceMapper;

    @Resource
    private AssetCatalogLinkService catalogLinks;

    public record Prepared(DataAssetDO asset, AssetRegistrationSourceDO source) {

    }

    /**
     * 持久化资产登记的表。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> persistTables(List<DataAssetDO> assets) {
        List<Long> ids = new ArrayList<>();
        for (DataAssetDO asset : assets) {
            assetMapper.insert(asset);
            linkMapper.insert(AssetTechLinkDO.builder().assetId(asset.getId()).providerModule("governance")
                    .resourceUrn(asset.getResourceUrn()).datasourceId(asset.getDatasourceId())
                    .schemaName(asset.getSchemaName())
                    .objectName(asset.getTableName()).metaTableId(asset.getMetaTableId())
                    .snapshotId(asset.getPublishedSnapshotId()).primaryFlag(1).build());
            catalogLinks.enqueue(asset, asset.getMetaTableId(), true);
            ids.add(asset.getId());
        }
        return ids;
    }

    /**
     * 持久化已准备的资产登记记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> persist(List<Prepared> rows) {
        List<Long> ids = new ArrayList<>();
        for (Prepared row : rows) {
            assetMapper.insert(row.asset());
            row.source().setAssetId(row.asset().getId());
            sourceMapper.insert(row.source());
            linkMapper.insert(AssetTechLinkDO.builder().assetId(row.asset().getId())
                    .providerModule(row.asset().getAssetType() == DataAssetDO.TYPE_FILE ? "governance" : "supply")
                    .resourceUrn(row.asset().getResourceUrn()).objectName(row.source().getSourceName())
                    .datasourceId(row.asset().getDatasourceId()).primaryFlag(1).build());
            ids.add(row.asset().getId());
        }
        return ids;
    }
}
