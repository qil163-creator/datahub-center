package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetCatalogLinkOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetCatalogLinkOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkCommandDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkResultDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 资产领域交付意图；数据库提交与远端 RPC 分开，回执查询始终先于重发。 */
@Service
@RequiredArgsConstructor
public class AssetCatalogLinkService {

    private final AssetCatalogLinkOperationMapper operations;

    private final DataAssetMapper assets;

    private final MetadataApi metadataApi;

    private final PlatformTransactionManager transactionManager;

    private final MarketAuditRecorder audit;

    /**
     * 保存待执行的资产目录关联请求。
     */
    public void enqueue(DataAssetDO asset, Long tableId, boolean linked) {
        if (tableId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Catalog intent requires transaction");
        }

        assets.selectForCatalogUpdate(asset.getId());
        var row = new AssetCatalogLinkOperationDO();

        row.setOperationId(UUID.randomUUID().toString());
        row.setAssetId(asset.getId());
        row.setTableId(tableId);
        row.setSourceKey(asset.getCatalogItemUrn());
        row.setSourceRevision(operations.nextRevision(asset.getCatalogItemUrn(), tableId));
        row.setLinked(linked);
        row.setRequestHash(DigestUtil.sha256Hex(JsonUtils.toJsonString(command(row))));
        row.setStatus("PENDING");
        row.setOutcome("UNCONFIRMED");
        row.setAttempts(0);
        operations.insert(row);
    }

