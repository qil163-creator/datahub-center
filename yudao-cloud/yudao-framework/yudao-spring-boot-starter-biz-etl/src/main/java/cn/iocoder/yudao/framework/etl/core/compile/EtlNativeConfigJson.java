package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** 规范化持久 JSON 配置的对象键；数组和 JSON 文本保序，不处理编译后有序物理列合同。 */
public final class EtlNativeConfigJson {
    private EtlNativeConfigJson() { }

    public static JSONObject normalize(Map<String, ?> configuration) {
        if (configuration == null) throw new IllegalArgumentException("原生组件配置不能为空");
        return JSONUtil.parseObj(JSONUtil.toJsonStr(ordered(configuration, 0)));
    }

    private static Object ordered(Object value, int depth) {
        if (depth > 128) throw new IllegalArgumentException("原生组件配置嵌套超过预算");
        if (value instanceof Map<?, ?> object) {
            var result = new TreeMap<String, Object>();
            object.forEach((key, item) -> result.put(String.valueOf(key), ordered(item, depth + 1)));
            return result;
        }
        if (value instanceof Collection<?> array) return array.stream().map(item -> ordered(item, depth + 1)).toList();
        return value;
    }
}
