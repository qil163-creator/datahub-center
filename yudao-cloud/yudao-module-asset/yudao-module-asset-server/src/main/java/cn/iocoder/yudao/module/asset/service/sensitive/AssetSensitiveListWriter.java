package cn.iocoder.yudao.module.asset.service.sensitive;

import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.sensitive.AssetSensitiveListDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.sensitive.AssetSensitiveListMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.*;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 全部锁和本地审计在同一事务内；此组件不调用任何远程接口。 */
@Service
@Transactional(rollbackFor = Exception.class)
public class AssetSensitiveListWriter {

    @Resource
    private AssetSensitiveListMapper mapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private AssetImportDatasetMapper datasetMapper;

    @Resource
    private AssetCatalogService catalog;

    @Resource
    private MarketAuditRecorder audit;

    /**
     * 校验输入并创建资产敏感字段列表记录。
     */
    public Long create(AssetSensitiveSaveReqVO req, AssetSensitiveListService.Prepared prepared,
            AssetMaintenanceAuthorization auth) {
        verifyPrepared(prepared, lockAssets(List.of(req.getAssetId()), auth).get(req.getAssetId()));
        var row = definition(req, prepared);
        row.setRevision(0);
        row.setCreator(actor(auth));
        row.setUpdater(actor(auth));
        mapper.insert(row);
        audit(row, "SENSITIVE_CREATE", null, row.getStatus(), auth);
        return row.getId();
    }

    /**
     * 校验目标和输入后更新资产敏感字段列表记录。
     */
    public void update(AssetSensitiveSaveReqVO req, Long previousAssetId, AssetSensitiveListService.Prepared prepared,
            AssetMaintenanceAuthorization auth) {
        var assets = lockAssets(List.of(previousAssetId, req.getAssetId()), auth);
        var row = requireLocked(req.getId());
        revision(row, req.getRevision());
        if (!Objects.equals(row.getAssetId(), previousAssetId)) {
            throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
        }
        if (!Objects.equals(row.getStatus(), 1)) {
            throw exception(ASSET_SENSITIVE_ENABLED);
        }
        verifyPrepared(prepared, assets.get(req.getAssetId()));
        var update = definition(req, prepared);
        update.setId(row.getId());
        if (mapper.updateDefinition(update, req.getRevision(), actor(auth)) != 1) {
            throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
        }
        audit.recordAs("SENSITIVE_UPDATE", "ASSET_SENSITIVE_LIST", update.getId(), 1, update.getStatus(),
                "修改前=" + summary(row) + ";修改后=" + summary(update), "OK", auth.getUserId(), auth.getDeptId());
    }

    /**
     * 变更资产敏感字段列表的状态。
     */
    public void changeStatus(AssetSensitiveStatusReqVO req, Long previousAssetId,
            AssetSensitiveListService.Prepared prepared,
            AssetMaintenanceAuthorization auth) {
        var asset = lockAssets(List.of(previousAssetId), auth).get(previousAssetId);
        var row = requireLocked(req.getId());
        revision(row, req.getRevision());
        if (!Objects.equals(row.getAssetId(), previousAssetId)) {
            throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
        }
        if (req.getStatus() == null || req.getStatus() < 0 || req.getStatus() > 1) {
            throw exception(ASSET_SENSITIVE_INPUT_INVALID);
        }
        if (prepared != null) {
            verifyPrepared(prepared, asset);
        }
        if (Objects.equals(row.getStatus(), req.getStatus())) {
            return;
        }
        if (mapper.changeStatus(row.getId(), req.getRevision(), req.getStatus(), actor(auth)) != 1) {
            throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
        }
        audit(row, "SENSITIVE_STATUS", row.getStatus(), req.getStatus(), auth);
    }

    /**
     * 校验目标后删除资产敏感字段列表记录。
     */
    public AssetSensitiveOptionVO.Deleted delete(List<AssetSensitiveDeleteReqVO.Item> items,
            Map<Long, Long> previousAssets,
            boolean strict, AssetMaintenanceAuthorization auth) {
        lockAssets(previousAssets.values(), auth);
        var revisions = new TreeMap<Long, Integer>();
        items.forEach(item -> revisions.put(item.getId(), item.getRevision()));
        var rows = new ArrayList<AssetSensitiveListDO>();
        for (var entry : revisions.entrySet()) {
            var row = requireLocked(entry.getKey());
            revision(row, entry.getValue());
            if (!Objects.equals(row.getAssetId(), previousAssets.get(row.getId()))) {
                throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
            }
            if (strict && !Objects.equals(row.getStatus(), 1)) {
                throw exception(ASSET_SENSITIVE_ENABLED);
            }
            rows.add(row);
        }
        var deleted = new ArrayList<Long>();
        var skipped = new ArrayList<Long>();
        for (var row : rows) {
            if (!Objects.equals(row.getStatus(), 1)) {
                skipped.add(row.getId());
                continue;
            }
            if (mapper.deleteRevision(row.getId(), revisions.get(row.getId()), actor(auth)) != 1) {
                throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
            }
            audit(row, "SENSITIVE_DELETE", row.getStatus(), null, auth);
            deleted.add(row.getId());
        }
        return new AssetSensitiveOptionVO.Deleted(deleted, skipped);
    }

