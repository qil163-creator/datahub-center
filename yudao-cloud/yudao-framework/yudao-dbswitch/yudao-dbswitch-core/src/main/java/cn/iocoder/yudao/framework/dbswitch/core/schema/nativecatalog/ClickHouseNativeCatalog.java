package cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * ClickHouse 25.8.33.6 的列目录。{@code system.columns.type} 是原生类型。
 * 此服务器无法识别 {@code information_schema.COLUMNS.GENERATION_EXPRESSION}（代码 47），
 * 所以此查询不得使用 MySQL 列目录。未选择主键标记或其他对象。
 */
public final class ClickHouseNativeCatalog {

    public static final String SQL = """
            SELECT name AS column_name, type AS native_type, default_kind, default_expression,
              comment AS column_comment, numeric_precision, numeric_scale,
              character_octet_length AS character_length
            FROM system.columns WHERE database=? AND table=? ORDER BY position
            """;

    /**
     * 初始化 {@code ClickHouseNativeCatalog}。
     */
    private ClickHouseNativeCatalog() {
    }

    /** 将一行 {@code system.columns} 覆盖到 JDBC 列空壳上，不设置主键。 */
    public static void apply(ColumnDescription column, ResultSet rows, MetadataProvenance source) throws SQLException {
        if (column == null || source == null || !source.nativeProduct(ProductTypeEnum.CLICKHOUSE)) {
            throw new SQLFeatureNotSupportedException("ClickHouse catalog provenance is unverified", "0A000");
        }
        String name = rows.getString("column_name");
        String nativeType = rows.getString("native_type");
        if (name == null || name.isBlank() || !name.equals(column.getFieldName())
                || nativeType == null || nativeType.isBlank()) {
            throw new SQLException("ClickHouse column catalog row is incomplete");
        }
        String kind = rows.getString("default_kind");
        String expression = rows.getString("default_expression");
        MetadataValue<Integer> precision = integer(rows, "numeric_precision");
        MetadataValue<Integer> scale = integer(rows, "numeric_scale");
        if ((precision.state() == MetadataValue.State.ABSENT) != (scale.state() == MetadataValue.State.ABSENT)) {
            throw new SQLException("ClickHouse numeric precision and scale were not observed together");
        }
        MetadataValue<String> defaultExpression;
        MetadataValue<String> generated;
        String publishedDefault;
        if (kind == null || kind.isEmpty()) {
            if (expression != null && !expression.isEmpty()) {
                throw new SQLException("ClickHouse default expression has no default kind");
            }
            defaultExpression = MetadataValue.absent();
            generated = MetadataValue.absent();
            publishedDefault = null;
        } else if ("DEFAULT".equals(kind)) {
            if (expression == null || expression.isEmpty()) {
                throw new SQLException("ClickHouse DEFAULT expression is missing");
            }
            // 存储的文本已经是 SQL，例如整数 1、字符串 'a'，不要再次加引号。
            defaultExpression = MetadataValue.value(expression);
            generated = MetadataValue.absent();
            publishedDefault = expression;
        } else {
            // 此服务器上未读取 MATERIALIZED、ALIAS 和 EPHEMERAL。
            defaultExpression = MetadataValue.unsupported();
            generated = MetadataValue.unsupported();
            publishedDefault = null;
        }
        column.setNullable(nullable(nativeType));
        column.setRemarks(rows.getString("column_comment"));
        column.setDefaultValue(publishedDefault);
        // 在固定的 25.8 目录上，普通列仅在观测到 kind 和 expression 均为空时
        // 才没有生成器。DEFAULT 可能调用 generateSerialID（Keeper 计数器），所以不能针对
        // 此类型、未探测的生成类型或缺失目录单元格推断不存在。
        MetadataValue<String> identity = kind != null && kind.isEmpty() && expression != null && expression.isEmpty()
                ? MetadataValue.absent() : MetadataValue.unknown();
        // 所有已探测 information_schema 行的字符集和排序规则均为 NULL，且它们不是
        // system.columns 的列。
        column.setMetadataEvidence(new ColumnMetadataEvidence(source, MetadataValue.value(nativeType),
                defaultExpression, MetadataValue.value(column.isNullable()), precision, scale,
                wide(rows, "character_length"), identity, generated,
                MetadataValue.absent(), MetadataValue.absent()));
    }

    /** 空值约束由原生类型文本携带，不存在 is_nullable 列。 */
    private static boolean nullable(String type) {
        return type.startsWith("Nullable(") && type.endsWith(")")
                || type.startsWith("LowCardinality(Nullable(") && type.endsWith("))");
    }

    /**
     * 从 JDBC 结果集读取并转换整数。
     */
    private static MetadataValue<Integer> integer(ResultSet rows, String label) throws SQLException {
        Long value = integral(rows, label);
        if (value == null) {
            return MetadataValue.absent();
        }
        if (value > Integer.MAX_VALUE) {
            throw new SQLException("ClickHouse catalog numeric value is out of range");
        }
        return MetadataValue.value(value.intValue());
    }

    /**
     * 为当前值域选择能够容纳输入的宽类型。
     */
    private static MetadataValue<Long> wide(ResultSet rows, String label) throws SQLException {
        Long value = integral(rows, label);
        return value == null ? MetadataValue.absent() : MetadataValue.value(value);
    }

    /** SQL NULL 返回 null。负数或小数单元格不是此处观测到的目录宽度。 */
    private static Long integral(ResultSet rows, String label) throws SQLException {
        Object value = rows.getObject(label);
        if (value == null || rows.wasNull()) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new SQLException("ClickHouse catalog numeric value is not an integer");
        }
        long exact;
        try {
            if (number instanceof BigInteger integer) {
                exact = integer.longValueExact();
            } else if (number instanceof BigDecimal decimal) {
                exact = decimal.longValueExact();
            } else if (number instanceof Double || number instanceof Float) {
                throw new SQLException("ClickHouse catalog numeric value is not an integer");
            } else {
                exact = number.longValue();
            }
        } catch (ArithmeticException overflow) {
            throw new SQLException("ClickHouse catalog numeric value is out of range");
        }
        if (exact < 0) {
            throw new SQLException("ClickHouse catalog numeric value is out of range");
        }
        return exact;
    }
}
