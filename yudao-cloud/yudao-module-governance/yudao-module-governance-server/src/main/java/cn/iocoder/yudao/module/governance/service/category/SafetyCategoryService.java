package cn.iocoder.yudao.module.governance.service.category;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategorySaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryBatchReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryBatchLevelReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryStatusReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据安全--数据分类信息 Service 接口
 *
 * @author Li
 */
public interface SafetyCategoryService {

    /**
     * 创建数据安全--数据分类信息
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createCategory(@Valid SafetyCategorySaveReqVO createReqVO);

    /**
     * 更新数据安全--数据分类信息
     *
     * @param updateReqVO 更新信息
     */
    void updateCategory(@Valid SafetyCategorySaveReqVO updateReqVO);

    /**
     * 删除数据安全--数据分类信息
     *
     * @param id 编号
     */
    void deleteCategory(Long id, Long revision);

    /**
     * 批量删除数据安全--数据分类信息
     *
     * @param request 批量删除请求，包含分类编号与预期修订号
     */
    void deleteCategoryList(@Valid SafetyCategoryBatchReqVO request);

    /**
     * 修改分类启停状态。
     */
    void changeStatus(@Valid SafetyCategoryStatusReqVO request);

    /**
     * 批量指定数据分级。
     */
    void assignLevel(@Valid SafetyCategoryBatchLevelReqVO request);

    /**
     * 获得数据安全--数据分类信息
     *
     * @param id 编号
     * @return 数据安全--数据分类信息
     */
    SafetyCategoryDO getCategory(Long id);

    /**
     * 获得数据安全--数据分类信息分页
     *
     * @param pageReqVO 分页查询
     * @return 数据安全--数据分类信息分页
     */
    PageResult<SafetyCategoryDO> getCategoryPage(SafetyCategoryPageReqVO pageReqVO);

}
