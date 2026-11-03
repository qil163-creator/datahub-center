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
 * 数据合并节点（toolCode: data-merge）
 *
 * 配置：
 * mergeStrategy UNION（纵向联合，字段对齐缺失补 null）| APPEND（按上游顺序追加）| JOIN（两源关联）
 * joinType INNER|LEFT|RIGHT（JOIN 时）
 * joinConditions [{leftField, rightField}]（JOIN 时，左=第一个上游 右=第二个上游）
 * addSourceTag + sourceTagField：追加来源标记字段（UNION/APPEND）
 * removeDuplicates：全行去重
 *
 * 输入受流程预算约束，结果逐行输出，索引及去重键使用共享中间缓存预算。
 *
 * @author Li
 */
public class DataMergeExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "data-merge";

    /**
     * 初始化 {@code DataMergeExecutor}及其父类状态。
     */
    public DataMergeExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String strategy = StrUtil.nullToEmpty(config().getStr("mergeStrategy", "UNION")).toUpperCase();
        Map<String, List<Map<String, Object>>> upstreamData = readAllUpstreams();
        logLine(String.format("已接收 %s 个上游，共 %s 行", upstreamData.size(), readCount.get()));
        Set<String> seen = config().getBool("removeDuplicates", false) ? new HashSet<>() : null;
        try (EtlBufferBudget budget = new EtlBufferBudget(context, Long.MAX_VALUE)) {
            RowOutput output = row -> {
                checkStop();
                budget.accountCandidate();
                if (seen != null) {
                    String key = String.valueOf(row);
                    if (seen.contains(key)) {
                        return;
                    }
                    budget.reserve(64L + key.length() * 2L);
                    seen.add(key);
                }
                pushRow(row);
            };
            switch (strategy) {
                case "UNION", "APPEND" -> unionRows(upstreamData, output, budget);
                case "JOIN" -> joinRows(upstreamData, output, budget);
                default -> throw new IllegalStateException(String.format("不支持的合并策略【%s】", strategy));
            }
        }
        logLine(String.format("合并完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 纵向联合：字段取全部上游的并集，缺失补 null，可选来源标记
     */
    private void unionRows(Map<String, List<Map<String, Object>>> upstreamData, RowOutput output,
            EtlBufferBudget budget) throws Exception {
        boolean addSourceTag = config().getBool("addSourceTag", false);
        String sourceTagField = StrUtil.blankToDefault(config().getStr("sourceTagField"), "_source");
        // 字段并集（保持出现顺序）
        Set<String> allFields = new LinkedHashSet<>();
        for (var rows : upstreamData.values()) {
            for (var row : rows) {
                checkStop();
                retainFields(allFields, row, budget);
            }
        }
        for (var upstream : upstreamData.entrySet()) {
            String sourceName = context.getDag().getNode(upstream.getKey()).getName();
            for (Map<String, Object> row : upstream.getValue()) {
                checkStop();
                Map<String, Object> aligned = new LinkedHashMap<>();
                for (String field : allFields) {
                    aligned.put(field, row.get(field));
                }
                if (addSourceTag) {
                    aligned.put(sourceTagField, sourceName);
                }
                output.accept(aligned);
            }
        }
    }

    /**
     * 两源哈希关联：左=第一个上游，右=第二个上游；RIGHT 通过交换左右转为 LEFT 处理
     */
    private void joinRows(Map<String, List<Map<String, Object>>> upstreamData, RowOutput output,
            EtlBufferBudget budget) throws Exception {
        if (upstreamData.size() != 2) {
            throw new IllegalStateException("JOIN 策略当前仅支持两个数据上游");
        }
        List<JoinCondition> conditions = parseJoinConditions();
        if (conditions.isEmpty()) {
            throw new IllegalStateException("JOIN 策略至少需要一条关联条件");
        }
        String joinType = StrUtil.nullToEmpty(config().getStr("joinType", "INNER")).toUpperCase();
        Iterator<List<Map<String, Object>>> iterator = upstreamData.values().iterator();
        List<Map<String, Object>> left = iterator.next();
        List<Map<String, Object>> right = iterator.next();
        boolean rightJoin = "RIGHT".equals(joinType);
        if (rightJoin) {
            List<Map<String, Object>> tmp = left;
            left = right;
            right = tmp;
        }
        boolean outer = rightJoin || "LEFT".equals(joinType);

        // 右表按关联键建哈希索引
        Map<String, List<Map<String, Object>>> rightIndex = new HashMap<>();
        for (Map<String, Object> row : right) {
            checkStop();
            String key = buildJoinKey(row, conditions, rightJoin);
            budget.reserve(rightIndex.containsKey(key) ? 16 : 128L + key.length() * 2L);
            rightIndex.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(row);
        }
        Set<String> rightFields = new LinkedHashSet<>();
        for (var row : right) {
            checkStop();
            retainFields(rightFields, row, budget);
        }

        for (Map<String, Object> leftRow : left) {
            checkStop();
            List<Map<String, Object>> matches =
                    rightIndex.get(buildJoinKey(leftRow, conditions, !rightJoin));
            if (matches == null || matches.isEmpty()) {
                if (outer) {
                    Map<String, Object> joined = new LinkedHashMap<>(leftRow);
                    rightFields.forEach(field -> joined.putIfAbsent(field, null));
                    output.accept(joined);
                }
                continue;
            }
            for (Map<String, Object> rightRow : matches) {
                checkStop();
                Map<String, Object> joined = new LinkedHashMap<>(leftRow);
                rightRow.forEach(joined::putIfAbsent);
                output.accept(joined);
            }
        }
    }

    @FunctionalInterface
    private interface RowOutput {

        /**
         * 接收当前输入并更新当前处理器的状态。
         */
        void accept(Map<String, Object> row) throws Exception;
    }

    /**
     * 保留字段集合。
     */
    private void retainFields(Set<String> fields, Map<String, Object> row, EtlBufferBudget budget) {
        for (String field : row.keySet()) {
            if (!fields.contains(field)) {
                budget.reserve(64L + field.length() * 2L);
                fields.add(field);
            }
        }
    }

    private record JoinCondition(String leftField, String rightField) {

    }

    /**
     * 解析连接条件集合。
     */
    private List<JoinCondition> parseJoinConditions() {
        List<JoinCondition> conditions = new ArrayList<>();
        JSONArray array = config().getJSONArray("joinConditions");
        if (array != null) {
            for (Object item : array) {
                JSONObject json = (JSONObject) item;
                if (StrUtil.isNotBlank(json.getStr("leftField"))
                        && StrUtil.isNotBlank(json.getStr("rightField"))) {
                    conditions.add(new JoinCondition(json.getStr("leftField"), json.getStr("rightField")));
                }
            }
        }
        return conditions;
    }

    /**
     * @param leftSide 当前行属于关联条件的左侧还是右侧
     */
    private String buildJoinKey(Map<String, Object> row, List<JoinCondition> conditions, boolean leftSide) {
        StringBuilder key = new StringBuilder();
        for (JoinCondition condition : conditions) {
            Object value = row.get(leftSide ? condition.leftField : condition.rightField);
            key.append(value).append('\u0001');
        }
        return key.toString();
    }

}
