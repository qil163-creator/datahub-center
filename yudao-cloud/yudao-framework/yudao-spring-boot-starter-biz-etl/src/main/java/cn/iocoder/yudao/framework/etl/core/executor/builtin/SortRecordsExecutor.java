package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 排序记录节点（toolCode: sort-records）
 *
 * 配置：sorts [{field 排序字段, order ASC|DESC}]，按顺序多级排序；
 * 两侧均为数值时按数值比较，否则按字符串比较，空值排在最后。
 * 数据在内存中缓冲后整体排序，超大数据量请控制上游行数
 *
 * @author Li
 */
public class SortRecordsExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "sort-records";

    /**
     * 初始化 {@code SortRecordsExecutor}及其父类状态。
     */
    public SortRecordsExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        JSONArray sorts = config().getJSONArray("sorts");
        if (sorts == null || sorts.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：排序字段", node.getName()));
        }
        Comparator<Map<String, Object>> comparator = null;
        for (Object item : sorts) {
            JSONObject sort = (JSONObject) item;
            String field = sort.getStr("field");
            if (StrUtil.isBlank(field)) {
                continue;
            }
            boolean desc = "DESC".equalsIgnoreCase(sort.getStr("order", "ASC"));
            Comparator<Map<String, Object>> current =
                    Comparator.comparing(row -> row.get(field), SortRecordsExecutor::compareValues);
            if (desc) {
                current = current.reversed();
            }
            comparator = comparator == null ? current : comparator.thenComparing(current);
        }
        if (comparator == null) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：排序字段", node.getName()));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            rows.add(row);
        }
        rows.sort(comparator);
        for (Map<String, Object> sorted : rows) {
            checkStop();
            pushRow(sorted);
        }
        logLine(String.format("排序完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 空值最后；两侧均为数值按数值比较，否则按字符串比较
     */
    private static int compareValues(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        String leftText = String.valueOf(left);
        String rightText = String.valueOf(right);
        if (NumberUtil.isNumber(leftText) && NumberUtil.isNumber(rightText)) {
            return new BigDecimal(leftText).compareTo(new BigDecimal(rightText));
        }
        return leftText.compareTo(rightText);
    }

}
