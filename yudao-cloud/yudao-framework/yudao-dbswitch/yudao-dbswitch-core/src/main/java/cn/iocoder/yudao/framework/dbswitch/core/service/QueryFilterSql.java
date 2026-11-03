package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.List;
import java.util.Objects;

/**
 * Renders one typed predicate with JDBC placeholders, without executing SQL or opening resources.
 * Callers must resolve the column from authorized server metadata and convert input to a typed value.
 * Identifier quoting reuses the existing verified physical-identifier contract. Common comparisons
 * retain that product scope; literal LIKE is deliberately limited to the dialects listed below.
 * Rendering does not establish native-type, collation or full query execution compatibility.
 */
public final class QueryFilterSql {

    /**
     * 初始化 {@code QueryFilterSql}。
     */
    private QueryFilterSql() {
    }

    /** Product capability for this predicate only, separate from portable field applicability. */
    public static boolean supports(ProductTypeEnum product, QueryFilterOperator operator) {
        if (product == null || operator == null || product == ProductTypeEnum.UNKNOWN
                || product == ProductTypeEnum.MONGODB || product == ProductTypeEnum.ELASTICSEARCH) {
            return false;
        }
        if (operator == QueryFilterOperator.CONTAINS || operator == QueryFilterOperator.NOT_CONTAINS) {
            return product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.MYSQL
                    || product == ProductTypeEnum.MARIADB || product == ProductTypeEnum.SQLITE3
                    || product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000;
        }
        // DM's empty-string behavior depends on database compatibility mode, absent from this API.
        return product != ProductTypeEnum.DM
                || (operator != QueryFilterOperator.IS_EMPTY && operator != QueryFilterOperator.IS_NOT_EMPTY);
    }

    /**
     * 将当前结构定义渲染为目标 SQL 或表达式。
     */
    public static Fragment render(ProductTypeEnum product, String columnName, int fieldType,
            QueryFilterOperator operator, Object value) {
        if (operator == null || !operator.supports(fieldType)) {
            throw new IllegalArgumentException("Query filter operator is incompatible with the column type");
        }
        if (!supports(product, operator)) {
            throw new IllegalArgumentException("Query filter operator is not supported for the database product");
        }
        String column = RestrictedSelectGuard.quotePhysicalIdentifier(product, columnName);
        if (operator.requiresValue()) {
            validateValue(fieldType, value);
        }
        return switch (operator) {
            case EQ -> bound(column + " = ?", value);
            case NE -> bound(column + " <> ?", value);
            case GT -> bound(column + " > ?", value);
            case GE -> bound(column + " >= ?", value);
            case LT -> bound(column + " < ?", value);
            case LE -> bound(column + " <= ?", value);
            case CONTAINS -> bound(column + " LIKE ? ESCAPE '!'", containsPattern(product, (String) value));
            case NOT_CONTAINS -> bound(column + " NOT LIKE ? ESCAPE '!'", containsPattern(product, (String) value));
            case IS_NULL -> new Fragment(column + " IS NULL", List.of());
            case IS_NOT_NULL -> new Fragment(column + " IS NOT NULL", List.of());
            // Oracle stores the empty string as NULL; the two concepts cannot be queried as equals.
            case IS_EMPTY -> product == ProductTypeEnum.ORACLE
                    ? new Fragment("1 = 0", List.of()) : bound(column + " = ?", "");
            case IS_NOT_EMPTY -> product == ProductTypeEnum.ORACLE
                    ? new Fragment(column + " IS NOT NULL", List.of()) : bound(column + " <> ?", "");
        };
    }

    /**
     * 返回当前边界。
     */
    private static Fragment bound(String sql, Object value) {
        return new Fragment(sql, List.of(value));
    }

    /**
     * 判断是否包含匹配表达式。
     */
    private static String containsPattern(ProductTypeEnum product, String value) {
        String escaped = value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        // SQL Server LIKE also treats '[' as the start of a character class.
        if (product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000) {
            escaped = escaped.replace("[", "![");
        }
        return "%" + escaped + "%";
    }

    /**
     * 校验值。
     */
    private static void validateValue(int fieldType, Object value) {
        boolean valid = switch (fieldType) {
            case ColumnMetaData.TYPE_STRING -> value instanceof String;
            case ColumnMetaData.TYPE_BOOLEAN -> value instanceof Boolean;
            case ColumnMetaData.TYPE_NUMBER -> value instanceof Number number && Double.isFinite(number.doubleValue());
            case ColumnMetaData.TYPE_INTEGER -> integral(value);
            case ColumnMetaData.TYPE_BIGNUMBER -> value instanceof BigDecimal || integral(value);
            case ColumnMetaData.TYPE_DATE -> value instanceof java.sql.Date || value instanceof LocalDate;
            case ColumnMetaData.TYPE_TIMESTAMP -> value instanceof java.sql.Timestamp || value instanceof LocalDateTime
                    || value instanceof Instant || value instanceof OffsetDateTime;
            case ColumnMetaData.TYPE_TIME ->
                    value instanceof java.sql.Time || value instanceof LocalTime || value instanceof OffsetTime;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Query filter requires a non-null value converted to the column type");
        }
    }

    /**
     * 检查并转换当前输入为不含小数部分的整数值。
     */
    private static boolean integral(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger;
    }

    /** Ordered bindings are copied and cannot be added, removed or replaced by a caller. */
    public record Fragment(String sql, List<Object> parameters) {

        /**
         * 校验构造参数并初始化 {@code Fragment}。
         */
        public Fragment {
            Objects.requireNonNull(sql, "sql");
            parameters = List.copyOf(parameters);
        }

        /** Avoid including filter values in accidental diagnostic logging. */
        @Override
        public String toString() {
            return "QueryFilterSql.Fragment[parameters=" + parameters.size() + "]";
        }
    }
}
