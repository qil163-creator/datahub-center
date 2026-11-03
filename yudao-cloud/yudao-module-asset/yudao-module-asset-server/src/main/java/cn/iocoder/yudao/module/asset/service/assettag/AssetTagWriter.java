package cn.iocoder.yudao.module.asset.service.assettag;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.*;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.*;
import cn.iocoder.yudao.module.asset.dal.mysql.categorytag.CategoryTagMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.*;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 标签定义平台共享；关联写入必须在本地事务中锁资产并复核维护证据。 */
@Service
@Transactional(rollbackFor = Exception.class)
public class AssetTagWriter {

    @Resource
    private AssetTagMapper tagMapper;

    @Resource
    private AssetTagRelMapper relMapper;

    @Resource
    private CategoryTagMapper categoryMapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    @Lazy
    private AssetCatalogService catalog;

    @Resource
    private MarketAuditRecorder audit;

    /**
     * 保存资产标签配置或业务记录。
     */
    public Long save(AssetTagSaveReqVO req, AssetMaintenanceAuthorization auth) {
        if (req.getName() == null || req.getName().trim().isEmpty() || req.getName().trim().length() > 50
                || req.getCategoryId() == null) {
            throw exception(ASSET_TAG_INPUT_INVALID);
        }
        String name = req.getName().trim();
        var previous = req.getId() == null ? null : locked(req.getId());
        if (previous != null) {
            revision(previous, req.getRevision());
            disabled(previous);
        }
        var category = categoryMapper.selectForTagging(req.getCategoryId());
        if (category == null || !Objects.equals(category.getStatus(), 0)) {
            throw exception(CATEGORY_TAG_NOT_EXISTS);
        }
        var exists = tagMapper.selectByCategoryAndName(req.getCategoryId(), name);
        if (exists != null && !Objects.equals(exists.getId(), req.getId())) {
            throw exception(ASSET_TAG_NAME_DUPLICATE);
        }
        if (previous == null) {
            var tag = BeanUtils.toBean(req, AssetTagDO.class);
            tag.setId(null);
            tag.setName(name);
            tag.setRevision(0);
            tag.setStatus(req.getStatus() == null ? 0 : req.getStatus());
            if (tag.getStatus() < 0 || tag.getStatus() > 1) {
                throw exception(ASSET_TAG_INPUT_INVALID);
            }
            tag.setCreator(actor(auth));
            tag.setUpdater(actor(auth));
            tagMapper.insert(tag);
            record("TAG_CREATE", tag.getId(), null, tag.getStatus(), "标签=" + tag.getName(), auth);
            return tag.getId();
        }
        int changed = tagMapper.update(null, Wrappers.<AssetTagDO>lambdaUpdate()
                .set(AssetTagDO::getName, name).set(AssetTagDO::getCategoryId, req.getCategoryId())
                .set(AssetTagDO::getColor, req.getColor()).set(AssetTagDO::getNearSynonyms, req.getNearSynonyms())
                .set(AssetTagDO::getSynonyms, req.getSynonyms()).set(AssetTagDO::getDescription, req.getDescription())
                .set(AssetTagDO::getRemark, req.getRemark()).set(AssetTagDO::getUpdater, actor(auth))
                .set(AssetTagDO::getSourceUpdaterName, null).set(AssetTagDO::getSourceUpdateTime, null)
                .setSql("revision=revision+1,update_time=CURRENT_TIMESTAMP")
                .eq(AssetTagDO::getId, previous.getId()).eq(AssetTagDO::getRevision, req.getRevision())
                .eq(AssetTagDO::getStatus, 1));
        if (changed != 1) {
            throw exception(ASSET_TAG_REVISION_CONFLICT);
        }
        record("TAG_UPDATE", previous.getId(), 1, 1,
                "名称=" + previous.getName() + "→" + name + ";分类=" + previous.getCategoryId() + "→" + req.getCategoryId(),
                auth);
        return previous.getId();
    }

