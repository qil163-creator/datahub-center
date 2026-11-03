package cn.iocoder.yudao.module.governance.service.metadata;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagBindReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagBindingRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTagRelDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaChangeDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaChangeMapper;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTagRelMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据治理-元数据标签 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class MetaTagServiceImpl implements MetaTagService {

    @Resource
    private MetaTagMapper tagMapper;

    @Resource
    private MetaTagRelMapper tagRelMapper;

    @Resource
    private MetaDbMapper dbMapper;

    @Resource
    private MetaTableMapper tableMapper;

    @Resource
    private MetaColumnMapper columnMapper;

    @Resource
    private MetaChangeMapper changeMapper;

    /**
     * 创建元数据标签。
     */
    @Override
    public Long createTag(MetaTagSaveReqVO createReqVO) {
        validateNameUnique(null, createReqVO.getName());
        MetaTagDO tag = BeanUtils.toBean(createReqVO, MetaTagDO.class);
        tagMapper.insert(tag);
        return tag.getId();
    }

    /**
     * 更新元数据标签。
     */
    @Override
    public void updateTag(MetaTagSaveReqVO updateReqVO) {
        validateExists(updateReqVO.getId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getName());
        tagMapper.updateById(BeanUtils.toBean(updateReqVO, MetaTagDO.class));
    }

    /**
     * 删除元数据标签。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTag(Long id) {
        if (tagMapper.selectByIdForUpdate(id) == null) {
            throw exception(META_TAG_NOT_EXISTS);
        }
        if (tagRelMapper.selectCountByTagId(id) > 0) {
            throw exception(META_TAG_REFERENCED);
        }
        tagMapper.deleteById(id);
    }

    /**
     * 获得元数据标签。
     */
    @Override
    public MetaTagRespVO getTag(Long id) {
        MetaTagDO tag = validateExists(id);
        MetaTagRespVO respVO = BeanUtils.toBean(tag, MetaTagRespVO.class);
        respVO.setRefCount(tagRelMapper.selectCountByTagId(id));
        return respVO;
    }

    /**
     * 获得元数据标签分页。
     */
    @Override
    public PageResult<MetaTagRespVO> getTagPage(MetaTagPageReqVO pageReqVO) {
        PageResult<MetaTagDO> pageResult = tagMapper.selectPage(pageReqVO);
        PageResult<MetaTagRespVO> result = BeanUtils.toBean(pageResult, MetaTagRespVO.class);
        for (MetaTagRespVO item : result.getList()) {
            item.setRefCount(tagRelMapper.selectCountByTagId(item.getId()));
        }
        return result;
    }

    /**
     * 获得元数据标签精简列表。
     */
    @Override
    public List<MetaTagRespVO> getTagSimpleList() {
        List<MetaTagDO> list = tagMapper.selectList();
        return BeanUtils.toBean(list, MetaTagRespVO.class);
    }

    /**
     * 全量覆盖对象已绑标签
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindTags(MetaTagBindReqVO reqVO) {
        BindingOwner owner = lockOwner(reqVO.getObjectType(), reqVO.getObjectId());
        validateRevision(owner, reqVO.getRevision());
        if (reqVO.getTagIds() == null || reqVO.getTagIds().stream().anyMatch(Objects::isNull)) {
            throw exception(META_TAG_BINDING_CONCURRENT_CHANGED);
        }
        replaceTags(reqVO.getObjectType(), reqVO.getObjectId(), owner,
                new LinkedHashSet<>(reqVO.getTagIds()));
    }

    /**
     * 替换标签。
     */
    private void replaceTags(Integer objectType, Long objectId, BindingOwner owner, Set<Long> tagIds) {
        for (Long tagId : tagIds.stream().sorted().toList()) {
            if (tagMapper.selectByIdForUpdate(tagId) == null) {
                throw exception(META_TAG_NOT_EXISTS);
            }
        }
        List<MetaTagRelDO> existings = tagRelMapper.selectListByObject(objectType, objectId);
        Set<Long> existingIds = new LinkedHashSet<>(convertList(existings, MetaTagRelDO::getTagId));
        if (existingIds.equals(tagIds)) {
            return;
        }
        advanceRevision(objectType, objectId, owner.revision());
        for (MetaTagRelDO existing : existings) {
            if (!tagIds.contains(existing.getTagId())) {
                tagRelMapper.deleteById(existing.getId());
            }
        }
        List<MetaTagRelDO> inserts = new ArrayList<>();
        for (Long tagId : tagIds) {
            if (existingIds.contains(tagId)) {
                continue;
            }
            inserts.add(MetaTagRelDO.builder()
                    .tagId(tagId)
                    .objectType(objectType)
                    .objectId(objectId)
                    .build());
        }
        if (CollUtil.isNotEmpty(inserts)) {
            tagRelMapper.insertBatch(inserts);
        }
        changeMapper.insert(MetaChangeDO.builder()
                .changeSource(MetaChangeDO.SOURCE_MANUAL).objectType(objectType).objectId(objectId)
                .tableId(owner.tableId()).dbId(owner.dbId()).changeType(MetaChangeDO.TYPE_MODIFY)
                .property("TAGS").beforeValue(JsonUtils.toJsonString(existingIds.stream().sorted().toList()))
                .afterValue(JsonUtils.toJsonString(tagIds.stream().sorted().toList()))
                .operator(String.valueOf(SecurityFrameworkUtils.getLoginUserId())).build());
    }

    /**
     * 解绑单个标签。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindTag(Long tagId, Integer objectType, Long objectId, Integer revision) {
        BindingOwner owner = lockOwner(objectType, objectId);
        validateRevision(owner, revision);
        Set<Long> remaining = new LinkedHashSet<>(convertList(
                tagRelMapper.selectListByObject(objectType, objectId), MetaTagRelDO::getTagId));
        remaining.remove(tagId);
        replaceTags(objectType, objectId, owner, remaining);
    }

    /**
     * 获得对象标签集合及修订号。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MetaTagBindingRespVO getBindingSnapshot(Integer objectType, Long objectId) {
        BindingOwner owner = lockOwner(objectType, objectId);
        MetaTagBindingRespVO result = new MetaTagBindingRespVO();
        result.setObjectType(objectType);
        result.setObjectId(objectId);
        result.setRevision(owner.revision());
        result.setTags(getTagsByObject(objectType, objectId));
        result.setTagIds(convertList(result.getTags(), MetaTagRespVO::getId));
        return result;
    }

    /** 元数据管理端在平台内可见，操作仍受控制器细粒度权限和 Mapper 数据范围约束。 */
    private BindingOwner lockOwner(Integer objectType, Long objectId) {
        if (Objects.equals(objectType, MetaChangeDO.OBJECT_TYPE_DB)) {
            MetaDbDO db = dbMapper.selectByIdForUpdate(objectId);
            validateObject(db == null ? null : db.getObjectStatus());
            return new BindingOwner(db.getTagRevision(), null, db.getId());
        }
        if (Objects.equals(objectType, MetaChangeDO.OBJECT_TYPE_TABLE)) {
            MetaTableDO table = tableMapper.selectByIdForUpdate(objectId);
            validateObject(table == null ? null : table.getObjectStatus());
            return new BindingOwner(table.getTagRevision(), table.getId(), table.getDbId());
        }
        if (Objects.equals(objectType, MetaChangeDO.OBJECT_TYPE_COLUMN)) {
            MetaColumnDO column = columnMapper.selectByIdForUpdate(objectId);
            validateObject(column == null ? null : column.getObjectStatus());
            return new BindingOwner(column.getTagRevision(), column.getTableId(), column.getDbId());
        }
        throw exception(META_OBJECT_NOT_EDITABLE);
    }

    /**
     * 校验元数据标签的对象，不满足约束时拒绝当前操作。
     */
    private void validateObject(Integer status) {
        if (status == null || Objects.equals(status, MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(META_OBJECT_NOT_EDITABLE);
        }
    }

    /**
     * 校验元数据标签的修订号，不满足约束时拒绝当前操作。
     */
    private void validateRevision(BindingOwner owner, Integer expected) {
        if (expected == null || !Objects.equals(owner.revision(), expected)) {
            throw exception(META_TAG_BINDING_CONCURRENT_CHANGED);
        }
    }

    /**
     * 推进修订号。
     */
    private void advanceRevision(Integer objectType, Long objectId, Integer revision) {
        int updated;
        if (Objects.equals(objectType, MetaChangeDO.OBJECT_TYPE_DB)) {
            updated = dbMapper.update(null, Wrappers.lambdaUpdate(MetaDbDO.class)
                    .eq(MetaDbDO::getId, objectId).eq(MetaDbDO::getTagRevision, revision)
                    .set(MetaDbDO::getTagRevision, revision + 1));
        } else if (Objects.equals(objectType, MetaChangeDO.OBJECT_TYPE_TABLE)) {
            updated = tableMapper.update(null, Wrappers.lambdaUpdate(MetaTableDO.class)
                    .eq(MetaTableDO::getId, objectId).eq(MetaTableDO::getTagRevision, revision)
                    .set(MetaTableDO::getTagRevision, revision + 1));
        } else {
            updated = columnMapper.update(null, Wrappers.lambdaUpdate(MetaColumnDO.class)
                    .eq(MetaColumnDO::getId, objectId).eq(MetaColumnDO::getTagRevision, revision)
                    .set(MetaColumnDO::getTagRevision, revision + 1));
        }
        if (updated != 1) {
            throw exception(META_TAG_BINDING_CONCURRENT_CHANGED);
        }
    }

    private record BindingOwner(Integer revision, Long tableId, Long dbId) {

    }

    /**
     * 获得对象已绑标签。
     */
    @Override
    public List<MetaTagRespVO> getTagsByObject(Integer objectType, Long objectId) {
        List<MetaTagRelDO> rels = tagRelMapper.selectListByObject(objectType, objectId);
        if (CollUtil.isEmpty(rels)) {
            return List.of();
        }
        List<Long> tagIds = convertList(rels, MetaTagRelDO::getTagId);
        return BeanUtils.toBean(tagMapper.selectBatchIds(tagIds), MetaTagRespVO.class);
    }

    /**
     * 读取并校验元数据标签记录存在，不存在时抛出业务异常。
     */
    private MetaTagDO validateExists(Long id) {
        MetaTagDO tag = tagMapper.selectById(id);
        if (tag == null) {
            throw exception(META_TAG_NOT_EXISTS);
        }
        return tag;
    }

    /**
     * 校验元数据标签名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, String name) {
        MetaTagDO existing = tagMapper.selectByName(name);
        if (existing != null && (id == null || !existing.getId().equals(id))) {
            throw exception(META_TAG_NAME_DUPLICATE);
        }
    }

}
