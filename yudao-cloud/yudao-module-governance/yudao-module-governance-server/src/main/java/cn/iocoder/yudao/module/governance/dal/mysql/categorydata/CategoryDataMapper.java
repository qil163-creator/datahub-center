package cn.iocoder.yudao.module.governance.dal.mysql.categorydata;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydata.CategoryDataDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据分类 类目管理 Mapper
 *
 * @author Li
 */
@Mapper
public interface CategoryDataMapper extends BaseMapperX<CategoryDataDO> {

    /**
     * 查询分类目录列表。
     */
    default List<CategoryDataDO> selectList(CategoryDataListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<CategoryDataDO>()
                .likeIfPresent(CategoryDataDO::getCategoryName, reqVO.getCategoryName())
                .eqIfPresent(CategoryDataDO::getStatus, reqVO.getStatus())
                .orderByAsc(CategoryDataDO::getSort)
                .orderByAsc(CategoryDataDO::getId));
    }

    /**
     * 按父级编号与分类名称查询分类目录。
     */
    default CategoryDataDO selectByParentIdAndCategoryName(Long parentId, String categoryName) {
        return selectOne(CategoryDataDO::getParentId, parentId, CategoryDataDO::getCategoryName, categoryName);
    }

    /**
     * 按父级编号查询分类目录数量。
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(CategoryDataDO::getParentId, parentId);
    }

    /**
     * 按父级编号查询分类目录列表。
     */
    default List<CategoryDataDO> selectListByParentId(Long parentId) {
        return selectList(new LambdaQueryWrapperX<CategoryDataDO>()
                .eq(CategoryDataDO::getParentId, parentId)
                .orderByAsc(CategoryDataDO::getId));
    }

}
