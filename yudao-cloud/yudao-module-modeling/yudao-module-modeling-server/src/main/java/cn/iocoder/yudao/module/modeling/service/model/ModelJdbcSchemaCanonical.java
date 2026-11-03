package cn.iocoder.yudao.module.modeling.service.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.JDBCType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 原始 JDBC 表结构的规范表示及摘要。
 *
 * 摘要格式标识沿用 standard-gate-jdbc-schema-v1，保证与历史部署记录中的结构摘要可比。
 */
public final class ModelJdbcSchemaCanonical {

    /**
     * 限制模型JDBC结构规范工具的实例化，统一通过静态能力使用。
     */
    private ModelJdbcSchemaCanonical() {
    }

    /**
     * 计算输入内容的 SHA-256 摘要。
     *
     * @param columns 列集合
     * @return 计算输入内容的 SHA-256 摘要文本
     */
    public static String sha256(List<ColumnSnapshot> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("an existing table must expose at least one column");
        }
        List<ColumnSnapshot> ordered = new ArrayList<>(columns);
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("column snapshot must not be null");
        }
        ordered.sort(Comparator.comparing(ColumnSnapshot::canonicalName));
        Set<String> names = new HashSet<>();
        StringBuilder canonical = new StringBuilder(256 + ordered.size() * 96);
        add(canonical, "format", "standard-gate-jdbc-schema-v1");
        add(canonical, "columns.size", ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            ColumnSnapshot column = ordered.get(index);
            if (!names.add(column.canonicalName())) {
                throw new IllegalArgumentException("duplicate case-insensitive column: " + column.columnName());
            }
            String prefix = "columns[" + index + "].";
            add(canonical, prefix + "name", column.canonicalName());
            add(canonical, prefix + "jdbcType", column.jdbcType());
            add(canonical, prefix + "typeName", column.canonicalTypeName());
            add(canonical, prefix + "columnSize", column.columnSize());
            add(canonical, prefix + "decimalDigits", column.decimalDigits());
            add(canonical, prefix + "nullable", column.nullable());
            add(canonical, prefix + "primaryKey", column.primaryKey());
        }
        return sha256(canonical.toString());
    }

    /**
     * 获取模型JDBC结构规范的JDBC类型名称。
     *
     * @param jdbcType JDBC 类型
     * @return 模型JDBC结构规范的JDBC类型名称文本
     */
    public static String jdbcTypeName(int jdbcType) {
        try {
            return JDBCType.valueOf(jdbcType).getName().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return "unknown:" + jdbcType;
        }
    }

    /**
     * 校验安全标识符。
     *
     * @param identifier 标识符
     * @return 校验安全标识符文本
     */
    public static String requireSafeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("database identifier must not be blank");
        }
        String normalized = Normalizer.normalize(identifier, Normalizer.Form.NFC);
        if (!normalized.equals(identifier) || !normalized.equals(normalized.trim())
                || normalized.length() > 255 || normalized.indexOf(';') >= 0
                || normalized.indexOf('.') >= 0 || normalized.contains("--")
                || normalized.contains("/*") || normalized.contains("*/")) {
            throw new IllegalArgumentException("unsafe database identifier");
        }
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint) || Character.isSurrogate((char) codePoint)) {
                throw new IllegalArgumentException("unsafe database identifier");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    /**
     * 规范化类型名称。
     *
     * @param typeName 类型名称
     * @param jdbcType JDBC 类型
     * @return 规范化类型名称文本
     */
    private static String normalizeTypeName(String typeName, int jdbcType) {
        if (typeName == null || typeName.isBlank()) {
            return jdbcTypeName(jdbcType);
        }
        return Normalizer.normalize(typeName, Normalizer.Form.NFC).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 添加模型JDBC结构规范。
     *
     * @param out 输出
     * @param name 名称
     * @param value 待处理的值
     */
    private static void add(StringBuilder out, String name, Object value) {
        String encoded = value == null ? null : String.valueOf(value);
        out.append(name.length()).append(':').append(name).append('=');
        if (encoded == null) {
            out.append("-1:");
        } else {
            out.append(encoded.length()).append(':').append(encoded);
        }
        out.append(';');
    }

    /**
     * 计算输入内容的 SHA-256 摘要。
     *
     * @param value 待处理的值
     * @return 计算输入内容的 SHA-256 摘要文本
     */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** {@link java.sql.DatabaseMetaData#getColumns} 返回的原始值。 */
    public record ColumnSnapshot(String columnName, int jdbcType, String typeName,
                                 Integer columnSize, Integer decimalDigits,
                                 boolean nullable, boolean primaryKey) {

        /**
         * 创建列快照实例。
         *
         * @param columnName 列名
         * @param jdbcType JDBC 类型
         * @param typeName 类型名称
         * @param columnSize 列大小
         * @param decimalDigits 小数位数
         * @param nullable 可空
         * @param primaryKey 主键
         */
        public ColumnSnapshot {
            requireSafeIdentifier(columnName);
            typeName = normalizeTypeName(typeName, jdbcType);
            columnSize = columnSize == null || columnSize <= 0 ? null : columnSize;
            decimalDigits = decimalDigits == null || decimalDigits < 0 ? null : decimalDigits;
        }

        /**
         * 获取列快照的规范名称。
         *
         * @return 列快照的规范名称文本
         */
        public String canonicalName() {
            return Normalizer.normalize(columnName, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        }

        /**
         * 返回列快照的类型名称。
         *
         * @return 列快照的类型名称文本
         */
        public String canonicalTypeName() {
            return typeName;
        }
    }
}
