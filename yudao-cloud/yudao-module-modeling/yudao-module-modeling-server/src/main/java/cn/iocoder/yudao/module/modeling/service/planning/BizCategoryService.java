package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory.BizCategoryListReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory.BizCategoryRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory.BizCategorySaveReqVO;

import java.util.List;

/**
 * 业务分类 Service 接口
 *
 * @author Li
 */
public interface BizCategoryService {

    /**
     * 创建业务分类
     *
     * @return 编号
     */
    Long createBizCategory(BizCategorySaveReqVO createReqVO);

    /**
     * 更新业务分类
     */
    void updateBizCategory(BizCategorySaveReqVO updateReqVO);

    /**
     * 更新业务分类状态
     */
    void updateBizCategoryStatus(Long id, Integer status);

    /**
     * 删除业务分类（存在子级时不可删除）
     */
    void deleteBizCategory(Long id);

    /**
     * 获得业务分类
     */
    BizCategoryRespVO getBizCategory(Long id);

    /**
     * 获得业务分类列表（树形数据平铺返回）
     */
    List<BizCategoryRespVO> getBizCategoryList(BizCategoryListReqVO listReqVO);

}
