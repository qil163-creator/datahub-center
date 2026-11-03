package cn.iocoder.yudao.module.governance.service.categorydataquality;

import cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo.CategoryDataQualityListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo.CategoryDataQualitySaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydataquality.CategoryDataQualityDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据质量类目管理 Service 接口
 *
 * @author Li
 */
public interface CategoryDataQualityService {

    /**
     * 创建数据质量类目管理
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createCategoryDataQuality(@Valid CategoryDataQualitySaveReqVO createReqVO);

    /**
     * 更新数据质量类目管理
     *
     * @param updateReqVO 更新信息
     */
    void updateCategoryDataQuality(@Valid CategoryDataQualitySaveReqVO updateReqVO);

    /**
     * 删除数据质量类目管理
     *
     * @param id 编号
     */
    void deleteCategoryDataQuality(Long id);


    /**
     * 获得数据质量类目管理
     *
     * @param id 编号
     * @return 数据质量类目管理
     */
    CategoryDataQualityDO getCategoryDataQuality(Long id);

    /**
     * 获得数据质量类目管理列表
     *
     * @param listReqVO 查询条件
     * @return 数据质量类目管理列表
     */
    List<CategoryDataQualityDO> getCategoryDataQualityList(CategoryDataQualityListReqVO listReqVO);

}
