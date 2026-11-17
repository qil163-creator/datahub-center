package cn.iocoder.yudao.module.governance.service.categorycleanrule;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 清洗规则 类目管理 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class CategoryCleanRuleServiceImpl implements CategoryCleanRuleService {

    private static final String CATEGORY_CODE_SEPARATOR = ",";

    private static final String CATEGORY_CODE_ROOT = "0";

    @Resource
    private CategoryCleanRuleMapper categoryCleanRuleMapper;
    @Resource
    private CleanRuleMapper cleanRuleMapper;

    /**
     * 创建清洗规则 类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategoryCleanRule(CategoryCleanRuleSaveReqVO createReqVO) {
        // 校验父级ID的有效性
        validateParentCategoryCleanRule(null, createReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryCleanRuleCategoryNameUnique(null, createReqVO.getParentId(), createReqVO.getCategoryName());

        // 先插入拿到主键，再按 0,id / 父编码,id 回写层级编码
        CategoryCleanRuleDO categoryCleanRule = BeanUtils.toBean(createReqVO, CategoryCleanRuleDO.class);
        categoryCleanRule.setCategoryCode(CATEGORY_CODE_ROOT);
        categoryCleanRuleMapper.insert(categoryCleanRule);
        CategoryCleanRuleDO codeUpdate = new CategoryCleanRuleDO();
        codeUpdate.setId(categoryCleanRule.getId());
        codeUpdate.setCategoryCode(buildCategoryCode(createReqVO.getParentId(), categoryCleanRule.getId()));
        categoryCleanRuleMapper.updateById(codeUpdate);

        // 返回
        return categoryCleanRule.getId();
    }

    /**
     * 更新清洗规则 类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategoryCleanRule(CategoryCleanRuleSaveReqVO updateReqVO) {
        // 校验存在
        CategoryCleanRuleDO existing = categoryCleanRuleMapper.selectById(updateReqVO.getId());
        if (existing == null) {
            throw exception(CATEGORY_CLEAN_RULE_NOT_EXISTS);
        }
        // 校验父级ID的有效性
        validateParentCategoryCleanRule(updateReqVO.getId(), updateReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryCleanRuleCategoryNameUnique(updateReqVO.getId(), updateReqVO.getParentId(),
                updateReqVO.getCategoryName());

        // 更新，层级编码由后端维护，不接受前端传入
        CategoryCleanRuleDO updateObj = BeanUtils.toBean(updateReqVO, CategoryCleanRuleDO.class);
        updateObj.setCategoryCode(existing.getCategoryCode());
        if (!Objects.equals(existing.getParentId(), updateReqVO.getParentId())) {
            updateObj.setCategoryCode(buildCategoryCode(updateReqVO.getParentId(), updateReqVO.getId()));
            categoryCleanRuleMapper.updateById(updateObj);
            refreshChildrenCategoryCode(updateReqVO.getId());
            return;
        }
        categoryCleanRuleMapper.updateById(updateObj);
    }

    /**
     * 删除清洗规则 类目管理
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategoryCleanRule(Long id) {
        if (categoryCleanRuleMapper.selectByIdForUpdate(id) == null) {
            throw exception(CATEGORY_CLEAN_RULE_NOT_EXISTS);
        }
        // 校验是否有子清洗规则 类目管理
        if (categoryCleanRuleMapper.selectCountByParentId(id) > 0) {
            throw exception(CATEGORY_CLEAN_RULE_EXITS_CHILDREN);
        }
        if (cleanRuleMapper.selectCount(CleanRuleDO::getCategoryId, id) > 0) {
            throw invalidParamException("存在引用该类目的清洗规则，无法删除");
        }
        categoryCleanRuleMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategoryCleanRuleList(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw invalidParamException("请选择1至200个有效类目");
        }
        var selected = new TreeSet<>(ids);
        var categories = categoryCleanRuleMapper.selectList(new LambdaQueryWrapperX<CategoryCleanRuleDO>()
                .in(CategoryCleanRuleDO::getId, selected).orderByAsc(CategoryCleanRuleDO::getId).last("FOR UPDATE"));
        if (categories.size() != selected.size()) {
            throw exception(CATEGORY_CLEAN_RULE_NOT_EXISTS);
        }
        if (categories.stream().anyMatch(category -> category.getStatus() == null || category.getStatus() != 1)) {
            throw invalidParamException("请先禁用所选类目后再删除");
        }
        if (categoryCleanRuleMapper.selectCount(new LambdaQueryWrapperX<CategoryCleanRuleDO>()
                .in(CategoryCleanRuleDO::getParentId, selected).notIn(CategoryCleanRuleDO::getId, selected)) > 0) {
            throw exception(CATEGORY_CLEAN_RULE_EXITS_CHILDREN);
        }
        if (cleanRuleMapper.selectCount(new LambdaQueryWrapperX<CleanRuleDO>()
                .in(CleanRuleDO::getCategoryId, selected)) > 0) {
            throw invalidParamException("存在引用所选类目的清洗规则，无法删除");
        }
        categoryCleanRuleMapper.deleteByIds(selected);
    }



    /**
     * 校验清洗规则分类的父级分类清洗规则，不满足约束时拒绝当前操作。
     */
    private void validateParentCategoryCleanRule(Long id, Long parentId) {
        if (parentId == null || CategoryCleanRuleDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父清洗规则 类目管理
        if (Objects.equals(id, parentId)) {
            throw exception(CATEGORY_CLEAN_RULE_PARENT_ERROR);
        }
        // 2. 父清洗规则 类目管理不存在
        CategoryCleanRuleDO parentCategoryCleanRule = categoryCleanRuleMapper.selectByIdForUpdate(parentId);
        if (parentCategoryCleanRule == null) {
            throw exception(CATEGORY_CLEAN_RULE_PARENT_NOT_EXITS);
        }
        // 3. 递归校验父清洗规则 类目管理，如果父清洗规则 类目管理是自己的子清洗规则 类目管理，则报错，避免形成环路
        if (id == null) { // id 为空，说明新增，不需要考虑环路
            return;
        }
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            // 3.1 校验环路
            parentId = parentCategoryCleanRule.getParentId();
            if (Objects.equals(id, parentId)) {
                throw exception(CATEGORY_CLEAN_RULE_PARENT_IS_CHILD);
            }
            // 3.2 继续递归下一级父清洗规则 类目管理
            if (parentId == null || CategoryCleanRuleDO.PARENT_ID_ROOT.equals(parentId)) {
                break;
            }
            parentCategoryCleanRule = categoryCleanRuleMapper.selectById(parentId);
            if (parentCategoryCleanRule == null) {
                break;
            }
        }
    }

    /**
     * 校验清洗规则分类的清洗规则分类分类名称在指定范围内唯一。
     */
    private void validateCategoryCleanRuleCategoryNameUnique(Long id, Long parentId, String categoryName) {
        CategoryCleanRuleDO categoryCleanRule = categoryCleanRuleMapper.selectByParentIdAndCategoryName(parentId,
                categoryName);
        if (categoryCleanRule == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的清洗规则 类目管理
        if (id == null) {
            throw exception(CATEGORY_CLEAN_RULE_CATEGORY_NAME_DUPLICATE);
        }
        if (!Objects.equals(categoryCleanRule.getId(), id)) {
            throw exception(CATEGORY_CLEAN_RULE_CATEGORY_NAME_DUPLICATE);
        }
    }

    /**
     * 生成层级编码：顶级为 0,当前主键，子级为 父级编码,当前主键
     */
    private String buildCategoryCode(Long parentId, Long id) {
        if (parentId == null || CategoryCleanRuleDO.PARENT_ID_ROOT.equals(parentId)) {
            return CATEGORY_CODE_ROOT + CATEGORY_CODE_SEPARATOR + id;
        }
        CategoryCleanRuleDO parent = categoryCleanRuleMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) {
            throw exception(CATEGORY_CLEAN_RULE_PARENT_NOT_EXITS);
        }
        return parent.getCategoryCode() + CATEGORY_CODE_SEPARATOR + id;
    }

    /**
     * 父级变更后，按新的父级编码刷新子节点层级编码
     */
    private void refreshChildrenCategoryCode(Long parentId) {
        CategoryCleanRuleDO parent = categoryCleanRuleMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) {
            return;
        }
        List<CategoryCleanRuleDO> children = categoryCleanRuleMapper.selectListByParentId(parentId);
        for (CategoryCleanRuleDO child : children) {
            CategoryCleanRuleDO updateChild = new CategoryCleanRuleDO();
            updateChild.setId(child.getId());
            updateChild.setCategoryCode(parent.getCategoryCode() + CATEGORY_CODE_SEPARATOR + child.getId());
            categoryCleanRuleMapper.updateById(updateChild);
            refreshChildrenCategoryCode(child.getId());
        }
    }

    /**
     * 获得清洗规则 类目管理
     *
     *  @param id 编号
     *  @return 清洗规则 类目管理
     */
    @Override
    public CategoryCleanRuleDO getCategoryCleanRule(Long id) {
        return categoryCleanRuleMapper.selectById(id);
    }

    /**
     * 获得清洗规则 类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 清洗规则 类目管理列表
     */
    @Override
    public List<CategoryCleanRuleDO> getCategoryCleanRuleList(CategoryCleanRuleListReqVO listReqVO) {
        return categoryCleanRuleMapper.selectList(listReqVO);
    }

}
