package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlBufferBudget;

import java.util.*;

/**
 * 数据清洗节点（toolCode: data-clean）
 *
 * 配置（各能力独立开关）：
 * enableFieldRemoval + fieldsToRemove：去除字段（逗号分隔）
 * enableDeduplication + deduplicationStrategy ALL_FIELDS|KEY_FIELDS + keyFields：去重（保留首条）
 * enableNullHandling + nullHandlingStrategy REMOVE|FILL_DEFAULT + defaultValues[{fieldName,defaultValue}]
 * enableFormatting + formatRules[{fieldName,type TRIM|UPPER_CASE|LOWER_CASE|REGEX_REPLACE,pattern,replacement}]
 *
 * @author Li
 */
public class DataCleanExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "data-clean";

    /**
     * 初始化 {@code DataCleanExecutor}及其父类状态。
     */
    public DataCleanExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String executionMode = config().getStr("executionMode", "LOCAL");
        if ("GOVERNANCE".equalsIgnoreCase(executionMode)) {
            executeGovernanceRules();
            return;
        }
        if (!"LOCAL".equalsIgnoreCase(executionMode)) {
            throw new IllegalArgumentException("数据清洗执行模式无效");
        }
        Set<String> fieldsToRemove = new HashSet<>();
        if (config().getBool("enableFieldRemoval", false)) {
            fieldsToRemove.addAll(StrUtil.split(config().getStr("fieldsToRemove", ""), ',', true, true));
        }
        boolean dedup = config().getBool("enableDeduplication", false);
        boolean dedupByKey = "KEY_FIELDS".equalsIgnoreCase(config().getStr("deduplicationStrategy", "ALL_FIELDS"));
        List<String> keyFields = StrUtil.split(config().getStr("keyFields", ""), ',', true, true);
        boolean nullHandling = config().getBool("enableNullHandling", false);
        boolean nullRemove = "REMOVE".equalsIgnoreCase(config().getStr("nullHandlingStrategy", "REMOVE"));
        Map<String, String> defaultValues = parseDefaultValues();
        boolean formatting = config().getBool("enableFormatting", false);
        JSONArray formatRules = config().getJSONArray("formatRules");

        Set<String> seen = dedup ? new HashSet<>() : null;
        long dropped = 0;
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (fieldsToRemove.contains(entry.getKey())) {
                    continue;
                }
                cleaned.put(entry.getKey(), entry.getValue());
            }
            // 空值处理
            if (nullHandling) {
                if (nullRemove) {
                    boolean hasNull = cleaned.values().stream()
                            .anyMatch(value -> value == null || StrUtil.isBlank(String.valueOf(value)));
                    if (hasNull) {
                        dropped++;
                        continue;
                    }
                } else {
                    for (Map.Entry<String, String> entry : defaultValues.entrySet()) {
                        Object value = cleaned.get(entry.getKey());
                        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
                            cleaned.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            // 格式化
            if (formatting && formatRules != null) {
                applyFormatRules(cleaned, formatRules);
            }
            // 去重
            if (dedup) {
                String key = dedupByKey && !keyFields.isEmpty()
                        ? buildKey(cleaned, keyFields)
                        : String.valueOf(cleaned);
                if (!seen.add(key)) {
                    dropped++;
                    continue;
                }
            }
            pushRow(cleaned);
        }
        logLine(String.format("清洗完成，输出 %s 行，丢弃 %s 行", writeCount.get(), dropped));
    }

    /**
     * 使用已经冻结的治理清洗计划处理当前输入数据。
     */
    private void executeGovernanceRules() throws Exception {
        var policy = context.getRowPolicyPort();
        if (policy == null) {
            throw new IllegalStateException("未配置冻结治理清洗计划");
        }
        if (policy.requiresCleanRowSet(node.getNo())) {
            executeGovernanceRowSet(policy);
            return;
        }
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> cleaned = policy.clean(node.getNo(), row);
            if (cleaned == null || !cleaned.keySet().equals(row.keySet())) {
                throw new IllegalStateException("治理字段级清洗必须保持输入行及字段集合不变");
            }
            pushRow(cleaned);
        }
        logLine(String.format("治理清洗完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 全节点一次执行，不能分批去重，否则跨批次的重复键和最新记录会被遗漏。
     */
    private void executeGovernanceRowSet(cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort policy)
            throws Exception {
        // 全节点一次执行，不能分批去重，否则跨批次的重复键和最新记录会被遗漏。
        class RuleBudget implements cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort.CleanRowSetControl,
                AutoCloseable {

            private EtlBufferBudget current;

            private EtlBufferBudget output;

            /**
             * 在规则计算前检查停止请求和资源限制。
             */
            @Override
            public void beforeRule(List<Map<String, Object>> currentRows) {
                checkStop();
                close();
                current = new EtlBufferBudget(context, 100_000);
                output = new EtlBufferBudget(context, 100_000);
                for (var currentRow : currentRows) {
                    checkStop();
                    current.retainRow(currentRow);
                }
            }

            /**
             * 保留输出。
             */
            @Override
            public void retainOutput(Map<String, Object> outputRow) {
                checkStop();
                if (output == null) {
                    throw new IllegalStateException("集合清洗必须先申请转换限额");
                }
                output.retainRow(outputRow);
            }

            /**
             * 返回检查点。
             */
            @Override
            public void checkpoint() {
                checkStop();
            }

            /**
             * 关闭 {@code DataCleanExecutor} 当前持有的资源。
             */
            @Override
            public void close() {
                if (current != null) {
                    current.close();
                }
                if (output != null) {
                    output.close();
                }
            }
        }
        try (var inputBudget = new EtlBufferBudget(context, 100_000);
             var finalBudget = new EtlBufferBudget(context, 100_000);
             var ruleBudget = new RuleBudget()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> fields = null;
            Map<String, Object> row;
            while ((row = readNextRow()) != null) {
                if (fields == null) {
                    fields = Set.copyOf(row.keySet());
                }
                if (!fields.equals(row.keySet())) {
                    throw new IllegalStateException("治理集合清洗输入字段集合必须一致");
                }
                inputBudget.retainRow(row);
                rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
            }
            checkStop();
            List<Map<String, Object>> cleaned = policy.cleanRows(node.getNo(), Collections.unmodifiableList(rows),
                    ruleBudget);
            checkStop();
            if (cleaned == null || cleaned.size() > rows.size()) {
                throw new IllegalStateException("治理集合清洗不能增加输入行数");
            }
            ruleBudget.close();
            // 在发送第一行前校验整个结果，防止非法清洗结果产生部分下游输出。
            for (Map<String, Object> output : cleaned) {
                checkStop();
                if (output == null || !output.keySet().equals(fields)) {
                    throw new IllegalStateException("治理集合清洗必须保持字段集合不变");
                }
                finalBudget.retainRow(output);
            }
            for (Map<String, Object> output : cleaned) {
                pushRow(output);
            }
            logLine(String.format("治理集合清洗完成，输出 %s 行，丢弃 %s 行", writeCount.get(),
                    readCount.get() - writeCount.get()));
        }
    }

    /**
     * 解析默认值集合。
     */
    private Map<String, String> parseDefaultValues() {
        Map<String, String> defaults = new HashMap<>();
        JSONArray array = config().getJSONArray("defaultValues");
        if (array != null) {
            for (Object item : array) {
                JSONObject json = (JSONObject) item;
                if (StrUtil.isNotBlank(json.getStr("fieldName"))) {
                    defaults.put(json.getStr("fieldName"), json.getStr("defaultValue", ""));
                }
            }
        }
        return defaults;
    }

    /**
     * 按节点配置的格式规则转换指定字段。
     */
    private void applyFormatRules(Map<String, Object> row, JSONArray rules) {
        for (Object item : rules) {
            JSONObject rule = (JSONObject) item;
            String fieldName = rule.getStr("fieldName");
            if (StrUtil.isBlank(fieldName) || !row.containsKey(fieldName) || row.get(fieldName) == null) {
                continue;
            }
            String text = String.valueOf(row.get(fieldName));
            String type = StrUtil.nullToEmpty(rule.getStr("type")).toUpperCase();
            String formatted = switch (type) {
                case "TRIM" -> text.trim();
                case "UPPER_CASE" -> text.toUpperCase();
                case "LOWER_CASE" -> text.toLowerCase();
                case "REGEX_REPLACE" -> StrUtil.isBlank(rule.getStr("pattern")) ? text
                        : text.replaceAll(rule.getStr("pattern"), StrUtil.nullToEmpty(rule.getStr("replacement")));
                default -> text;
            };
            row.put(fieldName, formatted);
        }
    }

    /**
     * 构建键。
     */
    private String buildKey(Map<String, Object> row, List<String> keyFields) {
        StringBuilder key = new StringBuilder();
        for (String field : keyFields) {
            key.append(row.get(field)).append('\u0001');
        }
        return key.toString();
    }

}
