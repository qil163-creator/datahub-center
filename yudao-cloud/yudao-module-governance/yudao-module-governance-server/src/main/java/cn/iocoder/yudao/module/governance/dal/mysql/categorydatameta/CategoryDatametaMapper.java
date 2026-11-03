package cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaListReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 标准数据元类目管理 Mapper
 *
 * @author Li
 */
@Mapper
public interface CategoryDatametaMapper extends BaseMapperX<CategoryDatametaDO> {

    /**
     * 按指定条件读取并锁定元数据分类目录记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_category_datameta WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    CategoryDatametaDO selectByIdForUpdate(@Param("id") Long id);

    /**
     * 查询元数据分类目录列表。
     */
    default List<CategoryDatametaDO> selectList(CategoryDatametaListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<CategoryDatametaDO>()
                .likeIfPresent(CategoryDatametaDO::getCategoryName, reqVO.getCategoryName())
                .eqIfPresent(CategoryDatametaDO::getStatus, reqVO.getStatus())
                .orderByAsc(CategoryDatametaDO::getSort)
                .orderByAsc(CategoryDatametaDO::getId));
    }

    /**
     * 按父级编号与分类名称查询元数据分类目录。
     */
    default CategoryDatametaDO selectByParentIdAndCategoryName(Long parentId, String categoryName) {
        return selectOne(CategoryDatametaDO::getParentId, parentId, CategoryDatametaDO::getCategoryName, categoryName);
    }

    /**
     * 按父级编号查询元数据分类目录数量。
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(CategoryDatametaDO::getParentId, parentId);
    }

    /**
     * 按父级编号查询元数据分类目录列表。
     */
    default List<CategoryDatametaDO> selectListByParentId(Long parentId) {
        return selectList(new LambdaQueryWrapperX<CategoryDatametaDO>()
                .eq(CategoryDatametaDO::getParentId, parentId)
                .orderByAsc(CategoryDatametaDO::getId));
    }

    /**
     * 按分类名称查询元数据分类目录列表。
     */
    default List<CategoryDatametaDO> selectListByCategoryName(String categoryName) {
        return selectList(CategoryDatametaDO::getCategoryName, categoryName);
    }

}
