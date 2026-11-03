package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory.BizCategoryListReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory.BizCategoryRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory.BizCategorySaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.BizCategoryDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.BizCategoryDataDomainRelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryDataDomainRelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 业务分类 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class BizCategoryServiceImpl implements BizCategoryService {

    @Resource
    private BizCategoryMapper bizCategoryMapper;

    @Resource
    private BizCategoryDataDomainRelMapper bizCategoryDataDomainRelMapper;

    @Resource
    private LogicModelMapper logicModelMapper;

    @Resource
    private DataDomainMapper dataDomainMapper;

    /**
     * 创建业务分类
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createBizCategory(BizCategorySaveReqVO createReqVO) {
        validateParent(null, createReqVO.getParentId());
        validateNameUnique(null, createReqVO.getParentId(), createReqVO.getName());
        List<Long> dataDomainIds = validateAndNormalizeDataDomains(createReqVO.getDataDomainIds());
        BizCategoryDO category = convertToDO(createReqVO);
        if (category.getStatus() == null) {
            category.setStatus(CommonStatusEnum.ENABLE.getStatus());
        }
        bizCategoryMapper.insert(category);
        replaceDataDomainRels(category.getId(), dataDomainIds);
        return category.getId();
    }

    /**
     * 更新业务分类
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateBizCategory(BizCategorySaveReqVO updateReqVO) {
        validateBizCategoryExists(updateReqVO.getId());
        validateParent(updateReqVO.getId(), updateReqVO.getParentId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getParentId(), updateReqVO.getName());
        List<Long> dataDomainIds = validateAndNormalizeDataDomains(updateReqVO.getDataDomainIds());
        bizCategoryMapper.updateById(convertToDO(updateReqVO));
        replaceDataDomainRels(updateReqVO.getId(), dataDomainIds);
    }

    /**
     * 更新业务分类状态
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    @Override
    public void updateBizCategoryStatus(Long id, Integer status) {
        validateBizCategoryExists(id);
        bizCategoryMapper.update(null, Wrappers.lambdaUpdate(BizCategoryDO.class)
                .set(BizCategoryDO::getStatus, status)
                .eq(BizCategoryDO::getId, id));
    }

    /**
     * 删除业务分类（存在子级时不可删除）
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBizCategory(Long id) {
        long logicRefs = logicModelMapper.selectCountByBizCategoryId(id);
        if (logicRefs > 0) {
            throw exception(BIZ_CATEGORY_HAS_MODEL_REF);
        }

        // 有子分类，不允许本节点删除：硬边界。
        if (bizCategoryMapper.selectOne(Wrappers.lambdaQuery(BizCategoryDO.class)
                .eq(BizCategoryDO::getId, id).last("FOR UPDATE")) == null) {
            throw exception(BIZ_CATEGORY_NOT_EXISTS);
        }
        if (bizCategoryMapper.selectCountByParentId(id) > 0) {
            throw exception(BIZ_CATEGORY_EXITS_CHILDREN);
        }
        bizCategoryDataDomainRelMapper.deleteByBizCategoryId(id);
        bizCategoryMapper.deleteById(id);
    }

    /**
     * 获得业务分类
     *
     * @param id 记录编号
     * @return 业务分类响应数据
     */
    @Override
    public BizCategoryRespVO getBizCategory(Long id) {
        BizCategoryRespVO resp = convertToResp(validateBizCategoryExists(id));
        fillDataDomains(Collections.singletonList(resp));
        return resp;
    }

    /**
     * 获得业务分类列表（树形数据平铺返回）
     *
     * @param listReqVO 列表请求参数
     * @return 业务分类列表（树形数据平铺返回）结果集合
     */
    @Override
    public List<BizCategoryRespVO> getBizCategoryList(BizCategoryListReqVO listReqVO) {
        List<BizCategoryRespVO> list = convertList(bizCategoryMapper.selectList(listReqVO), this::convertToResp);
        fillDataDomains(list);
        return list;
    }

    /**
     * 转换目标。
     *
     * @param reqVO 请求参数
     * @return 业务分类
     */
    private BizCategoryDO convertToDO(BizCategorySaveReqVO reqVO) {
        // dataDomainIds 不落主表，由关联表维护
        BizCategoryDO category = BeanUtils.toBean(reqVO, BizCategoryDO.class);
        return category;
    }

    /**
     * 转换目标。
     *
     * @param category 分类
     * @return 业务分类响应数据
     */
    private BizCategoryRespVO convertToResp(BizCategoryDO category) {
        return BeanUtils.toBean(category, BizCategoryRespVO.class);
    }

    /**
     * 替换数据域关系集合。
     *
     * @param bizCategoryId 业务分类编号
     * @param dataDomainIds 数据域编号集合
     */
    private void replaceDataDomainRels(Long bizCategoryId, List<Long> dataDomainIds) {
        bizCategoryDataDomainRelMapper.deleteByBizCategoryId(bizCategoryId);
        if (dataDomainIds == null || dataDomainIds.isEmpty()) {
            return;
        }
        int sort = 0;
        for (Long dataDomainId : dataDomainIds) {
            BizCategoryDataDomainRelDO rel = new BizCategoryDataDomainRelDO();
            rel.setBizCategoryId(bizCategoryId);
            rel.setDataDomainId(dataDomainId);
            rel.setSortOrder(sort++);
            bizCategoryDataDomainRelMapper.insert(rel);
        }
    }

    /**
     * 校验域存在且启用；去重保序。
     */
    private List<Long> validateAndNormalizeDataDomains(List<Long> dataDomainIds) {
        if (dataDomainIds == null || dataDomainIds.isEmpty()) {
            throw exception(MODEL_DATA_DOMAIN_REQUIRED);
        }
        List<Long> normalized = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (Long id : dataDomainIds) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            normalized.add(id);
        }
        if (normalized.isEmpty()) {
            throw exception(MODEL_DATA_DOMAIN_REQUIRED);
        }
        List<DataDomainDO> domains = dataDomainMapper.selectBatchIds(normalized);
        Map<Long, DataDomainDO> byId = domains.stream()
                .collect(Collectors.toMap(DataDomainDO::getId, d -> d, (a, b) -> a));
        for (Long id : normalized) {
            DataDomainDO domain = byId.get(id);
            if (domain == null) {
                throw exception(DATA_DOMAIN_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(domain.getStatus())) {
                throw exception(MODEL_DATA_DOMAIN_DISABLED);
            }
        }
        return normalized;
    }

    /**
     * 补充数据域。
     *
     * @param list 待处理列表
     */
    private void fillDataDomains(List<BizCategoryRespVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> categoryIds = list.stream()
                .map(BizCategoryRespVO::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        for (BizCategoryRespVO resp : list) {
            // 先占位，避免前端拿到 undefined
            if (resp.getDataDomainIds() == null) {
                resp.setDataDomainIds(List.of());
            }
            if (resp.getDataDomains() == null) {
                resp.setDataDomains(List.of());
            }
        }
        if (categoryIds.isEmpty()) {
            return;
        }
        List<BizCategoryDataDomainRelDO> rels =
                bizCategoryDataDomainRelMapper.selectListByBizCategoryIds(categoryIds);
        if (rels == null || rels.isEmpty()) {
            return;
        }
        Map<Long, List<BizCategoryDataDomainRelDO>> relsByCategory = new LinkedHashMap<>();
        for (BizCategoryDataDomainRelDO rel : rels) {
            relsByCategory.computeIfAbsent(rel.getBizCategoryId(), k -> new ArrayList<>()).add(rel);
        }
        Set<Long> domainIds = rels.stream()
                .map(BizCategoryDataDomainRelDO::getDataDomainId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DataDomainDO> domainById = Map.of();
        if (!domainIds.isEmpty()) {
            List<DataDomainDO> domains = dataDomainMapper.selectBatchIds(domainIds);
            if (domains != null && !domains.isEmpty()) {
                domainById = domains.stream()
                        .collect(Collectors.toMap(DataDomainDO::getId, d -> d, (a, b) -> a));
            }
        }
        for (BizCategoryRespVO resp : list) {
            List<BizCategoryDataDomainRelDO> categoryRels =
                    relsByCategory.getOrDefault(resp.getId(), List.of());
            List<Long> ids = new ArrayList<>();
            List<BizCategoryRespVO.DataDomainSimple> simples = new ArrayList<>();
            for (BizCategoryDataDomainRelDO rel : categoryRels) {
                ids.add(rel.getDataDomainId());
                DataDomainDO domain = domainById.get(rel.getDataDomainId());
                BizCategoryRespVO.DataDomainSimple simple = new BizCategoryRespVO.DataDomainSimple();
                simple.setId(rel.getDataDomainId());
                if (domain != null) {
                    simple.setName(domain.getName());
                    simple.setCode(domain.getCode());
                } else if (rel.getDataDomainId() != null) {
                    simple.setName(String.valueOf(rel.getDataDomainId()));
                }
                simples.add(simple);
            }
            resp.setDataDomainIds(ids);
            resp.setDataDomains(simples);
        }
    }

    /**
     * 校验业务分类存在性。
     *
     * @param id 记录编号
     * @return 业务分类
     */
    private BizCategoryDO validateBizCategoryExists(Long id) {
        BizCategoryDO category = bizCategoryMapper.selectById(id);
        if (category == null) {
            throw exception(BIZ_CATEGORY_NOT_EXISTS);
        }
        return category;
    }

    /**
     * 校验父级：存在 + 不可选自己或自己的子孙
     */
    private void validateParent(Long id, Long parentId) {
        if (parentId == null || Objects.equals(parentId, BizCategoryDO.PARENT_ID_ROOT)) {
            return;
        }
        if (Objects.equals(parentId, id)) {
            throw exception(BIZ_CATEGORY_PARENT_ERROR);
        }
        BizCategoryDO parent = bizCategoryMapper.selectById(parentId);
        if (parent == null) {
            throw exception(BIZ_CATEGORY_PARENT_NOT_EXITS);
        }
        // 重复向上查，防止把「自己的子孙」设为上级形成环
        for (int i = 0; i < 100 && parent != null
                && !Objects.equals(parent.getParentId(), BizCategoryDO.PARENT_ID_ROOT); i++) {
            if (Objects.equals(parent.getParentId(), id)) {
                throw exception(BIZ_CATEGORY_PARENT_ERROR);
            }
            parent = bizCategoryMapper.selectById(parent.getParentId());
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
        BizCategoryDO exists = bizCategoryMapper.selectByParentIdAndName(
                parentId == null ? BizCategoryDO.PARENT_ID_ROOT : parentId, name);
        if (exists != null && !exists.getId().equals(id)) {
            throw exception(BIZ_CATEGORY_NAME_DUPLICATE);
        }
    }

}
