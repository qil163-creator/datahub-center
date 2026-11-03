package cn.iocoder.yudao.module.asset.service.term;

import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermBindReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.term.AssetTermColumnRelDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.term.AssetTermColumnRelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_CATALOG_REVISION_CONFLICT;

/** 字段关联的短本地事务；资产行锁使同一资产的首次绑定、替换和解绑串行。 */
@Service
public class AssetTermBindingWriter {

    @Resource
    private DataAssetMapper assets;

    @Resource
    private AssetTermColumnRelMapper relations;

    /**
     * 保存资产术语绑定关联关系。
     */
    @Transactional(rollbackFor = Exception.class)
    public void bind(DataAssetDO observed, AssetTermBindReqVO request, String columnName) {
        DataAssetDO current = assets.selectForCatalogUpdate(observed.getId());
        // 授权与元数据核查期间资产归属/来源发生变化时，要求重新读取再操作。
        if (current == null || !Objects.equals(request.getAssetId(), observed.getId())
                || !Objects.equals(current.getCatalogRevision(), observed.getCatalogRevision())
                || !Objects.equals(current.getMetaTableId(), observed.getMetaTableId())
                || !Objects.equals(current.getOwnerDeptId(), observed.getOwnerDeptId())
                || !Objects.equals(current.getProviderDeptId(), observed.getProviderDeptId())
                || !Objects.equals(current.getTechLeaderUserId(), observed.getTechLeaderUserId())
                || !Objects.equals(current.getBusinessLeaderUserId(), observed.getBusinessLeaderUserId())
                || !Objects.equals(current.getCreator(), observed.getCreator())) {
            throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
        }
        var existing = relations.selectByAssetIdAndColumnId(current.getId(), request.getMetaColumnId());
        if (request.getTermId() == null) {
            if (existing != null) {
                relations.deleteById(existing.getId());
            }
        } else if (existing != null) {
            relations.update(null, Wrappers.lambdaUpdate(AssetTermColumnRelDO.class)
                    .eq(AssetTermColumnRelDO::getId, existing.getId())
                    .set(AssetTermColumnRelDO::getStandardTermId, request.getTermId())
                    .set(AssetTermColumnRelDO::getColumnName, columnName)
                    .set(AssetTermColumnRelDO::getRemark, request.getRemark()));
        } else {
            relations.insert(AssetTermColumnRelDO.builder()
                    .standardTermId(request.getTermId()).assetId(current.getId())
                    .metaColumnId(request.getMetaColumnId()).columnName(columnName)
                    .remark(request.getRemark()).build());
        }
    }
}
