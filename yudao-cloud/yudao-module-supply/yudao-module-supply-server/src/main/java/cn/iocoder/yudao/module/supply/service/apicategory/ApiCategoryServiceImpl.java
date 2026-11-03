package cn.iocoder.yudao.module.supply.service.apicategory;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo.ApiCategoryListReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo.ApiCategorySaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apicategory.ApiCategoryDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apicategory.ApiCategoryMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.*;

/**
 * 数据服务-服务分类 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class ApiCategoryServiceImpl implements ApiCategoryService {

    @Resource
    private ApiCategoryMapper apiCategoryMapper;

    /**
     * 创建服务分类
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createApiCategory(ApiCategorySaveReqVO createReqVO) {
        validateParent(null, createReqVO.getParentId());
        validateNameUnique(null, createReqVO.getParentId(), createReqVO.getName());
        ApiCategoryDO category = BeanUtils.toBean(createReqVO, ApiCategoryDO.class);
        if (category.getStatus() == null) {
            category.setStatus(CommonStatusEnum.ENABLE.getStatus());
        }
        if (category.getSort() == null) {
            category.setSort(0);
        }
        apiCategoryMapper.insert(category);
        return category.getId();
    }

    /**
     * 更新服务分类
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateApiCategory(ApiCategorySaveReqVO updateReqVO) {
        validateApiCategoryExists(updateReqVO.getId());
        validateParent(updateReqVO.getId(), updateReqVO.getParentId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getParentId(), updateReqVO.getName());
        apiCategoryMapper.updateById(BeanUtils.toBean(updateReqVO, ApiCategoryDO.class));
    }

    /**
     * 更新服务分类状态
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    @Override
    public void updateApiCategoryStatus(Long id, Integer status) {
        validateApiCategoryExists(id);
        apiCategoryMapper.update(null, Wrappers.lambdaUpdate(ApiCategoryDO.class)
                .set(ApiCategoryDO::getStatus, status)
                .eq(ApiCategoryDO::getId, id));
    }

    /**
     * 删除服务分类（存在子级不可删除）
     *
     * @param id 记录编号
     */
    @Override
    public void deleteApiCategory(Long id) {
        validateApiCategoryExists(id);
        if (apiCategoryMapper.selectCountByParentId(id) > 0) {
            throw exception(CATEGORY_DATA_SUPPLY_EXITS_CHILDREN);
        }
        apiCategoryMapper.deleteById(id);
    }

    /**
     * 获得服务分类
     *
     * @param id 记录编号
     * @return API分类
     */
    @Override
    public ApiCategoryDO getApiCategory(Long id) {
        return validateApiCategoryExists(id);
    }

    /**
     * 获得服务分类列表（树形数据平铺返回）
     *
     * @param listReqVO 列表请求参数
     * @return 服务分类列表（树形数据平铺返回）结果集合
     */
    @Override
    public List<ApiCategoryDO> getApiCategoryList(ApiCategoryListReqVO listReqVO) {
        return apiCategoryMapper.selectList(listReqVO);
    }

    /**
     * 校验API分类存在性。
     *
     * @param id 记录编号
     * @return API分类
     */
    private ApiCategoryDO validateApiCategoryExists(Long id) {
        ApiCategoryDO category = apiCategoryMapper.selectById(id);
        if (category == null) {
            throw exception(CATEGORY_DATA_SUPPLY_NOT_EXISTS);
        }
        return category;
    }

    /**
     * 校验父级：存在性 + 不能选自己或自己的子孙
     */
    private void validateParent(Long id, Long parentId) {
        if (parentId == null || Objects.equals(parentId, ApiCategoryDO.PARENT_ID_ROOT)) {
            return;
        }
        if (Objects.equals(parentId, id)) {
            throw exception(CATEGORY_DATA_SUPPLY_PARENT_ERROR);
        }
        ApiCategoryDO parent = apiCategoryMapper.selectById(parentId);
        if (parent == null) {
            throw exception(CATEGORY_DATA_SUPPLY_PARENT_NOT_EXITS);
        }
        // 沿父链向上，防止把自己的子孙设为父级形成环
        for (int i = 0; i < 100 && parent != null
                && !Objects.equals(parent.getParentId(), ApiCategoryDO.PARENT_ID_ROOT); i++) {
            if (Objects.equals(parent.getParentId(), id)) {
                throw exception(CATEGORY_DATA_SUPPLY_PARENT_IS_CHILD);
            }
            parent = apiCategoryMapper.selectById(parent.getParentId());
        }
    }

    /**
     * 校验名称唯一性。
     *
     * @param id 记录编号
     * @param parentId 父级编号
     * @param name 名称
     */
    private void validateNameUnique(Long id, Long parentId, String name) {
        ApiCategoryDO exists = apiCategoryMapper.selectByParentIdAndName(
                parentId == null ? ApiCategoryDO.PARENT_ID_ROOT : parentId, name);
        if (exists != null && !exists.getId().equals(id)) {
            throw exception(CATEGORY_DATA_SUPPLY_CATEGORY_NAME_DUPLICATE);
        }
    }

}
