package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** 无上游的测试数据生成节点（toolCode: generate-records）。 */
public class GenerateRecordsExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "generate-records";

    /**
     * 初始化 {@code GenerateRecordsExecutor}及其父类状态。
     */
    public GenerateRecordsExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        int count = config().getInt("count", 1);
        if (count < 0 || count > 1_000_000) {
            throw new IllegalStateException("生成记录数必须在 0 到 1000000 之间");
        }
        JSONArray fields = config().getJSONArray("fields");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(String.format("节点【%s】缺少字段定义", node.getName()));
        }
        for (int i = 0; i < count; i++) {
            checkStop();
            Map<String, Object> row = new LinkedHashMap<>();
            for (Object item : fields) {
                JSONObject field = (JSONObject) item;
                String name = field.getStr("name");
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                row.put(name, convert(field.getStr("type", "String"), field.getStr("value")));
            }
            pushRow(row);
        }
        logLine(String.format("生成记录完成，共 %s 行", writeCount.get()));
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    static Object convert(String type, String value) {
        if (value == null) {
            return null;
        }
        return switch (StrUtil.blankToDefault(type, "String").toUpperCase()) {
            case "INTEGER" -> Integer.valueOf(value);
            case "LONG" -> Long.valueOf(value);
            case "DOUBLE" -> Double.valueOf(value);
            case "BOOLEAN" -> Boolean.valueOf(value);
            case "DATE" -> DateUtil.parse(value);
            default -> value;
        };
    }
}
