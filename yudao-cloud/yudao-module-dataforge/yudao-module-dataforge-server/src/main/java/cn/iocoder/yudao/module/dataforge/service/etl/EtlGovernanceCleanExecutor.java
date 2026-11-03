package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.module.governance.api.etl.dto.CleanRulePlanRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** DataForge 薄适配层：执行 Governance 已有字段清洗函数，并严格限制数据行边界。 */
@Component
public class EtlGovernanceCleanExecutor {

    @Resource
    private CleanFunctionRegistry cleanFunctionRegistry;

    /**
     * 创建ETL治理清洗实例。
     */
    public EtlGovernanceCleanExecutor() {
    }

    /**
     * 创建ETL治理清洗实例。
     *
     * @param cleanFunctionRegistry 清洗函数注册表
     */
    EtlGovernanceCleanExecutor(CleanFunctionRegistry cleanFunctionRegistry) {
        this.cleanFunctionRegistry = cleanFunctionRegistry;
    }

    /**
     * 判断需要行集合条件是否成立。
     *
     * @param rules 规则
     * @return 处理是否成功
     */
    public boolean requiresRowSet(List<CleanRulePlanRespDTO.Rule> rules) {
        return rules.stream().anyMatch(rule -> !cleanFunctionRegistry.supportsRowTransform(rule.getStrategyKey()));
    }

    /**
     * 应用ETL治理清洗处理规则。
     *
     * @param rules 规则
     * @param input 输入数据
     * @return 处理后的键值映射
     */
    public Map<String, Object> apply(List<CleanRulePlanRespDTO.Rule> rules, Map<String, Object> input) {
        if (rules == null || rules.isEmpty() || input == null) {
            throw new IllegalArgumentException("治理清洗计划或输入行无效");
        }
        Map<String, Object> original = new LinkedHashMap<>(input);
        Map<String, Object> current = new LinkedHashMap<>(input);
        for (CleanRulePlanRespDTO.Rule rule : rules) {
            if (rule == null || rule.getConfig() == null) {
                throw new IllegalStateException("治理清洗计划配置缺失");
            }
            if (!cleanFunctionRegistry.supportsRowTransform(rule.getStrategyKey())) {
                throw new IllegalStateException("冻结治理清洗策略不支持逐行转换，请使用行集清洗路径");
            }
            CleanFunction function = cleanFunctionRegistry.getFunction(rule.getStrategyKey());
            if (function == null) {
                throw new IllegalStateException("冻结治理清洗函数当前不可用");
            }
            Map<String, Object> config = cleanFunctionRegistry.normalizeConfig(rule.getStrategyKey(), rule.getConfig());
            Object rawColumns = config.get("columns");
            String field = rawColumns instanceof List<?> columns && columns.size() == 1
                    ? String.valueOf(columns.getFirst()) : "";
            if (StrUtil.isBlank(field) || !current.containsKey(field)) {
                throw new IllegalStateException("治理清洗目标字段不在输入行中");
            }
            List<Map<String, Object>> transformed = function.apply(List.of(new LinkedHashMap<>(current)),
                    config);
            if (transformed == null || transformed.size() != 1 || transformed.getFirst() == null
                    || !transformed.getFirst().keySet().equals(current.keySet())) {
                throw new IllegalStateException("治理清洗函数必须保持一进一出及原字段集合");
            }
            current = new LinkedHashMap<>(transformed.getFirst());
            if (!input.equals(original)) {
                throw new IllegalStateException("治理清洗函数修改了原始输入行");
            }
        }
        return current;
    }

    /** 行集路径对完整输入按冻结顺序执行，禁止增行、扩列或修改上游输入。 */
    public List<Map<String, Object>> applyRows(List<CleanRulePlanRespDTO.Rule> rules, List<Map<String, Object>> input) {
        return applyRows(rules, input, null);
    }

