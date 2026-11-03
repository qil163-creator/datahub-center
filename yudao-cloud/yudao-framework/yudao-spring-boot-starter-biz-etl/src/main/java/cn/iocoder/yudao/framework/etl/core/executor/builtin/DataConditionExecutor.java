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
import java.util.List;
import java.util.Map;

/**
 * 条件判断节点（toolCode: data-condition）
 *
 * 配置 branches: [{id, name, type IF|ELSE, fieldName, operator, value}]，
 * 每行数据按分支顺序判断，命中后仅推送到该分支输出端口（连线的 outputPort=分支 id）；
 * unmatchedPolicy DROP|ERROR：无命中分支时丢弃或报错
 *
 * @author Li
 */
public class DataConditionExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "data-condition";

    /**
     * 初始化 {@code DataConditionExecutor}及其父类状态。
     */
    public DataConditionExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    private record Branch(String id, String name, boolean elseBranch,
                          String fieldName, String operator, String value) {

    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        List<Branch> branches = parseBranches();
        if (branches.isEmpty()) {
            throw new IllegalStateException(String.format("节点【%s】未配置分支", node.getName()));
        }
        boolean errorOnUnmatched = "ERROR".equalsIgnoreCase(config().getStr("unmatchedPolicy", "DROP"));
        long dropped = 0;
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Branch matched = null;
            for (Branch branch : branches) {
                if (branch.elseBranch || evaluate(row.get(branch.fieldName), branch)) {
                    matched = branch;
                    break;
                }
            }
            if (matched == null) {
                if (errorOnUnmatched) {
                    throw new IllegalStateException("存在未命中任何分支的数据行");
                }
                dropped++;
                continue;
            }
            pushRow(row, matched.id);
        }
        logLine(String.format("条件分流完成，输出 %s 行，丢弃 %s 行", writeCount.get(), dropped));
    }

    /**
     * 解析分支集合。
     */
    private List<Branch> parseBranches() {
        List<Branch> branches = new ArrayList<>();
        JSONArray array = config().getJSONArray("branches");
        if (array == null) {
            return branches;
        }
        for (Object item : array) {
            JSONObject json = (JSONObject) item;
            if (StrUtil.isBlank(json.getStr("id"))) {
                continue;
            }
            boolean elseBranch = "ELSE".equalsIgnoreCase(json.getStr("type", "IF"));
            branches.add(new Branch(json.getStr("id"), json.getStr("name"), elseBranch,
                    json.getStr("fieldName"), StrUtil.nullToEmpty(json.getStr("operator")).toUpperCase(),
                    json.getStr("value")));
        }
        return branches;
    }

    /**
     * 根据当前表达式或规则计算输入值的结果。
     */
    private boolean evaluate(Object cell, Branch branch) {
        String text = cell == null ? null : String.valueOf(cell);
        return switch (branch.operator) {
            case "IS_NULL" -> cell == null || StrUtil.isEmpty(text);
            case "NOT_NULL" -> cell != null && StrUtil.isNotEmpty(text);
            case "EQ" -> compare(text, branch.value) == 0;
            case "NE" -> compare(text, branch.value) != 0;
            case "GT" -> compare(text, branch.value) > 0;
            case "GTE" -> compare(text, branch.value) >= 0;
            case "LT" -> compare(text, branch.value) < 0;
            case "LTE" -> compare(text, branch.value) <= 0;
            case "CONTAINS" -> text != null && branch.value != null && text.contains(branch.value);
            case "NOT_CONTAINS" -> text == null || branch.value == null || !text.contains(branch.value);
            default -> throw new IllegalStateException(
                    String.format("不支持的条件操作符【%s】", branch.operator));
        };
    }

    /**
     * 返回当前比较。
     */
    private int compare(String text, String expected) {
        if (text != null && expected != null
                && NumberUtil.isNumber(text) && NumberUtil.isNumber(expected)) {
            return new BigDecimal(text).compareTo(new BigDecimal(expected));
        }
        return StrUtil.compare(text, expected, false);
    }

}
