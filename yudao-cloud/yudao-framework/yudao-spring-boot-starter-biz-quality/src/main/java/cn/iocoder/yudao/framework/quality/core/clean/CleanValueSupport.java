package cn.iocoder.yudao.framework.quality.core.clean;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 清洗函数公共工具
 *
 * @author Li
 */
public class CleanValueSupport {

    /**
     * 初始化 {@code CleanValueSupport}。
     */
    private CleanValueSupport() {
    }

    /**
     * 读取 config.columns（目标字段列表）
     */
    @SuppressWarnings("unchecked")
    public static List<String> columns(Map<String, Object> config) {
        Object raw = config == null ? null : config.get("columns");
        List<String> result = new ArrayList<>();
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (raw instanceof String && StrUtil.isNotBlank((String) raw)) {
            for (String item : ((String) raw).split(",")) {
                if (StrUtil.isNotBlank(item)) {
                    result.add(item.trim());
                }
            }
        }
        return result;
    }

    /**
     * 读取首个目标字段，缺失时抛出配置异常
     */
    public static String firstColumn(Map<String, Object> config) {
        List<String> columns = columns(config);
        if (CollUtil.isEmpty(columns)) {
            throw new IllegalArgumentException("清洗规则缺少 columns（目标字段）配置");
        }
        return columns.get(0);
    }

    /**
     * 必填字段列表
     */
    public static List<String> requiredColumns(Map<String, Object> config) {
        List<String> columns = columns(config);
        if (CollUtil.isEmpty(columns)) {
            throw new IllegalArgumentException("清洗规则缺少 columns（目标字段）配置");
        }
        return columns;
    }

    /**
     * 值转字符串（null 返回 null）
     */
    public static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 是否空值（null / 空串 / 全空白）
     */
    public static boolean isBlankValue(Object value) {
        return value == null || StrUtil.isBlank(String.valueOf(value));
    }

    /**
     * 从清洗配置中读取指定字符串值。
     */
    public static String getStr(Map<String, Object> config, String key) {
        return MapUtil.getStr(config, key);
    }

    /**
     * 返回整数。
     */
    public static Integer getInt(Map<String, Object> config, String key, Integer defaultValue) {
        return MapUtil.getInt(config, key, defaultValue);
    }

    /** 保留每个键分量的类型与空值边界，避免字符串拼接导致不同复合键碰撞。 */
    public static List<?> compositeKey(Map<String, Object> row, List<String> columns) {
        List<KeyPart> parts = new ArrayList<>(columns.size());
        for (String column : columns) {
            Object value = row.get(column);
            parts.add(new KeyPart(value == null ? null : value.getClass(), value));
        }
        return List.copyOf(parts);
    }

    private record KeyPart(Class<?> type, Object value) {

    }

    /**
     * 对每行的指定字段应用值转换（浅拷贝行，null 值原样保留由转换器决定）
     */
    public static List<Map<String, Object>> mapColumn(List<Map<String, Object>> rows, String column,
            Function<Object, Object> mapper) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            CleanExecutionControl.checkpoint();
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put(column, mapper.apply(copy.get(column)));
            CleanExecutionControl.retainOutput(copy);
            result.add(copy);
        }
        return result;
    }

}
