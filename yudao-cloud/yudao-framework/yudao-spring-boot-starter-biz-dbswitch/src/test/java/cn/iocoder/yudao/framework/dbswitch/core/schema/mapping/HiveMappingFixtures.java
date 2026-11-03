package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveLogicalTypeMapper;

import java.sql.Types;
import java.time.Instant;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.HIVE;

/**
 * Column fixtures with the JDBC and DESCRIBE facts probed on Hive 4.1.0 through hive-jdbc 4.1.0.
 * Lengths, classes and signed flags are from that server, not from Doris or PostgreSQL.
 */
final class HiveMappingFixtures {
    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]+),([0-9]+)\\)");
    private static final Pattern TEXT = Pattern.compile("(char|varchar)\\(([0-9]+)\\)");

    private HiveMappingFixtures() { }

    static ColumnDescription family(String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(HIVE);
        column.setNullable(true);
        column.setSigned(false);
        MetadataValue<Integer> precision = MetadataValue.absent();
        MetadataValue<Integer> scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        switch (nativeType) {
            case "tinyint" -> integer(column, Types.TINYINT, 3, "java.lang.Byte");
            case "smallint" -> integer(column, Types.SMALLINT, 5, "java.lang.Short");
            case "int" -> integer(column, Types.INTEGER, 10, "java.lang.Integer");
            case "bigint" -> integer(column, Types.BIGINT, 19, "java.lang.Long");
            case "float" -> scaled(column, Types.FLOAT, 7, 7, "java.lang.Float", false);
            case "double" -> scaled(column, Types.DOUBLE, 15, 15, "java.lang.Double", false);
            case "boolean" -> scaled(column, Types.BOOLEAN, 1, 0, "java.lang.Boolean", false);
            case "string" -> unbounded(column, Types.VARCHAR, "java.lang.String");
            case "date" -> scaled(column, Types.DATE, 10, 0, "java.sql.Date", false);
            case "timestamp" -> scaled(column, Types.TIMESTAMP, 29, 9, "java.sql.Timestamp", false);
            case "binary" -> unbounded(column, Types.BINARY, "[B");
            default -> {
                Matcher decimal = DECIMAL.matcher(nativeType);
                Matcher text = TEXT.matcher(nativeType);
                if (decimal.matches()) {
                    int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
                    scaled(column, Types.DECIMAL, p, s, "java.math.BigInteger", false);
                } else if (text.matches()) {
                    int n = Integer.parseInt(text.group(2));
                    boolean fixed = text.group(1).equals("char");
                    scaled(column, fixed ? Types.CHAR : Types.VARCHAR, n, 0, "java.lang.String", false);
                    length = MetadataValue.value((long) n);
                } else {
                    column.setFieldType(Types.OTHER);
                    column.setFiledTypeClassName("java.lang.Object");
                }
            }
        }
        if (nativeType.equals("tinyint") || nativeType.equals("smallint") || nativeType.equals("int") || nativeType.equals("bigint")) {
            precision = MetadataValue.value(column.getPrecisionSize());
            scale = MetadataValue.value(0);
        } else if (nativeType.equals("float") || nativeType.equals("double") || nativeType.equals("boolean")
                || nativeType.equals("date") || nativeType.equals("timestamp") || nativeType.startsWith("decimal(")) {
            precision = MetadataValue.value(column.getPrecisionSize());
            scale = MetadataValue.value(column.getScaleSize());
        }
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(true), precision, scale, length,
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent()));
        return column;
    }

    static ColumnDescription withFacts(ColumnDescription column, UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(HIVE, "Apache Hive", HiveLogicalTypeMapper.VERSION, "HIVE_CATALOG_V1", Instant.EPOCH);
    }

    private static void integer(ColumnDescription column, int jdbc, int jdbcPrecision, String javaClass) {
        scaled(column, jdbc, jdbcPrecision, 0, javaClass, true);
    }

    private static void scaled(ColumnDescription column, int jdbc, int jdbcPrecision, int jdbcScale, String javaClass, boolean signed) {
        column.setFieldType(jdbc);
        column.setPrecisionSize(jdbcPrecision);
        column.setScaleSize(jdbcScale);
        column.setFiledTypeClassName(javaClass);
        column.setSigned(signed);
    }

    private static void unbounded(ColumnDescription column, int jdbc, String javaClass) {
        scaled(column, jdbc, 2147483647, 0, javaClass, false);
    }
}
