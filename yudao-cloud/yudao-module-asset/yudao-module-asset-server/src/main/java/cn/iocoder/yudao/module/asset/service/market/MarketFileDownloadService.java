package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFileBindingMapper;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
public class MarketFileDownloadService {
    private final MarketMyAssetService myAssets;
    private final MarketFileBindingMapper files;
    private final DataAssetMapper assets;
    private final ManagedFileApi storage;
    private final TransactionTemplate transactions;

    public record Download(String name, byte[] content) {}

    public Download download(long applyId) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        var initial = authorized(applyId, actor);
        var binding = files.find(initial.getCatalogVersionId());
        if (MarketFileDeliveryService.metadata(binding) == null) throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        byte[] content = storage.readManaged(binding.getManagedFileId(), binding.getOperationKey()).getCheckedData();
        if (content == null || content.length != binding.getFileSize() || !binding.getSha256().equals(DigestUtil.sha256Hex(content))) {
            throw exception(MARKET_FILE_UNAVAILABLE, "存储内容与获批文件不一致");
        }
        // 私有存储的网络读取在事务外。交付前重新检查权限版本、有效时间、目录及下载次数。
        transactions.executeWithoutResult(tx -> {
            // 与审批、撤权保持先申请后资产的顺序，单表锁可通过数据权限 SQL 解析器。
            Long assetId = files.lockDownloadAssetId(applyId, actor);
            if (assetId == null || !Objects.equals(assetId, initial.getAssetId())
                    || !Objects.equals(assetId, binding.getAssetId()) || assets.selectForMatching(assetId) == null) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            var current = authorized(applyId, actor);
            if (!Objects.equals(current.getSourceRevision(), initial.getSourceRevision())
                    || !Objects.equals(current.getRevision(), initial.getRevision())
                    || !Objects.equals(current.getCatalogVersionId(), binding.getCatalogVersionId())) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            Long limit = Objects.equals(current.getApplyUseType(), MarketResourceApplyDO.USE_LIMITED)
                    ? current.getApplyUseTimes().longValue() : null;
            if (files.consume(applyId, limit) != 1) throw exception(MARKET_FILE_LIMIT);
        });
        return new Download(binding.getFileName(), content);
    }

    private MarketResourceApplyDO authorized(long applyId, long actor) {
        var apply = myAssets.authorizeFileDownload(applyId);
        if (apply == null
                || !Objects.equals(apply.getApplyUserId(), actor) || apply.getCatalogVersionId() == null
                || Objects.equals(apply.getApplyUseType(), MarketResourceApplyDO.USE_ROWS)) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        MarketQuotaPolicy.requireFrozen(apply);
        return apply;
    }
}
