package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 增加常量节点（toolCode: add-constant）：为每行数据追加固定值字段
 *
 * 配置：fields[{name 字段名称, fieldType String|Integer|Long|Double|Boolean|Date,
 * value 默认值, setEmpty 设为空串（true 时忽略默认值，赋空字符串）}]
 *
 * @author Li
 */
public class AddConstantExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "add-constant";

    /**
     * 初始化 {@code AddConstantExecutor}及其父类状态。
     */
    public AddConstantExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        Map<String, Object> constants = resolveConstants();
        if (constants.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：常量字段", node.getName()));
        }
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> merged = new LinkedHashMap<>(row);
            merged.putAll(constants);
            pushRow(merged);
        }
        logLine(String.format("增加常量完成，追加 %s 个字段，输出 %s 行",
                constants.size(), writeCount.get()));
    }

    /**
     * 预解析常量值（配置错误在读取数据前直接失败）
     */
    private Map<String, Object> resolveConstants() {
        Map<String, Object> constants = new LinkedHashMap<>();
        JSONArray fields = config().getJSONArray("fields");
        if (fields == null) {
            return constants;
        }
        for (Object item : fields) {
            JSONObject field = (JSONObject) item;
            String name = field.getStr("name");
            if (StrUtil.isBlank(name)) {
                continue;
            }
            if (field.getBool("setEmpty", false)) {
                constants.put(name, "");
                continue;
            }
            constants.put(name, convertValue(name,
                    StrUtil.blankToDefault(field.getStr("fieldType"), "String"),
                    field.getStr("value")));
        }
        return constants;
    }

    /**
     * 转换值。
     */
    private Object convertValue(String name, String fieldType, String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return switch (fieldType) {
                case "Integer" -> Integer.valueOf(value.trim());
                case "Long" -> Long.valueOf(value.trim());
                case "Double" -> Double.valueOf(value.trim());
                case "Boolean" -> Boolean.valueOf(value.trim());
                case "Date" -> DateUtil.parse(value.trim());
                default -> value;
            };
        } catch (Exception ex) {
            throw new IllegalStateException(String.format(
                    "常量字段【%s】的默认值【%s】无法转换为 %s 类型", name, value, fieldType));
        }
    }

}