    /** 运行时控制由 ETL 组件提供，质量组件在每次生成或保留结果之前执行预算与取消检查。 */
    public List<Map<String, Object>> applyRows(List<CleanRulePlanRespDTO.Rule> rules, List<Map<String, Object>> input,
            EtlRowPolicyPort.CleanRowSetControl control) {
        if (rules == null || rules.isEmpty() || input == null) {
            throw new IllegalArgumentException("治理清洗计划或输入行集无效");
        }
        if (input.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("治理清洗输入不能包含空行");
        }
        List<Map<String, Object>> current = input;
        Set<String> schema = current.isEmpty() ? Set.of() : Set.copyOf(current.getFirst().keySet());
        if (current.stream().anyMatch(row -> !row.keySet().equals(schema))) {
            throw new IllegalStateException("治理清洗输入行的字段集合不一致");
        }
        boolean copied = false;
        for (var rule : rules) {
            if (control != null) {
                control.checkpoint();
                control.beforeRule(current);
            }
            if (!copied) {
                current = copyRows(current);
                copied = true;
            }
            if (rule == null || rule.getConfig() == null) {
                throw new IllegalStateException("治理清洗计划配置缺失");
            }
            CleanFunction function = cleanFunctionRegistry.getFunction(rule.getStrategyKey());
            if (function == null) {
                throw new IllegalStateException("冻结治理清洗函数当前不可用");
            }
            Map<String, Object> config = cleanFunctionRegistry.normalizeConfig(rule.getStrategyKey(), rule.getConfig());
            Object columns = config.get("columns");
            if (cleanFunctionRegistry.getColumnBinding(rule.getStrategyKey()) == CleanFunction.ColumnBinding.SINGLE
                    && (!(columns instanceof List<?> fields) || fields.size() != 1)) {
                throw new IllegalStateException("逐行清洗函数必须绑定一个字段");
            }
            if (!current.isEmpty()) {
                validateFields(rule.getStrategyKey(), config, schema);
            }
            int before = current.size();
            List<Map<String, Object>> transformed = cleanFunctionRegistry.applyControlled(rule.getStrategyKey(),
                    current, config,
                    row -> {
                        if (control != null) {
                            control.retainOutput(row);
                        }
                    },
                    () -> {
                        if (control != null) {
                            control.checkpoint();
                        }
                    });
            if (transformed == null || transformed.size() > before
                    || cleanFunctionRegistry.supportsRowTransform(rule.getStrategyKey()) && transformed.size() != before
                    || transformed.stream().anyMatch(row -> row == null || !row.keySet().equals(schema))) {
                throw new IllegalStateException("治理清洗函数不得增行或改变字段集合");
            }
            current = transformed;
        }
        if (control != null) {
            control.checkpoint();
        }
        return current;
    }

    /**
     * 校验字段。
     *
     * @param strategy 策略
     * @param config 处理配置
     * @param schema 结构
     */
    private void validateFields(String strategy, Map<String, Object> config, Set<String> schema) {
        Object rawColumns = config.get("columns");
        boolean wholeRow = cleanFunctionRegistry.getColumnBinding(strategy) == CleanFunction.ColumnBinding.ALL;
        if (wholeRow && rawColumns == null) {
            rawColumns = List.of();
        }
        if (!(rawColumns instanceof List<?> columns) || wholeRow && !columns.isEmpty()
                || !wholeRow && columns.isEmpty() || columns.size() > 32
                || columns.stream().anyMatch(column -> !(column instanceof String field) || field.isBlank()
                || !schema.contains(field)) || new HashSet<>(columns).size() != columns.size()) {
            throw new IllegalStateException("治理清洗目标字段不在输入行中或重复");
        }
        if (!schema.containsAll(cleanFunctionRegistry.getReferencedColumns(strategy, config))) {
            throw new IllegalStateException("治理清洗排序、优先或更新字段不在输入行中");
        }
    }

    /**
     * 复制行。
     *
     * @param rows 待处理的行数据
     * @return 复制行结果集合
     */
    private static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (var row : rows) {
            if (row == null) {
                throw new IllegalArgumentException("治理清洗输入不能包含空行");
            }
            result.add(new LinkedHashMap<>(row));
        }
        return result;
    }
}
