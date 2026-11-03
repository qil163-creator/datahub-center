package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.math.Calculator;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段派生器节点（toolCode: field-derive）：基于已有字段派生一个新字段
 *
 * 配置：operateType 操作类型 + targetField 新增字段名称；各类型附加配置：
 * CONCAT 拼接：sourceFields[] + prefix 前缀 + suffix 后缀 + separator 连接符
 * SUBSTRING 截取：sourceFields[0] + beginIndex 起始位置（1 基）+ length 长度（0 到末尾）
 * REPLACE 替换：sourceFields[0] + search 查找内容 + replacement 替换为 + useRegex 是否正则
 * EXPRESSION 表达式：expression（${字段名} 占位，数值表达式自动计算，否则输出替换后的文本）
 * HASH 哈希：sourceFields[] + algorithm MD5|SHA1|SHA256
 * REGEX_EXTRACT 正则提取：sourceFields[0] + pattern + groupIndex 分组序号（0 整体匹配）
 * CONSTANT 常量赋值：constantValue
 *
 * @author Li
 */
public class FieldDeriveExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "field-derive";

    /**
     * 初始化 {@code FieldDeriveExecutor}及其父类状态。
     */
    public FieldDeriveExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String operateType = StrUtil.blankToDefault(config().getStr("operateType"), "CONCAT").toUpperCase();
        String targetField = requireConfig("targetField", "新增字段名称");
        List<String> sourceFields = resolveSourceFields();
        validateConfig(operateType, sourceFields);

        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> derived = new LinkedHashMap<>(row);
            derived.put(targetField, derive(operateType, sourceFields, row));
            pushRow(derived);
        }
        logLine(String.format("字段派生完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 解析源端字段集合。
     */
    private List<String> resolveSourceFields() {
        List<String> fields = new ArrayList<>();
        var array = config().getJSONArray("sourceFields");
        if (array != null) {
            array.forEach(item -> {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    fields.add(String.valueOf(item));
                }
            });
        }
        return fields;
    }

    /**
     * 校验配置。
     */
    private void validateConfig(String operateType, List<String> sourceFields) {
        boolean needSource = !"EXPRESSION".equals(operateType) && !"CONSTANT".equals(operateType);
        if (needSource && sourceFields.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：来源字段", node.getName()));
        }
        switch (operateType) {
            case "EXPRESSION" -> requireConfig("expression", "表达式");
            case "REGEX_EXTRACT" -> requireConfig("pattern", "正则表达式");
            default -> {
            }
        }
    }

    /**
     * 根据派生操作类型计算字段值。
     */
    private Object derive(String operateType, List<String> sourceFields, Map<String, Object> row) {
        return switch (operateType) {
            case "CONCAT" -> concat(sourceFields, row);
            case "SUBSTRING" -> substring(sourceFields.get(0), row);
            case "REPLACE" -> replace(sourceFields.get(0), row);
            case "EXPRESSION" -> expression(row);
            case "HASH" -> hash(sourceFields, row);
            case "REGEX_EXTRACT" -> regexExtract(sourceFields.get(0), row);
            case "CONSTANT" -> config().getStr("constantValue", "");
            default -> throw new IllegalStateException(
                    String.format("暂不支持的派生操作类型【%s】", operateType));
        };
    }

    /**
     * 取得当前行字段的文本值，空值转换为空字符串。
     */
    private String textValue(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 返回当前拼接。
     */
    private String concat(List<String> sourceFields, Map<String, Object> row) {
        String separator = config().getStr("separator", "");
        List<String> values = new ArrayList<>();
        sourceFields.forEach(field -> values.add(textValue(row, field)));
        return config().getStr("prefix", "")
                + String.join(separator, values)
                + config().getStr("suffix", "");
    }

    /**
     * 返回当前子串。
     */
    private String substring(String sourceField, Map<String, Object> row) {
        String text = textValue(row, sourceField);
        int begin = Math.max(config().getInt("beginIndex", 1) - 1, 0);
        int length = config().getInt("length", 0);
        if (begin >= text.length()) {
            return "";
        }
        int end = length > 0 ? Math.min(begin + length, text.length()) : text.length();
        return text.substring(begin, end);
    }

    /**
     * 返回当前替换。
     */
    private String replace(String sourceField, Map<String, Object> row) {
        String text = textValue(row, sourceField);
        String search = config().getStr("search", "");
        if (StrUtil.isEmpty(search)) {
            return text;
        }
        String replacement = config().getStr("replacement", "");
        return config().getBool("useRegex", false)
                ? text.replaceAll(search, replacement)
                : text.replace(search, replacement);
    }

    /**
     * 表达式派生：先以行数据替换 ${字段名} 占位符，若为数值表达式则计算，否则输出替换后的文本
     */
    private Object expression(Map<String, Object> row) {
        String expression = config().getStr("expression", "");
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            expression = expression.replace("${" + entry.getKey() + "}",
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        try {
            double result = Calculator.conversion(expression);
            return BigDecimal.valueOf(result).stripTrailingZeros().toPlainString();
        } catch (Exception ignored) {
            return expression;
        }
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private String hash(List<String> sourceFields, Map<String, Object> row) {
        StringBuilder text = new StringBuilder();
        sourceFields.forEach(field -> text.append(textValue(row, field)));
        String algorithm = StrUtil.blankToDefault(config().getStr("algorithm"), "MD5").toUpperCase();
        return switch (algorithm) {
            case "MD5" -> DigestUtil.md5Hex(text.toString());
            case "SHA1" -> DigestUtil.sha1Hex(text.toString());
            case "SHA256" -> DigestUtil.sha256Hex(text.toString());
            default -> throw new IllegalStateException(
                    String.format("暂不支持的哈希算法【%s】", algorithm));
        };
    }

    /**
     * 按配置的正则表达式和分组编号提取字段文本。
     */
    private String regexExtract(String sourceField, Map<String, Object> row) {
        String text = textValue(row, sourceField);
        String pattern = config().getStr("pattern");
        int groupIndex = config().getInt("groupIndex", 1);
        String result = ReUtil.get(pattern, text, groupIndex);
        return result == null ? "" : result;
    }

}
