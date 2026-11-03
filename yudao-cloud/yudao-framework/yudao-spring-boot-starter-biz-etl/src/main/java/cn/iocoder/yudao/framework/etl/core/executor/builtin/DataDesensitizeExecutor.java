package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据脱敏节点（toolCode: data-desensitize）
 *
 * 字段级脱敏配置 desensitizeFields:
 * [{fieldName, strategy ID_CARD|PHONE|EMAIL|NAME|BANK_CARD|ADDRESS|IP_ADDRESS|MASK|REPLACE|TRUNCATE|NULL_REPLACE,
 *   maskChar, keepStart, keepEnd, replaceValue, truncateLength, outputFieldName, keepOriginal}]
 *
 * @author Li
 */
public class DataDesensitizeExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "data-desensitize";

    /**
     * 初始化 {@code DataDesensitizeExecutor}及其父类状态。
     */
    public DataDesensitizeExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        JSONArray fields = config().getJSONArray("desensitizeFields");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(String.format("节点【%s】未配置脱敏字段", node.getName()));
        }
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> result = new LinkedHashMap<>(row);
            for (Object item : fields) {
                JSONObject field = (JSONObject) item;
                String fieldName = field.getStr("fieldName");
                if (StrUtil.isBlank(fieldName) || !result.containsKey(fieldName)) {
                    continue;
                }
                Object value = result.get(fieldName);
                String desensitized = value == null ? null
                        : desensitize(String.valueOf(value), field);
                String outputField = StrUtil.blankToDefault(field.getStr("outputFieldName"), fieldName);
                if (!field.getBool("keepOriginal", false) && !outputField.equals(fieldName)) {
                    result.remove(fieldName);
                }
                result.put(outputField, desensitized);
            }
            pushRow(result);
        }
        logLine(String.format("脱敏完成，共 %s 行", writeCount.get()));
    }

    /**
     * 根据字段策略对当前敏感值进行脱敏。
     */
    private String desensitize(String text, JSONObject field) {
        String strategy = StrUtil.nullToEmpty(field.getStr("strategy")).toUpperCase();
        return switch (strategy) {
            case "ID_CARD" -> DesensitizedUtil.idCardNum(text, 1, 2);
            case "PHONE" -> DesensitizedUtil.mobilePhone(text);
            case "EMAIL" -> DesensitizedUtil.email(text);
            case "NAME" -> DesensitizedUtil.chineseName(text);
            case "BANK_CARD" -> DesensitizedUtil.bankCard(text);
            case "ADDRESS" -> DesensitizedUtil.address(text, 8);
            case "IP_ADDRESS" -> desensitizeIp(text);
            case "MASK" -> mask(text, field);
            case "REPLACE" -> field.getStr("replaceValue", "***");
            case "TRUNCATE" -> {
                int length = Math.max(field.getInt("truncateLength", 4), 0);
                yield text.length() <= length ? text : text.substring(0, length);
            }
            case "NULL_REPLACE" -> null;
            default -> throw new IllegalStateException(
                    String.format("不支持的脱敏策略【%s】", strategy));
        };
    }

    /**
     * 通用掩码：保留首尾指定位数，其余以掩码字符替换
     */
    private String mask(String text, JSONObject field) {
        int keepStart = Math.max(field.getInt("keepStart", 1), 0);
        int keepEnd = Math.max(field.getInt("keepEnd", 1), 0);
        String maskChar = StrUtil.blankToDefault(field.getStr("maskChar"), "*");
        if (text.length() <= keepStart + keepEnd) {
            return maskChar.repeat(text.length());
        }
        return text.substring(0, keepStart)
                + maskChar.repeat(text.length() - keepStart - keepEnd)
                + text.substring(text.length() - keepEnd);
    }

    /**
     * 保留 IP 地址的前部网段并掩码其余部分。
     */
    private String desensitizeIp(String ip) {
        int index = ip.indexOf('.');
        if (index < 0) {
            return "*";
        }
        return ip.substring(0, ip.indexOf('.', index + 1) < 0 ? index : ip.indexOf('.', index + 1)) + ".*.*";
    }

}
