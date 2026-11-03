package cn.iocoder.yudao.module.governance.service.categorydatameta;

import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 标准数据元类目管理 Service 接口
 *
 * @author Li
 */
public interface CategoryDatametaService {

    /**
     * 创建标准数据元类目管理
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createCategoryDatameta(@Valid CategoryDatametaSaveReqVO createReqVO);

    /**
     * 更新标准数据元类目管理
     *
     * @param updateReqVO 更新信息
     */
    void updateCategoryDatameta(@Valid CategoryDatametaSaveReqVO updateReqVO);

    /**
     * 删除标准数据元类目管理
     *
     * @param id 编号
     */
    void deleteCategoryDatameta(Long id);


    /**
     * 获得标准数据元类目管理
     *
     * @param id 编号
     * @return 标准数据元类目管理
     */
    CategoryDatametaDO getCategoryDatameta(Long id);

    /**
     * 获得标准数据元类目管理列表
     *
     * @param listReqVO 查询条件
     * @return 标准数据元类目管理列表
     */
    List<CategoryDatametaDO> getCategoryDatametaList(CategoryDatametaListReqVO listReqVO);

}
