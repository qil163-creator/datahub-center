package cn.iocoder.yudao.module.asset.service.categorytag;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagListReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorytag.CategoryTagDO;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.AssetTagMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.categorytag.CategoryTagMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Service
@Validated
public class CategoryTagServiceImpl implements CategoryTagService {

    @Resource
    private CategoryTagMapper categoryTagMapper;

    @Resource
    private AssetTagMapper assetTagMapper;

    /**
     * 创建标签类目。
     */
    @Override
    public Long createCategoryTag(CategoryTagSaveReqVO reqVO) {
        validateParent(null, reqVO.getParentId());
        validateNameUnique(null, reqVO.getParentId(), reqVO.getCategoryName());
        CategoryTagDO category = BeanUtils.toBean(reqVO, CategoryTagDO.class);
        categoryTagMapper.insert(category);
        return category.getId();
    }

    /**
     * 更新标签类目。
     */
    @Override
    public void updateCategoryTag(CategoryTagSaveReqVO reqVO) {
        validateExists(reqVO.getId());
        validateParent(reqVO.getId(), reqVO.getParentId());
        validateNameUnique(reqVO.getId(), reqVO.getParentId(), reqVO.getCategoryName());
        categoryTagMapper.updateById(BeanUtils.toBean(reqVO, CategoryTagDO.class));
    }

    /**
     * 删除标签类目。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategoryTag(Long id) {
        if (id == null || categoryTagMapper.selectForUpdate(id) == null) {
            throw exception(CATEGORY_TAG_NOT_EXISTS);
        }
        if (categoryTagMapper.selectCountByParentId(id) > 0) {
            throw exception(CATEGORY_TAG_EXITS_CHILDREN);
        }
        if (assetTagMapper.selectCountByCategoryId(id) > 0) {
            throw exception(CATEGORY_TAG_IN_USE);
        }
        categoryTagMapper.deleteById(id);
    }

    /**
     * 获得标签类目。
     */
    @Override
    public CategoryTagDO getCategoryTag(Long id) {
        return categoryTagMapper.selectById(id);
    }

    /**
     * 获得标签类目列表（前端组装树）。
     */
    @Override
    public List<CategoryTagRespVO> getCategoryTagList(CategoryTagListReqVO reqVO) {
        List<CategoryTagRespVO> list =
                BeanUtils.toBean(categoryTagMapper.selectList(reqVO), CategoryTagRespVO.class);
        Map<Long, Long> counts = assetTagMapper.selectCountGroupByCategoryId();
        list.forEach(item -> item.setTagCount(counts.getOrDefault(item.getId(), 0L)));
        return list;
    }

    /**
     * 校验类目存在且启用；为空直接返回
     */
    @Override
    public void validateCategoryEnabled(Long id) {
        if (id == null) {
            return;
        }
        CategoryTagDO category = categoryTagMapper.selectById(id);
        if (category == null || !Objects.equals(category.getStatus(), 0)) {
            throw exception(CATEGORY_TAG_NOT_EXISTS);
        }
    }

    /**
     * 类目及其全部后代编号（用于按类目筛选标签）
     */
    @Override
    public List<Long> getCategoryAndDescendantIds(Long id) {
        Map<Long, List<Long>> children = categoryTagMapper.selectList().stream()
                .collect(Collectors.groupingBy(CategoryTagDO::getParentId,
                        Collectors.mapping(CategoryTagDO::getId, Collectors.toList())));
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty()) {
            Long current = queue.removeFirst();
            if (result.add(current)) {
                queue.addAll(children.getOrDefault(current, List.of()));
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 读取并校验标签分类记录存在，不存在时抛出业务异常。
     */
    private void validateExists(Long id) {
        if (id == null || categoryTagMapper.selectById(id) == null) {
            throw exception(CATEGORY_TAG_NOT_EXISTS);
        }
    }

    /**
     * 校验标签分类的父级，不满足约束时拒绝当前操作。
     */
    private void validateParent(Long id, Long parentId) {
        if (parentId == null || CategoryTagDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        if (Objects.equals(id, parentId)) {
            throw exception(CATEGORY_TAG_PARENT_ERROR);
        }
        CategoryTagDO parent = categoryTagMapper.selectById(parentId);
        if (parent == null) {
            throw exception(CATEGORY_TAG_PARENT_NOT_EXITS);
        }
        if (id == null) {
            return;
        }
        // 沿父链向上，若遇到自己则形成环
        for (int i = 0; i < Short.MAX_VALUE && parent != null; i++) {
            Long ancestorId = parent.getParentId();
            if (Objects.equals(id, ancestorId)) {
                throw exception(CATEGORY_TAG_PARENT_IS_CHILD);
            }
            if (ancestorId == null || CategoryTagDO.PARENT_ID_ROOT.equals(ancestorId)) {
                break;
            }
            parent = categoryTagMapper.selectById(ancestorId);
        }
    }

    /**
     * 校验标签分类名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, Long parentId, String categoryName) {
        CategoryTagDO exists = categoryTagMapper.selectByParentIdAndCategoryName(parentId, categoryName);
        if (exists != null && !Objects.equals(exists.getId(), id)) {
            throw exception(CATEGORY_TAG_CATEGORY_NAME_DUPLICATE);
        }
    }

}
