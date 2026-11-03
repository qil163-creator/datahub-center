package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据治理-数据质量-质量规则 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class QualityRuleServiceImpl implements QualityRuleService {

    @Resource
    private QualityRuleMapper qualityRuleMapper;

    @Resource
    private AuditSqlRegistry auditSqlRegistry;

    @Resource
    private CurrentStandardQualityRules currentStandardRules;

    /**
     * 创建质量规则。
     */
    @Override
    public Long createQualityRule(QualityRuleSaveReqVO createReqVO) {
        validateSaveReq(createReqVO, null);
        QualityRuleDO rule = BeanUtils.toBean(createReqVO, QualityRuleDO.class);
        if (rule.getStatus() == null) {
            rule.setStatus(QualityRuleDO.STATUS_ENABLE);
        }
        if (StrUtil.isBlank(rule.getCheckLevel())) {
            rule.setCheckLevel(QualityRuleDO.CHECK_LEVEL_FIELD);
        }
        if (rule.getCollectIssueDetail() == null) {
            rule.setCollectIssueDetail(1);
        }
        qualityRuleMapper.insert(rule);
        return rule.getId();
    }

    /**
     * 更新质量规则。
     */
    @Override
    public void updateQualityRule(QualityRuleSaveReqVO updateReqVO) {
        QualityRuleDO existing = validateRuleExists(updateReqVO.getId());
        validateSaveReq(updateReqVO, updateReqVO.getId());
        QualityRuleDO update = BeanUtils.toBean(updateReqVO, QualityRuleDO.class);
        if (existing.getStandardElementId() != null) {
            if (changed(existing.getStrategyKey(), update.getStrategyKey())
                    || changed(existing.getParamSchema(), update.getParamSchema())
                    || changed(existing.getRuleType(), update.getRuleType())
                    || changed(existing.getQualityDim(), update.getQualityDim())
                    || changed(existing.getRuleCode(), update.getRuleCode())) {
                throw exception(QUALITY_RULE_STANDARD_FIELD_IMMUTABLE);
            }
            update.setStandardElementId(existing.getStandardElementId());
            update.setConstraintHash(existing.getConstraintHash());
            update.setBuiltin(existing.getBuiltin());
        }
        qualityRuleMapper.updateById(update);
    }

    /**
     * 更新质量规则状态。
     */
    @Override
    public void updateQualityRuleStatus(Long id, Integer status) {
        validateRuleExists(id);
        qualityRuleMapper.updateById(QualityRuleDO.builder().id(id).status(status).build());
    }

    /**
     * 删除质量规则。
     */
    @Override
    public void deleteQualityRule(Long id) {
        QualityRuleDO rule = validateRuleExists(id);
        if (Objects.equals(rule.getBuiltin(), 1)) {
            throw exception(QUALITY_RULE_BUILTIN_CANNOT_DELETE);
        }
        if (rule.getStandardElementId() != null) {
            throw exception(QUALITY_RULE_STANDARD_CANNOT_DELETE);
        }
        qualityRuleMapper.deleteById(id);
    }

    /**
     * 获得质量规则。
     */
    @Override
    public QualityRuleDO getQualityRule(Long id) {
        return qualityRuleMapper.selectById(id);
    }

    /**
     * 获得质量规则分页。
     */
    @Override
    public PageResult<QualityRuleDO> getQualityRulePage(QualityRulePageReqVO pageReqVO) {
        return qualityRuleMapper.selectPage(pageReqVO);
    }

    /**
     * 全部启用规则精简列表（规则集选择器用）
     */
    @Override
    public List<QualityRuleDO> getQualityRuleSimpleList() {
        return getQualityRuleSimpleList(null);
    }

    /**
     * 启用规则精简列表；传入标准数据元编号时只返回该标准物化的规则。
     */
    @Override
    public List<QualityRuleDO> getQualityRuleSimpleList(Long standardElementId) {
        return currentStandardRules.selectable(qualityRuleMapper.selectList(new LambdaQueryWrapperX<QualityRuleDO>()
                .eqIfPresent(QualityRuleDO::getStandardElementId, standardElementId)
                .orderByAsc(QualityRuleDO::getId)));
    }

    /**
     * 检查质量规则写入结果是否反映预期变更。
     */
    private static boolean changed(Object left, Object right) {
        if (left instanceof String leftText && right instanceof String rightText) {
            return !Objects.equals(StrUtil.blankToDefault(leftText, null),
                    StrUtil.blankToDefault(rightText, null));
        }
        return !Objects.equals(left, right);
    }

    /**
     * 校验质量规则的保存请求，不满足约束时拒绝当前操作。
     */
    private void validateSaveReq(QualityRuleSaveReqVO reqVO, Long id) {
        validateKeyConfiguration(reqVO.getParamSchema());
        // 校验名称唯一
        QualityRuleDO existsByName = qualityRuleMapper.selectByName(reqVO.getRuleName());
        if (existsByName != null && !Objects.equals(existsByName.getId(), id)) {
            throw exception(QUALITY_RULE_NAME_DUPLICATE);
        }
        // 校验编码唯一
        if (StrUtil.isNotBlank(reqVO.getRuleCode())) {
            QualityRuleDO existsByCode = qualityRuleMapper.selectByCode(reqVO.getRuleCode());
            if (existsByCode != null && !Objects.equals(existsByCode.getId(), id)) {
                throw exception(QUALITY_RULE_CODE_DUPLICATE);
            }
        }
        // 校验策略标识已接入引擎
        if (StrUtil.isNotBlank(reqVO.getStrategyKey()) && !auditSqlRegistry.contains(reqVO.getStrategyKey())) {
            throw exception(AUDIT_RULE_STRATEGY_NOT_SUPPORTED, reqVO.getStrategyKey());
        }
    }

    /**
     * 校验质量规则的键配置，不满足约束时拒绝当前操作。
     */
    private void validateKeyConfiguration(String parameters) {
        if (StrUtil.isBlank(parameters)) {
            return;
        }
        // 使用组件的无日志解析入口；无效配置可能含源端 SQL/字段值，不能输出完整载荷。
        Map<String, Object> values = JsonUtils.parseMap(parameters);
        if (values == null) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, "参数必须是 JSON 对象");
        }
        if (!QualityKeyDefinition.from(values).valid()) {
            throw exception(AUDIT_RULE_CONFIG_INVALID,
                    "主键须配置为单列 pkColumn，或 2–4 个不重复普通列组成的 pkColumns 数组，不能同时配置");
        }
    }

    /**
     * 校验规则记录存在，不存在时抛出业务异常。
     */
    private QualityRuleDO validateRuleExists(Long id) {
        QualityRuleDO rule = id == null ? null : qualityRuleMapper.selectById(id);
        if (rule == null) {
            throw exception(QUALITY_RULE_NOT_EXISTS);
        }
        return rule;
    }

}
