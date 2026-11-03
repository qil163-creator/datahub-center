package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.variable.EtlParameterDefinition;
import cn.iocoder.yudao.framework.etl.core.variable.EtlParameterValidator;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPipelineSaveReqVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 DataForge 运行快照表示映射为框架的强类型参数契约。 */
final class EtlRunParameterResolver {

    /**
     * 限制ETL运行参数工具的实例化，统一通过静态能力使用。
     */
    private EtlRunParameterResolver() {
    }

    /**
     * 解析参数定义及输入值，按 ETL 参数规则补默认值并校验类型和范围。
     *
     * @param definitionsJson 定义JSON
     * @param valuesJson 值JSON
     * @return 处理后的键值映射
     */
    static Map<String, String> resolve(JSONArray definitionsJson, JSONObject valuesJson) {
        List<EtlParameterDefinition> definitions = new ArrayList<>();
        if (definitionsJson != null) {
            for (Object item : definitionsJson) {
                JSONObject definition = (JSONObject) item;
                definitions.add(new EtlParameterDefinition(definition.getStr("name"), definition.getStr("type"),
                        definition.getBool("required"), definition.getStr("defaultValue"),
                        definition.getStr("min"), definition.getStr("max"),
                        definition.getInt("minLength"), definition.getInt("maxLength")));
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (valuesJson != null) {
            for (String key : valuesJson.keySet()) {
                Object value = valuesJson.get(key);
                if (!(value instanceof String stringValue)) {
                    throw new IllegalStateException("运行参数快照值必须是字符串");
                }
                values.put(key, stringValue);
            }
        }
        return EtlParameterValidator.resolve(definitions, values, EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS);
    }

    /**
     * 解析参数定义及输入值，按 ETL 参数规则补默认值并校验类型和范围。
     *
     * @param definitions 定义
     * @param values 字段值集合
     * @return 处理后的键值映射
     */
    static Map<String, String> resolve(List<EtlPipelineSaveReqVO.ParamItem> definitions,
            Map<String, String> values) {
        JSONArray json = definitions == null ? new JSONArray() : JSONUtil.parseArray(JSONUtil.toJsonStr(definitions));
        JSONObject supplied = new JSONObject();
        if (values != null) {
            supplied.putAll(values);
        }
        return resolve(json, supplied);
    }
}
