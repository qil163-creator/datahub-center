package cn.iocoder.yudao.module.governance.service.rule;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyReplaceItemVO;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.RULE_CONFIG_INVALID;

/**
 * 脱敏规则参数校验：按脱敏方式（method_code）校验 config 结构与取值
 *
 * 各方式的 config 约定：
 *   MASK             掩码       旧单区间使用 maskStart/maskEnd；展示配置使用 intervals 正整数闭区间。
 *   RANDOM_OFFSET    随机偏移   {"maxOffsetDigits":3,"allowNegative":true}（最大偏移位数≥1；文本类型时在文本后追加正偏移数）
 *   REPLACE          替换       {"matchMode":"EXACT|FUZZY"} + 替换明细（码表）
 *   CONTENT_REPLACE  内容替换   同 REPLACE
 *   RANDOM_REPLACE   随机替换   {"replaceChar":"*","replaceCount":1}（替换字符留空默认*；次数≥1）
 *   REDUCE_PRECISION 降低精度   {"precision":2}（数字按小数位截断，时间按精度置零，≥1）
 *   REVERSE          反向处理   无参数
 *   TRUNCATE         内容截断   {"truncateLength":4}（保留前 N 位，≥1，下标从1开始）
 *   NULLIFY          置空       无参数
 *   REGEX            正则替换   {"pattern":"...","replacement":"..."}
 *   HASH             哈希       {"algorithm":"MD5|SHA|SHA256|SHA384|SHA512|SM3"}
 *   ENCRYPT          加解密     {"encryptType":"BASE64"}
 *                               {"encryptType":"DIGEST","algorithm":"MD5|SHA|SHA256|SHA384|SHA512"}
 *                               {"encryptType":"SM","algorithm":"SM2","publicKey":"...","privateKey":"..."}
 *                               {"encryptType":"SM","algorithm":"SM3"}
 *                               {"encryptType":"SM","algorithm":"SM4","secretKey":"..."}
 *                               {"encryptType":"AES","secretKey":"..."}（对称加解密）
 *
 * @author Li
 */
public class SafetyRuleConfigValidator {

    private static final Set<String> DIGEST_ALGORITHMS = Set.of("MD5", "SHA", "SHA256", "SHA384", "SHA512");

    private static final Set<String> HASH_ALGORITHMS = Set.of("MD5", "SHA", "SHA256", "SHA384", "SHA512", "SM3");

    private static final Set<String> SM_ALGORITHMS = Set.of("SM2", "SM3", "SM4");

    private static final Set<String> MATCH_MODES = Set.of("EXACT", "FUZZY");

    /** 使用替换明细码表的脱敏方式 */
    public static boolean useReplaceItems(String methodCode) {
        return "REPLACE".equals(methodCode) || "CONTENT_REPLACE".equals(methodCode);
    }

    /**
     * 按脱敏方式分派参数校验，拒绝不支持的方式或不符合约定的配置。
     */
    public static void validate(String methodCode, Map<String, Object> config, List<SafetyReplaceItemVO> replaceItems) {
        switch (methodCode) {
            case "MASK" -> validateMask(config);
            case "RANDOM_OFFSET" -> validateRandomOffset(config);
            case "REPLACE", "CONTENT_REPLACE" -> validateContentReplace(config, replaceItems);
            case "RANDOM_REPLACE" -> validateRandomReplace(config);
            case "REDUCE_PRECISION" -> validateReducePrecision(config);
            case "TRUNCATE" -> validateTruncate(config);
            case "REGEX" -> validateRegex(config);
            case "HASH" -> validateHash(config);
            case "ENCRYPT" -> validateEncrypt(config);
            case "REVERSE", "NULLIFY" -> { /* 无参数 */ }
            default -> throw exception(RULE_CONFIG_INVALID, "不支持的脱敏方式 " + methodCode);
        }
    }

