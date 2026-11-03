package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePreviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePreviewRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDimensionDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.AuditRuleMapper;
import jakarta.annotation.Resource;
import tools.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 稽查规则 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class AuditRuleServiceImpl implements AuditRuleService {

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private AuditRuleMapper auditRuleMapper;

    @Resource
    private AuditRuleDimensionService auditRuleDimensionService;

    @Resource
    private AuditSqlRegistry auditSqlRegistry;

    /**
     * 创建稽查规则。
     */
    @Override
    public Long createAuditRule(AuditRuleSaveReqVO createReqVO) {
        validateSaveReq(createReqVO, null);
        AuditRuleDO rule = BeanUtils.toBean(createReqVO, AuditRuleDO.class);
        if (rule.getRuleLevel() == null) {
            rule.setRuleLevel(AuditRuleDO.LEVEL_FIELD);
        }
        if (rule.getStatus() == null) {
            rule.setStatus(AuditRuleDO.STATUS_ENABLE);
        }
        auditRuleMapper.insert(rule);
        return rule.getId();
    }

    /**
     * 更新稽查规则。
     */
    @Override
    public void updateAuditRule(AuditRuleSaveReqVO updateReqVO) {
        validateRuleExists(updateReqVO.getId());
        validateSaveReq(updateReqVO, updateReqVO.getId());
        auditRuleMapper.updateById(BeanUtils.toBean(updateReqVO, AuditRuleDO.class));
    }

    /**
     * 更新稽查规则状态。
     */
    @Override
    public void updateAuditRuleStatus(Long id, Integer status) {
        validateRuleExists(id);
        auditRuleMapper.updateById(AuditRuleDO.builder().id(id).status(status).build());
    }

    /**
     * 删除稽查规则。
     */
    @Override
    public void deleteAuditRule(Long id) {
        validateRuleExists(id);
        auditRuleMapper.deleteById(id);
    }

    /**
     * 获得稽查规则。
     */
    @Override
    public AuditRuleDO getAuditRule(Long id) {
        return auditRuleMapper.selectById(id);
    }

    /**
     * 获得稽查规则分页。
     */
    @Override
    public PageResult<AuditRuleDO> getAuditRulePage(AuditRulePageReqVO pageReqVO) {
        Long dimensionId = pageReqVO.getDimensionId();
        if (dimensionId != null && auditRuleDimensionService.getDimension(dimensionId) == null) {
            return PageResult.empty();
        }
        return auditRuleMapper.selectPage(pageReqVO, dimensionId == null ? null : List.of(dimensionId));
    }

    /**
     * 按策略生成稽查 SQL 预览（错误统计 + 错误明细）
     */
    @Override
    public AuditRulePreviewRespVO previewSql(AuditRulePreviewReqVO reqVO) {
        String strategyKey = reqVO.getStrategyKey();
        if (StrUtil.isBlank(strategyKey) && reqVO.getRuleId() != null) {
            AuditRuleDO rule = auditRuleMapper.selectById(reqVO.getRuleId());
            if (rule == null) {
                throw exception(AUDIT_RULE_NOT_EXISTS);
            }
            strategyKey = rule.getStrategyKey();
        }
        if (StrUtil.isBlank(strategyKey)) {
            throw exception(AUDIT_RULE_NO_STRATEGY);
        }
        AuditSqlGenerator generator = auditSqlRegistry.getGenerator(strategyKey);
        if (generator == null) {
            throw exception(AUDIT_RULE_STRATEGY_NOT_SUPPORTED, strategyKey);
        }
        if (generator.getExecutionMode() == AuditSqlGenerator.ExecutionMode.METADATA
                || "RECORD_COMPLETENESS_VALIDATION".equals(strategyKey)) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, "该策略需要真实表结构，请在质量任务中选择已授权的稽查对象后抽查");
        }

        AuditRuleContext context = new AuditRuleContext(dbSwitchRuntime.products());
        try {
            context.setProductType(ProductTypeEnum.valueOf(reqVO.getProductType().trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, "不支持的数据库产品类型：" + reqVO.getProductType());
        }
        context.setTableName(reqVO.getTableName().trim());
        context.setColumn(StrUtil.trimToNull(reqVO.getColumn()));
        if (StrUtil.isNotBlank(reqVO.getColumns())) {
            List<String> columns = new ArrayList<>();
            Arrays.stream(reqVO.getColumns().split(",")).map(String::trim)
                    .filter(StrUtil::isNotBlank).forEach(columns::add);
            context.setColumns(columns);
        }
        context.setWhereClause(StrUtil.trimToNull(reqVO.getWhereClause()));
        context.setConfig(parseConfig(reqVO.getConfigJson()));

        AuditRulePreviewRespVO respVO = new AuditRulePreviewRespVO();
        respVO.setStrategyKey(strategyKey);
        try {
            respVO.setCountSql(generator.buildCountSql(context));
            respVO.setErrorDataSql(generator.buildErrorDataSql(context, 100));
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, ex.getMessage());
        }
        return respVO;
    }

    /**
     * 解析审核规则的配置。
     */
    private Map<String, Object> parseConfig(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> config = JsonUtils.parseObject(configJson, new TypeReference<Map<String, Object>>() {
            });
            return config == null ? new HashMap<>() : config;
        } catch (Exception ex) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, "规则配置须为合法 JSON 对象");
        }
    }

    /**
     * 校验审核规则的保存请求，不满足约束时拒绝当前操作。
     */
    private void validateSaveReq(AuditRuleSaveReqVO reqVO, Long id) {
        // 稽查维度仅从独立维度表读取，数据质量业务类目不能充当规则维度。
        AuditRuleDimensionDO dimension = auditRuleDimensionService.getDimension(reqVO.getDimensionId());
        if (dimension == null) {
            throw exception(AUDIT_RULE_DIMENSION_NOT_EXISTS);
        }
        // 校验名称唯一
        AuditRuleDO exists = auditRuleMapper.selectByName(reqVO.getName());
        if (exists != null && !Objects.equals(exists.getId(), id)) {
            throw exception(AUDIT_RULE_NAME_DUPLICATE);
        }
        // 校验策略标识已接入引擎
        if (StrUtil.isNotBlank(reqVO.getStrategyKey()) && !auditSqlRegistry.contains(reqVO.getStrategyKey())) {
            throw exception(AUDIT_RULE_STRATEGY_NOT_SUPPORTED, reqVO.getStrategyKey());
        }
    }

    /**
     * 校验规则记录存在，不存在时抛出业务异常。
     */
    private void validateRuleExists(Long id) {
        if (id == null || auditRuleMapper.selectById(id) == null) {
            throw exception(AUDIT_RULE_NOT_EXISTS);
        }
    }

}