    /** 明确拒绝才能生成新版本；未知命令必须查询原回执，禁止人工直接标记成功。 */
    public void retryRejected(Long assetId, Long id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "重投原因");
        }
        transaction().executeWithoutResult(status -> {
            DataAssetDO asset = assets.selectForCatalogUpdate(assetId);
            if (asset == null) {
                throw exception(DATA_ASSET_NOT_EXISTS);
            }
            AssetCatalogLinkOperationDO old = operations.selectById(id);
            if (old == null || !Objects.equals(old.getAssetId(), assetId) || !"REJECTED".equals(old.getOutcome())) {
                throw exception(DATA_ASSET_CATALOG_LINK_FAILED);
            }
            if (operations.nextRevision(old.getSourceKey(), old.getTableId()) != old.getSourceRevision() + 1) {
                throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
            }
            enqueue(asset, old.getTableId(), Boolean.TRUE.equals(old.getLinked()));
            operations.update(null, Wrappers.<AssetCatalogLinkOperationDO>lambdaUpdate()
                    .set(AssetCatalogLinkOperationDO::getDispositionReason, reason)
                    .eq(AssetCatalogLinkOperationDO::getId, id));
            audit.record("CATALOG_LINK_REDELIVER", "CATALOG", assetId, null, null, reason, "REQUESTED");
        });
    }

    /**
     * 校验资产目录关联的已收敛，不满足约束时拒绝当前操作。
     */
    public void assertSettled(Long assetId) {
        if (operations.unresolved(assetId) > 0) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "目录挂接尚未确认，请核查挂接状态");
        }
    }

    /** 删除第一阶段保留目录直到所有解绑回执已确认，便于人工核查。 */
    public boolean prepareUnlink(DataAssetDO asset, Long tableId) {
        if (tableId == null) {
            return true;
        }
        AssetCatalogLinkOperationDO last = operations.selectOne(Wrappers.<AssetCatalogLinkOperationDO>lambdaQuery()
                .eq(AssetCatalogLinkOperationDO::getAssetId, asset.getId())
                .eq(AssetCatalogLinkOperationDO::getTableId, tableId)
                .orderByDesc(AssetCatalogLinkOperationDO::getSourceRevision).last("LIMIT 1"));
        if (last != null && Boolean.FALSE.equals(last.getLinked())) {
            return "SUCCESS".equals(last.getStatus());
        }
        enqueue(asset, tableId, false);
        return false;
    }

    /**
     * 对账到期。
     */
    public void reconcileDue() {

        for (Long id : operations.due()) {
            deliver(id);
        }
    }

    /**
     * 派发资产目录关联已持久化操作并记录交付回执。
     */
    public void deliver(Long id) {

        String token = UUID.randomUUID().toString();
        AssetCatalogLinkOperationDO row = transaction().execute(status -> operations.claim(id, token));
        if (row == null) {
            return;
        }
        String state = "UNKNOWN";
        String outcome = "UNKNOWN";
        String reason = "远端结果尚未确认";
        try {
            MetadataCatalogLinkResultDTO result = metadataApi.queryCatalogLinkOperation(row.getOperationId())
                    .getCheckedData();
            if (result != null && "NOT_FOUND".equals(result.getOutcome())) {
                result = metadataApi.applyCatalogLink(command(row)).getCheckedData();
            }
            if (result != null && Objects.equals(result.getOperationId(), row.getOperationId())
                    && Objects.equals(result.getRequestHash(), row.getRequestHash())
                    && Objects.equals(result.getSourceRevision(), row.getSourceRevision())) {
                outcome = result.getOutcome();
                if ("APPLIED".equals(outcome)) {
                    state = "SUCCESS";
                    reason = null;
                } else if ("REJECTED".equals(outcome)) {
                    state = "MANUAL";
                    reason = receiptReason(result.getReason());
                }
            }
        } catch (RuntimeException failure) {
            // 不将连接串、远端响应正文或冻结载荷持久化到对外错误字段。
            reason = "远端调用或回执查询失败，请重新核查";
        }
        if ("UNKNOWN".equals(state) && row.getAttempts() >= 6) {
            state = "MANUAL";
        }
        String finalState = state, finalOutcome = outcome, finalReason = reason;
        transaction().executeWithoutResult(
                status -> operations.complete(id, token, finalState, finalOutcome, finalReason));
    }

    /** 人工重核保留原操作身份；被明确拒绝的命令不能原地改写后重投。 */
    public void recheck(Long assetId, Long id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "核查原因");
        }
        transaction().executeWithoutResult(status -> {
            AssetCatalogLinkOperationDO row = operations.selectById(id);
            if (row == null || !Objects.equals(row.getAssetId(), assetId)) {
                throw exception(DATA_ASSET_NOT_EXISTS);
            }
            if ("SUCCESS".equals(row.getStatus())) {
                return;
            }
            if ("REJECTED".equals(row.getOutcome())) {
                throw exception(DATA_ASSET_CATALOG_LINK_FAILED);
            }
            if (operations.update(null, Wrappers.<AssetCatalogLinkOperationDO>lambdaUpdate()
                    .set(AssetCatalogLinkOperationDO::getStatus, "UNKNOWN")
                    .set(AssetCatalogLinkOperationDO::getDispositionReason, reason)
                    .set(AssetCatalogLinkOperationDO::getNextRetryTime, null)
                    .eq(AssetCatalogLinkOperationDO::getId, id)
                    .in(AssetCatalogLinkOperationDO::getStatus, "MANUAL", "UNKNOWN", "PENDING")) != 1) {
                throw exception(DATA_ASSET_CATALOG_REVISION_CONFLICT);
            }
            audit.record("CATALOG_LINK_RECHECK", "CATALOG", assetId, null, null, reason, "REQUESTED");
        });
        deliver(id);
    }

    /**
     * 提取目录关联交付回执中的处理原因。
     */
    private static String receiptReason(String reason) {
        if (reason == null) {
            return "提供方明确拒绝，请核查目录关联";
        }
        return switch (reason) {
            case "CATALOG_CONFLICT" -> "技术表已被其他目录占用；先解除冲突，再按新来源版本重投";
            case "STALE_SOURCE_REVISION" -> "本操作已落后于提供方来源版本；请核查后续挂接或解绑记录";
            case "OPERATION_CONFLICT" -> "同一操作编号出现不同命令摘要；需核查交付证据";
            case "TABLE_NOT_FOUND" -> "技术表不存在或已删除；请核查元数据";
            default -> "提供方明确拒绝，请核查目录关联";
        };
    }

    /**
     * 在平台事务边界内完成资产目录关联的本地读写。
     */
    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return tx;
    }

    /**
     * 根据资产目录关联持久化记录组装待发送的业务命令。
     */
    private static MetadataCatalogLinkCommandDTO command(AssetCatalogLinkOperationDO row) {
        return new MetadataCatalogLinkCommandDTO(row.getOperationId(), row.getSourceKey(), row.getSourceRevision(),
                row.getTableId(), row.getLinked());
    }
}
