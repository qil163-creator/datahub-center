package cn.iocoder.yudao.framework.dbswitch.common.entity;

import java.util.Arrays;

/**
 * 由本组件持有且已脱离驱动的产品原生值。数据库产品定义带版本的传输格式
 * 及规范化的匹配键编码。原生写入器须在写入前校验二者是否一致；
 * 此载体无法证明任意调用方传入的键是真实有效的。
 * 值相等判断保留原生类型；键相等判断可以有意将不同原生数值类型视为相等。
 * 缺少键的验证依据仍允许无损全量传输，但绝不能参与 DIFF 匹配。
 */
public final class NativeCellValue {

    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private final String format;

    private final byte[] encoded;

    private final byte[] matchingKey;

    /**
     * 校验构造参数并初始化 {@code NativeCellValue}。
     */
    public NativeCellValue(String format, byte[] encoded, byte[] matchingKey) {
        if (format == null || !format.matches("[A-Z][A-Z0-9_]{0,63}")
                || encoded == null || encoded.length == 0 || encoded.length > MAX_BYTES
                || matchingKey != null && (matchingKey.length == 0 || matchingKey.length > MAX_BYTES)) {
            throw new IllegalArgumentException("Invalid native cell value envelope");
        }
        this.format = format;
        this.encoded = encoded.clone();
        this.matchingKey = matchingKey == null ? null : matchingKey.clone();
    }

    /**
     * 返回格式。
     */
    public String format() {
        return format;
    }

    /**
     * 返回当前编码载荷的防御性副本。
     */
    public byte[] encoded() {
        return encoded.clone();
    }

    /**
     * 检查当前行是否存在与指定键相匹配的值。
     */
    public boolean hasMatchingKey() {
        return matchingKey != null;
    }

    /**
     * 提取当前行用于匹配的键值。
     */
    public byte[] matchingKey() {
        return matchingKey == null ? null : matchingKey.clone();
    }

    /**
     * 返回估算字节数。
     */
    public long estimatedBytes() {
        return 48L + 2L * format.length() + encoded.length + (matchingKey == null ? 0 : matchingKey.length);
    }

    /**
     * 按已声明键类型比较两个匹配键的顺序。
     */
    public int compareMatchingKey(NativeCellValue other) {
        requireFormat(other);
        if (!hasMatchingKey() || !other.hasMatchingKey()) {
            throw new IllegalArgumentException("Native value has no proven matching-key encoding");
        }
        return Arrays.compareUnsigned(matchingKey, other.matchingKey);
    }

    /**
     * 比较值。
     */
    public int compareValue(NativeCellValue other) {
        requireFormat(other);
        return Arrays.compareUnsigned(encoded, other.encoded);
    }

    /**
     * 校验并取得格式。
     */
    private void requireFormat(NativeCellValue other) {
        if (other == null || !format.equals(other.format)) {
            throw new IllegalArgumentException("Native value formats require an explicit conversion plan");
        }
    }

    /**
     * 比较当前对象与给定对象是否具有相同的值。
     */
    @Override
    public boolean equals(Object value) {
        return value instanceof NativeCellValue other && format.equals(other.format)
                && Arrays.equals(encoded, other.encoded) && Arrays.equals(matchingKey, other.matchingKey);
    }

    /**
     * 计算与值相等语义一致的哈希码。
     */
    @Override
    public int hashCode() {
        return 31 * (31 * format.hashCode() + Arrays.hashCode(encoded)) + Arrays.hashCode(matchingKey);
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "NativeCellValue[format=" + format + ",encodedBytes=" + encoded.length + "]";
    }
}
