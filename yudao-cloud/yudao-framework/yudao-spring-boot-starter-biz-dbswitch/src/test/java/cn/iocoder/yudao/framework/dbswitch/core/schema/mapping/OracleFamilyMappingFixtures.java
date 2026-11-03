package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.sql.Types;
import java.time.Instant;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;

/**
 * Column fixtures with the facts the probed Oracle 23ai/26ai Free 23.26.3, KingbaseES V008R006C009B0014 oracle
 * mode and DM8 8.1.5.60 catalogs and drivers report (JDBC types, class names, precision/scale, catalog rows),
 * shared by the three oracle-family golden test classes. Expected values come from the real servers.
 */
final class OracleFamilyMappingFixtures {
    private static final Pattern DECIMAL = Pattern.compile("(?:number|numeric|decimal)\\(([0-9]+),(-?[0-9]+)\\)");
    private static final Pattern ORACLE_LEN = Pattern.compile("(?:varchar2|char|nvarchar2|nchar|raw)\\(([0-9]+)");
    private static final Pattern KB_LEN = Pattern.compile("character(?: varying)?\\(([0-9]+)");
    private static final Pattern DM_LEN = Pattern.compile("(?:varchar2?|char|binary|varbinary)\\(([0-9]+)");

    private OracleFamilyMappingFixtures() { }

