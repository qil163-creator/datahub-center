package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON 文件输入节点（toolCode: read-json）。根节点可以是数组，或包含 data/rows 数组的对象。 */
public class ReadJsonExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "read-json";

    /**
     * 初始化 {@code ReadJsonExecutor}及其父类状态。
     */
    public ReadJsonExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String fileUrl = requireConfig("fileUrl", "JSON 文件地址");
        int maxRows = Math.max(config().getInt("maxRows", 100_000), 1);
        File temp = File.createTempFile("etl-read-json-", ".json");
        try {
            long maxBytes = context.getLimits() == null ? 64L * 1024 * 1024 : context.getLimits().getMaxBytes();
            cn.iocoder.yudao.framework.etl.core.executor.support.EtlFileInput.download(fileUrl, temp.toPath(),
                    maxBytes);
            String text = FileUtil.readString(temp, StandardCharsets.UTF_8);
            Object parsed = cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.parse(text);
            java.util.List<?> rows = toRows(parsed);
            for (Object item : rows) {
                checkStop();
                if (readCount.get() >= maxRows) {
                    throw new IllegalStateException(String.format("JSON 输入超过最大行数 %s", maxRows));
                }
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((key, value) -> row.put(String.valueOf(key), value));
                    readCount.incrementAndGet();
                    pushRow(row);
                } else {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("value", item);
                    readCount.incrementAndGet();
                    pushRow(row);
                }
            }
        } finally {
            FileUtil.del(temp);
        }
        logLine(String.format("JSON 读取完成，共 %s 行", readCount.get()));
    }

    /**
     * 转换为数据行。
     */
    private java.util.List<?> toRows(Object parsed) {
        if (parsed instanceof java.util.List<?> array) {
            return array;
        }
        if (parsed instanceof Map<?, ?> object) {
            for (String key : new String[]{"data", "rows", "items", "results"}) {
                Object value = object.get(key);
                if (value instanceof java.util.List<?> array) {
                    return array;
                }
            }
        }
        if (parsed == null || StrUtil.isBlank(String.valueOf(parsed))) {
            return java.util.List.of();
        }
        throw new IllegalStateException("JSON 根节点必须是数组，或包含 data/rows/items/results 数组");
    }
}
