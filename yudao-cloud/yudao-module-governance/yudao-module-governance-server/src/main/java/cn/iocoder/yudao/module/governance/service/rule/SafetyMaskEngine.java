package cn.iocoder.yudao.module.governance.service.rule;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.digest.Digester;
import cn.iocoder.yudao.module.governance.dal.dataobject.replaceitem.SafetyReplaceItemDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASK_METHOD_NOT_SUPPORTED;

/**
 * 脱敏执行引擎：按脱敏方式（method_code）与规则参数（config_json）对单个值执行脱敏
 *
 * 与 {@link SafetyRuleConfigValidator} 的 config 约定一一对应，
 * 供静态脱敏任务批量执行使用，后续动态脱敏（API 出口）也可复用。
 *
 * 类型约定：
 *   输入为 null 时直接返回 null（NULLIFY 除外，本身就返回 null）；
 *   RANDOM_OFFSET / REDUCE_PRECISION / NULLIFY 对数字、时间输入保持原类型，
 *   其余方式统一转字符串处理并返回字符串。
 *
 * @author Li
 */
public class SafetyMaskEngine {

    public static final String UNAVAILABLE_PLACEHOLDER = "***";

    /**
     * 限制脱敏工具类的直接实例化。
     */
    private SafetyMaskEngine() {
    }

    /**
     * 规则不可用时的失败关闭掩码。不回退原文；字符串占位避免把明文数字/时间继续交给调用方。
     */
    public static Object unavailableMask(Object value) {
        if (value == null) {
            return null;
        }
        return UNAVAILABLE_PLACEHOLDER;
    }

    /**
     * 执行脱敏
     *
     * @param value        原始值（JDBC 读出的对象）
     * @param methodCode   脱敏方式编码
     * @param config       规则参数（config_json 反序列化结果，可为 null）
     * @param replaceItems 替换明细码表（仅 REPLACE / CONTENT_REPLACE 使用，按 sequenceNo 命中即停）
     * @return 脱敏后的值
     */
    public static Object mask(Object value, String methodCode, Map<String, Object> config,
            List<SafetyReplaceItemDO> replaceItems) {
        return mask(value, methodCode, config, replaceItems, null);
    }

    /**
     * 按规则脱敏；ENCRYPT/KEY_REF 通过 {@code keyCipher} 加密，未提供时该方式不可执行（调用方失败关闭）。
     */
    public static Object mask(Object value, String methodCode, Map<String, Object> config,
            List<SafetyReplaceItemDO> replaceItems, SafetyKeyCipher keyCipher) {
        if (value == null) {
            return null;
        }
        return switch (StrUtil.nullToEmpty(methodCode)) {
            case "MASK" -> maskRange(str(value), config);
            case "RANDOM_OFFSET" -> randomOffset(value, config);
            case "REPLACE", "CONTENT_REPLACE" -> contentReplace(str(value), config, replaceItems);
            case "RANDOM_REPLACE" -> randomReplace(str(value), config);
            case "REDUCE_PRECISION" -> reducePrecision(value, config);
            case "REVERSE" -> StrUtil.reverse(str(value));
            case "TRUNCATE" -> truncate(str(value), config);
            case "NULLIFY" -> null;
            case "REGEX" -> regexReplace(str(value), config);
            case "HASH" -> hash(str(value), MapUtil.getStr(config, "algorithm"));
            case "ENCRYPT" -> "KEY_REF".equals(MapUtil.getStr(config, "encryptType"))
                    ? encryptByKeyRef(str(value), config, keyCipher) : encrypt(str(value), config);
            default -> throw exception(MASK_METHOD_NOT_SUPPORTED, methodCode);
        };
    }

    /**
     * 将非空字段值转换为脱敏算法使用的字符串表示。
     */
    private static String str(Object value) {
        return String.valueOf(value);
    }

    // ==================== MASK 掩码 ====================

