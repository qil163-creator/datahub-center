package cn.iocoder.yudao.module.asset.dal.mysql.categorytag;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagListReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorytag.CategoryTagDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CategoryTagMapper extends BaseMapperX<CategoryTagDO> {

    /**
     * 查询用于打标。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_asset_category_tag WHERE id=#{id} AND deleted=0 FOR SHARE")
    CategoryTagDO selectForTagging(@Param("id") Long id);

    /**
     * 按指定条件读取并锁定标签分类记录，供事务内一致性校验或修改使用。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_asset_category_tag WHERE id=#{id} AND deleted=0 FOR UPDATE")
    CategoryTagDO selectForUpdate(@Param("id") Long id);

    /**
     * 查询标签分类列表。
     */
    default List<CategoryTagDO> selectList(CategoryTagListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<CategoryTagDO>()
                .likeIfPresent(CategoryTagDO::getCategoryName, reqVO.getCategoryName())
                .eqIfPresent(CategoryTagDO::getStatus, reqVO.getStatus())
                .orderByAsc(CategoryTagDO::getSort)
                .orderByAsc(CategoryTagDO::getId));
    }

    /**
     * 按父级编号与分类名称查询标签分类。
     */
    default CategoryTagDO selectByParentIdAndCategoryName(Long parentId, String categoryName) {
        return selectOne(CategoryTagDO::getParentId, parentId,
                CategoryTagDO::getCategoryName, categoryName);
    }

    /**
     * 按父级编号查询标签分类数量。
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(CategoryTagDO::getParentId, parentId);
    }

}