    /**
     * 校验掩码起止位置与顺序；配置多区间时转交区间校验。
     */
    private static void validateMask(Map<String, Object> config) {
        if (config != null && config.containsKey("intervals")) {
            validateIntervals(config);
            return;
        }
        Integer start = getInt(config, "maskStart");
        Integer end = getInt(config, "maskEnd");
        if (start == null || end == null) {
            throw exception(RULE_CONFIG_INVALID, "掩码范围不能为空");
        }
        if (start < 0 || end < 1) {
            throw exception(RULE_CONFIG_INVALID, "掩码范围最少为 0~1");
        }
        if (end <= start) {
            throw exception(RULE_CONFIG_INVALID, "掩码截止数必须大于起始数");
        }
    }

    /**
     * 校验掩码区间及替换内容的数量、长度和正整数边界，并拒绝重叠区间。
     */
    private static void validateIntervals(Map<String, Object> config) {
        Object raw = config.get("intervals");
        if (!(raw instanceof List<?> intervals) || intervals.isEmpty() || intervals.size() > 100) {
            throw exception(RULE_CONFIG_INVALID, "展示掩码须包含 1 至 100 个区间");
        }
        if (!(config.get("maskChar") instanceof String replacement) || StrUtil.isBlank(replacement)
                || replacement.length() > 256) {
            throw exception(RULE_CONFIG_INVALID, "替换内容须为 1 至 256 个字符");
        }
        java.util.List<int[]> ranges = new java.util.ArrayList<>();
        for (Object item : intervals) {
            if (!(item instanceof Map<?, ?> range)) {
                throw exception(RULE_CONFIG_INVALID, "掩码区间格式不正确");
            }
            int start = positiveInteger(range.get("start"));
            int end = positiveInteger(range.get("end"));
            if (end < start) {
                throw exception(RULE_CONFIG_INVALID, "掩码截止数不能小于起始数");
            }
            ranges.add(new int[]{start, end});
        }
        ranges.sort(java.util.Comparator.comparingInt(range -> range[0]));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0] <= ranges.get(i - 1)[1]) {
                throw exception(RULE_CONFIG_INVALID, "掩码区间不能重叠");
            }
        }
    }

    /**
     * 读取并校验脱敏规则中必须为正整数的配置项。
     */
    private static int positiveInteger(Object value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        throw exception(RULE_CONFIG_INVALID, "掩码起止位置须为正整数");
    }

    /**
     * 校验最大随机偏移位数已配置且为正整数。
     */
    private static void validateRandomOffset(Map<String, Object> config) {
        Integer digits = getInt(config, "maxOffsetDigits");
        if (digits == null || digits < 1) {
            throw exception(RULE_CONFIG_INVALID, "最大偏移位数只能是不为 0 的正整数");
        }
    }

    /**
     * 校验替换匹配方式、明细非空及匹配值唯一性。
     */
    private static void validateContentReplace(Map<String, Object> config, List<SafetyReplaceItemVO> replaceItems) {
        String matchMode = getStr(config, "matchMode");
        if (matchMode == null || !MATCH_MODES.contains(matchMode)) {
            throw exception(RULE_CONFIG_INVALID, "匹配方式只能是完全匹配（EXACT）或模糊匹配（FUZZY）");
        }
        if (CollUtil.isEmpty(replaceItems)) {
            throw exception(RULE_CONFIG_INVALID, "匹配内容至少填写一行");
        }
        Set<String> matchValues = new java.util.HashSet<>();
        for (SafetyReplaceItemVO item : replaceItems) {
            if (StrUtil.isEmpty(item.getMatchValue())) {
                throw exception(RULE_CONFIG_INVALID, "匹配值不能为空");
            }
            if (!matchValues.add(item.getMatchValue())) {
                throw exception(RULE_CONFIG_INVALID, "匹配值【" + item.getMatchValue() + "】重复");
            }
        }
    }

    /**
     * 配置随机替换次数时，校验其不小于一次。
     */
    private static void validateRandomReplace(Map<String, Object> config) {
        Integer count = getInt(config, "replaceCount");
        if (count != null && count < 1) {
            throw exception(RULE_CONFIG_INVALID, "替换次数不允许为 0");
        }
    }

    /**
     * 校验降低精度位数已配置且至少为一。
     */
    private static void validateReducePrecision(Map<String, Object> config) {
        Integer precision = getInt(config, "precision");
        if (precision == null || precision < 1) {
            throw exception(RULE_CONFIG_INVALID, "降低精度的位数最少 1 位");
        }
    }

    /**
     * 校验截断长度已配置且至少为一。
     */
    private static void validateTruncate(Map<String, Object> config) {
        Integer length = getInt(config, "truncateLength");
        if (length == null || length < 1) {
            throw exception(RULE_CONFIG_INVALID, "截断长度最少 1 位");
        }
    }

    /**
     * 校验正则表达式非空且能够成功编译。
     */
    private static void validateRegex(Map<String, Object> config) {
        String pattern = getStr(config, "pattern");
        if (StrUtil.isEmpty(pattern)) {
            throw exception(RULE_CONFIG_INVALID, "正则表达式不能为空");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw exception(RULE_CONFIG_INVALID, "正则表达式语法错误");
        }
    }

    /**
     * 校验摘要算法属于当前支持的算法集合。
     */
    private static void validateHash(Map<String, Object> config) {
        String algorithm = getStr(config, "algorithm");
        if (algorithm == null || !HASH_ALGORITHMS.contains(algorithm)) {
            throw exception(RULE_CONFIG_INVALID, "哈希算法只支持 " + String.join("、", HASH_ALGORITHMS));
        }
    }

    /**
     * 校验加密或编码方式、算法选项与相应公私钥或对称密钥。
     */
    private static void validateEncrypt(Map<String, Object> config) {
        String encryptType = getStr(config, "encryptType");
        if (encryptType == null) {
            throw exception(RULE_CONFIG_INVALID, "加解密类型不能为空");
        }
        switch (encryptType) {
            case "BASE64" -> { /* 无额外参数 */ }
            case "DIGEST" -> {
                String algorithm = getStr(config, "algorithm");
                if (algorithm == null || !DIGEST_ALGORITHMS.contains(algorithm)) {
                    throw exception(RULE_CONFIG_INVALID, "摘要算法只支持 " + String.join("、", DIGEST_ALGORITHMS));
                }
            }
            case "SM" -> {
                String algorithm = getStr(config, "algorithm");
                if (algorithm == null || !SM_ALGORITHMS.contains(algorithm)) {
                    throw exception(RULE_CONFIG_INVALID, "国密算法只支持 SM2、SM3、SM4");
                }
                if ("SM2".equals(algorithm)
                        && (StrUtil.isEmpty(getStr(config, "publicKey")) || StrUtil.isEmpty(
                        getStr(config, "privateKey")))) {
                    throw exception(RULE_CONFIG_INVALID, "SM2 需要配置公钥和私钥");
                }
                if ("SM4".equals(algorithm) && StrUtil.isEmpty(getStr(config, "secretKey"))) {
                    throw exception(RULE_CONFIG_INVALID, "SM4 需要配置密钥");
                }
            }
            case "AES" -> {
                if (StrUtil.isEmpty(getStr(config, "secretKey"))) {
                    throw exception(RULE_CONFIG_INVALID, "AES 需要配置密钥");
                }
            }
            default -> throw exception(RULE_CONFIG_INVALID, "加解密类型只支持 BASE64、DIGEST、SM、AES");
        }
    }

    /**
     * 按配置键读取整数类型参数。
     */
    private static Integer getInt(Map<String, Object> config, String key) {
        return MapUtil.getInt(config, key);
    }

    /**
     * 按配置键读取字符串类型参数。
     */
    private static String getStr(Map<String, Object> config, String key) {
        return MapUtil.getStr(config, key);
    }

}
