package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompiler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 把数据元当前约束幂等物化为质量模板。
 *
 * <p>调用方持有标准行锁。任务运行时按当前约束选择模板并冻结执行计划。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardQualityRuleMaterializer {

    static final String RULE_CODE_PREFIX = "STD-";

    static final String ASSET_TASK_CATEGORY = "ASSET";

    private final QualityRuleMapper qualityRuleMapper;

    /**
     * 依据标准约束生成并保存关联质量规则。
     */
    public void materialize(StandardElementDO element, StandardConstraintCompiler.Compilation compilation) {
        if (element == null || element.getId() == null) {
            return;
        }
        Long elementId = element.getId().longValue();
        if (compilation == null || compilation.constraints() == null) {
            return;
        }
        List<QualityRuleDO> existing = qualityRuleMapper.selectListByStandardElementId(elementId);
        Map<String, QualityRuleDO> byHash = existing.stream()
                .filter(rule -> StrUtil.isNotBlank(rule.getConstraintHash()))
                .collect(Collectors.toMap(QualityRuleDO::getConstraintHash, rule -> rule, (left, right) -> right,
                        LinkedHashMap::new));
        for (StandardConstraintCompiler.CompiledConstraint constraint : compilation.constraints()) {
            if (constraint == null || StrUtil.isBlank(constraint.constraintHash())) {
                continue;
            }
            if (!byHash.containsKey(constraint.constraintHash())) {
                QualityRuleDO inserted = insertRule(element, constraint);
                byHash.put(constraint.constraintHash(), inserted);
            }
        }
    }

    /**
     * 停用与指定标准数据元关联的生成质量规则。
     */
    public void disableByElement(Integer elementId) {
        if (elementId == null) {
            return;
        }
        List<QualityRuleDO> existing = qualityRuleMapper.selectListByStandardElementId(elementId.longValue());
        for (QualityRuleDO previous : existing) {
            if (!Objects.equals(previous.getStatus(), QualityRuleDO.STATUS_DISABLE)) {
                qualityRuleMapper.updateById(QualityRuleDO.builder()
                        .id(previous.getId())
                        .status(QualityRuleDO.STATUS_DISABLE)
                        .build());
            }
        }
    }

    /**
     * 新增标准质量规则生成器的规则。
     */
    private QualityRuleDO insertRule(StandardElementDO element,
            StandardConstraintCompiler.CompiledConstraint constraint) {
        QualityRuleDO rule = preview(element, constraint);
        qualityRuleMapper.insert(rule);
        return rule;
    }

    /** 构造当前约束的建议规则，不写入数据库。 */
    public QualityRuleDO preview(StandardElementDO element, StandardConstraintCompiler.CompiledConstraint constraint) {
        return QualityRuleDO.builder()
                .ruleName(buildRuleName(element, constraint))
                .ruleCode(buildRuleCode(element.getId(), constraint))
                .qualityDim(qualityDim(constraint.constraintType()))
                .checkLevel(QualityRuleDO.CHECK_LEVEL_FIELD)
                .ruleType(ruleType(constraint.constraintType()))
                .strategyKey(constraint.strategyKey())
                .paramSchema(JsonUtils.toJsonString(constraint.config()))
                .severity(severity(constraint.constraintType()))
                .collectIssueDetail(1)
                .status(QualityRuleDO.STATUS_ENABLE)
                .builtin(0)
                .description(buildDescription(element, constraint.constraintType()))
                .standardElementId(element.getId().longValue())
                .constraintHash(constraint.constraintHash())
                .build();
    }

    /**
     * 构造标准质量规则生成器的规则名称。
     */
    static String buildRuleName(StandardElementDO element, StandardConstraintCompiler.CompiledConstraint constraint) {
        String elementName = StrUtil.blankToDefault(element.getName(),
                StrUtil.blankToDefault(element.getCode(), "DE" + element.getId()));
        String suffix = "-" + typeLabel(constraint.constraintType()) + "-"
                + constraint.constraintHash().substring(0, 8);
        int prefixLimit = 128 - suffix.length();
        if (elementName.codePointCount(0, elementName.length()) > prefixLimit) {
            elementName = elementName.substring(0, elementName.offsetByCodePoints(0, prefixLimit));
        }
        return elementName + suffix;
    }

    /**
     * 构造标准质量规则生成器的规则编码。
     */
    static String buildRuleCode(Integer elementId,
            StandardConstraintCompiler.CompiledConstraint constraint) {
        String hash = StrUtil.blankToDefault(constraint.constraintHash(), "0");
        String suffix = hash.length() >= 8 ? hash.substring(0, 8) : hash;
        return RULE_CODE_PREFIX + elementId + "-" + constraint.constraintType() + "-" + suffix;
    }

    /**
     * 将标准约束类型映射到质量维度。
     */
    static String qualityDim(String constraintType) {
        return switch (StrUtil.nullToEmpty(constraintType)) {
            case StandardConstraintCompiler.NON_NULL -> "COMPLETENESS";
            case StandardConstraintCompiler.ENUM, StandardConstraintCompiler.NUMERIC_RANGE,
                 StandardConstraintCompiler.PRECISION_SCALE -> "ACCURACY";
            default -> "NORMALIZATION";
        };
    }

    /**
     * 将标准约束类型映射到质量规则类型。
     */
    static String ruleType(String constraintType) {
        return switch (StrUtil.nullToEmpty(constraintType)) {
            case StandardConstraintCompiler.NON_NULL -> "NOT_NULL";
            case StandardConstraintCompiler.PRECISION_SCALE -> "PRECISION";
            case StandardConstraintCompiler.NUMERIC_RANGE -> "RANGE";
            case StandardConstraintCompiler.DATE_FORMAT -> "DATE_FORMAT";
            default -> constraintType;
        };
    }

    /**
     * 将标准约束级别转换为质量问题严重级别。
     */
    static String severity(String constraintType) {
        return StandardConstraintCompiler.NON_NULL.equals(constraintType) ? "HIGH" : "MID";
    }

    /**
     * 读取标准约束类型的显示名称。
     */
    static String typeLabel(String constraintType) {
        return switch (StrUtil.nullToEmpty(constraintType)) {
            case StandardConstraintCompiler.NON_NULL -> "非空";
            case StandardConstraintCompiler.LENGTH -> "长度";
            case StandardConstraintCompiler.PRECISION_SCALE -> "精度";
            case StandardConstraintCompiler.ENUM -> "码值";
            case StandardConstraintCompiler.NUMERIC_RANGE -> "值域";
            case StandardConstraintCompiler.REGEX -> "正则";
            case StandardConstraintCompiler.DATE_FORMAT -> "日期格式";
            default -> constraintType;
        };
    }

    /**
     * 构造标准质量规则生成器的描述。
     */
    private static String buildDescription(StandardElementDO element, String constraintType) {
        return "由标准数据元「" + StrUtil.blankToDefault(element.getName(), String.valueOf(element.getId()))
                + "」按当前约束物化：" + typeLabel(constraintType);
    }
}
