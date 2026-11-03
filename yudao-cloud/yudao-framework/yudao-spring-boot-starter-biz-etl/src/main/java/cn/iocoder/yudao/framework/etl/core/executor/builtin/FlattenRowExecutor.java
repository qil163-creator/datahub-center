package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.lang.reflect.Array;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 行扁平化节点（toolCode: flatten-row），把嵌套对象展开为点号字段。 */
public class FlattenRowExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "flatten-row";

    /**
     * 初始化 {@code FlattenRowExecutor}及其父类状态。
     */
    public FlattenRowExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String sourceField = config().getStr("sourceField");
        String separator = StrUtil.blankToDefault(config().getStr("separator"), ".");
        boolean keepSource = config().getBool("keepSource", false);
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> flattened = new LinkedHashMap<>();
            if (StrUtil.isBlank(sourceField)) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    flatten(entry.getKey(), entry.getValue(), separator, flattened);
                }
            } else {
                row.forEach((key, value) -> {
                    if (!key.equals(sourceField) || keepSource) {
                        flattened.put(key, value);
                    }
                });
                flatten(sourceField, row.get(sourceField), separator, flattened);
            }
            pushRow(flattened);
        }
        logLine(String.format("行扁平化完成，共输出 %s 行", writeCount.get()));
    }

    /**
     * 展开 JSON 对象或数组并输出对应的数据行。
     */
    private void flatten(String prefix, Object value, String separator, Map<String, Object> output) {
        if (value instanceof String text && (text.trim().startsWith("{") || text.trim().startsWith("["))) {
            try {
                value = JSONUtil.parse(text);
            } catch (Exception ignored) { /* 普通字符串 */ }
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> flatten(prefix + separator + key, child, separator, output));
        } else if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object child : iterable) {
                flatten(prefix + separator + index++, child, separator, output);
            }
        } else if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                flatten(prefix + separator + i, Array.get(value, i), separator, output);
            }
        } else {
            output.put(prefix, value);
        }
    }
}