    /**
     * 锁定资产。
     */
    private Map<Long, DataAssetDO> lockAssets(Collection<Long> ids, AssetMaintenanceAuthorization auth) {
        var result = new HashMap<Long, DataAssetDO>();
        for (Long id : new TreeSet<>(ids)) {
            var asset = assetMapper.selectForCatalogUpdate(id);
            if (asset == null) {
                throw exception(DATA_ASSET_NOT_EXISTS);
            }
            catalog.assertCanMaintain(asset, auth);
            result.put(id, asset);
        }
        return result;
    }

    /**
     * 核验资产敏感字段列表的准备结果，不满足约束时拒绝当前操作。
     */
    private void verifyPrepared(AssetSensitiveListService.Prepared prepared, DataAssetDO current) {
        var before = prepared.asset();
        if (!Objects.equals(before.getId(), current.getId()) || !Objects.equals(before.getCatalogRevision(),
                current.getCatalogRevision())
                || !Objects.equals(before.getMetaTableId(), current.getMetaTableId())
                || !Objects.equals(before.getDatasourceId(), current.getDatasourceId())
                || !Objects.equals(before.getPublishedCatalogVersionId(), current.getPublishedCatalogVersionId())) {
            throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
        }
        var dataset = datasetMapper.selectById(current.getId());
        if (!Objects.equals(prepared.datasetRevision(), dataset == null ? null : dataset.getRevision())) {
            throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
        }
    }

    /**
     * 校验资产敏感字段列表的已锁定，不满足约束时拒绝当前操作。
     */
    private AssetSensitiveListDO requireLocked(Long id) {
        var row = mapper.selectForUpdate(id);
        if (row == null) {
            throw exception(ASSET_SENSITIVE_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 校验资产敏感字段列表的预期修订号。
     */
    private void revision(AssetSensitiveListDO row, Integer expected) {
        if (expected == null || !Objects.equals(row.getRevision(), expected)) {
            throw exception(ASSET_SENSITIVE_REVISION_CONFLICT);
        }
    }

    /**
     * 用核验后的字段和分类事实构造敏感清单记录。
     */
    private AssetSensitiveListDO definition(AssetSensitiveSaveReqVO req, AssetSensitiveListService.Prepared prepared) {
        var row = new AssetSensitiveListDO();
        row.setAssetId(req.getAssetId());
        row.setColumnId(prepared.columnId());
        row.setColumnName(prepared.columnName());
        var category = prepared.category();
        row.setCategoryId(category.getCategoryId());
        row.setRuleId(category.getRuleId());
        row.setClassificationName(category.getName());
        row.setLevelName(category.getLevelName());
        row.setLevelColor(category.getLevelColor());
        row.setRuleName(category.getRuleName());
        row.setDescription(Objects.requireNonNullElse(req.getDescription(), ""));
        row.setStatus(req.getStatus());
        return row;
    }

    /**
     * 读取资产敏感字段列表当前操作人的身份信息。
     */
    private String actor(AssetMaintenanceAuthorization auth) {
        return String.valueOf(auth.getUserId());
    }

    /**
     * 记录资产敏感字段列表变更的操作人、对象与结果。
     */
    private void audit(AssetSensitiveListDO row, String type, Integer from, Integer to,
            AssetMaintenanceAuthorization auth) {
        audit.recordAs(type, "ASSET_SENSITIVE_LIST", row.getId(), from, to,
                summary(row), "OK",
                auth.getUserId(), auth.getDeptId());
    }

    /**
     * 生成包含资产、字段、分类编号及说明摘要的审计说明。
     */
    private String summary(AssetSensitiveListDO row) {
        return "资产=" + row.getAssetId() + ",字段=" + row.getColumnId() + ",分类=" + row.getCategoryId()
                + ",说明摘要=" + cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                Objects.requireNonNullElse(row.getDescription(), ""));
    }
}
