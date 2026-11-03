package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 多输入排序合并节点（toolCode: sort-merge）。读取所有上游后按字段稳定排序输出。 */
public class SortMergeExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "sort-merge";

    /**
     * 初始化 {@code SortMergeExecutor}及其父类状态。
     */
    public SortMergeExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        readAllUpstreams().values().forEach(rows::addAll);
        JSONArray fields = config().getJSONArray("sortFields");
        if ((fields == null || fields.isEmpty()) && config().getJSONArray("sorts") != null) {
            fields = config().getJSONArray("sorts");
        }
        if (fields == null || fields.isEmpty()) {
            String field = config().getStr("sortField");
            if (StrUtil.isBlank(field)) {
                throw new IllegalStateException("排序合并至少需要一个排序字段");
            }
            fields = new JSONArray();
            fields.add(new JSONObject().set("field", field));
        }
        List<JSONObject> rules = new ArrayList<>();
        for (Object item : fields) {
            rules.add((JSONObject) item);
        }
        rows.sort((left, right) -> compareRows(left, right, rules));
        for (Map<String, Object> row : rows) {
            checkStop();
            pushRow(row);
        }
        logLine(String.format("排序合并完成，共输出 %s 行", writeCount.get()));
    }

    /**
     * 比较数据行。
     */
    private int compareRows(Map<String, Object> left, Map<String, Object> right, List<JSONObject> rules) {
        for (JSONObject rule : rules) {
            String field = rule.getStr("field");
            int cmp = compare(left.get(field), right.get(field));
            if (cmp != 0) {
                String direction = StrUtil.blankToDefault(rule.getStr("direction"), rule.getStr("order"));
                return "DESC".equalsIgnoreCase(direction) ? -cmp : cmp;
            }
        }
        return 0;
    }

    /**
     * 返回当前比较。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compare(Object left, Object right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }
}
