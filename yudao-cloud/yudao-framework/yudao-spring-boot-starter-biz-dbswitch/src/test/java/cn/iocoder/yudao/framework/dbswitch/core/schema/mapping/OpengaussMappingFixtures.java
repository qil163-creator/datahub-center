package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.opengauss.OpengaussLogicalTypeMapper;

import java.sql.Types;
import java.time.Instant;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OPENGAUSS;

/**
 * Column fixtures for openGauss 7.0.0-RC3 build 01b7e318, server_version 9.2.4, sql_compatibility A,
 * org.opengauss.Driver 7.0.0-RC3-og. JDBC type, precision, scale, class and signed are the probed driver
 * values; native_type is pg_catalog.format_type. Nothing is copied from the PostgreSQL 16 fixtures.
 */
final class OpengaussMappingFixtures {
    private static final int UNBOUNDED = Integer.MAX_VALUE;
    private static final Pattern NUMERIC = Pattern.compile("numeric\\(([0-9]+),([0-9]+)\\)");
    private static final Pattern CHARS = Pattern.compile("(character varying|character)\\(([0-9]+)\\)");
    private static final Pattern TIME = Pattern.compile("time(?:\\(([0-6])\\))? without time zone");
    private static final Pattern TIMESTAMP = Pattern.compile("timestamp(?:\\(([0-6])\\))? (with|without) time zone");

    private OpengaussMappingFixtures() { }

    static ColumnDescription column(String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(OPENGAUSS);
        column.setNullable(true);
        MetadataValue<Integer> precision = MetadataValue.absent();
        MetadataValue<Integer> scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        if (nativeType.startsWith("timestamp")) {
            var temporal = TIMESTAMP.matcher(nativeType);
            if (!temporal.matches()) throw new IllegalArgumentException(nativeType);
            int digits = temporal.group(1) == null ? 6 : Integer.parseInt(temporal.group(1));
            boolean zone = temporal.group(2).equals("with");
            int extra = digits == 0 ? 0 : digits + 1;
            column.setFieldType(Types.TIMESTAMP);
            column.setFieldTypeName(zone ? "timestamptz" : "timestamp");
            column.setPrecisionSize((zone ? 28 : 22) + extra);
            column.setScaleSize(digits);
            column.setFiledTypeClassName("java.sql.Timestamp");
            column.setSigned(false);
        } else {
            var time = TIME.matcher(nativeType);
            var numeric = NUMERIC.matcher(nativeType);
            var chars = CHARS.matcher(nativeType);
            if (time.matches()) {
                int digits = time.group(1) == null ? 6 : Integer.parseInt(time.group(1));
                column.setFieldType(Types.TIME);
                column.setFieldTypeName("time");
                column.setPrecisionSize(8 + (digits == 0 ? 0 : digits + 1));
                column.setScaleSize(digits);
                column.setFiledTypeClassName("java.sql.Time");
                column.setSigned(false);
            } else if (numeric.matches()) {
                int p = Integer.parseInt(numeric.group(1)), s = Integer.parseInt(numeric.group(2));
                number(column, Types.DECIMAL, "numeric", p, s, true, "java.math.BigDecimal");
                precision = MetadataValue.value(p);
                scale = MetadataValue.value(s);
            } else if (chars.matches()) {
                boolean fixed = chars.group(1).equals("character");
                int n = Integer.parseInt(chars.group(2));
                number(column, fixed ? Types.CHAR : Types.VARCHAR, fixed ? "bpchar" : "varchar", n, 0, false, "java.lang.String");
                length = MetadataValue.value((long) n);
            } else {
                switch (nativeType) {
                    case "smallint" -> { number(column, Types.SMALLINT, "int2", 5, 0, true, "java.lang.Integer"); precision = MetadataValue.value(16); scale = MetadataValue.value(0); }
                    case "integer" -> { number(column, Types.INTEGER, "int4", 10, 0, true, "java.lang.Integer"); precision = MetadataValue.value(32); scale = MetadataValue.value(0); }
                    case "bigint" -> { number(column, Types.BIGINT, "int8", 19, 0, true, "java.lang.Long"); precision = MetadataValue.value(64); scale = MetadataValue.value(0); }
                    case "numeric" -> number(column, Types.DECIMAL, "numeric", 0, 0, true, "java.math.BigDecimal");
                    case "real" -> { number(column, Types.REAL, "float4", 8, 8, true, "java.lang.Float"); precision = MetadataValue.value(24); }
                    case "double precision" -> { number(column, Types.DOUBLE, "float8", 17, 17, true, "java.lang.Double"); precision = MetadataValue.value(53); }
                    case "boolean" -> number(column, Types.BOOLEAN, "bool", 1, 0, false, "java.lang.Boolean");
                    case "text" -> number(column, Types.VARCHAR, "text", UNBOUNDED, 0, false, "java.lang.String");
                    case "bytea" -> number(column, Types.BINARY, "bytea", UNBOUNDED, 0, false, "[B");
                    case "json" -> number(column, Types.VARCHAR, "json", UNBOUNDED, 0, false, "java.lang.String");
                    case "uuid" -> number(column, Types.OTHER, "uuid", UNBOUNDED, 0, false, "java.util.UUID");
                    case "date", "int2" -> number(column, Types.OTHER, nativeType, 0, 0, false, "java.lang.Object");
                    default -> throw new IllegalArgumentException(nativeType);
                }
            }
        }
        column.setMetadataEvidence(evidence(nativeType, precision, scale, length));
        return column;
    }

    static ColumnDescription withFacts(ColumnDescription column, java.util.function.UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(OPENGAUSS, OpengaussLogicalTypeMapper.BANNER, OpengaussLogicalTypeMapper.SERVER_VERSION,
                OpengaussLogicalTypeMapper.METHOD, Instant.EPOCH);
    }

    private static void number(ColumnDescription column, int jdbc, String typeName, int precision, int scale,
                               boolean signed, String className) {
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setPrecisionSize(precision);
        column.setScaleSize(scale);
        column.setSigned(signed);
        column.setFiledTypeClassName(className);
    }

    private static ColumnMetadataEvidence evidence(String nativeType, MetadataValue<Integer> precision,
                                                    MetadataValue<Integer> scale, MetadataValue<Long> length) {
        return new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType), MetadataValue.absent(),
                MetadataValue.value(true), precision, scale, length, MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
    }
}
