package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlBufferBudget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 多输入 JOIN 节点（toolCode: multi-join），按顺序把多个上游关联到同一结果集。 */
public class MultiJoinExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "multi-join";

    /**
     * 初始化 {@code MultiJoinExecutor}及其父类状态。
     */
    public MultiJoinExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        Map<String, List<Map<String, Object>>> upstreams = readAllUpstreams();
        if (upstreams.size() < 2) {
            throw new IllegalStateException("多 JOIN 至少需要两个数据上游");
        }
        List<JSONObject> rules = resolveRules();
        String joinType = StrUtil.blankToDefault(config().getStr("joinType"), "INNER").toUpperCase();
        int maxOutputRows = Math.max(config().getInt("maxOutputRows", 1_000_000), 1);
        List<Map<String, Object>> current = new ArrayList<>(upstreams.values().iterator().next());
        EtlBufferBudget currentBudget = new EtlBufferBudget(context, maxOutputRows);
        try {
            int index = 1;
            for (List<Map<String, Object>> rightRows : upstreams.values().stream().skip(1).toList()) {
                checkStop();
                JSONObject rule = rules.get(Math.min(index - 1, rules.size() - 1));
                String leftField = rule.getStr("leftField", "id");
                String rightField = rule.getStr("rightField", leftField);
                Map<String, List<Map<String, Object>>> indexRows = new LinkedHashMap<>();
                java.util.LinkedHashSet<String> rightFields = new java.util.LinkedHashSet<>();
                EtlBufferBudget nextBudget = new EtlBufferBudget(context, maxOutputRows);
                boolean retained = false;
                try (EtlBufferBudget indexBudget = new EtlBufferBudget(context, maxOutputRows)) {
                    for (Map<String, Object> row : rightRows) {
                        checkStop();
                        for (String field : row.keySet()) {
                            if (!rightFields.contains(field)) {
                                indexBudget.reserve(64L + field.length() * 2L);
                                rightFields.add(field);
                            }
                        }
                        String key = key(row.get(rightField));
                        indexBudget.reserve(indexRows.containsKey(key) ? 16 : 128L + key.length() * 2L);
                        indexRows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
                    }
                    List<Map<String, Object>> next = new ArrayList<>();
                    for (Map<String, Object> left : current) {
                        checkStop();
                        List<Map<String, Object>> matches = indexRows.getOrDefault(key(left.get(leftField)), List.of());
                        if (matches.isEmpty()) {
                            if ("LEFT".equals(joinType)) {
                                Map<String, Object> unmatched = new LinkedHashMap<>(left);
                                rightFields.forEach(field -> unmatched.putIfAbsent(field, null));
                                nextBudget.retainRow(unmatched);
                                next.add(unmatched);
                            }
                            continue;
                        }
                        for (Map<String, Object> right : matches) {
                            checkStop();
                            Map<String, Object> merged = new LinkedHashMap<>(left);
                            right.forEach(merged::putIfAbsent);
                            nextBudget.retainRow(merged);
                            next.add(merged);
                        }
                    }
                    current = next;
                    currentBudget.close();
                    currentBudget = nextBudget;
                    retained = true;
                    index++;
                } finally {
                    if (!retained) {
                        nextBudget.close();
                    }
                }
            }
            for (Map<String, Object> row : current) {
                checkStop();
                pushRow(row);
            }
            logLine(String.format("多 JOIN 完成，共输出 %s 行", writeCount.get()));
        } finally {
            currentBudget.close();
        }
    }

    /**
     * 解析规则集合。
     */
    private List<JSONObject> resolveRules() {
        JSONArray array = config().getJSONArray("joinConditions");
        List<JSONObject> rules = new ArrayList<>();
        if (array != null) {
            for (Object item : array) {
                rules.add((JSONObject) item);
            }
        }
        rules.removeIf(rule -> StrUtil.isBlank(rule.getStr("leftField"))
                || StrUtil.isBlank(rule.getStr("rightField")));
        if (rules.isEmpty()) {
            String field = config().getStr("keyField");
            if (StrUtil.isBlank(field)) {
                throw new IllegalStateException("多 JOIN 缺少关联字段");
            }
            rules.add(new JSONObject().set("leftField", field).set("rightField", field));
        }
        return rules;
    }

    /**
     * 返回键。
     */
    private String key(Object value) {
        return value == null ? "\u0000" : String.valueOf(value);
    }
}
