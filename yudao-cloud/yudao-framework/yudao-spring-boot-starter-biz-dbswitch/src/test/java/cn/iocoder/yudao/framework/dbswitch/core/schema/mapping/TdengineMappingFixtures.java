package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.sql.Types;
import java.time.Instant;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TDENGINE;

/**
 * Column fixtures with the JDBC and DESCRIBE facts probed on TDengine 3.3.8.8 through taos-jdbcdriver 3.9.0.
 * Lengths are DESCRIBE lengths. Timestamp numericScale is the database PRECISION in digits, because JDBC scale is 0
 * and ins_columns.col_scale is null. ins_columns.col_precision is null; numeric precision here is the JDBC precision.
 */
final class TdengineMappingFixtures {
    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]+),\\s*([0-9]+)\\)");
    private static final Pattern SIZED = Pattern.compile("(varchar|nchar|varbinary|geometry)\\(([0-9]+)\\)");

    private TdengineMappingFixtures() { }

    static ColumnDescription column(String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(TDENGINE);
        column.setNullable(true);
        String type = nativeType.toLowerCase(Locale.ROOT);
        MetadataValue<Integer> precision = MetadataValue.absent();
        MetadataValue<Integer> scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        var decimal = DECIMAL.matcher(type);
        var sized = SIZED.matcher(type);
        if (type.equals("bool")) {
            shape(column, Types.BOOLEAN, "BOOL", "java.lang.Boolean", 1, 0, false);
        } else if (type.matches("(tinyint|smallint|int|bigint)( unsigned)?")) {
            boolean unsigned = type.endsWith(" unsigned");
            String base = unsigned ? type.substring(0, type.indexOf(' ')) : type;
            int bits = switch (base) { case "tinyint" -> 8; case "smallint" -> 16; case "int" -> 32; default -> 64; };
            int digits = switch (bits) { case 8 -> 3; case 16 -> 5; case 32 -> 10; default -> unsigned ? 20 : 19; };
            int jdbc = switch (bits) {
                case 8 -> unsigned ? Types.SMALLINT : Types.TINYINT;
                case 16 -> unsigned ? Types.INTEGER : Types.SMALLINT;
                case 32 -> unsigned ? Types.BIGINT : Types.INTEGER;
                default -> unsigned ? Types.NUMERIC : Types.BIGINT;
            };
            String className = switch (bits) {
                case 8 -> unsigned ? "java.lang.Short" : "java.lang.Byte";
                case 16 -> unsigned ? "java.lang.Integer" : "java.lang.Short";
                case 32 -> unsigned ? "java.lang.Long" : "java.lang.Integer";
                default -> unsigned ? "java.math.BigInteger" : "java.lang.Long";
            };
            shape(column, jdbc, base.toUpperCase(Locale.ROOT) + (unsigned ? " UNSIGNED" : ""), className, digits, 0, !(unsigned && bits == 64));
            precision = MetadataValue.value(digits);
            scale = MetadataValue.value(0);
        } else if (decimal.matches()) {
            int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
            shape(column, Types.DECIMAL, "DECIMAL", "java.math.BigDecimal", p, s, false);
            precision = MetadataValue.value(p);
            scale = MetadataValue.value(s);
        } else if (type.equals("float") || type.equals("double")) {
            boolean single = type.equals("float");
            shape(column, single ? Types.FLOAT : Types.DOUBLE, single ? "FLOAT" : "DOUBLE",
                    single ? "java.lang.Float" : "java.lang.Double", single ? 12 : 22, 0, true);
            precision = MetadataValue.value(single ? 12 : 22);
            scale = MetadataValue.value(0);
        } else if (sized.matches()) {
            int n = Integer.parseInt(sized.group(2));
            length = MetadataValue.value((long) n);
            switch (sized.group(1)) {
                case "varchar" -> shape(column, Types.VARCHAR, "BINARY", "[B", n, 0, false);
                case "nchar" -> shape(column, Types.NCHAR, "NCHAR", "java.lang.String", n, 0, false);
                case "varbinary" -> shape(column, Types.VARBINARY, "VARBINARY", "[B", n, 0, false);
                default -> shape(column, Types.BINARY, "GEOMETRY", "[B", n, 0, false);
            }
        } else if (type.equals("timestamp")) {
            shape(column, Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", 0, 0, false);
            scale = MetadataValue.value(3);
        } else if (type.equals("blob")) {
            shape(column, Types.BLOB, "BLOB", "java.sql.Blob", 4194304, 0, false);
        } else if (type.equals("json")) {
            shape(column, Types.OTHER, "JSON", "java.lang.String", 4095, 0, false);
        } else {
            shape(column, Types.OTHER, "UNKNOWN", "java.lang.Object", 0, 0, false);
        }
        column.setMetadataEvidence(evidence(type, true, precision, scale, length));
        return column;
    }

    /** Database PRECISION joined onto a TIMESTAMP column. JDBC scale stays 0. */
    static ColumnDescription timestamp(String name, int digits) {
        var column = column(name, "timestamp");
        var facts = column.getMetadataEvidence();
        column.setMetadataEvidence(new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(), facts.defaultExpression(),
                facts.nullable(), facts.numericPrecision(), MetadataValue.value(digits), facts.characterLength(),
                facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
        return column;
    }

    static ColumnDescription withFacts(ColumnDescription column, UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(TDENGINE, "TDengine", "3.3.8.8", "TDENGINE_CATALOG_V1", Instant.EPOCH);
    }

    private static void shape(ColumnDescription column, int jdbc, String typeName, String className,
                              int precision, int scale, boolean signed) {
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setFiledTypeClassName(className);
        column.setPrecisionSize(precision);
        column.setScaleSize(scale);
        column.setSigned(signed);
    }

    private static ColumnMetadataEvidence evidence(String nativeType, boolean nullable, MetadataValue<Integer> precision,
                                                   MetadataValue<Integer> scale, MetadataValue<Long> length) {
        return new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType), MetadataValue.absent(),
                MetadataValue.value(nullable), precision, scale, length, MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
    }
}
