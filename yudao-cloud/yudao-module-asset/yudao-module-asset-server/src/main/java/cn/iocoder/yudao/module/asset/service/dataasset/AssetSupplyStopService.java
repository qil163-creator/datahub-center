package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetSupplyStopMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.market.MarketApplyService;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 停供意图先提交，逐申请撤权使用已有可靠来源账本，异常不会丢失后续待办。 */
@Service
@RequiredArgsConstructor
public class AssetSupplyStopService {

    private final DataAssetMapper assets;

    private final AssetSupplyStopMapper stops;

    private final AssetCatalogService catalog;

    private final MarketApplyService applies;

    private final MarketAuditRecorder audit;

    private final PlatformTransactionManager transactionManager;

    /**
     * 创建资产供数停止处理请求并保存待处理状态。
     */
    public void request(Long id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw exception(DATA_ASSET_STOP_REASON_REQUIRED);
        }
        DataAssetDO asset = assets.selectById(id);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalog.assertCanMaintain(asset); // 权限 RPC 在行锁事务之前。
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            DataAssetDO locked = assets.selectForCatalogUpdate(id);
            if (locked == null) {
                throw exception(DATA_ASSET_NOT_EXISTS);
            }
            if (Objects.equals(locked.getStatus(),
                    DataAssetDO.STATUS_OFFLINE) && locked.getStopSupplyUserId() != null) {
                return;
            }
            assets.update(null, Wrappers.<DataAssetDO>lambdaUpdate()
                    .set(DataAssetDO::getStatus, DataAssetDO.STATUS_OFFLINE)
                    .set(DataAssetDO::getStopSupplyUserId, SecurityFrameworkUtils.getLoginUserId())
                    .set(DataAssetDO::getStopSupplyDeptId, SecurityFrameworkUtils.getLoginUserDeptId())
                    .set(DataAssetDO::getStopSupplyReason, reason)
                    .set(DataAssetDO::getStopSupplyTime, LocalDateTime.now())
                    .eq(DataAssetDO::getId, id));
            audit.record("CATALOG_STOP_SUPPLY", "CATALOG", id, locked.getStatus(), DataAssetDO.STATUS_OFFLINE, reason,
                    "REQUESTED");
        });
    }

    /**
     * 对账到期。
     */
    public void reconcileDue() {
        int failures = 0;
        for (Long applyId : stops.due()) {
            try {
                applies.revokeForAssetStop(applyId);
            } catch (RuntimeException failure) {
                failures++;
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("部分停供撤权尚未完成，待下次重试：" + failures);
        }
    }

    /**
     * 查询资产供数停止尚未完成的处理记录。
     */
    public long pending(Long assetId) {
        return stops.pending(assetId);
    }

    /**
     * 校验资产供数停止的可删除，不满足约束时拒绝当前操作。
     */
    public void assertDeletable(Long assetId) {

        if (stops.openApplications(assetId) > 0 || stops.pending(assetId) > 0 || stops.unclosedSources(assetId) > 0) {
            throw exception(DATA_ASSET_RETIRE_PENDING);
        }
    }
}
