package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 已完成远程鉴权后的短本地事务；历史关联只追加，不覆盖。 */
@Service
public class AssetEtlBatchBindingWriter {

    @Resource
    private DataAssetMapper assets;

    @Resource
    private AssetEtlBatchBindingMapper bindings;

    /**
     * 保存资产与 ETL 批次的新绑定记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer append(DataAssetDO observed, Integer expectedRevision, AssetEtlBatchBindingDO binding) {
        if (binding.getActorId() == null || !Objects.equals(binding.getAssetId(), observed.getId())) {
            throw exception(DATA_ASSET_FORBIDDEN);
        }
        var current = assets.selectForCatalogUpdate(observed.getId());
        if (current == null || !Objects.equals(current.getCatalogRevision(), observed.getCatalogRevision())
                || !Objects.equals(current.getDatasourceId(), observed.getDatasourceId())
                || !Objects.equals(current.getSchemaName(), observed.getSchemaName())
                || !Objects.equals(current.getTableName(), observed.getTableName())) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        var previous = bindings.latest(observed.getId());
        if (!Objects.equals(previous == null ? null : previous.getRevision(), expectedRevision)) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        int revision = previous == null ? 0 : Math.addExact(previous.getRevision(), 1);
        binding.setRevision(revision);
        binding.setCreator(String.valueOf(binding.getActorId()));
        binding.setUpdater(String.valueOf(binding.getActorId()));
        try {
            if (bindings.insert(binding) != 1) {
                throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
            }
        } catch (DuplicateKeyException race) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        return revision;
    }
}
