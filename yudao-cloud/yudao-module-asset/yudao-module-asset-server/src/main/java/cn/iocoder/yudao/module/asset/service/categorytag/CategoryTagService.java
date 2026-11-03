package cn.iocoder.yudao.module.asset.service.categorytag;

import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagListReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo.CategoryTagSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorytag.CategoryTagDO;

import java.util.List;

public interface CategoryTagService {

    /**
     * 创建标签类目。
     */
    Long createCategoryTag(CategoryTagSaveReqVO reqVO);

    /**
     * 更新标签类目。
     */
    void updateCategoryTag(CategoryTagSaveReqVO reqVO);

    /**
     * 删除标签类目。
     */
    void deleteCategoryTag(Long id);

    /**
     * 获得标签类目。
     */
    CategoryTagDO getCategoryTag(Long id);

    /**
     * 获得标签类目列表（前端组装树）。
     */
    List<CategoryTagRespVO> getCategoryTagList(CategoryTagListReqVO reqVO);

    /**
     * 校验类目存在且启用；为空直接返回
     */
    void validateCategoryEnabled(Long id);

    /**
     * 类目及其全部后代编号（用于按类目筛选标签）
     */
    List<Long> getCategoryAndDescendantIds(Long id);

}
