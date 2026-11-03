package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategoryRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 采集计划分类管理；分类与计划沿用平台内共享的数据范围。 */
@Service
public class FillPlanCategoryServiceImpl implements FillPlanCategoryService {

    @Resource
    private FillPlanCategoryMapper categoryMapper;

    @Resource
    private FillPlanMapper planMapper;

    /**
     * 创建采集计划分类。
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategory(FillPlanCategorySaveReqVO reqVO) {
        lockTree();
        validateParent(null, reqVO.getParentId());
        String name = reqVO.getName().trim();
        validateNameUnique(null, reqVO.getParentId(), name);
        FillPlanCategoryDO category = BeanUtils.toBean(reqVO, FillPlanCategoryDO.class);
        category.setId(null);
        category.setName(name);
        category.setSort(reqVO.getSort() == null ? 0 : reqVO.getSort());
        categoryMapper.insert(category);
        return category.getId();
    }

    /**
     * 更新采集计划分类。
     *
     * @param reqVO 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(FillPlanCategorySaveReqVO reqVO) {
        lockTree();
        validateCategoryExists(reqVO.getId());
        validateParent(reqVO.getId(), reqVO.getParentId());
        String name = reqVO.getName().trim();
        validateNameUnique(reqVO.getId(), reqVO.getParentId(), name);
        FillPlanCategoryDO update = BeanUtils.toBean(reqVO, FillPlanCategoryDO.class);
        update.setName(name);
        update.setSort(reqVO.getSort() == null ? 0 : reqVO.getSort());
        categoryMapper.updateById(update);
    }

    /**
     * 删除采集计划分类，有子分类或计划引用时拒绝。
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long id) {
        lockTree();
        validateCategoryExists(id);
        if (categoryMapper.selectCountByParentId(id) > 0) {
            throw exception(FILL_PLAN_CATEGORY_HAS_CHILDREN);
        }
        if (planMapper.selectCountByCategoryId(id) > 0) {
            throw exception(FILL_PLAN_CATEGORY_HAS_PLANS);
        }
        categoryMapper.deleteById(id);
    }

    /**
     * 获得采集计划分类。
     *
     * @param id 记录编号
     * @return 填报计划分类响应数据
     */
    @Override
    public FillPlanCategoryRespVO getCategory(Long id) {
        return BeanUtils.toBean(validateCategoryExists(id), FillPlanCategoryRespVO.class);
    }

    /**
     * 获得采集计划分类列表，前端构建目录树。
     *
     * @return 分类列表
     */
    @Override
    public List<FillPlanCategoryRespVO> getCategoryList() {
        return BeanUtils.toBean(categoryMapper.selectListOrdered(), FillPlanCategoryRespVO.class);
    }

    /**
     * 读取分类名称映射。
     *
     * @param categoryIds 分类编号集合
     * @return 处理后的键值映射
     */
    @Override
    public Map<Long, String> getCategoryNameMap(Collection<Long> categoryIds) {
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectByIds(categoryIds).stream()
                .collect(Collectors.toMap(FillPlanCategoryDO::getId, FillPlanCategoryDO::getName));
    }

    /**
     * 在计划写入事务结束前校验分类引用；调用方须先完成组织/人员远程查询。
     *
     * @param categoryId 分类编号
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateCategoryReference(Long categoryId) {
        if (categoryId != null) {
            lockTree();
            validateCategoryExists(categoryId);
        }
    }

    /**
     * 获取指定分类及所有子孙分类，分类不存在时拒绝查询。
     *
     * @param categoryId 分类编号
     * @return 指定分类及所有子孙分类，分类不存在时拒绝查询结果集合
     */
    @Override
    public List<Long> getCategoryAndDescendantIds(Long categoryId) {
        List<FillPlanCategoryDO> categories = categoryMapper.selectListOrdered();
        if (categories.stream().noneMatch(category -> Objects.equals(category.getId(), categoryId))) {
            throw exception(FILL_PLAN_CATEGORY_NOT_EXISTS);
        }
        Map<Long, List<FillPlanCategoryDO>> children = categories.stream()
                .collect(Collectors.groupingBy(FillPlanCategoryDO::getParentId));
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.add(categoryId);
        while (!pending.isEmpty()) {
            Long current = pending.removeFirst();
            if (result.add(current)) {
                children.getOrDefault(current, List.of()).forEach(child -> pending.addLast(child.getId()));
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 锁定树。
     */
    private void lockTree() {
        categoryMapper.lockCategoryTree();
    }

    /**
     * 校验分类存在性。
     *
     * @param id 记录编号
     * @return 填报计划分类
     */
    private FillPlanCategoryDO validateCategoryExists(Long id) {
        FillPlanCategoryDO category = id == null ? null : categoryMapper.selectById(id);
        if (category == null) {
            throw exception(FILL_PLAN_CATEGORY_NOT_EXISTS);
        }
        return category;
    }

    /**
     * 校验父级。
     *
     * @param id 记录编号
     * @param parentId 父级编号
     */
    private void validateParent(Long id, Long parentId) {
        Set<Long> visited = new HashSet<>();
        Long current = parentId;
        while (!FillPlanCategoryDO.PARENT_ID_ROOT.equals(current)) {
            if (Objects.equals(current, id) || !visited.add(current)) {
                throw exception(FILL_PLAN_CATEGORY_PARENT_INVALID);
            }
            FillPlanCategoryDO parent = current == null ? null : categoryMapper.selectById(current);
            if (parent == null) {
                throw exception(FILL_PLAN_CATEGORY_PARENT_NOT_EXISTS);
            }
            current = parent.getParentId();
        }
    }

    /**
     * 校验名称唯一性。
     *
     * @param id 记录编号
     * @param parentId 父级编号
     * @param name 名称
     */
    private void validateNameUnique(Long id, Long parentId, String name) {
        FillPlanCategoryDO exists = categoryMapper.selectByParentIdAndName(parentId, name);
        if (exists != null && !Objects.equals(exists.getId(), id)) {
            throw exception(FILL_PLAN_CATEGORY_NAME_DUPLICATE);
        }
    }

}