    /**
     * 读取或转换资产标签状态。
     */
    public void status(Long id, Integer status, Integer revision, AssetMaintenanceAuthorization auth) {
        var tag = locked(id);
        revision(tag, revision);
        if (status == null || status < 0 || status > 1) {
            throw exception(ASSET_TAG_INPUT_INVALID);
        }
        if (Objects.equals(tag.getStatus(), status)) {
            return;
        }
        int changed = tagMapper.update(null, Wrappers.<AssetTagDO>lambdaUpdate().set(AssetTagDO::getStatus, status)
                .set(AssetTagDO::getUpdater, actor(auth)).set(AssetTagDO::getSourceUpdaterName, null)
                .set(AssetTagDO::getSourceUpdateTime, null)
                .setSql("revision=revision+1,update_time=CURRENT_TIMESTAMP").eq(AssetTagDO::getId, id)
                .eq(AssetTagDO::getRevision, revision));
        if (changed != 1) {
            throw exception(ASSET_TAG_REVISION_CONFLICT);
        }
        record("TAG_STATUS", id, tag.getStatus(), status, null, auth);
    }

    /**
     * 校验目标后删除资产标签记录。
     */
    public void delete(List<AssetTagDeleteListReqVO.Item> items, AssetMaintenanceAuthorization auth) {
        var revisions = new TreeMap<Long, Integer>();
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw exception(ASSET_TAG_INPUT_INVALID);
        }
        for (var item : items) {
            if (item == null || item.getId() == null || item.getRevision() == null || revisions.put(item.getId(),
                    item.getRevision()) != null) {
                throw exception(ASSET_TAG_INPUT_INVALID);
            }
        }
        for (var entry : revisions.entrySet()) {
            var tag = locked(entry.getKey());
            revision(tag, entry.getValue());
            disabled(tag);
            if (relMapper.selectCountByTagId(tag.getId()) > 0) {
                throw exception(ASSET_TAG_IN_USE);
            }
        }
        for (var entry : revisions.entrySet()) {
            if (tagMapper.update(null, Wrappers.<AssetTagDO>lambdaUpdate()
                    .set(AssetTagDO::getUpdater, actor(auth)).set(AssetTagDO::getSourceUpdaterName, null)
                    .set(AssetTagDO::getSourceUpdateTime, null)
                    .setSql("deleted=1,revision=revision+1,update_time=CURRENT_TIMESTAMP")
                    .eq(AssetTagDO::getId, entry.getKey()).eq(AssetTagDO::getRevision, entry.getValue())
                    .eq(AssetTagDO::getStatus, 1)) != 1) {
                throw exception(ASSET_TAG_REVISION_CONFLICT);
            }
            record("TAG_DELETE", entry.getKey(), 1, null, null, auth);
        }
    }

    /**
     * 保存资产标签关联关系。
     */
    public void bind(AssetTagBindReqVO req, AssetMaintenanceAuthorization auth) {
        if (req.getCatalogRevision() == null || req.getCatalogRevision() < 0 || req.getTagIds() == null || req.getTagIds()
                .size() > 200
                || req.getTagIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw exception(ASSET_TAG_INPUT_INVALID);
        }
        var asset = assetMapper.selectForCatalogUpdate(req.getAssetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalog.assertCanMaintain(asset, auth);
        if (!Objects.equals(asset.getCatalogRevision(), req.getCatalogRevision())) {
            throw exception(ASSET_TAG_REVISION_CONFLICT);
        }
        var selected = new TreeSet<>(req.getTagIds());
        var previous = relMapper.selectListByAssetIds(List.of(asset.getId()));
        var all = new TreeSet<>(selected);
        previous.forEach(row -> all.add(row.getTagId()));
        for (Long id : all) {
            var tag = locked(id);
            if (selected.contains(id) && !Objects.equals(tag.getStatus(), 0)) {
                throw exception(ASSET_TAG_DISABLED);
            }
        }
        Set<Long> old = new HashSet<>();
        previous.forEach(row -> old.add(row.getTagId()));
        if (old.equals(selected)) {
            return;
        }
        for (var row : previous) {
            if (!selected.contains(row.getTagId())) {
                remove(row, auth);
            }
        }
        for (Long id : selected) {
            if (!old.contains(id)) {
                var row = AssetTagRelDO.builder().assetId(asset.getId()).tagId(id).revision(0).build();
                row.setCreator(actor(auth));
                row.setUpdater(actor(auth));
                relMapper.insert(row);
            }
        }
        assetMapper.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO.class)
                        .setSql("catalog_revision=COALESCE(catalog_revision,0)+1")
                        .eq(cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO::getId, asset.getId()));
        record("ASSET_TAG_BIND", asset.getId(), null, null, "原标签=" + new TreeSet<>(old) + ";现标签=" + selected,
                auth);
    }

    /**
     * 删除指定资产标签关联关系。
     */
    public void unbind(Long relationId, Integer revision, Long expectedAssetId, AssetMaintenanceAuthorization auth) {
        var asset = assetMapper.selectForCatalogUpdate(expectedAssetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalog.assertCanMaintain(asset, auth);
        var candidate = relMapper.selectById(relationId);
        if (candidate == null) {
            throw exception(ASSET_TAG_NOT_EXISTS);
        }
        locked(candidate.getTagId());
        var row = relMapper.selectForUpdate(relationId);
        if (row == null || !Objects.equals(row.getAssetId(), expectedAssetId) || !Objects.equals(row.getRevision(),
                revision)) {
            throw exception(ASSET_TAG_REVISION_CONFLICT);
        }
        remove(row, auth);
        assetMapper.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO.class)
                        .setSql("catalog_revision=COALESCE(catalog_revision,0)+1")
                        .eq(cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO::getId, asset.getId()));
        record("ASSET_TAG_UNBIND", asset.getId(), null, null, "关联=" + row.getId() + ";标签=" + row.getTagId(), auth);
    }

    /**
     * 移除指定的资产标签记录或关联。
     */
    private void remove(AssetTagRelDO row, AssetMaintenanceAuthorization auth) {
        if (relMapper.update(null, Wrappers.<AssetTagRelDO>lambdaUpdate()
                .set(AssetTagRelDO::getUpdater, actor(auth))
                .setSql("deleted=1,revision=revision+1,update_time=CURRENT_TIMESTAMP")
                .eq(AssetTagRelDO::getId, row.getId()).eq(AssetTagRelDO::getRevision, row.getRevision())) != 1) {
            throw exception(ASSET_TAG_REVISION_CONFLICT);
        }
    }

    /**
     * 读取并锁定资产标签记录，供事务内更新使用。
     */
    private AssetTagDO locked(Long id) {
        var tag = id == null ? null : tagMapper.selectForUpdate(id);
        if (tag == null) {
            throw exception(ASSET_TAG_NOT_EXISTS);
        }
        return tag;
    }

    /**
     * 校验资产标签的预期修订号。
     */
    private void revision(AssetTagDO tag, Integer expected) {
        if (expected == null || !Objects.equals(tag.getRevision(), expected)) {
            throw exception(ASSET_TAG_REVISION_CONFLICT);
        }
    }

    /**
     * 校验标签已经停用，阻止直接修改启用中的标签定义。
     */
    private void disabled(AssetTagDO tag) {
        if (!Objects.equals(tag.getStatus(), 1)) {
            throw exception(ASSET_TAG_ENABLED_IMMUTABLE);
        }
    }

    /**
     * 读取资产标签当前操作人的身份信息。
     */
    private String actor(AssetMaintenanceAuthorization auth) {
        return String.valueOf(auth.getUserId());
    }

    /**
     * 保存资产标签操作记录。
     */
    private void record(String type, Long id, Integer from, Integer to, String reason,
            AssetMaintenanceAuthorization auth) {
        audit.recordAs(type, "ASSET_TAG", id, from, to, reason, "OK", auth.getUserId(), auth.getDeptId());
    }
}
