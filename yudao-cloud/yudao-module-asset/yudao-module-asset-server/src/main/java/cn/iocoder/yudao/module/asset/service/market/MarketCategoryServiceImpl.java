package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCategoryDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketListingDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCategoryMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketListingMapper;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Service
@Validated
public class MarketCategoryServiceImpl implements MarketCategoryService {

    @Resource
    private MarketCategoryMapper categoryMapper;

    @Resource
    private MarketListingMapper listingMapper;

    @Resource
    private AssetUserNameResolver userNameResolver;

    /**
     * 新增集市类目。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(MarketCategorySaveReqVO req) {
        categoryMapper.lockTree();
        validateParent(null, req.getParentId());
        validateName(null, req.getParentId(), req.getName().trim());
        var category = BeanUtils.toBean(req, MarketCategoryDO.class);
        category.setId(null);
        category.setName(req.getName().trim());
        categoryMapper.insert(category);
        return category.getId();
    }

    /**
     * 修改集市类目。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(MarketCategorySaveReqVO req) {
        categoryMapper.lockTree();
        requireLocked(req.getId());
        validateParent(req.getId(), req.getParentId());
        validateName(req.getId(), req.getParentId(), req.getName().trim());
        var category = BeanUtils.toBean(req, MarketCategoryDO.class);
        category.setName(req.getName().trim());
        categoryMapper.updateById(category);
    }

    /**
     * 更新市场分类的状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Short status) {
        categoryMapper.lockTree();
        requireLocked(id);
        if (status == null || status < 0 || status > 1) {
            throw exception(MARKET_LISTING_INVALID);
        }
        var update = new MarketCategoryDO();
        update.setId(id);
        update.setStatus(status);
        categoryMapper.updateById(update);
    }

    /**
     * 删除无引用的集市类目。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw exception(MARKET_CATEGORY_NOT_EXISTS);
        }
        categoryMapper.lockTree();
        var selected = new TreeSet<>(ids);
        selected.forEach(this::requireLocked);
        if (categoryMapper.selectCount(new LambdaQueryWrapperX<MarketCategoryDO>()
                .in(MarketCategoryDO::getParentId, selected).notIn(MarketCategoryDO::getId, selected)) > 0) {
            throw exception(MARKET_CATEGORY_HAS_CHILDREN);
        }
        if (listingMapper.selectCount(new LambdaQueryWrapperX<MarketListingDO>()
                .in(MarketListingDO::getMarketCategoryId, selected)) > 0) {
            throw exception(MARKET_CATEGORY_IN_USE);
        }
        categoryMapper.deleteByIds(selected);
    }

    /**
     * 读取集市类目。
     */
    @Override
    public MarketCategoryRespVO get(Long id) {
        var category = categoryMapper.selectById(id);
        if (category == null) {
            throw exception(MARKET_CATEGORY_NOT_EXISTS);
        }
        return describe(List.of(category)).getFirst();
    }

    /**
     * 查询平台集市类目树节点。
     */
    @Override
    public List<MarketCategoryRespVO> list(MarketCategoryListReqVO req) {
        return describe(categoryMapper.selectCategoryList(req));
    }

    /**
     * 读取启用列表。
     */
    @Override
    public List<MarketCategoryRespVO> getEnabledList() {
        var categories = categoryMapper.selectCategoryList(new MarketCategoryListReqVO());
        var byId = categories.stream().collect(java.util.stream.Collectors.toMap(MarketCategoryDO::getId,
                java.util.function.Function.identity()));
        var enabled = new HashMap<Long, Boolean>();
        for (var category : categories) {
            Long id = category.getId();
            var chain = new HashSet<Long>();
            boolean available = true;
            while (id != null && id != 0) {
                if (enabled.containsKey(id)) {
                    available = enabled.get(id);
                    break;
                }
                var current = byId.get(id);
                if (current == null || !Objects.equals(current.getStatus(), (short) 0) || !chain.add(id)) {
                    available = false;
                    break;
                }
                id = current.getParentId();
            }
            for (Long current : chain) {
                enabled.put(current, available);
            }
        }
        // 消费导航无需创建人显示信息，不为类目选项额外调用用户服务。
        return categories.stream().filter(item -> Boolean.TRUE.equals(enabled.get(item.getId())))
                .map(item -> BeanUtils.toBean(item, MarketCategoryRespVO.class)).toList();
    }

