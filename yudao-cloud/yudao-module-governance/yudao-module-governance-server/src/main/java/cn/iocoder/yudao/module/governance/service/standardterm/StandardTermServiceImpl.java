package cn.iocoder.yudao.module.governance.service.standardterm;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm.CategoryTermDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categoryterm.CategoryTermMapper;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardterm.StandardTermMapper;
import jakarta.annotation.Resource;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据标准--业务术语 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class StandardTermServiceImpl implements StandardTermService {

    @Resource
    private StandardTermMapper standardTermMapper;

    @Resource
    private CategoryDatametaMapper categoryDatametaMapper;

    @Resource
    private CategoryTermMapper categoryTermMapper;

    @Resource
    private StandardElementMapper standardElementMapper;

    /**
     * 创建业务术语
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createStandardTerm(StandardTermSaveReqVO createReqVO) {
        validateNameUnique(null, createReqVO.getName());
        validateRelations(createReqVO);
        StandardTermDO term = BeanUtils.toBean(createReqVO, StandardTermDO.class);
        standardTermMapper.insert(term);
        return term.getId();
    }

    /**
     * 更新业务术语
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStandardTerm(StandardTermSaveReqVO updateReqVO) {
        validateStandardTermExists(updateReqVO.getId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getName());
        validateRelations(updateReqVO);
        StandardTermDO update = BeanUtils.toBean(updateReqVO, StandardTermDO.class);
        if (updateReqVO.isTermCategorySpecified()) {
            update.setTermCategoryId(null);
            standardTermMapper.update(update, new LambdaUpdateWrapper<StandardTermDO>()
                    .eq(StandardTermDO::getId, updateReqVO.getId())
                    .set(StandardTermDO::getTermCategoryId, updateReqVO.getTermCategoryId()));
        } else {
            standardTermMapper.updateById(update);
        }
    }

    /**
     * 删除业务术语
     */
    @Override
    public void deleteStandardTerm(Long id) {
        validateStandardTermExists(id);
        standardTermMapper.deleteById(id);
    }

    /**
     * 批量删除业务术语
     */
    @Override
    public void deleteStandardTermListByIds(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        standardTermMapper.deleteByIds(ids);
    }

    /**
     * 获得业务术语
     */
    @Override
    public StandardTermDO getStandardTerm(Long id) {
        return standardTermMapper.selectById(id);
    }

    /**
     * 检索平台内启用的当前术语，最多 50 条，按名称或别名匹配。
     */
    @Override
    public List<StandardTermDO> getEnabledStandardTermList(String keyword) {
        return standardTermMapper.selectEnabledList(keyword == null ? null : keyword.trim());
    }

    /**
     * 批量读取平台内当前术语，保留已停用项，逻辑删除项不可见。
     */
    @Override
    public List<StandardTermDO> getStandardTermList(List<Long> ids) {
        return CollUtil.isEmpty(ids) ? List.of() : standardTermMapper.selectByIds(ids);
    }

    /**
     * 获得业务术语分页
     */
    @Override
    public PageResult<StandardTermDO> getStandardTermPage(StandardTermPageReqVO pageReqVO) {
        if (pageReqVO.getTermCategoryId() == null) {
            return standardTermMapper.selectTermPage(pageReqVO, null);
        }
        List<CategoryTermDO> categories = categoryTermMapper.selectList(new CategoryTermListReqVO());
        Set<Long> ids = new HashSet<>();
        ids.add(pageReqVO.getTermCategoryId());
        boolean changed;
        do {
            changed = false;
            for (CategoryTermDO category : categories) {
                if (ids.contains(category.getParentId()) && ids.add(category.getId())) {
                    changed = true;
                }
            }
        } while (changed);
        return standardTermMapper.selectTermPage(pageReqVO, new ArrayList<>(ids));
    }

    /**
     * 校验标准术语记录存在，不存在时抛出业务异常。
     */
    private StandardTermDO validateStandardTermExists(Long id) {
        StandardTermDO term = standardTermMapper.selectById(id);
        if (term == null) {
            throw exception(STANDARD_TERM_NOT_EXISTS);
        }
        return term;
    }

    /**
     * 校验标准术语名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, String name) {
        StandardTermDO term = standardTermMapper.selectByName(name);
        if (term != null && !Objects.equals(term.getId(), id)) {
            throw exception(STANDARD_TERM_NAME_DUPLICATE);
        }
    }

    /**
     * 校验标准术语的关联，不满足约束时拒绝当前操作。
     */
    private void validateRelations(StandardTermSaveReqVO reqVO) {
        if (reqVO.getTermCategoryId() != null
                && categoryTermMapper.selectByIdForUpdate(reqVO.getTermCategoryId()) == null) {
            throw exception(CATEGORY_TERM_NOT_EXISTS);
        }
        if (reqVO.getCategoryId() != null
                && categoryDatametaMapper.selectById(reqVO.getCategoryId()) == null) {
            throw exception(STANDARD_ELEMENT_CATEGORY_NOT_EXISTS);
        }
        if (reqVO.getRelatedElementId() != null
                && standardElementMapper.selectById(reqVO.getRelatedElementId()) == null) {
            throw exception(STANDARD_ELEMENT_NOT_EXISTS);
        }
    }

}
