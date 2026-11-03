package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.sql.Types;
import java.time.Instant;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.CLICKHOUSE;

/**
 * Column fixtures with the JDBC and system.columns facts probed on ClickHouse 25.8.33.6 through clickhouse-jdbc 0.5.0.
 * Numbers are from that server, not from the MySQL, Doris or PostgreSQL fixtures.
 */
final class ClickHouseMappingFixtures {
    private static final Pattern DECIMAL = Pattern.compile("Decimal\\(([0-9]+),\\s*([0-9]+)\\)");
    private static final Pattern FIXED = Pattern.compile("FixedString\\(([0-9]+)\\)");
    private static final Pattern DATETIME64 = Pattern.compile("DateTime64\\(([0-9])\\)");

    private ClickHouseMappingFixtures() { }

    static ColumnDescription family(String name, String nativeType) {
        String stored = nativeType;
        boolean nullable = false;
        if (stored.startsWith("LowCardinality(") && stored.endsWith(")"))
            stored = stored.substring("LowCardinality(".length(), stored.length() - 1);
        if (stored.startsWith("Nullable(") && stored.endsWith(")")) {
            nullable = true;
            stored = stored.substring("Nullable(".length(), stored.length() - 1);
        }
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(CLICKHOUSE);
        column.setNullable(nullable);
        column.setSigned(false);
        MetadataValue<Integer> precision = MetadataValue.absent();
        MetadataValue<Integer> scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        switch (stored) {
            case "Int8" -> integer(column, Types.TINYINT, 3, "java.lang.Byte", true, 8);
            case "Int16" -> integer(column, Types.SMALLINT, 5, "java.lang.Short", true, 16);
            case "Int32" -> integer(column, Types.INTEGER, 10, "java.lang.Integer", true, 32);
            case "Int64" -> integer(column, Types.BIGINT, 19, "java.lang.Long", true, 64);
            case "UInt8" -> integer(column, Types.SMALLINT, 3, "com.clickhouse.data.value.UnsignedByte", false, 8);
            case "UInt16" -> integer(column, Types.INTEGER, 5, "com.clickhouse.data.value.UnsignedShort", false, 16);
            case "UInt32" -> integer(column, Types.BIGINT, 10, "com.clickhouse.data.value.UnsignedInteger", false, 32);
            case "UInt64" -> integer(column, Types.NUMERIC, 20, "com.clickhouse.data.value.UnsignedLong", false, 64);
            case "Bool" -> {
                column.setFieldType(Types.BOOLEAN);
                column.setFiledTypeClassName("java.lang.Boolean");
                column.setSigned(true);
                column.setPrecisionSize(1);
                precision = MetadataValue.value(8);
                scale = MetadataValue.value(0);
            }
            case "Float32" -> floating(column, Types.FLOAT, 12, "java.lang.Float");
            case "Float64" -> floating(column, Types.DOUBLE, 22, "java.lang.Double");
            case "String" -> text(column, Types.VARCHAR, 0, "java.lang.String");
            case "Date", "Date32" -> temporal(column, Types.DATE, 10, 0, "java.time.LocalDate");
            case "DateTime" -> temporal(column, Types.TIMESTAMP, 29, 0, "java.time.LocalDateTime");
            case "UUID" -> text(column, Types.VARCHAR, 69, "java.util.UUID");
            default -> {
                Matcher decimal = DECIMAL.matcher(stored);
                Matcher fixed = FIXED.matcher(stored);
                Matcher dateTime64 = DATETIME64.matcher(stored);
                if (decimal.matches()) {
                    int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
                    column.setFieldType(Types.DECIMAL);
                    column.setFiledTypeClassName("java.math.BigDecimal");
                    column.setSigned(true);
                    column.setPrecisionSize(p);
                    column.setScaleSize(s);
                    precision = MetadataValue.value(p);
                    scale = MetadataValue.value(s);
                } else if (fixed.matches()) {
                    int n = Integer.parseInt(fixed.group(1));
                    text(column, Types.VARCHAR, n, "java.lang.String");
                    length = MetadataValue.value((long) n);
                } else if (dateTime64.matches()) {
                    temporal(column, Types.TIMESTAMP, 29, Integer.parseInt(dateTime64.group(1)), "java.time.LocalDateTime");
                } else if (stored.startsWith("DateTime(")) {
                    temporal(column, Types.TIMESTAMP_WITH_TIMEZONE, 29, 0, "java.time.OffsetDateTime");
                } else if (stored.startsWith("Array(")) {
                    text(column, Types.ARRAY, 0, "java.lang.Object");
                } else if (stored.startsWith("Map(")) {
                    text(column, Types.STRUCT, 0, "java.util.Map");
                } else {
                    text(column, Types.VARCHAR, 0, "java.lang.String");
                }
            }
        }
        if (stored.equals("Int8") || stored.equals("Int16") || stored.equals("Int32") || stored.equals("Int64")
                || stored.equals("UInt8") || stored.equals("UInt16") || stored.equals("UInt32") || stored.equals("UInt64")) {
            precision = MetadataValue.value(switch (stored) {
                case "Int8", "UInt8" -> 8;
                case "Int16", "UInt16" -> 16;
                case "Int32", "UInt32" -> 32;
                default -> 64;
            });
            scale = MetadataValue.value(0);
        }
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(nullable), precision, scale, length,
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }

    static ColumnDescription withFacts(ColumnDescription column, UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(CLICKHOUSE, "ClickHouse", "25.8.33.6", "CLICKHOUSE_CATALOG_V1", Instant.EPOCH);
    }

    private static void integer(ColumnDescription column, int jdbc, int jdbcPrecision, String javaClass, boolean signed, int ignoredBits) {
        column.setFieldType(jdbc);
        column.setPrecisionSize(jdbcPrecision);
        column.setFiledTypeClassName(javaClass);
        column.setSigned(signed);
        if (ignoredBits < 0) column.setScaleSize(0);
    }

    private static void floating(ColumnDescription column, int jdbc, int jdbcPrecision, String javaClass) {
        column.setFieldType(jdbc);
        column.setPrecisionSize(jdbcPrecision);
        column.setFiledTypeClassName(javaClass);
        column.setSigned(true);
    }

    private static void text(ColumnDescription column, int jdbc, int jdbcPrecision, String javaClass) {
        column.setFieldType(jdbc);
        column.setPrecisionSize(jdbcPrecision);
        column.setFiledTypeClassName(javaClass);
        column.setSigned(false);
    }

    private static void temporal(ColumnDescription column, int jdbc, int jdbcPrecision, int scale, String javaClass) {
        column.setFieldType(jdbc);
        column.setPrecisionSize(jdbcPrecision);
        column.setScaleSize(scale);
        column.setFiledTypeClassName(javaClass);
        column.setSigned(false);
    }
}