    /**
     * 组装市场分类的描述信息。
     */
    private List<MarketCategoryRespVO> describe(List<MarketCategoryDO> categories) {
        var names = userNameResolver.resolveByTexts(categories.stream().map(MarketCategoryDO::getCreator).toList());
        return categories.stream().map(category -> {
            var result = BeanUtils.toBean(category, MarketCategoryRespVO.class);
            result.setCreatorName(userNameResolver.nameOf(names, category.getCreator()));
            return result;
        }).toList();
    }

    /**
     * 校验市场分类的已锁定，不满足约束时拒绝当前操作。
     */
    private MarketCategoryDO requireLocked(Long id) {
        var value = id == null ? null : categoryMapper.selectForUpdate(id);
        if (value == null) {
            throw exception(MARKET_CATEGORY_NOT_EXISTS);
        }
        return value;
    }

    /**
     * 校验市场分类的父级，不满足约束时拒绝当前操作。
     */
    private void validateParent(Long id, Long parentId) {
        var seen = new HashSet<Long>();
        while (parentId != null && parentId != 0) {
            if (Objects.equals(id, parentId) || !seen.add(parentId)) {
                throw exception(MARKET_CATEGORY_PARENT_INVALID);
            }
            var parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw exception(MARKET_CATEGORY_PARENT_INVALID);
            }
            parentId = parent.getParentId();
        }
    }

    /**
     * 校验市场分类的名称，不满足约束时拒绝当前操作。
     */
    private void validateName(Long id, Long parentId, String name) {
        var duplicate = categoryMapper.selectOne(new LambdaQueryWrapperX<MarketCategoryDO>()
                .eq(MarketCategoryDO::getParentId, parentId).eq(MarketCategoryDO::getName, name));
        if (duplicate != null && !Objects.equals(id, duplicate.getId())) {
            throw exception(MARKET_CATEGORY_NAME_DUPLICATE);
        }
    }

    /**
     * 校验市场分类的启用用于发布，不满足约束时拒绝当前操作。
     */
    @Override
    public void requireEnabledForPublish(Long id) {
        Long selectedId = id;
        var seen = new TreeSet<Long>();
        while (id != null && id != 0) {
            if (!seen.add(id)) {
                throw exception(MARKET_CATEGORY_PARENT_INVALID);
            }
            var category = categoryMapper.selectById(id);
            if (category == null) {
                throw exception(MARKET_CATEGORY_NOT_EXISTS);
            }
            id = category.getParentId();
        }
        if (seen.isEmpty()) {
            throw exception(MARKET_CATEGORY_NOT_EXISTS);
        }
        // 与批量删除使用相同的编号顺序，避免子类目和父类目的交叉行锁。
        var locked = new HashMap<Long, MarketCategoryDO>();
        for (Long current : seen) {
            var category = categoryMapper.selectForPublish(current);
            if (category == null) {
                throw exception(MARKET_CATEGORY_NOT_EXISTS);
            }
            locked.put(current, category);
        }
        seen.clear();
        while (selectedId != null && selectedId != 0) {
            var category = locked.get(selectedId);
            if (category == null || !seen.add(selectedId)) {
                throw exception(MARKET_CATEGORY_PARENT_INVALID);
            }
            if (!Objects.equals(category.getStatus(), (short) 0)) {
                throw exception(MARKET_CATEGORY_DISABLED);
            }
            selectedId = category.getParentId();
        }
    }

    /**
     * 查询指定市场分类及其全部后代编号。
     */
    @Override
    public Set<Long> descendants(Long id) {
        if (categoryMapper.selectById(id) == null) {
            throw exception(MARKET_CATEGORY_NOT_EXISTS);
        }
        var children = categoryMapper.selectCategoryList(new MarketCategoryListReqVO()).stream()
                .collect(java.util.stream.Collectors.groupingBy(MarketCategoryDO::getParentId));
        var result = new LinkedHashSet<Long>();
        var queue = new ArrayDeque<Long>();
        queue.add(id);
        while (!queue.isEmpty()) {
            Long current = queue.removeFirst();
            if (result.add(current)) {
                children.getOrDefault(current, List.of()).forEach(child -> queue.add(child.getId()));
            }
        }
        return result;
    }
}
