package cn.iocoder.yudao.module.governance.dal.mysql.categoryterm;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm.CategoryTermDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryTermMapper extends BaseMapperX<CategoryTermDO> {

    @Select("SELECT * FROM dh_governance_category_term WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    CategoryTermDO selectByIdForUpdate(@Param("id") Long id);

    default List<CategoryTermDO> selectList(CategoryTermListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<CategoryTermDO>()
                .likeIfPresent(CategoryTermDO::getCategoryName, reqVO.getCategoryName())
                .eqIfPresent(CategoryTermDO::getStatus, reqVO.getStatus())
                .orderByAsc(CategoryTermDO::getSort)
                .orderByAsc(CategoryTermDO::getId));
    }

    default CategoryTermDO selectByParentIdAndCategoryName(Long parentId, String categoryName) {
        return selectOne(CategoryTermDO::getParentId, parentId,
                CategoryTermDO::getCategoryName, categoryName);
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(CategoryTermDO::getParentId, parentId);
    }

    default List<CategoryTermDO> selectListByParentId(Long parentId) {
        return selectList(new LambdaQueryWrapperX<CategoryTermDO>()
                .eq(CategoryTermDO::getParentId, parentId)
                .orderByAsc(CategoryTermDO::getId));
    }

}
