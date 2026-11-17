package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketFileBindingRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFileBinding;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFileBindingMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileCreateReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Service
@RequiredArgsConstructor
public class MarketFileDeliveryService {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    private final DataAssetMapper assets;
    private final MarketFileBindingMapper bindings;
    private final AssetCatalogService catalog;
    private final MarketObjectGuard guard;
    private final ManagedFileApi files;
    private final TransactionTemplate transactions;

    public MarketFileBindingRespVO getBinding(long assetId, long versionId) {
        var asset = assets.selectById(assetId);
        catalog.assertCanMaintain(asset);
        requireVersion(asset, versionId);
        return metadata(bindings.find(versionId));
    }

    public MarketFileBindingRespVO upload(long assetId, long versionId, MultipartFile file) throws IOException {
        var authorization = catalog.prepareMaintenanceAuthorization();
        long actor = guard.requireUserId();
        var asset = assets.selectById(assetId);
        catalog.assertCanMaintain(asset, authorization);
        requireVersion(asset, versionId);
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 180 || name.contains("/") || name.contains("\\")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw exception(MARKET_FILE_UNAVAILABLE, "文件名称无效");
        }
        if (file.getSize() <= 0 || file.getSize() > MAX_BYTES) {
            throw exception(MARKET_FILE_UNAVAILABLE, "请选择不超过20 MiB的非空文件");
        }
        byte[] content;
        try (var in = file.getInputStream()) {
            content = in.readNBytes(MAX_BYTES + 1);
        }
        if (content.length != file.getSize() || content.length > MAX_BYTES) {
            throw exception(MARKET_FILE_UNAVAILABLE, "文件长度与上传声明不符");
        }
        var intent = new MarketFileBinding();
        intent.setCatalogVersionId(versionId);
        intent.setAssetId(assetId);
        intent.setUploaderId(actor);
        intent.setFileName(name);
        intent.setFileSize((long) content.length);
        intent.setSha256(DigestUtil.sha256Hex(content));
        intent.setOperationKey("asset_market_file_" + assetId + "_" + versionId);
        // 先提交本地身份，再写私有存储。网络断开后，重新提交同一文件可继续同一操作。
        MarketFileBinding reserved = transactions.execute(tx -> {
            var locked = assets.selectForCatalogUpdate(assetId);
            catalog.assertCanMaintain(locked, authorization);
            requireVersion(locked, versionId);
            bindings.reserve(intent);
            var existing = bindings.find(versionId);
            if (existing == null || !Objects.equals(existing.getAssetId(), assetId)
                    || existing.getManagedFileId() != null && !sameFile(existing, intent)) {
                throw exception(MARKET_FILE_CONFLICT);
            }
            return existing;
        });
        if (reserved.getManagedFileId() != null) return metadata(reserved);
        var request = new ManagedFileCreateReqDTO();
        request.setOperationKey(intent.getOperationKey());
        request.setName(name);
        request.setContent(content);
        var response = files.createManaged(request);
        if (Objects.equals(response.getCode(), cn.iocoder.yudao.module.infra.enums.ErrorCodeConstants.MANAGED_FILE_CONFLICT.getCode())) {
            throw exception(MARKET_FILE_CONFLICT);
        }
        var receipt = response.getCheckedData();
        if (receipt == null || !"READY".equals(receipt.getState()) || receipt.getId() == null || receipt.getId() <= 0
                || !intent.getSha256().equals(receipt.getSha256()) || !intent.getFileSize().equals(receipt.getSize())) {
            throw exception(MARKET_FILE_UNAVAILABLE, "私有存储尚未确认该文件，请重新提交同一文件");
        }
        // 未获 READY 的旧意图只跟踪远端操作；不同候选由同一受管键仲裁。
        // 只有存储确认后才锁定文件身份，失联或业务拒绝都不会删除可能存在的远端对象。
        intent.setManagedFileId(receipt.getId());
        var finalAuthorization = catalog.prepareMaintenanceAuthorization();
        return transactions.execute(tx -> {
            var locked = assets.selectForCatalogUpdate(assetId);
            catalog.assertCanMaintain(locked, finalAuthorization);
            requireVersion(locked, versionId);
            if (bindings.complete(intent) != 1) throw exception(MARKET_FILE_CONFLICT);
            return metadata(intent);
        });
    }

    private static boolean sameFile(MarketFileBinding bound, MarketFileBinding candidate) {
        return Objects.equals(bound.getFileName(), candidate.getFileName())
                && Objects.equals(bound.getSha256(), candidate.getSha256())
                && Objects.equals(bound.getFileSize(), candidate.getFileSize());
    }

    static MarketFileBindingRespVO frozenMetadata(MarketResourceApplyDO apply) {
        if (!Objects.equals(apply.getAssetType(), DataAssetDO.TYPE_FILE) || apply.getSnapshot() == null) return null;
        var snapshot = JsonUtils.parseObjectQuietly(apply.getSnapshot(), Map.class);
        if (snapshot == null || !(snapshot.get("fileDelivery") instanceof Map<?, ?> file)) return null;
        try {
            long version = Long.parseLong(String.valueOf(file.get("catalogVersionId")));
            long size = Long.parseLong(String.valueOf(file.get("size")));
            if (!Objects.equals(version, apply.getCatalogVersionId()) || size <= 0 || size > MAX_BYTES
                    || !(file.get("fileName") instanceof String name) || name.isBlank()
                    || !(file.get("sha256") instanceof String hash) || !hash.matches("[0-9a-f]{64}")) return null;
            return new MarketFileBindingRespVO(version, name, size, hash);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static void requireVersion(DataAssetDO asset, long versionId) {
        if (asset == null || !Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)
                || !Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_FILE)
                || Boolean.TRUE.equals(asset.getRetirementRequested()) || !Objects.equals(asset.getPublishedCatalogVersionId(), versionId)) {
            throw exception(MARKET_FILE_UNAVAILABLE, "请选择当前已发布的文件目录版本");
        }
    }

    static MarketFileBindingRespVO metadata(MarketFileBinding file) {
        return file == null || file.getManagedFileId() == null ? null :
                new MarketFileBindingRespVO(file.getCatalogVersionId(), file.getFileName(), file.getFileSize(), file.getSha256());
    }

    void requirePublishedBinding(DataAssetDO asset) {
        if (asset == null || asset.getPublishedCatalogVersionId() == null) {
            throw exception(MARKET_FILE_UNAVAILABLE, "请先发布文件目录版本");
        }
        requireReadyBinding(asset.getId(), asset.getPublishedCatalogVersionId());
    }

    private MarketFileBinding requireReadyBinding(Long assetId, Long versionId) {
        var binding = versionId == null ? null : bindings.find(versionId);
        if (metadata(binding) == null || !Objects.equals(binding.getAssetId(), assetId)) {
            throw exception(MARKET_FILE_UNAVAILABLE, "请为当前发布的目录版本绑定交付文件");
        }
        return binding;
    }

    void freeze(MarketResourceApplyDO apply, DataAssetDO asset, Map<String, Object> snapshot) {
        if (apply.getAppId() != null || apply.getApiId() != null || Objects.equals(apply.getApplyUseType(), 2)
                || apply.getFieldScope() != null && !apply.getFieldScope().isBlank()
                    && !List.of("*", "ALL", "全部", "全部字段", "整份文件").contains(apply.getFieldScope().trim())) {
            throw exception(MARKET_FILE_UNAVAILABLE, "文件按整份授权，可选不限次数或下载次数");
        }
        var binding = requireReadyBinding(asset.getId(), apply.getCatalogVersionId());
        snapshot.put("fileDelivery", evidence(binding));
    }

    void requireGrant(MarketResourceApplyDO apply, DataAssetDO asset) {
        if (apply.getCatalogVersionId() == null) throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        requireGrant(apply, asset, bindings.find(apply.getCatalogVersionId()));
    }

    static void requireGrant(MarketResourceApplyDO apply, DataAssetDO asset, MarketFileBinding binding) {
        var snapshot = JsonUtils.parseObjectQuietly(apply.getSnapshot(), Map.class);
        if (snapshot == null || binding == null || binding.getManagedFileId() == null || asset == null
                || !MarketFieldScope.supportsExistingGrant(asset) || !Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_FILE)
                || apply.getSourceWorkOrderId() != null || apply.getApiId() != null || apply.getAppId() != null
                || !Objects.equals(apply.getAssetType(), DataAssetDO.TYPE_FILE)
                || !Objects.equals(asset.getId(), apply.getAssetId()) || !Objects.equals(binding.getAssetId(), apply.getAssetId())
                || !Objects.equals(asset.getPublishedCatalogVersionId(), apply.getCatalogVersionId())
                || !Objects.equals(binding.getCatalogVersionId(), apply.getCatalogVersionId())
                || !Objects.equals(snapshot.get("assetVersion"), Objects.toString(asset.getPublishedVersion(), ""))
                || !Objects.equals(snapshot.get("assetId"), String.valueOf(asset.getId()))
                || !Objects.equals(snapshot.get("catalogVersionId"), String.valueOf(binding.getCatalogVersionId()))
                || !Objects.equals(snapshot.get("fileDelivery"), evidence(binding))) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
    }

    private static Map<String, String> evidence(MarketFileBinding binding) {
        return Map.of("catalogVersionId", String.valueOf(binding.getCatalogVersionId()),
                "fileName", binding.getFileName(), "size", String.valueOf(binding.getFileSize()), "sha256", binding.getSha256());
    }
}
