package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_AUTH_FAILED;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_REQUEST_INVALID;

/**
 * 数据服务开放网关唯一签名协议：HMAC-SM3。
 *
 * <p>签名原文按固定顺序包含协议标识、HTTP 方法、规范路径、规范化查询串、原始请求体 SM3 摘要、
 * AppKey、毫秒时间戳、Nonce 与 Content-Type；每个字段以“长度:值\n”拼接，避免字段边界歧义。
 * {@code X-Sign = hex(HMAC-SM3(AppSecret, 签名原文))}。平台只提供这一种协议，不按请求头协商或降级。</p>
 */
public final class DataApiSignature {

    /** 签名原文首字段，绑定算法与字段布局；变更布局必须同时更换此标识。 */
    public static final String PROTOCOL = "HMAC-SM3";

    public static final int MAX_BODY_BYTES = 1024 * 1024;

    public static final int MAX_NONCE_LENGTH = 64;

    private static final Pattern NONCE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Pattern PATH_UNSAFE = Pattern.compile(".*(\\.\\.|//|%2f|%2F|%5c|%5C).*");

    private static final byte[] EMPTY_BODY = new byte[0];

    /**
     * 限制签名工具的实例化，统一通过静态能力使用。
     */
    private DataApiSignature() {
    }

    /**
     * 计算请求签名。
     *
     * @param appSecret 应用密钥明文
     * @param method HTTP 方法
     * @param canonicalPath 规范路径（/services/{version}{path}）
     * @param rawQuery 原始查询字符串
     * @param rawBody 原始请求体字节
     * @param appKey 应用标识
     * @param timestamp 毫秒时间戳
     * @param nonce 随机数
     * @param contentType 内容类型
     * @return 小写十六进制签名
     */
    public static String sign(String appSecret, String method, String canonicalPath, String rawQuery,
            byte[] rawBody, String appKey, String timestamp, String nonce, String contentType) {
        return hmacSm3Hex(appSecret, canonicalString(method, canonicalPath, rawQuery, rawBody, appKey,
                timestamp, nonce, contentType));
    }

    /**
     * 按协议规定的字段顺序构造签名原文。
     *
     * @param method HTTP 方法
     * @param canonicalPath 规范路径
     * @param rawQuery 原始查询字符串
     * @param rawBody 原始请求体
     * @param appKey 应用标识
     * @param timestamp 时间戳
     * @param nonce 随机数
     * @param contentType 内容类型
     * @return 签名原文
     */
    public static String canonicalString(String method, String canonicalPath, String rawQuery,
            byte[] rawBody, String appKey, String timestamp, String nonce, String contentType) {
        LinkedHashMap<String, String> query = DataApiQueryCodec.parse(rawQuery);
        validatePath(canonicalPath);
        validateNonce(nonce);
        validateTimestamp(timestamp);
        // 摘要覆盖实际传输字节；不能用解析后重建的 JSON 替代，以免丢失字段顺序、空白和编码差异。
        byte[] body = rawBody == null ? EMPTY_BODY : rawBody;
        if (body.length > MAX_BODY_BYTES) {
            throw exception(DATA_API_REQUEST_INVALID, "请求体超过大小限制");
        }
        return join(
                PROTOCOL,
                canonicalValue(method).toUpperCase(Locale.ROOT),
                canonicalPath,
                DataApiQueryCodec.canonicalize(query),
                bodyHash(body),
                canonicalValue(appKey),
                canonicalValue(timestamp),
                canonicalValue(nonce),
                DataApiQueryCodec.normalizeContentType(contentType));
    }

    /**
     * 计算原始请求体字节的 SM3 摘要。
     *
     * @param rawBody 原始请求体
     * @return 小写十六进制摘要
     */
    public static String bodyHash(byte[] rawBody) {
        byte[] body = rawBody == null ? EMPTY_BODY : rawBody;
        SM3Digest digest = new SM3Digest();
        digest.update(body, 0, body.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return Hex.toHexString(out);
    }

    /**
     * 将传入十六进制签名转为小写后，按固定时间比较预期签名。
     *
     * @param expected 预期值
     * @param actual 实际值
     * @return 是否一致
     */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        // 十六进制大小写统一后用固定时间比较，避免逐字符短路比较泄露已匹配的签名前缀。
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验签名路径，拒绝相对段和存在歧义的编码形式。
     *
     * @param path 路径
     */
    public static void validatePath(String path) {
        if (StrUtil.isBlank(path) || !path.startsWith("/")) {
            throw exception(DATA_API_REQUEST_INVALID, "签名路径不合法");
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('\n') >= 0) {
            throw exception(DATA_API_REQUEST_INVALID, "签名路径不合法");
        }
        if (PATH_UNSAFE.matcher(path).matches()) {
            throw exception(DATA_API_REQUEST_INVALID, "签名路径含相对段或编码斜线");
        }
    }

    /**
     * 校验签名随机数的长度及允许字符。
     *
     * @param nonce 随机数
     */
    public static void validateNonce(String nonce) {
        if (nonce == null || !NONCE_PATTERN.matcher(nonce).matches()) {
            throw exception(DATA_API_AUTH_FAILED, "Nonce 不合法");
        }
    }

    /**
     * 校验签名时间戳的十进制毫秒格式。
     *
     * @param timestamp 时间戳
     */
    public static void validateTimestamp(String timestamp) {
        // 此处只约束签名字段格式；请求是否过期及 nonce 是否已使用由网关入口校验。
        if (timestamp == null || !timestamp.chars().allMatch(Character::isDigit)
                || timestamp.length() < 13 || timestamp.length() > 16) {
            throw exception(DATA_API_AUTH_FAILED, "时间戳必须是十进制毫秒");
        }
        try {
            Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw exception(DATA_API_AUTH_FAILED, "时间戳格式不正确");
        }
    }

    /**
     * 使用 HMAC-SM3 计算签名并输出十六进制字符串。
     *
     * @param secret 密钥
     * @param canonical 签名原文
     * @return 小写十六进制签名
     */
    public static String hmacSm3Hex(String secret, String canonical) {
        byte[] key = canonicalValue(secret).getBytes(StandardCharsets.UTF_8);
        byte[] data = canonical.getBytes(StandardCharsets.UTF_8);
        HMac hmac = new HMac(new SM3Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] out = new byte[hmac.getMacSize()];
        hmac.doFinal(out, 0);
        return Hex.toHexString(out);
    }

    /**
     * 将已解析的查询参数转为执行参数映射。
     *
     * @param query 查询
     * @return 执行参数映射
     */
    public static Map<String, Object> toParamMap(LinkedHashMap<String, String> query) {
        Map<String, Object> params = new LinkedHashMap<>();
        query.forEach(params::put);
        return params;
    }

    /**
     * 按长度前缀与换行拼接签名字段，拒绝字段内换行，避免字段边界歧义。
     */
    private static String join(String... parts) {
        // 长度前缀与换行共同确定字段边界，字段包含冒号等分隔字符时也不会形成同一签名串。
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            if (value.indexOf('\n') >= 0) {
                throw exception(DATA_API_REQUEST_INVALID, "签名字段含非法换行");
            }
            result.append(value.length()).append(':').append(value).append('\n');
        }
        return result.toString();
    }

    private static String canonicalValue(String value) {
        return value == null ? "" : value;
    }
}
