package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetItemSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetItemDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据治理-数据质量-规则集 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class QualityRuleSetServiceImpl implements QualityRuleSetService {

    @Resource
    private QualityRuleSetMapper ruleSetMapper;

    @Resource
    private QualityRuleSetItemMapper ruleSetItemMapper;

    @Resource
    private QualityRuleMapper qualityRuleMapper;

    /**
     * 创建规则集。
     */
    @Override
    public Long createRuleSet(QualityRuleSetSaveReqVO createReqVO) {
        validateNameUnique(null, createReqVO.getSetName());
        QualityRuleSetDO ruleSet = BeanUtils.toBean(createReqVO, QualityRuleSetDO.class);
        if (ruleSet.getStatus() == null) {
            ruleSet.setStatus(QualityRuleSetDO.STATUS_ENABLE);
        }
        ruleSetMapper.insert(ruleSet);
        return ruleSet.getId();
    }

    /**
     * 更新规则集。
     */
    @Override
    public void updateRuleSet(QualityRuleSetSaveReqVO updateReqVO) {
        validateExists(updateReqVO.getId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getSetName());
        ruleSetMapper.updateById(BeanUtils.toBean(updateReqVO, QualityRuleSetDO.class));
    }

    /**
     * 更新规则集状态。
     */
    @Override
    public void updateRuleSetStatus(Long id, Integer status) {
        validateExists(id);
        ruleSetMapper.updateById(QualityRuleSetDO.builder().id(id).status(status).build());
    }

    /**
     * 删除规则集。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRuleSet(Long id) {
        validateExists(id);
        ruleSetMapper.deleteById(id);
        ruleSetItemMapper.deleteByRuleSetId(id);
    }

    /**
     * 获得规则集。
     */
    @Override
    public QualityRuleSetDO getRuleSet(Long id) {
        return ruleSetMapper.selectById(id);
    }

    /**
     * 获得规则集分页。
     */
    @Override
    public PageResult<QualityRuleSetRespVO> getRuleSetPage(QualityRuleSetPageReqVO pageReqVO) {
        PageResult<QualityRuleSetDO> pageResult = ruleSetMapper.selectPage(pageReqVO);
        PageResult<QualityRuleSetRespVO> result = BeanUtils.toBean(pageResult, QualityRuleSetRespVO.class);
        // 批量统计各规则集的规则数，避免逐行查询
        List<Long> ruleSetIds = convertList(result.getList(), QualityRuleSetRespVO::getId);
        Map<Long, Long> countMap = ruleSetItemMapper.selectListByRuleSetIds(ruleSetIds).stream()
                .collect(Collectors.groupingBy(QualityRuleSetItemDO::getRuleSetId, Collectors.counting()));
        result.getList().forEach(item ->
                item.setRuleCount(countMap.getOrDefault(item.getId(), 0L).intValue()));
        return result;
    }

    /**
     * 获得规则集精简列表（启用状态）。
     */
    @Override
    public List<QualityRuleSetDO> getRuleSetSimpleList() {
        return ruleSetMapper.selectList(new LambdaQueryWrapperX<QualityRuleSetDO>()
                .eq(QualityRuleSetDO::getStatus, QualityRuleSetDO.STATUS_ENABLE)
                .orderByAsc(QualityRuleSetDO::getId));
    }

    /**
     * 规则集明细列表（带规则名称/类型/维度）
     */
    @Override
    public List<QualityRuleSetRespVO.Item> getRuleSetItems(Long ruleSetId) {
        validateExists(ruleSetId);
        List<QualityRuleSetItemDO> items = ruleSetItemMapper.selectListByRuleSetId(ruleSetId);
        if (CollUtil.isEmpty(items)) {
            return List.of();
        }
        List<Long> ruleIds = convertList(items, QualityRuleSetItemDO::getRuleId);
        Map<Long, QualityRuleDO> ruleMap = qualityRuleMapper.selectBatchIds(ruleIds).stream()
                .collect(Collectors.toMap(QualityRuleDO::getId, rule -> rule));
        List<QualityRuleSetRespVO.Item> result = new ArrayList<>();
        for (QualityRuleSetItemDO item : items) {
            QualityRuleSetRespVO.Item vo = new QualityRuleSetRespVO.Item();
            vo.setId(item.getId());
            vo.setRuleId(item.getRuleId());
            vo.setWeight(item.getWeight());
            QualityRuleDO rule = ruleMap.get(item.getRuleId());
            if (rule != null) {
                vo.setRuleName(rule.getRuleName());
                vo.setRuleType(rule.getRuleType());
                vo.setQualityDim(rule.getQualityDim());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 保存规则集规则（全量覆盖）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveRuleSetItems(QualityRuleSetItemSaveReqVO reqVO) {
        validateExists(reqVO.getRuleSetId());
        if (CollUtil.isEmpty(reqVO.getItems())) {
            throw exception(QUALITY_RULE_SET_ITEM_EMPTY);
        }
        // 全量覆盖：先删后插
        ruleSetItemMapper.deleteByRuleSetId(reqVO.getRuleSetId());
        List<QualityRuleSetItemDO> inserts = new ArrayList<>();
        for (QualityRuleSetItemSaveReqVO.Item item : reqVO.getItems()) {
            inserts.add(QualityRuleSetItemDO.builder()
                    .ruleSetId(reqVO.getRuleSetId())
                    .ruleId(item.getRuleId())
                    .weight(item.getWeight() == null ? BigDecimal.ONE : item.getWeight())
                    .build());
        }
        ruleSetItemMapper.insertBatch(inserts);
    }

    /**
     * 读取并校验质量规则集合记录存在，不存在时抛出业务异常。
     */
    private void validateExists(Long id) {
        if (id == null || ruleSetMapper.selectById(id) == null) {
            throw exception(QUALITY_RULE_SET_NOT_EXISTS);
        }
    }

    /**
     * 校验质量规则集合名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, String setName) {
        QualityRuleSetDO existing = ruleSetMapper.selectByName(setName);
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(QUALITY_RULE_SET_NAME_DUPLICATE);
        }
    }

}
