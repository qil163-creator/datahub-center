package cn.iocoder.yudao.module.asset.service.assettag;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.AssetTagDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.AssetTagRelDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorytag.CategoryTagDO;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.AssetTagMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.AssetTagRelMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.categorytag.CategoryTagMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.categorytag.CategoryTagService;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.framework.mybatis.core.util.MyBatisUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Propagation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Service
@Validated
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AssetTagServiceImpl implements AssetTagService {

    @Resource
    private AssetTagMapper tagMapper;

    @Resource
    private AssetTagRelMapper tagRelMapper;

    @Resource
    private DataAssetMapper dataAssetMapper;

    @Resource
    private CategoryTagMapper categoryTagMapper;

    @Resource
    private CategoryTagService categoryTagService;

    @Resource
    private AssetUserNameResolver userNameResolver;

    @Resource
    @Lazy
    private AssetCatalogService catalog;

    @Resource
    private AssetTagWriter writer;

    @Resource
    private AdminUserApi userApi;

    /**
     * 创建资产标签。
     */
    @Override
    public Long createTag(AssetTagSaveReqVO reqVO) {
        reqVO.setId(null);
        return writer.save(reqVO, catalog.prepareMaintenanceAuthorization());
    }

    /**
     * 更新资产标签。
     */
    @Override
    public void updateTag(AssetTagSaveReqVO reqVO) {
        if (reqVO.getId() == null) {
            throw exception(ASSET_TAG_NOT_EXISTS);
        }
        writer.save(reqVO, catalog.prepareMaintenanceAuthorization());
    }

    /**
     * 启用/停用资产标签。
     */
    @Override
    public void updateTagStatus(Long id, Integer status, Integer revision) {
        writer.status(id, status, revision, catalog.prepareMaintenanceAuthorization());
    }

    /**
     * 删除资产标签。
     */
    @Override
    public void deleteTag(Long id, Integer revision) {
        var item = new AssetTagDeleteListReqVO.Item();
        item.setId(id);
        item.setRevision(revision);
        deleteTags(List.of(item));
    }

    /**
     * 删除标签。
     */
    @Override
    public void deleteTags(List<AssetTagDeleteListReqVO.Item> items) {
        writer.delete(items, catalog.prepareMaintenanceAuthorization());
    }

    /**
     * 分页读取指定标签已关联的资产。
     */
    @Override
    public PageResult<AssetTagLinkedRespVO> linkedAssets(AssetTagLinkedPageReqVO req) {
        validateTagExists(req.getId());
        var auth = catalog.prepareMaintenanceAuthorization();
        var page = tagRelMapper.linkedPage(MyBatisUtils.buildPage(req), req, auth.getUserId(), auth.getDeptId(),
                String.valueOf(auth.getUserId()), auth.isOperator() || auth.isSuperAdmin());
        var resolved = userNameResolver.resolveByTexts(
                page.getRecords().stream().map(AssetTagLinkedRespVO::getCreator).toList());
        page.getRecords().forEach(row -> {
            row.setCreatorName(row.getSourceCreatorName() != null
                    ? row.getSourceCreatorName() : userNameResolver.nameOf(resolved, row.getCreator()));
            if (row.getSourceCreateTime() != null) {
                row.setCreateTime(row.getSourceCreateTime());
            }
        });
        return new PageResult<>(page.getRecords(), page.getTotal());
    }

    /**
     * 维护范围内按修订号移除单条资产标签关联。
     */
    @Override
    public void unbind(Long relationId, Integer revision) {
        var auth = catalog.prepareMaintenanceAuthorization();
        var row = tagRelMapper.selectById(relationId);
        if (row == null) {
            throw exception(ASSET_TAG_NOT_EXISTS);
        }
        var asset = dataAssetMapper.selectById(row.getAssetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalog.assertCanMaintain(asset, auth);
        writer.unbind(relationId, revision, row.getAssetId(), auth);
    }

    /**
     * 获得资产标签详情。
     */
    @Override
    public AssetTagRespVO getTag(Long id) {
        AssetTagDO tag = validateTagExists(id);
        List<AssetTagRespVO> list = fillDisplayInfo(List.of(BeanUtils.toBean(tag, AssetTagRespVO.class)));
        return list.get(0);
    }

    /**
     * 获得资产标签分页。
     */
    @Override
    public PageResult<AssetTagRespVO> getTagPage(AssetTagPageReqVO reqVO) {
        Collection<Long> categoryIds = null;
        if (reqVO.getCategoryId() != null) {
            categoryIds = categoryTagService.getCategoryAndDescendantIds(reqVO.getCategoryId());
        }
        var auth = catalog.prepareMaintenanceAuthorization();
        Collection<String> creators = null;
        if (reqVO.getCreatorName() != null && !reqVO.getCreatorName().isBlank()) {
            creators = userApi.getUserListByNickname(reqVO.getCreatorName())
                    .getCheckedData().stream().map(user -> String.valueOf(user.getId())).toList();
        }
        var pagination = MyBatisUtils.<AssetTagDO>buildPage(reqVO);
        pagination.setOptimizeCountSql(false);
        var page = tagMapper.scopedPage(pagination, reqVO, categoryIds, creators, auth.getUserId(), auth.getDeptId(),
                String.valueOf(auth.getUserId()), auth.isOperator() || auth.isSuperAdmin());
        return new PageResult<>(fillDisplayInfo(BeanUtils.toBean(page.getRecords(), AssetTagRespVO.class)),
                page.getTotal());
    }

    /**
     * 获得资产标签列表（下拉选择用）。
     */
    @Override
    public List<AssetTagRespVO> getTagList() {
        return fillDisplayInfo(BeanUtils.toBean(tagMapper.selectOrderedList(), AssetTagRespVO.class));
    }

    /**
     * 全量覆盖资产标签。
     */
    @Override
    public void bindTags(AssetTagBindReqVO reqVO) {
        writer.bind(reqVO, catalog.prepareMaintenanceAuthorization());
    }

    /**
     * 按资产编号查询标签。
     */
    @Override
    public List<AssetTagRespVO> getTagsByAssetId(Long assetId) {
        var auth = catalog.prepareMaintenanceAuthorization();
        var asset = dataAssetMapper.selectById(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalog.assertCanMaintain(asset, auth);
        return getTagsByAssetIds(List.of(assetId)).getOrDefault(assetId, List.of());
    }

    /**
     * 按资产编号查询标签。
     */
    @Override
    public Map<Long, List<AssetTagRespVO>> getTagsByAssetIds(Collection<Long> assetIds) {
        if (CollUtil.isEmpty(assetIds)) {
            return Collections.emptyMap();
        }
        List<AssetTagRelDO> relations = tagRelMapper.selectListByAssetIds(assetIds);
        if (relations.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> tagIds = relations.stream().map(AssetTagRelDO::getTagId).collect(Collectors.toSet());
        Map<Long, AssetTagRespVO> tags = tagMapper.selectBatchIds(tagIds).stream()
                .map(tag -> BeanUtils.toBean(tag, AssetTagRespVO.class))
                .collect(Collectors.toMap(AssetTagRespVO::getId, Function.identity()));
        Map<Long, List<AssetTagRespVO>> result = new HashMap<>();
        relations.forEach(relation -> {
            AssetTagRespVO tag = tags.get(relation.getTagId());
            if (tag != null) {
                result.computeIfAbsent(relation.getAssetId(), key -> new ArrayList<>()).add(tag);
            }
        });
        return result;
    }

    /**
     * 补充资产标签的展示信息。
     */
    private List<AssetTagRespVO> fillDisplayInfo(List<AssetTagRespVO> tags) {
        if (CollUtil.isEmpty(tags)) {
            return tags;
        }
        Set<Long> categoryIds = tags.stream().map(AssetTagRespVO::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryTagMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(CategoryTagDO::getId, CategoryTagDO::getCategoryName));
        var auth = catalog.prepareMaintenanceAuthorization();
        Map<Long, Long> assetCounts = new HashMap<>();
        tagRelMapper.scopedCounts(tags.stream().map(AssetTagRespVO::getId).toList(), auth.getUserId(), auth.getDeptId(),
                String.valueOf(auth.getUserId()), auth.isOperator() || auth.isSuperAdmin()).forEach(row ->
                assetCounts.put(((Number) row.get("tag_id")).longValue(), ((Number) row.get("cnt")).longValue()));
        Set<String> userTexts = new HashSet<>();
        tags.forEach(tag -> {
            userTexts.add(tag.getCreator());
            userTexts.add(tag.getUpdater());
        });
        Map<String, String> userNames = userNameResolver.resolveByTexts(userTexts);
        tags.forEach(tag -> {
            tag.setCategoryName(categoryNames.get(tag.getCategoryId()));
            tag.setAssetCount(assetCounts.getOrDefault(tag.getId(), 0L));
            tag.setCreatorName(
                    tag.getSourceCreatorName() != null ? tag.getSourceCreatorName() : userNameResolver.nameOf(userNames,
                            tag.getCreator()));
            tag.setUpdaterName(
                    tag.getSourceUpdaterName() != null ? tag.getSourceUpdaterName() : userNameResolver.nameOf(userNames,
                            tag.getUpdater()));
            if (tag.getSourceCreateTime() != null) {
                tag.setCreateTime(tag.getSourceCreateTime());
            }
            if (tag.getSourceUpdaterName() != null && tag.getSourceUpdateTime() != null) {
                tag.setUpdateTime(tag.getSourceUpdateTime());
            }
        });
        return tags;
    }

    /**
     * 校验标签记录存在，不存在时抛出业务异常。
     */
    private AssetTagDO validateTagExists(Long id) {
        AssetTagDO tag = id == null ? null : tagMapper.selectById(id);
        if (tag == null) {
            throw exception(ASSET_TAG_NOT_EXISTS);
        }
        return tag;
    }

}
