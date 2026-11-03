package cn.iocoder.yudao.module.governance.service.categorydatameta;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo.CategoryDatametaSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import cn.iocoder.yudao.module.governance.service.categorydatameta.CategoryDatametaService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 标准数据元类目管理 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class CategoryDatametaServiceImpl implements CategoryDatametaService {

    private static final String CATEGORY_CODE_SEPARATOR = ",";

    private static final String CATEGORY_CODE_ROOT = "0";

    @Resource
    private CategoryDatametaMapper categoryDatametaMapper;

    @Resource
    private StandardDocMapper standardDocMapper;

    /**
     * 创建标准数据元类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategoryDatameta(CategoryDatametaSaveReqVO createReqVO) {
        // 校验父级ID的有效性
        validateParentCategoryDatameta(null, createReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryDatametaCategoryNameUnique(null, createReqVO.getParentId(), createReqVO.getCategoryName());

        // 先插入拿到主键，再按 0,id / 父编码,id 回写层级编码
        CategoryDatametaDO categoryDatameta = BeanUtils.toBean(createReqVO, CategoryDatametaDO.class);
        categoryDatameta.setCategoryCode(CATEGORY_CODE_ROOT);
        categoryDatametaMapper.insert(categoryDatameta);
        CategoryDatametaDO codeUpdate = new CategoryDatametaDO();
        codeUpdate.setId(categoryDatameta.getId());
        codeUpdate.setCategoryCode(buildCategoryCode(createReqVO.getParentId(), categoryDatameta.getId()));
        categoryDatametaMapper.updateById(codeUpdate);

        // 返回
        return categoryDatameta.getId();
    }

    /**
     * 更新标准数据元类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategoryDatameta(CategoryDatametaSaveReqVO updateReqVO) {
        // 校验存在
        CategoryDatametaDO existing = categoryDatametaMapper.selectById(updateReqVO.getId());
        if (existing == null) {
            throw exception(CATEGORY_DATAMETA_NOT_EXISTS);
        }
        // 校验父级ID的有效性
        validateParentCategoryDatameta(updateReqVO.getId(), updateReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryDatametaCategoryNameUnique(updateReqVO.getId(), updateReqVO.getParentId(),
                updateReqVO.getCategoryName());

        // 更新，层级编码由后端维护，不接受前端传入
        CategoryDatametaDO updateObj = BeanUtils.toBean(updateReqVO, CategoryDatametaDO.class);
        updateObj.setCategoryCode(existing.getCategoryCode());
        if (!Objects.equals(existing.getParentId(), updateReqVO.getParentId())) {
            updateObj.setCategoryCode(buildCategoryCode(updateReqVO.getParentId(), updateReqVO.getId()));
            categoryDatametaMapper.updateById(updateObj);
            refreshChildrenCategoryCode(updateReqVO.getId());
            return;
        }
        categoryDatametaMapper.updateById(updateObj);
    }

    /**
     * 删除标准数据元类目管理
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategoryDatameta(Long id) {
        // 与标准文档登记使用同一行锁，避免删除后仍写入该类目。
        if (categoryDatametaMapper.selectByIdForUpdate(id) == null) {
            throw exception(CATEGORY_DATAMETA_NOT_EXISTS);
        }
        // 校验是否有子标准数据元类目管理
        if (categoryDatametaMapper.selectCountByParentId(id) > 0) {
            throw exception(CATEGORY_DATAMETA_EXITS_CHILDREN);
        }
        if (standardDocMapper.selectCount(StandardDocDO::getCategoryId, id) > 0) {
            throw exception(STANDARD_CATALOG_INVALID, "该类目已被标准文件引用，不能删除");
        }
        // 删除
        categoryDatametaMapper.deleteById(id);
    }


    /**
     * 校验元数据分类目录记录存在，不存在时抛出业务异常。
     */
    private void validateCategoryDatametaExists(Long id) {
        if (categoryDatametaMapper.selectById(id) == null) {
            throw exception(CATEGORY_DATAMETA_NOT_EXISTS);
        }
    }

    /**
     * 校验元数据分类目录的父级分类元数据，不满足约束时拒绝当前操作。
     */
    private void validateParentCategoryDatameta(Long id, Long parentId) {
        if (parentId == null || CategoryDatametaDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父标准数据元类目管理
        if (Objects.equals(id, parentId)) {
            throw exception(CATEGORY_DATAMETA_PARENT_ERROR);
        }
        // 2. 父标准数据元类目管理不存在
        CategoryDatametaDO parentCategoryDatameta = categoryDatametaMapper.selectById(parentId);
        if (parentCategoryDatameta == null) {
            throw exception(CATEGORY_DATAMETA_PARENT_NOT_EXITS);
        }
        // 3. 递归校验父标准数据元类目管理，如果父标准数据元类目管理是自己的子标准数据元类目管理，则报错，避免形成环路
        if (id == null) { // id 为空，说明新增，不需要考虑环路
            return;
        }
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            // 3.1 校验环路
            parentId = parentCategoryDatameta.getParentId();
            if (Objects.equals(id, parentId)) {
                throw exception(CATEGORY_DATAMETA_PARENT_IS_CHILD);
            }
            // 3.2 继续递归下一级父标准数据元类目管理
            if (parentId == null || CategoryDatametaDO.PARENT_ID_ROOT.equals(parentId)) {
                break;
            }
            parentCategoryDatameta = categoryDatametaMapper.selectById(parentId);
            if (parentCategoryDatameta == null) {
                break;
            }
        }
    }

    /**
     * 校验元数据分类目录的元数据分类目录分类名称在指定范围内唯一。
     */
    private void validateCategoryDatametaCategoryNameUnique(Long id, Long parentId, String categoryName) {
        CategoryDatametaDO categoryDatameta = categoryDatametaMapper.selectByParentIdAndCategoryName(parentId,
                categoryName);
        if (categoryDatameta == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的标准数据元类目管理
        if (id == null) {
            throw exception(CATEGORY_DATAMETA_CATEGORY_NAME_DUPLICATE);
        }
        if (!Objects.equals(categoryDatameta.getId(), id)) {
            throw exception(CATEGORY_DATAMETA_CATEGORY_NAME_DUPLICATE);
        }
    }

    /**
     * 生成层级编码：顶级为 0,当前主键，子级为 父级编码,当前主键
     */
    private String buildCategoryCode(Long parentId, Long id) {
        if (parentId == null || CategoryDatametaDO.PARENT_ID_ROOT.equals(parentId)) {
            return CATEGORY_CODE_ROOT + CATEGORY_CODE_SEPARATOR + id;
        }
        CategoryDatametaDO parent = categoryDatametaMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) {
            throw exception(CATEGORY_DATAMETA_PARENT_NOT_EXITS);
        }
        return parent.getCategoryCode() + CATEGORY_CODE_SEPARATOR + id;
    }

    /**
     * 父级变更后，按新的父级编码刷新子节点层级编码
     */
    private void refreshChildrenCategoryCode(Long parentId) {
        CategoryDatametaDO parent = categoryDatametaMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) {
            return;
        }
        List<CategoryDatametaDO> children = categoryDatametaMapper.selectListByParentId(parentId);
        for (CategoryDatametaDO child : children) {
            CategoryDatametaDO updateChild = new CategoryDatametaDO();
            updateChild.setId(child.getId());
            updateChild.setCategoryCode(parent.getCategoryCode() + CATEGORY_CODE_SEPARATOR + child.getId());
            categoryDatametaMapper.updateById(updateChild);
            refreshChildrenCategoryCode(child.getId());
        }
    }

    /**
     * 获得标准数据元类目管理
     *
     *  @param id 编号
     *  @return 标准数据元类目管理
     */
    @Override
    public CategoryDatametaDO getCategoryDatameta(Long id) {
        return categoryDatametaMapper.selectById(id);
    }

    /**
     * 获得标准数据元类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 标准数据元类目管理列表
     */
    @Override
    public List<CategoryDatametaDO> getCategoryDatametaList(CategoryDatametaListReqVO listReqVO) {
        return categoryDatametaMapper.selectList(listReqVO);
    }

}
