package cn.iocoder.yudao.module.governance.service.categoryterm;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm.CategoryTermDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categoryterm.CategoryTermMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardterm.StandardTermMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

@Service
@Validated
public class CategoryTermServiceImpl implements CategoryTermService {

    private static final String CATEGORY_CODE_ROOT = "0";

    @Resource
    private CategoryTermMapper categoryTermMapper;

    @Resource
    private StandardTermMapper standardTermMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategoryTerm(CategoryTermSaveReqVO reqVO) {
        validateParent(null, reqVO.getParentId());
        validateNameUnique(null, reqVO.getParentId(), reqVO.getCategoryName());
        CategoryTermDO term = BeanUtils.toBean(reqVO, CategoryTermDO.class);
        term.setCategoryCode(CATEGORY_CODE_ROOT);
        categoryTermMapper.insert(term);
        CategoryTermDO codeUpdate = new CategoryTermDO();
        codeUpdate.setId(term.getId());
        codeUpdate.setCategoryCode(buildCategoryCode(reqVO.getParentId(), term.getId()));
        categoryTermMapper.updateById(codeUpdate);
        return term.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategoryTerm(CategoryTermSaveReqVO reqVO) {
        CategoryTermDO existing = categoryTermMapper.selectById(reqVO.getId());
        if (existing == null) throw exception(CATEGORY_TERM_NOT_EXISTS);
        validateParent(reqVO.getId(), reqVO.getParentId());
        validateNameUnique(reqVO.getId(), reqVO.getParentId(), reqVO.getCategoryName());
        CategoryTermDO update = BeanUtils.toBean(reqVO, CategoryTermDO.class);
        update.setCategoryCode(existing.getCategoryCode());
        if (!Objects.equals(existing.getParentId(), reqVO.getParentId())) {
            update.setCategoryCode(buildCategoryCode(reqVO.getParentId(), reqVO.getId()));
            categoryTermMapper.updateById(update);
            refreshChildrenCategoryCode(reqVO.getId());
        } else {
            categoryTermMapper.updateById(update);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategoryTerm(Long id) {
        if (id == null || categoryTermMapper.selectByIdForUpdate(id) == null) {
            throw exception(CATEGORY_TERM_NOT_EXISTS);
        }
        if (categoryTermMapper.selectCountByParentId(id) > 0) {
            throw exception(CATEGORY_TERM_EXITS_CHILDREN);
        }
        if (standardTermMapper.selectCountByTermCategoryId(id) > 0) {
            throw exception(CATEGORY_TERM_REFERENCED);
        }
        categoryTermMapper.deleteById(id);
    }

    @Override
    public CategoryTermDO getCategoryTerm(Long id) {
        CategoryTermDO result = categoryTermMapper.selectById(id);
        if (result == null) throw exception(CATEGORY_TERM_NOT_EXISTS);
        return result;
    }

    @Override
    public List<CategoryTermDO> getCategoryTermList(CategoryTermListReqVO reqVO) {
        return categoryTermMapper.selectList(reqVO);
    }

    private void validateParent(Long id, Long parentId) {
        if (parentId == null || CategoryTermDO.PARENT_ID_ROOT.equals(parentId)) return;
        if (Objects.equals(id, parentId)) throw exception(CATEGORY_TERM_PARENT_ERROR);
        CategoryTermDO parent = categoryTermMapper.selectById(parentId);
        if (parent == null) throw exception(CATEGORY_TERM_PARENT_NOT_EXITS);
        for (int i = 0; id != null && i < Short.MAX_VALUE; i++) {
            Long ancestorId = parent.getParentId();
            if (Objects.equals(id, ancestorId)) throw exception(CATEGORY_TERM_PARENT_IS_CHILD);
            if (ancestorId == null || CategoryTermDO.PARENT_ID_ROOT.equals(ancestorId)) break;
            parent = categoryTermMapper.selectById(ancestorId);
            if (parent == null) break;
        }
    }

    private void validateNameUnique(Long id, Long parentId, String name) {
        CategoryTermDO existing = categoryTermMapper.selectByParentIdAndCategoryName(parentId, name);
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(CATEGORY_TERM_CATEGORY_NAME_DUPLICATE);
        }
    }

    private String buildCategoryCode(Long parentId, Long id) {
        if (parentId == null || CategoryTermDO.PARENT_ID_ROOT.equals(parentId)) return CATEGORY_CODE_ROOT + "," + id;
        CategoryTermDO parent = categoryTermMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) throw exception(CATEGORY_TERM_PARENT_NOT_EXITS);
        return parent.getCategoryCode() + "," + id;
    }

    private void refreshChildrenCategoryCode(Long parentId) {
        CategoryTermDO parent = categoryTermMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) return;
        for (CategoryTermDO child : categoryTermMapper.selectListByParentId(parentId)) {
            CategoryTermDO update = new CategoryTermDO();
            update.setId(child.getId());
            update.setCategoryCode(parent.getCategoryCode() + "," + child.getId());
            categoryTermMapper.updateById(update);
            refreshChildrenCategoryCode(child.getId());
        }
    }

}
