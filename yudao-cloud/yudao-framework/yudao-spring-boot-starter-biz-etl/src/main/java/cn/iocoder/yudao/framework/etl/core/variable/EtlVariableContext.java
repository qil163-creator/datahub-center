package cn.iocoder.yudao.framework.etl.core.variable;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流程变量上下文：节点配置中的 ${params.xxx} 与 ${sys.xxx} 占位符替换
 *
 * 系统变量在一次运行内固定：sys.runTime / sys.today / sys.yesterday / sys.epochMillis
 *
 * @author Li
 */
public class EtlVariableContext {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<String, String> values = new HashMap<>();

    /**
     * 初始化 {@code EtlVariableContext}。
     */
    public EtlVariableContext(Map<String, String> params) {
        Date runTime = new Date();
        values.put("sys.runTime", DateUtil.format(runTime, DatePattern.NORM_DATETIME_PATTERN));
        values.put("sys.today", DateUtil.format(runTime, DatePattern.NORM_DATE_PATTERN));
        values.put("sys.yesterday", DateUtil.format(DateUtil.offsetDay(runTime, -1), DatePattern.NORM_DATE_PATTERN));
        values.put("sys.epochMillis", String.valueOf(runTime.getTime()));
        if (params != null) {
            params.forEach((name, value) -> values.put("params." + name, value == null ? "" : value));
        }
    }

    /**
     * 替换文本中的占位符；未定义的变量抛出异常（避免脏配置静默执行）
     */
    public String resolve(String text) {
        if (StrUtil.isBlank(text) || !text.contains("${")) {
            return text;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String value = values.get(name);
            if (value == null) {
                throw new IllegalStateException(String.format("未定义的流程变量【%s】", name));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 仅替换 JSON 字符串叶节点中的占位符，然后重新序列化 JSON 树。
     * 绝不插值 JSON 键、结构或非字符串标量值。
     */
    public String resolveJson(String json) {
        if (StrUtil.isBlank(json) || !json.contains("${")) {
            return json;
        }
        Object parsed = JSONUtil.parse(json);
        return JSONUtil.toJsonStr(resolveJsonValue(parsed));
    }

    /**
     * 解析JSON值。
     */
    private Object resolveJsonValue(Object value) {
        if (value instanceof String text) {
            return resolve(text);
        }
        if (value instanceof JSONObject object) {
            for (String key : object.keySet()) {
                object.set(key, resolveJsonValue(object.get(key)));
            }
            return object;
        }
        if (value instanceof JSONArray array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, resolveJsonValue(array.get(index)));
            }
            return array;
        }
        return value;
    }

}
