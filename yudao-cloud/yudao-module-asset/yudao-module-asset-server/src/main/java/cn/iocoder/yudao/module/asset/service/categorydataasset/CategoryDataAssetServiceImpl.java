package cn.iocoder.yudao.module.asset.service.categorydataasset;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorydataasset.CategoryDataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.categorydataasset.CategoryDataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/**
 * 数据资产类目管理 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class CategoryDataAssetServiceImpl implements CategoryDataAssetService {

    @Resource
    private CategoryDataAssetMapper categoryDataAssetMapper;

    @Resource
    private DataAssetMapper dataAssetMapper;

    /**
     * 创建数据资产类目管理
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    public Long createCategoryDataAsset(CategoryDataAssetSaveReqVO createReqVO) {
        // 校验父级ID的有效性
        validateParentCategoryDataAsset(null, createReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryDataAssetCategoryNameUnique(null, createReqVO.getParentId(), createReqVO.getCategoryName());

        // 插入
        CategoryDataAssetDO categoryDataAsset = BeanUtils.toBean(createReqVO, CategoryDataAssetDO.class);
        categoryDataAssetMapper.insert(categoryDataAsset);

        // 返回
        return categoryDataAsset.getId();
    }

    /**
     * 更新数据资产类目管理
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    public void updateCategoryDataAsset(CategoryDataAssetSaveReqVO updateReqVO) {
        // 校验存在
        validateCategoryDataAssetExists(updateReqVO.getId());
        // 校验父级ID的有效性
        validateParentCategoryDataAsset(updateReqVO.getId(), updateReqVO.getParentId());
        // 校验类目名称的唯一性
        validateCategoryDataAssetCategoryNameUnique(updateReqVO.getId(), updateReqVO.getParentId(),
                updateReqVO.getCategoryName());

        // 更新
        CategoryDataAssetDO updateObj = BeanUtils.toBean(updateReqVO, CategoryDataAssetDO.class);
        categoryDataAssetMapper.updateById(updateObj);
    }

    /**
     * 删除数据资产类目管理
     *
     *  @param id 编号
     */
    @Override
    public void deleteCategoryDataAsset(Long id) {
        // 校验存在
        validateCategoryDataAssetExists(id);
        // 校验是否有子数据资产类目管理
        if (categoryDataAssetMapper.selectCountByParentId(id) > 0) {
            throw exception(CATEGORY_DATA_ASSET_EXITS_CHILDREN);
        }
        if (dataAssetMapper.selectCountByCategoryId(id) > 0) {
            throw exception(CATEGORY_DATA_ASSET_IN_USE);
        }
        // 删除
        categoryDataAssetMapper.deleteById(id);
    }


    /**
     * 校验资产分类目录记录存在，不存在时抛出业务异常。
     */
    private void validateCategoryDataAssetExists(Long id) {
        if (categoryDataAssetMapper.selectById(id) == null) {
            throw exception(CATEGORY_DATA_ASSET_NOT_EXISTS);
        }
    }

    /**
     * 校验资产分类目录的父级分类数据资产，不满足约束时拒绝当前操作。
     */
    private void validateParentCategoryDataAsset(Long id, Long parentId) {
        if (parentId == null || CategoryDataAssetDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父数据资产类目管理
        if (Objects.equals(id, parentId)) {
            throw exception(CATEGORY_DATA_ASSET_PARENT_ERROR);
        }
        // 2. 父数据资产类目管理不存在
        CategoryDataAssetDO parentCategoryDataAsset = categoryDataAssetMapper.selectById(parentId);
        if (parentCategoryDataAsset == null) {
            throw exception(CATEGORY_DATA_ASSET_PARENT_NOT_EXITS);
        }
        // 3. 递归校验父数据资产类目管理，如果父数据资产类目管理是自己的子数据资产类目管理，则报错，避免形成环路
        if (id == null) { // id 为空，说明新增，不需要考虑环路
            return;
        }
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            // 3.1 校验环路
            parentId = parentCategoryDataAsset.getParentId();
            if (Objects.equals(id, parentId)) {
                throw exception(CATEGORY_DATA_ASSET_PARENT_IS_CHILD);
            }
            // 3.2 继续递归下一级父数据资产类目管理
            if (parentId == null || CategoryDataAssetDO.PARENT_ID_ROOT.equals(parentId)) {
                break;
            }
            parentCategoryDataAsset = categoryDataAssetMapper.selectById(parentId);
            if (parentCategoryDataAsset == null) {
                break;
            }
        }
    }

    /**
     * 校验资产分类目录的资产分类目录分类名称在指定范围内唯一。
     */
    private void validateCategoryDataAssetCategoryNameUnique(Long id, Long parentId, String categoryName) {
        CategoryDataAssetDO categoryDataAsset = categoryDataAssetMapper.selectByParentIdAndCategoryName(parentId,
                categoryName);
        if (categoryDataAsset == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的数据资产类目管理
        if (id == null) {
            throw exception(CATEGORY_DATA_ASSET_CATEGORY_NAME_DUPLICATE);
        }
        if (!Objects.equals(categoryDataAsset.getId(), id)) {
            throw exception(CATEGORY_DATA_ASSET_CATEGORY_NAME_DUPLICATE);
        }
    }

    /**
     * 获得数据资产类目管理
     *
     *  @param id 编号
     *  @return 数据资产类目管理
     */
    @Override
    public CategoryDataAssetDO getCategoryDataAsset(Long id) {
        return categoryDataAssetMapper.selectById(id);
    }

    /**
     * 获得数据资产类目管理列表
     *
     *  @param listReqVO 查询条件
     *  @return 数据资产类目管理列表
     */
    @Override
    public List<CategoryDataAssetDO> getCategoryDataAssetList(CategoryDataAssetListReqVO listReqVO) {
        return categoryDataAssetMapper.selectList(listReqVO);
    }

}