    /**
     * 掩码：下标从 1 开始，[maskStart, maskEnd] 区间以 maskChar 填充
     */
    private static String maskRange(String text, Map<String, Object> config) {
        if (config != null && config.containsKey("intervals")) {
            SafetyRuleConfigValidator.validate("MASK", config, List.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ranges = (List<Map<String, Object>>) config.get("intervals");
            String replacement = (String) config.get("maskChar");
            StringBuilder masked = new StringBuilder(text);
            // 自定义替换串整体替换闭区间；从后向前处理，前面的位置始终对应原始文本。
            for (Map<String, Object> range : ranges.stream()
                    .sorted(Comparator.comparingInt((Map<String, Object> item) -> MapUtil.getInt(item, "start"))
                            .reversed())
                    .toList()) {
                int start = MapUtil.getInt(range, "start");
                int end = Math.min(MapUtil.getInt(range, "end"), text.length());
                if (start <= text.length()) {
                    masked.replace(start - 1, end, replacement);
                }
            }
            return masked.toString();
        }
        int start = Math.max(MapUtil.getInt(config, "maskStart", 1), 1);
        int end = MapUtil.getInt(config, "maskEnd", text.length());
        char maskChar = StrUtil.blankToDefault(MapUtil.getStr(config, "maskChar"), "*").charAt(0);
        if (start > text.length()) {
            return text;
        }
        end = Math.min(end, text.length());
        return text.substring(0, start - 1)
                + StrUtil.repeat(maskChar, end - start + 1)
                + text.substring(end);
    }

    // ==================== RANDOM_OFFSET 随机偏移 ====================

    /**
     * 随机偏移：数字加减不超过 maxOffsetDigits 位数的随机数；文本在末尾追加正偏移数
     */
    private static Object randomOffset(Object value, Map<String, Object> config) {
        int digits = Math.min(MapUtil.getInt(config, "maxOffsetDigits", 3), 12);
        boolean allowNegative = Boolean.TRUE.equals(MapUtil.getBool(config, "allowNegative"));
        long bound = (long) Math.pow(10, digits);
        long offset = RandomUtil.randomLong(1, bound);
        String text = str(value);
        if (value instanceof Number || NumberUtil.isNumber(text)) {
            if (allowNegative && RandomUtil.randomBoolean()) {
                offset = -offset;
            }
            BigDecimal result = new BigDecimal(text).add(BigDecimal.valueOf(offset));
            return value instanceof Number ? result : result.toPlainString();
        }
        // 文本类型：末尾追加正偏移数
        return text + offset;
    }

    // ==================== REPLACE / CONTENT_REPLACE 替换 ====================

    /**
     * 按码表替换：EXACT 全值相等命中，FUZZY 包含命中（替换全部出现处）；按 sequenceNo 顺序命中即停
     */
    private static String contentReplace(String text, Map<String, Object> config,
            List<SafetyReplaceItemDO> replaceItems) {
        if (replaceItems == null || replaceItems.isEmpty()) {
            return text;
        }
        boolean fuzzy = "FUZZY".equals(MapUtil.getStr(config, "matchMode"));
        List<SafetyReplaceItemDO> sorted = replaceItems.stream()
                .sorted(Comparator.comparing(item -> item.getSequenceNo() == null ? 0 : item.getSequenceNo()))
                .toList();
        for (SafetyReplaceItemDO item : sorted) {
            String matchValue = StrUtil.nullToEmpty(item.getMatchValue());
            String replacement = StrUtil.nullToEmpty(item.getReplacementValue());
            if (fuzzy) {
                if (text.contains(matchValue)) {
                    return text.replace(matchValue, replacement);
                }
            } else if (text.equals(matchValue)) {
                return replacement;
            }
        }
        return text;
    }

    // ==================== RANDOM_REPLACE 随机替换 ====================

    /**
     * 随机替换：随机选取 replaceCount 个位置替换为 replaceChar
     */
    private static String randomReplace(String text, Map<String, Object> config) {
        if (text.isEmpty()) {
            return text;
        }
        char replaceChar = StrUtil.blankToDefault(MapUtil.getStr(config, "replaceChar"), "*").charAt(0);
        int count = Math.min(MapUtil.getInt(config, "replaceCount", 1), text.length());
        char[] chars = text.toCharArray();
        // 随机不重复位置
        java.util.Set<Integer> positions = new java.util.HashSet<>();
        while (positions.size() < count) {
            positions.add(RandomUtil.randomInt(0, chars.length));
        }
        for (Integer position : positions) {
            chars[position] = replaceChar;
        }
        return new String(chars);
    }

    // ==================== REDUCE_PRECISION 降低精度 ====================

    /**
     * 降低精度：数字按小数位截断（保留 precision 位小数）；
     * 时间按精度从小到大逐级置零（1-秒 2-分秒 3-时分秒 ≥4-回到当月1日零点）
     */
    private static Object reducePrecision(Object value, Map<String, Object> config) {
        int precision = Math.max(MapUtil.getInt(config, "precision", 1), 1);
        // 时间类型
        if (value instanceof Timestamp timestamp) {
            return Timestamp.valueOf(reduceDateTime(timestamp.toLocalDateTime(), precision));
        }
        if (value instanceof LocalDateTime dateTime) {
            return reduceDateTime(dateTime, precision);
        }
        if (value instanceof java.sql.Date) {
            return value; // 纯日期无更低精度可置零，原样返回
        }
        String text = str(value);
        // 数字：截断小数位
        if (value instanceof Number || NumberUtil.isNumber(text)) {
            BigDecimal decimal = new BigDecimal(text);
            if (decimal.scale() <= precision) {
                return value;
            }
            BigDecimal reduced = decimal.setScale(precision, RoundingMode.DOWN);
            return value instanceof Number ? reduced : reduced.toPlainString();
        }
        // 字符串形式的时间
        try {
            DateTime parsed = DateUtil.parse(text);
            return reduceDateTime(parsed, precision).toString("yyyy-MM-dd HH:mm:ss");
        } catch (Exception ignore) {
            return text;
        }
    }

    /**
     * 按指定时间精度清除秒、分、时或日以下的信息。
     */
    private static LocalDateTime reduceDateTime(LocalDateTime dateTime, int precision) {
        LocalDateTime reduced = dateTime.withNano(0).withSecond(0);
        if (precision >= 2) {
            reduced = reduced.withMinute(0);
        }
        if (precision >= 3) {
            reduced = reduced.withHour(0);
        }
        if (precision >= 4) {
            reduced = reduced.withDayOfMonth(1);
        }
        return reduced;
    }

    /**
     * 按指定时间精度清除秒、分、时或日以下的信息。
     */
    private static DateTime reduceDateTime(DateTime dateTime, int precision) {
        DateTime reduced = DateUtil.truncate(dateTime, DateField.MINUTE);
        if (precision >= 2) {
            reduced = DateUtil.truncate(reduced, DateField.HOUR_OF_DAY);
        }
        if (precision >= 3) {
            reduced = DateUtil.truncate(reduced, DateField.DAY_OF_MONTH);
        }
        if (precision >= 4) {
            reduced = DateUtil.truncate(reduced, DateField.MONTH);
        }
        return reduced;
    }

    // ==================== TRUNCATE 内容截断 ====================

    /**
     * 内容截断：保留前 truncateLength 位
     */
    private static String truncate(String text, Map<String, Object> config) {
        int length = Math.max(MapUtil.getInt(config, "truncateLength", 1), 1);
        return text.length() <= length ? text : text.substring(0, length);
    }

    // ==================== REGEX 正则替换 ====================

    /**
     * 按配置的正则表达式替换文本，未配置表达式时保留输入。
     */
    private static String regexReplace(String text, Map<String, Object> config) {
        String pattern = MapUtil.getStr(config, "pattern");
        String replacement = StrUtil.nullToEmpty(MapUtil.getStr(config, "replacement"));
        if (StrUtil.isEmpty(pattern)) {
            return text;
        }
        return text.replaceAll(pattern, replacement);
    }

    // ==================== HASH 哈希 ====================

    /**
     * 按配置的摘要算法计算文本摘要，拒绝不支持的算法。
     */
    private static String hash(String text, String algorithm) {
        return switch (StrUtil.nullToEmpty(algorithm)) {
            case "MD5" -> SecureUtil.md5(text);
            case "SHA" -> SecureUtil.sha1(text);
            case "SHA256" -> SecureUtil.sha256(text);
            case "SHA384" -> new Digester("SHA-384").digestHex(text);
            case "SHA512" -> new Digester("SHA-512").digestHex(text);
            case "SM3" -> SmUtil.sm3(text);
            default -> throw exception(MASK_METHOD_NOT_SUPPORTED, "HASH/" + algorithm);
        };
    }

    // ==================== ENCRYPT 加解密 ====================

    /**
     * 按配置选择编码、摘要或对称加密方式处理文本。
     */
    private static String encryptByKeyRef(String text, Map<String, Object> config, SafetyKeyCipher keyCipher) {
        if (keyCipher == null) {
            throw exception(MASK_METHOD_NOT_SUPPORTED, "ENCRYPT/KEY_REF");
        }
        return keyCipher.encrypt(MapUtil.getStr(config, "keyCode"), text);
    }

    /**
     * 按配置的加密方式处理文本。内联密钥（secretKey）只为兼容历史规则保留，新规则必须使用 KEY_REF。
     */
    private static String encrypt(String text, Map<String, Object> config) {
        String encryptType = StrUtil.nullToEmpty(MapUtil.getStr(config, "encryptType"));
        return switch (encryptType) {
            case "BASE64" -> Base64.encode(text);
            case "DIGEST" -> hash(text, MapUtil.getStr(config, "algorithm"));
            case "AES" -> SecureUtil.aes(normalizeKey(MapUtil.getStr(config, "secretKey"), 16))
                    .encryptHex(text);
            case "SM" -> encryptSm(text, config);
            default -> throw exception(MASK_METHOD_NOT_SUPPORTED, "ENCRYPT/" + encryptType);
        };
    }

    /**
     * 按配置的国密算法加密文本或计算摘要。
     */
    private static String encryptSm(String text, Map<String, Object> config) {
        String algorithm = StrUtil.nullToEmpty(MapUtil.getStr(config, "algorithm"));
        return switch (algorithm) {
            case "SM2" -> SmUtil.sm2(MapUtil.getStr(config, "privateKey"), MapUtil.getStr(config, "publicKey"))
                    .encryptHex(text, KeyType.PublicKey);
            case "SM3" -> SmUtil.sm3(text);
            case "SM4" -> SmUtil.sm4(normalizeKey(MapUtil.getStr(config, "secretKey"), 16))
                    .encryptHex(text);
            default -> throw exception(MASK_METHOD_NOT_SUPPORTED, "SM/" + algorithm);
        };
    }

    /**
     * 归一化对称密钥：长度恰好等于要求时按 UTF-8 字节使用，否则取密钥 MD5 摘要的前 N 字节
     * （保证任意长度口令都能得到确定的合法密钥）
     */
    private static byte[] normalizeKey(String secretKey, int length) {
        byte[] raw = StrUtil.nullToEmpty(secretKey).getBytes(StandardCharsets.UTF_8);
        if (raw.length == length) {
            return raw;
        }
        byte[] digest = SecureUtil.md5().digest(raw);
        byte[] key = new byte[length];
        System.arraycopy(digest, 0, key, 0, Math.min(length, digest.length));
        return key;
    }

}
