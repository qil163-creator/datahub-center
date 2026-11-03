package cn.iocoder.yudao.module.asset.dal.mysql.categorydataasset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorydataasset.CategoryDataAssetDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据资产类目管理 Mapper
 *
 * @author Li
 */
@Mapper
public interface CategoryDataAssetMapper extends BaseMapperX<CategoryDataAssetDO> {

    /**
     * 查询资产分类目录列表。
     */
    default List<CategoryDataAssetDO> selectList(CategoryDataAssetListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<CategoryDataAssetDO>()
                .likeIfPresent(CategoryDataAssetDO::getCategoryName, reqVO.getCategoryName())
                .eqIfPresent(CategoryDataAssetDO::getStatus, reqVO.getStatus())
                .orderByAsc(CategoryDataAssetDO::getSort)
                .orderByAsc(CategoryDataAssetDO::getId));
    }

    /**
     * 按父级编号与分类名称查询资产分类目录。
     */
    default CategoryDataAssetDO selectByParentIdAndCategoryName(Long parentId, String categoryName) {
        return selectOne(CategoryDataAssetDO::getParentId, parentId, CategoryDataAssetDO::getCategoryName,
                categoryName);
    }

    /**
     * 按父级编号查询资产分类目录数量。
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(CategoryDataAssetDO::getParentId, parentId);
    }

}
