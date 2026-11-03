package cn.iocoder.yudao.module.asset.service.categorydataasset;

import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorydataasset.CategoryDataAssetDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据资产类目管理 Service 接口
 *
 * @author Li
 */
public interface CategoryDataAssetService {

    /**
     * 创建数据资产类目管理
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createCategoryDataAsset(@Valid CategoryDataAssetSaveReqVO createReqVO);

    /**
     * 更新数据资产类目管理
     *
     * @param updateReqVO 更新信息
     */
    void updateCategoryDataAsset(@Valid CategoryDataAssetSaveReqVO updateReqVO);

    /**
     * 删除数据资产类目管理
     *
     * @param id 编号
     */
    void deleteCategoryDataAsset(Long id);


    /**
     * 获得数据资产类目管理
     *
     * @param id 编号
     * @return 数据资产类目管理
     */
    CategoryDataAssetDO getCategoryDataAsset(Long id);

    /**
     * 获得数据资产类目管理列表
     *
     * @param listReqVO 查询条件
     * @return 数据资产类目管理列表
     */
    List<CategoryDataAssetDO> getCategoryDataAssetList(CategoryDataAssetListReqVO listReqVO);

}
