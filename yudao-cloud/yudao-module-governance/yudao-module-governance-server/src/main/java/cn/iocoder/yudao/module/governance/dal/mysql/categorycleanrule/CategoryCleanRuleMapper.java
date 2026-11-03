package cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleListReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 清洗规则 类目管理 Mapper
 *
 * @author Li
 */
@Mapper
public interface CategoryCleanRuleMapper extends BaseMapperX<CategoryCleanRuleDO> {

    /**
     * 查询清洗规则分类列表。
     */
    default List<CategoryCleanRuleDO> selectList(CategoryCleanRuleListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<CategoryCleanRuleDO>()
                .likeIfPresent(CategoryCleanRuleDO::getCategoryName, reqVO.getCategoryName())
                .eqIfPresent(CategoryCleanRuleDO::getStatus, reqVO.getStatus())
                .orderByAsc(CategoryCleanRuleDO::getSort)
                .orderByAsc(CategoryCleanRuleDO::getId));
    }

    /**
     * 按父级编号与分类名称查询清洗规则分类。
     */
    default CategoryCleanRuleDO selectByParentIdAndCategoryName(Long parentId, String categoryName) {
        return selectOne(CategoryCleanRuleDO::getParentId, parentId, CategoryCleanRuleDO::getCategoryName,
                categoryName);
    }

    /**
     * 按父级编号查询清洗规则分类数量。
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(CategoryCleanRuleDO::getParentId, parentId);
    }

    /**
     * 按父级编号查询清洗规则分类列表。
     */
    default List<CategoryCleanRuleDO> selectListByParentId(Long parentId) {
        return selectList(new LambdaQueryWrapperX<CategoryCleanRuleDO>()
                .eq(CategoryCleanRuleDO::getParentId, parentId)
                .orderByAsc(CategoryCleanRuleDO::getId));
    }

}
