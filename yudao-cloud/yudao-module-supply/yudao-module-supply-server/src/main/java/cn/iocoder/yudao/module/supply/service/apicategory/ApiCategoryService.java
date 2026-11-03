package cn.iocoder.yudao.module.supply.service.apicategory;

import cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo.ApiCategoryListReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo.ApiCategorySaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apicategory.ApiCategoryDO;

import java.util.List;

/**
 * 数据服务-服务分类 Service 接口
 *
 * @author Li
 */
public interface ApiCategoryService {

    /**
     * 创建服务分类
     *
     * @return 编号
     */
    Long createApiCategory(ApiCategorySaveReqVO createReqVO);

    /**
     * 更新服务分类
     */
    void updateApiCategory(ApiCategorySaveReqVO updateReqVO);

    /**
     * 更新服务分类状态
     */
    void updateApiCategoryStatus(Long id, Integer status);

    /**
     * 删除服务分类（存在子级不可删除）
     */
    void deleteApiCategory(Long id);

    /**
     * 获得服务分类
     */
    ApiCategoryDO getApiCategory(Long id);

    /**
     * 获得服务分类列表（树形数据平铺返回）
     */
    List<ApiCategoryDO> getApiCategoryList(ApiCategoryListReqVO listReqVO);

}
