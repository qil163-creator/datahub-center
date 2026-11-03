package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外部保留的源代际。UUID 必须是非零、小写且规范的 UUID 字符串；
 * 不裁剪空白、不转换大小写，也不解析缩写 UUID。保留 schema 的实际拼写。
 * 随恢复数据库一起复制的固定信息无法检测该恢复。恢复仍需
 * 停止消费方、隔离克隆，并在外部协调代际及固定信息的旋转。
 */
public record SourceGenerationPin(String schema, String instanceId, String generation) {

    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Set<String> RESERVED = Set.of("mysql", "information_schema", "performance_schema", "sys");

    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    /**
     * 初始化 {@code SourceGenerationPin}。
     */
    public SourceGenerationPin {
        validateSchema(schema);
        validateUuid(instanceId);
        validateUuid(generation);
    }

    /**
     * 校验模式。
     */
    public static void validateSchema(String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()
                || RESERVED.contains(schema.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("A dedicated non-system ASCII schema name is required");
        }
    }

    /**
     * 校验并解析 UUID 格式的标识符。
     */
    private static void validateUuid(String value) {
        if (value == null || !UUID.matcher(value).matches() || ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException("A nonzero lowercase canonical UUID is required");
        }
    }

    /** 对 UTF-8 编码的版本、schema、实例及代际计算 SHA-256，字段以 NUL 分隔，输出小写十六进制。 */
    public String fingerprint() {
        String material = String.join("\0", "DBSWITCH_SOURCE_GENERATION_V1", schema, instanceId, generation);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "SourceGenerationPin[redacted]";
    }
}
