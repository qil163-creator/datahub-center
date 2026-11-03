package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleTestReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleTestRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import jakarta.annotation.Resource;
import tools.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 清洗规则 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class CleanRuleServiceImpl implements CleanRuleService {

    @Resource
    private CleanRuleMapper cleanRuleMapper;

    @Resource
    private CategoryCleanRuleMapper categoryCleanRuleMapper;

    @Resource
    private CleanFunctionRegistry cleanFunctionRegistry;

    /**
     * 创建清洗规则。
     */
    @Override
    public Long createCleanRule(CleanRuleSaveReqVO createReqVO) {
        Map<String, Object> config = validateSaveReq(createReqVO, null);
        CleanRuleDO rule = BeanUtils.toBean(createReqVO, CleanRuleDO.class);
        rule.setConfigJson(JsonUtils.toJsonString(config));
        if (rule.getRuleLevel() == null) {
            rule.setRuleLevel(1);
        }
        if (rule.getStatus() == null) {
            rule.setStatus(CleanRuleDO.STATUS_ENABLE);
        }
        cleanRuleMapper.insert(rule);
        return rule.getId();
    }

    /**
     * 更新清洗规则。
     */
    @Override
    public void updateCleanRule(CleanRuleSaveReqVO updateReqVO) {
        validateRuleExists(updateReqVO.getId());
        Map<String, Object> config = validateSaveReq(updateReqVO, updateReqVO.getId());
        CleanRuleDO rule = BeanUtils.toBean(updateReqVO, CleanRuleDO.class);
        rule.setConfigJson(JsonUtils.toJsonString(config));
        cleanRuleMapper.updateById(rule);
    }

    /**
     * 更新清洗规则状态。
     */
    @Override
    public void updateCleanRuleStatus(Long id, Integer status) {
        validateRuleExists(id);
        cleanRuleMapper.updateById(CleanRuleDO.builder().id(id).status(status).build());
    }

    /**
     * 删除清洗规则。
     */
    @Override
    public void deleteCleanRule(Long id) {
        validateRuleExists(id);
        cleanRuleMapper.deleteById(id);
    }

    /**
     * 获得清洗规则。
     */
    @Override
    public CleanRuleDO getCleanRule(Long id) {
        return cleanRuleMapper.selectById(id);
    }

    /**
     * 获得清洗规则分页。
     */
    @Override
    public PageResult<CleanRuleDO> getCleanRulePage(CleanRulePageReqVO pageReqVO) {
        List<Long> categoryIds = resolveCategoryIds(pageReqVO.getCategoryId());
        if (pageReqVO.getCategoryId() != null && CollUtil.isEmpty(categoryIds)) {
            return PageResult.empty();
        }
        return cleanRuleMapper.selectPage(pageReqVO, categoryIds);
    }

    /**
     * 在线测试清洗规则：对样例数据执行清洗函数并返回结果
     */
    @Override
    public CleanRuleTestRespVO testCleanRule(CleanRuleTestReqVO reqVO) {
        String strategyKey = reqVO.getStrategyKey();
        if (StrUtil.isBlank(strategyKey) && reqVO.getRuleId() != null) {
            CleanRuleDO rule = cleanRuleMapper.selectById(reqVO.getRuleId());
            if (rule == null) {
                throw exception(CLEAN_RULE_NOT_EXISTS);
            }
            strategyKey = rule.getStrategyKey();
        }
        if (StrUtil.isBlank(strategyKey)) {
            throw exception(CLEAN_RULE_NO_STRATEGY);
        }
        CleanFunction function = cleanFunctionRegistry.getFunction(strategyKey);
        if (function == null) {
            throw exception(CLEAN_RULE_STRATEGY_NOT_SUPPORTED, strategyKey);
        }

        Map<String, Object> config;
        List<Map<String, Object>> rows;
        try {
            config = JsonUtils.parseObject(reqVO.getConfigJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            throw exception(CLEAN_RULE_TEST_FAILED, "规则配置须为合法 JSON 对象");
        }
        try {
            rows = JsonUtils.getObjectMapper().readerFor(new TypeReference<List<Map<String, Object>>>() {
                    }).with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(tools.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                    .readValue(reqVO.getRowsJson());
        } catch (Exception ex) {
            throw exception(CLEAN_RULE_TEST_FAILED, "样例数据须为合法 JSON 数组");
        }
        if (config == null) {
            config = new HashMap<>();
        }
        try {
            config = cleanFunctionRegistry.normalizeConfig(strategyKey, config);
        } catch (IllegalArgumentException ex) {
            throw exception(CLEAN_RULE_TEST_FAILED, ex.getMessage());
        }
        if (rows == null) {
            rows = new ArrayList<>();
        }

        CleanRuleTestRespVO respVO = new CleanRuleTestRespVO();
        respVO.setStrategyKey(strategyKey);
        respVO.setBeforeCount(rows.size());
        try {
            List<Map<String, Object>> cleaned = function.apply(rows, config);
            respVO.setAfterCount(cleaned.size());
            respVO.setRows(cleaned.stream().map(row -> {
                Map<String, Object> wire = new java.util.LinkedHashMap<>();
                row.forEach((key, value) -> wire.put(key, toTestWireValue(value)));
                return wire;
            }).toList());
        } catch (IllegalArgumentException ex) {
            throw exception(CLEAN_RULE_TEST_FAILED, ex.getMessage());
        } catch (Exception ex) {
            throw exception(CLEAN_RULE_TEST_FAILED,
                    StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()));
        }
        return respVO;
    }

    /** 试跑响应在 JSON 编码前保留精确数值，生产清洗仍保留数值类型参与运算。 */
    private static Object toTestWireValue(Object value) {
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof java.math.BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), toTestWireValue(item)));
            return result;
        }
        if (value instanceof java.util.Collection<?> values) {
            return values.stream().map(CleanRuleServiceImpl::toTestWireValue).toList();
        }
        if (value instanceof Object[] values) {
            return java.util.Arrays.stream(values).map(CleanRuleServiceImpl::toTestWireValue).toList();
        }
        return value;
    }

    /**
     * 校验清洗规则的保存请求，不满足约束时拒绝当前操作。
     */
    private Map<String, Object> validateSaveReq(CleanRuleSaveReqVO reqVO, Long id) {
        // 校验类目存在
        CategoryCleanRuleDO category = categoryCleanRuleMapper.selectById(reqVO.getCategoryId());
        if (category == null) {
            throw exception(CLEAN_RULE_CATEGORY_NOT_EXISTS);
        }
        // 校验名称唯一
        CleanRuleDO exists = cleanRuleMapper.selectByName(reqVO.getName());
        if (exists != null && !Objects.equals(exists.getId(), id)) {
            throw exception(CLEAN_RULE_NAME_DUPLICATE);
        }
        Map<String, Object> config = reqVO.getConfig();
        if (config == null && id != null) {
            CleanRuleDO current = cleanRuleMapper.selectById(id);
            if (current != null && StrUtil.isNotBlank(current.getStrategyKey())
                    && !Objects.equals(StrUtil.trimToNull(reqVO.getStrategyKey()), current.getStrategyKey())) {
                throw exception(CLEAN_RULE_STRATEGY_NOT_SUPPORTED, "更换清洗函数时必须显式提交新参数");
            }
            try {
                config = current == null || StrUtil.isBlank(current.getConfigJson()) ? Map.of()
                        : JsonUtils.parseObject(current.getConfigJson(), new tools.jackson.core.type.TypeReference<>() {
                });
            } catch (RuntimeException ex) {
                throw exception(CLEAN_RULE_STRATEGY_NOT_SUPPORTED, "已保存的清洗参数格式无效");
            }
        }
        if (config == null) {
            config = Map.of();
        }
        String strategyKey = StrUtil.trimToNull(reqVO.getStrategyKey());
        if (strategyKey == null || !cleanFunctionRegistry.contains(strategyKey)) {
            if (!config.isEmpty()) {
                throw exception(CLEAN_RULE_STRATEGY_NOT_SUPPORTED, strategyKey);
            }
            return Map.of(); // 保留没有可执行函数、仅用于展示的历史规则。
        }
        try {
            Map<String, Object> normalized = cleanFunctionRegistry.normalizeConfig(strategyKey, config);
            if (normalized.containsKey("columns")) {
                throw new IllegalArgumentException("字段绑定由ETL节点提供，不能保存在函数参数中");
            }
            if (JsonUtils.toJsonString(normalized).getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
                throw new IllegalArgumentException("配置超过 64 KiB");
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw exception(CLEAN_RULE_STRATEGY_NOT_SUPPORTED, ex.getMessage());
        }
    }

    /**
     * 校验规则记录存在，不存在时抛出业务异常。
     */
    private void validateRuleExists(Long id) {
        if (id == null || cleanRuleMapper.selectById(id) == null) {
            throw exception(CLEAN_RULE_NOT_EXISTS);
        }
    }

    /**
     * 解析类目编号及其全部子级
     */
    private List<Long> resolveCategoryIds(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        List<CategoryCleanRuleDO> all = categoryCleanRuleMapper.selectList();
        Set<Long> result = new LinkedHashSet<>();
        result.add(categoryId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CategoryCleanRuleDO category : all) {
                if (category.getParentId() != null && result.contains(category.getParentId())
                        && result.add(category.getId())) {
                    changed = true;
                }
            }
        }
        return new ArrayList<>(result);
    }

}
