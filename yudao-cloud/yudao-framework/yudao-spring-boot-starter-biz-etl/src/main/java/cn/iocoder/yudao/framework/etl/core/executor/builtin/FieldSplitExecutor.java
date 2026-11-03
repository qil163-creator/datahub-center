package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlTokenSplit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段拆分节点（toolCode: field-split）：把一个字段按分隔符/正则拆分为多个新字段
 *
 * 配置：sourceField 需拆分的字段；splitType DELIMITER|REGEX；delimiter 分隔符 / pattern 正则；
 * wrapper 包裹字符（可选，去除每段两端的包裹字符）；keepSource 保留原字段（默认否）；
 * outputs [{name 字段名称, trimType NONE|BOTH|LEFT|RIGHT 去除空格类型}] 按顺序对应拆分结果
 *
 * @author Li
 */
public class FieldSplitExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "field-split";

    /**
     * 初始化 {@code FieldSplitExecutor}及其父类状态。
     */
    public FieldSplitExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String sourceField = requireConfig("sourceField", "需拆分的字段");
        boolean regexMode = "REGEX".equalsIgnoreCase(config().getStr("splitType", "DELIMITER"));
        String separator = regexMode
                ? requireConfig("pattern", "正则表达式")
                : requireConfig("delimiter", "分隔符");
        String wrapper = config().getStr("wrapper", "");
        boolean keepSource = config().getBool("keepSource", false);
        List<JSONObject> outputs = resolveOutputs();
        var split = EtlTokenSplit.compile(separator, regexMode, wrapper);

        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> result = new LinkedHashMap<>(row);
            String text = row.get(sourceField) == null ? "" : String.valueOf(row.get(sourceField));
            String[] tokens = split.split(text);
            for (int i = 0; i < outputs.size(); i++) {
                JSONObject output = outputs.get(i);
                String token = i < tokens.length ? tokens[i] : null;
                result.put(output.getStr("name"), split.clean(token, output.getStr("trimType")));
            }
            if (!keepSource) {
                result.remove(sourceField);
            }
            pushRow(result);
        }
        logLine(String.format("字段拆分完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 解析输出端集合。
     */
    private List<JSONObject> resolveOutputs() {
        List<JSONObject> outputs = new ArrayList<>();
        JSONArray array = config().getJSONArray("outputs");
        if (array != null) {
            for (Object item : array) {
                JSONObject output = (JSONObject) item;
                if (StrUtil.isNotBlank(output.getStr("name"))) {
                    outputs.add(output);
                }
            }
        }
        if (outputs.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：拆分字段", node.getName()));
        }
        return outputs;
    }

}
