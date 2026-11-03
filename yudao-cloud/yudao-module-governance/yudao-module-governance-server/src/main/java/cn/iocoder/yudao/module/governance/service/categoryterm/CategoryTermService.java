package cn.iocoder.yudao.module.governance.service.categoryterm;

import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm.CategoryTermDO;
import jakarta.validation.Valid;

import java.util.List;

public interface CategoryTermService {

    Long createCategoryTerm(@Valid CategoryTermSaveReqVO reqVO);

    void updateCategoryTerm(@Valid CategoryTermSaveReqVO reqVO);

    void deleteCategoryTerm(Long id);

    CategoryTermDO getCategoryTerm(Long id);

    List<CategoryTermDO> getCategoryTermList(CategoryTermListReqVO reqVO);

}
