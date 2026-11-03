package cn.iocoder.yudao.module.governance.service.categorydata;

import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydata.CategoryDataDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据分类 类目管理 Service 接口
 *
 * @author Li
 */
public interface CategoryDataService {

    /**
     * 创建数据分类 类目管理
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createCategoryData(@Valid CategoryDataSaveReqVO createReqVO);

    /**
     * 更新数据分类 类目管理
     *
     * @param updateReqVO 更新信息
     */
    void updateCategoryData(@Valid CategoryDataSaveReqVO updateReqVO);

    /**
     * 删除数据分类 类目管理
     *
     * @param id 编号
     */
    void deleteCategoryData(Long id, Long revision);


    /**
     * 获得数据分类 类目管理
     *
     * @param id 编号
     * @return 数据分类 类目管理
     */
    CategoryDataDO getCategoryData(Long id);

    /**
     * 获得数据分类 类目管理列表
     *
     * @param listReqVO 查询条件
     * @return 数据分类 类目管理列表
     */
    List<CategoryDataDO> getCategoryDataList(CategoryDataListReqVO listReqVO);

}
