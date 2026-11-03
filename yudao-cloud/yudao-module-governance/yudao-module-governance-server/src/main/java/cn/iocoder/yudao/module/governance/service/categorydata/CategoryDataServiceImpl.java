package cn.iocoder.yudao.module.governance.service.categorydata;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydata.CategoryDataDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.service.category.SafetyCategoryServiceImpl;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;
import java.time.LocalDateTime;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据分类 类目管理 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class CategoryDataServiceImpl implements CategoryDataService {

    private static final String CATEGORY_CODE_SEPARATOR = ",";

    private static final String CATEGORY_CODE_ROOT = "0";

    @Resource
    private CategoryDataMapper categoryDataMapper;

    @Resource
    private SafetyCategoryMapper safetyCategoryMapper;

    /**
     * 创建数据分类 类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategoryData(CategoryDataSaveReqVO createReqVO) {
        safetyCategoryMapper.lockDefinitions();
        createReqVO.setCategoryName(createReqVO.getCategoryName().trim());
        // 校验父级ID的有效性
        validateParentCategoryData(null, createReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryDataCategoryNameUnique(null, createReqVO.getParentId(), createReqVO.getCategoryName());

        // 先插入拿到主键，再按 0,id / 父编码,id 回写层级编码
        CategoryDataDO categoryData = BeanUtils.toBean(createReqVO, CategoryDataDO.class);
        categoryData.setId(null);
        categoryData.setCategoryName(createReqVO.getCategoryName().trim());
        categoryData.setSort(createReqVO.getSort() == null ? 0 : createReqVO.getSort());
        categoryData.setStatus(createReqVO.getStatus() == null ? (short) 0 : createReqVO.getStatus());
        categoryData.setRevision(1L);
        categoryData.setCategoryCode(CATEGORY_CODE_ROOT);
        categoryDataMapper.insert(categoryData);
        CategoryDataDO codeUpdate = new CategoryDataDO();
        codeUpdate.setId(categoryData.getId());
        codeUpdate.setCategoryCode(buildCategoryCode(createReqVO.getParentId(), categoryData.getId()));
        categoryDataMapper.updateById(codeUpdate);

        // 返回
        return categoryData.getId();
    }

    /**
     * 更新数据分类 类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategoryData(CategoryDataSaveReqVO updateReqVO) {
        safetyCategoryMapper.lockDefinitions();
        updateReqVO.setCategoryName(updateReqVO.getCategoryName().trim());
        // 校验存在
        CategoryDataDO existing = categoryDataMapper.selectById(updateReqVO.getId());
        if (existing == null) {
            throw exception(CATEGORY_DATA_NOT_EXISTS);
        }
        SafetyCategoryServiceImpl.checkRevision(updateReqVO.getRevision(), existing.getRevision());
        // 校验父级ID的有效性
        validateParentCategoryData(updateReqVO.getId(), updateReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryDataCategoryNameUnique(updateReqVO.getId(), updateReqVO.getParentId(),
                updateReqVO.getCategoryName());

        // 更新，层级编码由后端维护，不接受前端传入
        CategoryDataDO updateObj = BeanUtils.toBean(updateReqVO, CategoryDataDO.class);
        updateObj.setCategoryName(updateReqVO.getCategoryName().trim());
        updateObj.setRevision(existing.getRevision() + 1);
        updateObj.setCategoryCode(existing.getCategoryCode());
        if (!Objects.equals(existing.getParentId(),
                updateReqVO.getParentId()) && existing.getSourceRecordId() == null) {
            updateObj.setCategoryCode(buildCategoryCode(updateReqVO.getParentId(), updateReqVO.getId()));
            updateDefinition(updateObj, existing.getRevision());
            refreshChildrenCategoryCode(updateReqVO.getId());
            return;
        }
        updateDefinition(updateObj, existing.getRevision());
    }

    /**
     * 删除数据分类 类目管理
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategoryData(Long id, Long revision) {
        safetyCategoryMapper.lockDefinitions();
        // 校验存在
        validateCategoryDataExists(id);
        SafetyCategoryServiceImpl.checkRevision(revision, categoryDataMapper.selectById(id).getRevision());
        // 校验是否有子数据分类 类目管理
        if (categoryDataMapper.selectCountByParentId(id) > 0) {
            throw exception(CATEGORY_DATA_EXITS_CHILDREN);
        }
        if (safetyCategoryMapper.countByDirectory(id) > 0) {
            throw exception(SAFETY_DIRECTORY_REFERENCED);
        }
        // 删除
        if (categoryDataMapper.update(null, new LambdaUpdateWrapper<CategoryDataDO>()
                .eq(CategoryDataDO::getId, id).eq(CategoryDataDO::getRevision, revision)
                .setSql("deleted=1").set(CategoryDataDO::getRevision, revision + 1)
                .set(CategoryDataDO::getUpdater, String.valueOf(SecurityFrameworkUtils.getLoginUserId()))
                .set(CategoryDataDO::getUpdateTime, LocalDateTime.now())) != 1) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
    }


    /**
     * 校验分类目录记录存在，不存在时抛出业务异常。
     */
    private void validateCategoryDataExists(Long id) {
        if (categoryDataMapper.selectById(id) == null) {
            throw exception(CATEGORY_DATA_NOT_EXISTS);
        }
    }

    /**
     * 更新定义。
     */
    private void updateDefinition(CategoryDataDO row, Long revision) {
        if (categoryDataMapper.update(row, new LambdaUpdateWrapper<CategoryDataDO>()
                .eq(CategoryDataDO::getId, row.getId()).eq(CategoryDataDO::getRevision, revision)) != 1) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
    }

    /**
     * 校验分类目录的父级分类数据，不满足约束时拒绝当前操作。
     */
    private void validateParentCategoryData(Long id, Long parentId) {
        if (parentId == null || CategoryDataDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父数据分类 类目管理
        if (Objects.equals(id, parentId)) {
            throw exception(CATEGORY_DATA_PARENT_ERROR);
        }
        // 2. 父数据分类 类目管理不存在
        CategoryDataDO parentCategoryData = categoryDataMapper.selectById(parentId);
        if (parentCategoryData == null) {
            throw exception(CATEGORY_DATA_PARENT_NOT_EXITS);
        }
        // 3. 递归校验父数据分类 类目管理，如果父数据分类 类目管理是自己的子数据分类 类目管理，则报错，避免形成环路
        if (id == null) { // id 为空，说明新增，不需要考虑环路
            return;
        }
        java.util.Set<Long> visited = new java.util.HashSet<>();
        while (visited.add(parentCategoryData.getId())) {
            // 3.1 校验环路
            parentId = parentCategoryData.getParentId();
            if (Objects.equals(id, parentId)) {
                throw exception(CATEGORY_DATA_PARENT_IS_CHILD);
            }
            // 3.2 继续递归下一级父数据分类 类目管理
            if (parentId == null || CategoryDataDO.PARENT_ID_ROOT.equals(parentId)) {
                break;
            }
            parentCategoryData = categoryDataMapper.selectById(parentId);
            if (parentCategoryData == null) {
                throw exception(CATEGORY_DATA_PARENT_NOT_EXITS);
            }
        }
        if (parentId != null && !CategoryDataDO.PARENT_ID_ROOT.equals(parentId)) {
            throw exception(CATEGORY_DATA_PARENT_IS_CHILD);
        }
    }

    /**
     * 校验分类目录的分类目录分类名称在指定范围内唯一。
     */
    private void validateCategoryDataCategoryNameUnique(Long id, Long parentId, String categoryName) {
        CategoryDataDO categoryData = categoryDataMapper.selectByParentIdAndCategoryName(parentId, categoryName);
        if (categoryData == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的数据分类 类目管理
        if (id == null) {
            throw exception(CATEGORY_DATA_CATEGORY_NAME_DUPLICATE);
        }
        if (!Objects.equals(categoryData.getId(), id)) {
            throw exception(CATEGORY_DATA_CATEGORY_NAME_DUPLICATE);
        }
    }

    /**
     * 生成层级编码：顶级为 0,当前主键，子级为 父级编码,当前主键
     */
    private String buildCategoryCode(Long parentId, Long id) {
        if (parentId == null || CategoryDataDO.PARENT_ID_ROOT.equals(parentId)) {
            return CATEGORY_CODE_ROOT + CATEGORY_CODE_SEPARATOR + id;
        }
        CategoryDataDO parent = categoryDataMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) {
            throw exception(CATEGORY_DATA_PARENT_NOT_EXITS);
        }
        return parent.getCategoryCode() + CATEGORY_CODE_SEPARATOR + id;
    }

    /**
     * 父级变更后，按新的父级编码刷新子节点层级编码
     */
    private void refreshChildrenCategoryCode(Long parentId) {
        CategoryDataDO parent = categoryDataMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) {
            return;
        }
        List<CategoryDataDO> children = categoryDataMapper.selectListByParentId(parentId);
        for (CategoryDataDO child : children) {
            if (child.getSourceRecordId() != null) {
                continue;
            }
            CategoryDataDO updateChild = new CategoryDataDO();
            updateChild.setId(child.getId());
            updateChild.setCategoryCode(parent.getCategoryCode() + CATEGORY_CODE_SEPARATOR + child.getId());
            updateChild.setRevision(child.getRevision() + 1);
            updateDefinition(updateChild, child.getRevision());
            refreshChildrenCategoryCode(child.getId());
        }
    }

    /**
     * 获得数据分类 类目管理
     *
     *  @param id 编号
     *  @return 数据分类 类目管理
     */
    @Override
    public CategoryDataDO getCategoryData(Long id) {
        return categoryDataMapper.selectById(id);
    }

    /**
     * 获得数据分类 类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 数据分类 类目管理列表
     */
    @Override
    public List<CategoryDataDO> getCategoryDataList(CategoryDataListReqVO listReqVO) {
        return categoryDataMapper.selectList(listReqVO);
    }

}
