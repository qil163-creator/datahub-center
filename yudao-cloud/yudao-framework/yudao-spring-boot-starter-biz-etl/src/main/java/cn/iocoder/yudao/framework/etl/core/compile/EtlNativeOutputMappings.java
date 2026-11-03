package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.util.*;

/** 消费连接器 UI 的同序字段映射；源字段与目标结构分开，不把映射丢失为 identity。 */
public final class EtlNativeOutputMappings {
    private EtlNativeOutputMappings() { }
    public static List<Map<String, Object>> require(JSONObject config) {
        var columns = config.getJSONArray("columns");
        if (columns == null || columns.isEmpty()) throw invalid("输出需要明确的字段合同");
        var mappings = new ArrayList<Map<String, Object>>(); var targets = new HashSet<String>();
        for (Object value : columns) {
            var field = value instanceof String name ? JSONUtil.createObj().set("name", name) : JSONUtil.parseObj(value);
            String target = field.getStr("name", ""), source = field.getStr("key", target);
            if (source.isBlank()) source = target;
            if (!target.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}") || !source.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}") || !targets.add(target)) throw invalid("输出映射字段无效或目标重复");
            mappings.add(Map.of("source", source, "target", target, "enabled", true));
        }
        var explicit = config.getJSONArray("fieldMappings");
        if (explicit != null) {
            if (explicit.size() != mappings.size()) throw invalid("输出属性字段与映射数量不一致");
            for (int index = 0; index < explicit.size(); index++) {
                var actual = JSONUtil.parseObj(explicit.get(index)); var expected = mappings.get(index);
                if (!Set.of("source", "target", "enabled").containsAll(actual.keySet()) || !Objects.equals(actual.getStr("source"), expected.get("source"))
                        || !Objects.equals(actual.getStr("target"), expected.get("target")) || actual.containsKey("enabled") && !Boolean.TRUE.equals(actual.get("enabled")))
                    throw invalid("输出映射必须与属性字段同序且显式启用");
            }
        }
        return List.copyOf(mappings);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
