package cn.iocoder.yudao.framework.dbswitch.common.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;

import java.sql.Types;
import java.util.Locale;

/** SQL 类型的保守等价判断及显式建表类型适配；未知类型不降级为字符串。 */
public final class SqlTypeSemantics {

    /**
     * 初始化 {@code SqlTypeSemantics}。
     */
    private SqlTypeSemantics() {
    }

    /**
     * 只移除数值参数，保留时区、无符号、数组等具有语义的后缀。
     */
    public static String canonicalName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        // 只移除数值参数，保留时区、无符号、数组等具有语义的后缀。
        String type = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\(\\s*\\d+\\s*(?:,\\s*\\d+\\s*)?\\)", "")
                .replaceAll("\\s+", " ").trim();
        return switch (type) {
            case "character varying" -> "varchar";
            case "character", "bpchar" -> "char";
            case "integer", "int4" -> "int";
            case "int8" -> "bigint";
            case "int2" -> "smallint";
            case "numeric", "decimalv3", "number" -> "decimal";
            case "double precision", "float8" -> "double";
            case "real", "float4" -> "float";
            case "bool" -> "boolean";
            case "timestamp without time zone", "datetime", "datetimev2" -> "timestamp";
            case "timestamp with time zone", "timestamp_with_timezone" -> "timestamptz";
            case "time without time zone" -> "time";
            case "time with time zone", "time_with_timezone" -> "timetz";
            default -> type;
        };
    }

    /** 保留驱动的原生类型；JDBC OTHER 等未知类型也不伪装成 VARCHAR。 */
    public static String metadataTypeName(ColumnDescription column) {
        String nativeName = column.getFieldTypeName();
        if (nativeName != null && !nativeName.isBlank()) {
            return nativeName.trim().toLowerCase(Locale.ROOT);
        }
        String jdbcName = JdbcTypesUtils.resolveTypeName(column.getFieldType());
        if (jdbcName == null) {
            throw new IllegalArgumentException("无法识别数据库字段类型，需人工确认");
        }
        return jdbcName.toLowerCase(Locale.ROOT);
    }

    /** 将显式声明的类型映射到已有 DDL 方言；定点数长度始终是总精度，小数位允许为零。 */
    public static void applyDeclaredType(ColumnDescription column, String typeName,
            Integer length, Integer precision, Integer scale) {
        String type = canonicalName(typeName);
        column.setPrecision(0);
        column.setLength(length == null ? 255 : length);
        switch (type) {
            case "decimal" -> {
                if (precision == null || precision <= 0 || scale == null || scale < 0 || scale > precision) {
                    throw new IllegalArgumentException("定点数字段必须明确总精度和小数位数；旧字段请确认后重新保存");
                }
                column.setFieldType(Types.DECIMAL);
                column.setValType(ColumnMetaData.TYPE_BIGNUMBER);
                column.setLength(precision);
                column.setPrecision(scale);
            }
            case "bigint", "int", "smallint", "tinyint" -> {
                column.setValType(ColumnMetaData.TYPE_INTEGER);
                column.setLength(switch (type) {
                    case "bigint" -> 18;
                    case "int" -> 9;
                    case "smallint" -> 4;
                    default -> 2;
                });
            }
            case "timestamp" -> column.setValType(ColumnMetaData.TYPE_TIMESTAMP);
            case "date" -> column.setValType(ColumnMetaData.TYPE_DATE);
            case "time" -> column.setValType(ColumnMetaData.TYPE_TIME);
            case "boolean" -> column.setValType(ColumnMetaData.TYPE_BOOLEAN);
            case "double", "float" -> {
                column.setValType(ColumnMetaData.TYPE_NUMBER);
                column.setLength(-1);
                column.setPrecision(-1);
            }
            case "varchar", "char", "text" -> {
                column.setValType(ColumnMetaData.TYPE_STRING);
                if ("text".equals(type)) {
                    column.setLength(-1);
                }
            }
            case "binary", "varbinary", "blob", "bytea" -> column.setValType(ColumnMetaData.TYPE_BINARY);
            default -> throw new IllegalArgumentException("当前自动建表尚不支持该原生类型：" + type);
        }
    }
}