    /** A non-character column exactly as the native reader reports it for the product. */
    static ColumnDescription family(ProductTypeEnum product, String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name); column.setDbType(product); column.setNullable(true);
        column.setFieldType(jdbc(product, nativeType));
        column.setFiledTypeClassName(className(product, nativeType));
        MetadataValue<Integer> precision = MetadataValue.absent(), scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        String base = base(nativeType);
        var decimal = DECIMAL.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
        if (decimal.matches()) {
            int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
            column.setPrecisionSize(p); column.setScaleSize(s);
            precision = MetadataValue.value(p); scale = MetadataValue.value(s);
        } else {
            var sized = ORACLE_LEN.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            if (sized.find() && product == ORACLE && base.startsWith("raw")) {
                column.setPrecisionSize(Integer.parseInt(sized.group(1)));
                length = MetadataValue.value(Long.parseLong(sized.group(1)));
            } else {
                var sized2 = (product == ORACLE ? ORACLE_LEN : product == DM ? DM_LEN : KB_LEN)
                        .matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
                if (sized2.find() && base.matches("varchar2?|char|character|character varying|nvarchar2|nchar|binary|varbinary|raw")) {
                    column.setPrecisionSize(Integer.parseInt(sized2.group(1)));
                    length = MetadataValue.value(Long.parseLong(sized2.group(1)));
                }
            }
            switch (base) {
                case "number", "numeric", "decimal" -> { column.setPrecisionSize(0); column.setScaleSize(product == ORACLE ? -127 : 0); }
                case "tinyint" -> { column.setPrecisionSize(3); precision = MetadataValue.value(3); scale = MetadataValue.value(0); }
                case "smallint" -> { column.setPrecisionSize(5); precision = MetadataValue.value(5); scale = MetadataValue.value(0); }
                case "int", "integer" -> { column.setPrecisionSize(10); precision = MetadataValue.value(10); scale = MetadataValue.value(0); }
                case "bigint" -> { column.setPrecisionSize(19); precision = MetadataValue.value(19); scale = MetadataValue.value(0); }
                case "double", "float" -> { column.setPrecisionSize(53); precision = MetadataValue.value(53); scale = MetadataValue.value(0); }
                case "real" -> { column.setPrecisionSize(product == DM ? 24 : 8); precision = MetadataValue.value(product == DM ? 24 : 8); scale = MetadataValue.value(0); }
                case "bit", "boolean" -> column.setPrecisionSize(1);
                case "date" -> { column.setPrecisionSize(product == KINGBASE ? 22 : 7); column.setScaleSize(0); }
                case "time" -> { column.setPrecisionSize(8); column.setScaleSize(digits(nativeType, 0)); }
                case "datetime" -> column.setScaleSize(digits(nativeType, 6));
                case "interval year to month" -> { column.setPrecisionSize(2); column.setScaleSize(0); }
                case "interval day to second" -> { column.setPrecisionSize(2); column.setScaleSize(6); }
                default -> { if (base.startsWith("timestamp")) { column.setPrecisionSize(0); column.setScaleSize(digits(nativeType, 6)); } }
            }
        }
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(product), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(true), precision, scale, length, MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.unknown()));
        return column;
    }

    /** A character column carrying the catalog character length in the declared unit. */
    static ColumnDescription text(ProductTypeEnum product, String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name); column.setDbType(product); column.setNullable(true);
        String base = base(nativeType);
        int jdbcType = switch (base) {
            case "char" -> Types.CHAR;
            case "nvarchar2" -> Types.NVARCHAR;
            case "nchar" -> Types.NCHAR;
            default -> Types.VARCHAR;
        };
        column.setFieldType(jdbcType);
        var sized = Pattern.compile("\\(([0-9]+)").matcher(nativeType);
        long length = sized.find() ? Long.parseLong(sized.group(1)) : 0;
        column.setPrecisionSize((int) length);
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(product), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(true), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.value(length), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.unknown()));
        return column;
    }

    static ColumnDescription withFacts(ColumnDescription column, java.util.function.UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    static MetadataProvenance provenance(ProductTypeEnum product) {
        return switch (product) {
            case ORACLE -> new MetadataProvenance(ORACLE, "Oracle", "Oracle AI Database 26ai Free Release 23.26.3.0.0",
                    "ORACLE_CATALOG_V1", Instant.EPOCH);
            case KINGBASE -> new MetadataProvenance(KINGBASE, "KingbaseES", "KingbaseES V008R006C009B0014",
                    "KINGBASE_CATALOG_V1", Instant.EPOCH);
            case DM -> new MetadataProvenance(DM, "DM DBMS", "8.1.5.60", "DM_CATALOG_V1", Instant.EPOCH);
            default -> throw new IllegalArgumentException(product.name());
        };
    }

    static int jdbc(ProductTypeEnum product, String nativeType) {
        String base = base(nativeType);
        if (product == ORACLE) return switch (base) {
            case "number", "float" -> Types.NUMERIC;
            case "binary_float" -> 100;
            case "binary_double" -> 101;
            case "varchar2" -> Types.VARCHAR;
            case "char" -> Types.CHAR;
            case "nvarchar2" -> Types.NVARCHAR;
            case "nchar" -> Types.NCHAR;
            case "raw" -> Types.VARBINARY;
            case "date", "timestamp" -> Types.TIMESTAMP;
            case "timestamp with time zone" -> -101;
            case "timestamp with local time zone" -> -102;
            case "interval year to month" -> -103;
            case "interval day to second" -> -104;
            case "blob" -> Types.BLOB;
            case "clob" -> Types.CLOB;
            case "nclob" -> Types.NCLOB;
            case "boolean" -> Types.BOOLEAN;
            default -> Types.OTHER;
        };
        if (product == KINGBASE) return switch (base) {
            case "numeric" -> Types.NUMERIC;
            case "real" -> Types.REAL;
            case "double precision" -> Types.DOUBLE;
            case "boolean" -> Types.BIT;
            case "bytea", "blob" -> Types.BINARY;
            case "clob", "text", "character varying", "varchar" -> Types.VARCHAR;
            case "character", "char" -> Types.CHAR;
            case "date", "timestamp", "timestamp without time zone", "timestamp with time zone" -> Types.TIMESTAMP;
            default -> Types.OTHER;
        };
        return switch (base) {
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "int", "integer" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "number" -> Types.NUMERIC;
            case "decimal" -> Types.DECIMAL;
            case "double", "float" -> Types.DOUBLE;
            case "real" -> Types.REAL;
            case "bit" -> Types.BIT;
            case "boolean" -> Types.BOOLEAN;
            case "varchar", "varchar2" -> Types.VARCHAR;
            case "char" -> Types.CHAR;
            case "binary" -> Types.BINARY;
            case "varbinary" -> Types.VARBINARY;
            case "blob", "image" -> Types.BLOB;
            case "text", "clob", "json" -> Types.CLOB;
            case "date" -> Types.DATE;
            case "time" -> Types.TIME;
            case "timestamp", "datetime" -> Types.TIMESTAMP;
            default -> Types.OTHER;
        };
    }

    private static String className(ProductTypeEnum product, String nativeType) {
        String base = base(nativeType);
        if (product == ORACLE) return switch (base) {
            case "number", "float" -> "java.math.BigDecimal";
            case "binary_float" -> "java.lang.Float";
            case "binary_double" -> "java.lang.Double";
            case "varchar2", "char", "nvarchar2", "nchar" -> "java.lang.String";
            case "raw" -> "[B";
            case "date" -> "java.sql.Timestamp";
            case "timestamp" -> "oracle.sql.TIMESTAMP";
            case "timestamp with time zone" -> "oracle.sql.TIMESTAMPTZ";
            case "timestamp with local time zone" -> "oracle.sql.TIMESTAMPLTZ";
            case "interval year to month" -> "oracle.sql.INTERVALYM";
            case "interval day to second" -> "oracle.sql.INTERVALDS";
            case "blob" -> "oracle.jdbc.OracleBlob";
            case "clob" -> "oracle.jdbc.OracleClob";
            case "nclob" -> "oracle.jdbc.OracleNClob";
            case "boolean" -> "java.lang.Boolean";
            default -> "java.lang.Object";
        };
        return switch (base) {
            case "tinyint" -> "java.lang.Byte";
            case "smallint" -> "java.lang.Short";
            case "int", "integer" -> "java.lang.Integer";
            case "bigint" -> "java.lang.Long";
            case "number", "decimal" -> "java.math.BigDecimal";
            case "double", "float" -> "java.lang.Double";
            case "real" -> "java.lang.Float";
            case "bit", "boolean" -> "java.lang.Boolean";
            case "varchar", "varchar2", "char" -> "java.lang.String";
            case "binary", "varbinary" -> "[B";
            case "blob", "image" -> product == DM ? "dm.jdbc.driver.DmdbBlob" : "java.sql.Blob";
            case "text", "clob", "json" -> product == DM ? "dm.jdbc.driver.DmdbNClob" : "java.sql.Clob";
            case "date" -> "java.sql.Date";
            case "time" -> "java.sql.Time";
            case "timestamp", "datetime" -> product == DM ? "dm.jdbc.driver.DmdbTimestamp" : "java.sql.Timestamp";
            default -> "java.lang.Object";
        };
    }

    private static int digits(String nativeType, int fallback) {
        var m = Pattern.compile("\\(([0-9])\\)").matcher(nativeType);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static String base(String nativeType) {
        String base = nativeType.replaceAll("\\([^)]*\\)", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (base.startsWith("timestamp")) {
            if (base.contains("local")) return "timestamp with local time zone";
            if (base.contains("with")) return "timestamp with time zone";
            return "timestamp";
        }
        if (base.startsWith("interval year")) return "interval year to month";
        if (base.startsWith("interval day")) return "interval day to second";
        return base;
    }
}
