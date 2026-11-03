package cn.iocoder.yudao.module.governance.service.categorydataquality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo.CategoryDataQualityListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo.CategoryDataQualitySaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydataquality.CategoryDataQualityDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydataquality.CategoryDataQualityMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据质量类目管理 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class CategoryDataQualityServiceImpl implements CategoryDataQualityService {

    private static final String CATEGORY_CODE_SEPARATOR = ",";

    private static final String CATEGORY_CODE_ROOT = "0";

    @Resource
    private CategoryDataQualityMapper categoryDataQualityMapper;

    /**
     * 创建数据质量类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategoryDataQuality(CategoryDataQualitySaveReqVO createReqVO) {
        // 校验父级ID的有效性
        validateParentCategoryDataQuality(null, createReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryDataQualityCategoryNameUnique(null, createReqVO.getParentId(), createReqVO.getCategoryName());

        // 先插入拿到主键，再按 0,id / 父编码,id 回写层级编码
        CategoryDataQualityDO categoryDataQuality = BeanUtils.toBean(createReqVO, CategoryDataQualityDO.class);
        categoryDataQuality.setCategoryCode(CATEGORY_CODE_ROOT);
        categoryDataQualityMapper.insert(categoryDataQuality);
        CategoryDataQualityDO codeUpdate = new CategoryDataQualityDO();
        codeUpdate.setId(categoryDataQuality.getId());
        codeUpdate.setCategoryCode(buildCategoryCode(createReqVO.getParentId(), categoryDataQuality.getId()));
        categoryDataQualityMapper.updateById(codeUpdate);

        // 返回
        return categoryDataQuality.getId();
    }

    /**
     * 更新数据质量类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategoryDataQuality(CategoryDataQualitySaveReqVO updateReqVO) {
        // 校验存在
        CategoryDataQualityDO existing = categoryDataQualityMapper.selectById(updateReqVO.getId());
        if (existing == null) {
            throw exception(CATEGORY_DATA_QUALITY_NOT_EXISTS);
        }
        // 校验父级ID的有效性
        validateParentCategoryDataQuality(updateReqVO.getId(), updateReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryDataQualityCategoryNameUnique(updateReqVO.getId(), updateReqVO.getParentId(),
                updateReqVO.getCategoryName());

        // 更新，层级编码由后端维护，不接受前端传入
        CategoryDataQualityDO updateObj = BeanUtils.toBean(updateReqVO, CategoryDataQualityDO.class);
        updateObj.setCategoryCode(existing.getCategoryCode());
        if (!Objects.equals(existing.getParentId(), updateReqVO.getParentId())) {
            updateObj.setCategoryCode(buildCategoryCode(updateReqVO.getParentId(), updateReqVO.getId()));
            categoryDataQualityMapper.updateById(updateObj);
            refreshChildrenCategoryCode(updateReqVO.getId());
            return;
        }
        categoryDataQualityMapper.updateById(updateObj);
    }

    /**
     * 删除数据质量类目管理
     *
     *  @param id 编号
     */
    @Override
    public void deleteCategoryDataQuality(Long id) {
        // 校验存在
        validateCategoryDataQualityExists(id);
        // 校验是否有子数据质量类目管理
        if (categoryDataQualityMapper.selectCountByParentId(id) > 0) {
            throw exception(CATEGORY_DATA_QUALITY_EXITS_CHILDREN);
        }
        // 删除
        categoryDataQualityMapper.deleteById(id);
    }


    /**
     * 校验质量分类目录记录存在，不存在时抛出业务异常。
     */
    private void validateCategoryDataQualityExists(Long id) {
        if (categoryDataQualityMapper.selectById(id) == null) {
            throw exception(CATEGORY_DATA_QUALITY_NOT_EXISTS);
        }
    }

    /**
     * 校验质量分类目录的父级分类数据质量，不满足约束时拒绝当前操作。
     */
    private void validateParentCategoryDataQuality(Long id, Long parentId) {
        if (parentId == null || CategoryDataQualityDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父数据质量类目管理
        if (Objects.equals(id, parentId)) {
            throw exception(CATEGORY_DATA_QUALITY_PARENT_ERROR);
        }
        // 2. 父数据质量类目管理不存在
        CategoryDataQualityDO parentCategoryDataQuality = categoryDataQualityMapper.selectById(parentId);
        if (parentCategoryDataQuality == null) {
            throw exception(CATEGORY_DATA_QUALITY_PARENT_NOT_EXITS);
        }
        // 3. 递归校验父数据质量类目管理，如果父数据质量类目管理是自己的子数据质量类目管理，则报错，避免形成环路
        if (id == null) { // id 为空，说明新增，不需要考虑环路
            return;
        }
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            // 3.1 校验环路
            parentId = parentCategoryDataQuality.getParentId();
            if (Objects.equals(id, parentId)) {
                throw exception(CATEGORY_DATA_QUALITY_PARENT_IS_CHILD);
            }
            // 3.2 继续递归下一级父数据质量类目管理
            if (parentId == null || CategoryDataQualityDO.PARENT_ID_ROOT.equals(parentId)) {
                break;
            }
            parentCategoryDataQuality = categoryDataQualityMapper.selectById(parentId);
            if (parentCategoryDataQuality == null) {
                break;
            }
        }
    }

    /**
     * 校验质量分类目录的质量分类目录分类名称在指定范围内唯一。
     */
    private void validateCategoryDataQualityCategoryNameUnique(Long id, Long parentId, String categoryName) {
        CategoryDataQualityDO categoryDataQuality = categoryDataQualityMapper.selectByParentIdAndCategoryName(parentId,
                categoryName);
        if (categoryDataQuality == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的数据质量类目管理
        if (id == null) {
            throw exception(CATEGORY_DATA_QUALITY_CATEGORY_NAME_DUPLICATE);
        }
        if (!Objects.equals(categoryDataQuality.getId(), id)) {
            throw exception(CATEGORY_DATA_QUALITY_CATEGORY_NAME_DUPLICATE);
        }
    }

    /**
     * 生成层级编码：顶级为 0,当前主键，子级为 父级编码,当前主键
     */
    private String buildCategoryCode(Long parentId, Long id) {
        if (parentId == null || CategoryDataQualityDO.PARENT_ID_ROOT.equals(parentId)) {
            return CATEGORY_CODE_ROOT + CATEGORY_CODE_SEPARATOR + id;
        }
        CategoryDataQualityDO parent = categoryDataQualityMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) {
            throw exception(CATEGORY_DATA_QUALITY_PARENT_NOT_EXITS);
        }
        return parent.getCategoryCode() + CATEGORY_CODE_SEPARATOR + id;
    }

    /**
     * 父级变更后，按新的父级编码刷新子节点层级编码
     */
    private void refreshChildrenCategoryCode(Long parentId) {
        CategoryDataQualityDO parent = categoryDataQualityMapper.selectById(parentId);
        if (parent == null || StrUtil.isBlank(parent.getCategoryCode())) {
            return;
        }
        List<CategoryDataQualityDO> children = categoryDataQualityMapper.selectListByParentId(parentId);
        for (CategoryDataQualityDO child : children) {
            CategoryDataQualityDO updateChild = new CategoryDataQualityDO();
            updateChild.setId(child.getId());
            updateChild.setCategoryCode(parent.getCategoryCode() + CATEGORY_CODE_SEPARATOR + child.getId());
            categoryDataQualityMapper.updateById(updateChild);
            refreshChildrenCategoryCode(child.getId());
        }
    }

    /**
     * 获得数据质量类目管理
     *
     *  @param id 编号
     *  @return 数据质量类目管理
     */
    @Override
    public CategoryDataQualityDO getCategoryDataQuality(Long id) {
        return categoryDataQualityMapper.selectById(id);
    }

    /**
     * 获得数据质量类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 数据质量类目管理列表
     */
    @Override
    public List<CategoryDataQualityDO> getCategoryDataQualityList(CategoryDataQualityListReqVO listReqVO) {
        return categoryDataQualityMapper.selectList(listReqVO);
    }

}
