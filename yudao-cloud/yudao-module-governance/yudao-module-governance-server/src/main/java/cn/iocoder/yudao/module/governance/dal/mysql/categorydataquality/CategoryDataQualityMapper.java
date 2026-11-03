package cn.iocoder.yudao.module.governance.dal.mysql.categorydataquality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo.CategoryDataQualityListReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydataquality.CategoryDataQualityDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据质量类目管理 Mapper
 *
 * @author Li
 */
@Mapper
public interface CategoryDataQualityMapper extends BaseMapperX<CategoryDataQualityDO> {

    /**
     * 查询质量分类目录列表。
     */
    default List<CategoryDataQualityDO> selectList(CategoryDataQualityListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<CategoryDataQualityDO>()
                .likeIfPresent(CategoryDataQualityDO::getCategoryName, reqVO.getCategoryName())
                .eqIfPresent(CategoryDataQualityDO::getSort, reqVO.getSort())
                .eqIfPresent(CategoryDataQualityDO::getStatus, reqVO.getStatus())
                .orderByAsc(CategoryDataQualityDO::getSort)
                .orderByAsc(CategoryDataQualityDO::getId));
    }

    /**
     * 按父级编号与分类名称查询质量分类目录。
     */
    default CategoryDataQualityDO selectByParentIdAndCategoryName(Long parentId, String categoryName) {
        return selectOne(CategoryDataQualityDO::getParentId, parentId, CategoryDataQualityDO::getCategoryName,
                categoryName);
    }

    /**
     * 按父级编号查询质量分类目录数量。
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(CategoryDataQualityDO::getParentId, parentId);
    }

    /**
     * 按父级编号查询质量分类目录列表。
     */
    default List<CategoryDataQualityDO> selectListByParentId(Long parentId) {
        return selectList(new LambdaQueryWrapperX<CategoryDataQualityDO>()
                .eq(CategoryDataQualityDO::getParentId, parentId)
                .orderByAsc(CategoryDataQualityDO::getId));
    }

}
