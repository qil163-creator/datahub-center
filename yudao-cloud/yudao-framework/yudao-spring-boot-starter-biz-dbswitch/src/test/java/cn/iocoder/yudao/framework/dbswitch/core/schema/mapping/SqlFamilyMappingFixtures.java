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
 * Column fixtures with the facts the probed SQL Server 2022 RTM-CU27 (mssql-jdbc 10.2.3), Sybase ASE 16.0 SP02
 * PL02 (jconn4) and DB2 LUW 12.1.5.0 (db2jcc4 4.13.127) catalogs and drivers report, shared by the three
 * sql-family golden test classes. Expected values come from the real servers.
 */
final class SqlFamilyMappingFixtures {
    private static final Pattern SQLSERVER_DECIMAL = Pattern.compile("(?:decimal|numeric)\\(([0-9]+),([0-9]+)\\)");
    private static final Pattern SQLSERVER_SIZED = Pattern.compile("(char|varchar|nchar|nvarchar|binary|varbinary)\\(([0-9]+|max)\\)");
    private static final Pattern SQLSERVER_TEMPORAL = Pattern.compile("(datetime2|time|datetimeoffset)\\(([0-7])\\)");
    private static final Pattern SQLSERVER_FLOAT = Pattern.compile("float\\(([0-9]+)\\)");
    private static final Pattern SYBASE_DECIMAL = Pattern.compile("(?:decimal|numeric)\\(([0-9]+),([0-9]+)\\)");
    private static final Pattern SYBASE_TEXT = Pattern.compile("((?:uni)?(?:var)?char)\\(([0-9]+)\\)");
    private static final Pattern SYBASE_BINARY = Pattern.compile("(binary|varbinary)\\(([0-9]+)\\)");
    private static final Pattern DB2_DECIMAL = Pattern.compile("decimal\\(([0-9]+),([0-9]+)\\)");
    private static final Pattern DB2_SIZED = Pattern.compile("(character|varchar|graphic|vargraphic)\\(([0-9]+)\\)( for bit data)?");
    private static final Pattern DB2_LOB = Pattern.compile("(clob|blob|dbclob)\\(([0-9]+)\\)");
    private static final Pattern DB2_TEMPORAL = Pattern.compile("timestamp\\(([0-9]{1,2})\\)");

    private SqlFamilyMappingFixtures() { }

    /** A non-character column exactly as the native reader reports it for the product. */
    static ColumnDescription family(ProductTypeEnum product, String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name); column.setDbType(product); column.setNullable(true);
        column.setFieldType(jdbc(product, nativeType));
        column.setFiledTypeClassName(className(product, nativeType));
        String base = base(nativeType);
        MetadataValue<Integer> precision = MetadataValue.unknown(), scale = MetadataValue.unknown();
        MetadataValue<Long> length = MetadataValue.unknown();
        MetadataValue<String> charset = MetadataValue.absent(), collation = MetadataValue.absent();
        if (product == SQLSERVER) {
            var decimal = SQLSERVER_DECIMAL.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            var sized = SQLSERVER_SIZED.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            var temporal = SQLSERVER_TEMPORAL.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            var floating = SQLSERVER_FLOAT.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            if (decimal.matches()) {
                int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
                column.setPrecisionSize(p); column.setScaleSize(s);
                precision = MetadataValue.value(p); scale = MetadataValue.value(s);
            } else if (sized.matches()) {
                long n = sized.group(2).equals("max") ? -1 : Long.parseLong(sized.group(2));
                boolean unicode = sized.group(1).startsWith("n");
                column.setPrecisionSize((int) (sized.group(2).equals("max") ? (unicode ? 1073741823 : 2147483647) : n));
                column.setScaleSize(0);
                length = MetadataValue.value(n);
                precision = MetadataValue.value(0); scale = MetadataValue.value(0);
                if (base.matches("(n)?(var)?char")) collation = MetadataValue.value("SQL_Latin1_General_CP1_CI_AS");
            } else if (temporal.matches()) {
                int s = Integer.parseInt(temporal.group(2));
                column.setScaleSize(s);
                column.setPrecisionSize(switch (temporal.group(1)) {
                    case "datetime2" -> 19 + s; case "time" -> 8 + s; default -> 26 + s; });
                precision = MetadataValue.value(column.getPrecisionSize()); scale = MetadataValue.value(s);
            } else if (floating.matches()) {
                int bits = Integer.parseInt(floating.group(1));
                column.setPrecisionSize(bits <= 24 ? 7 : 15); column.setScaleSize(0);
                precision = MetadataValue.value(bits); scale = MetadataValue.value(0);
            } else switch (base) {
                case "bigint" -> { column.setPrecisionSize(19); column.setScaleSize(0); precision = MetadataValue.value(19); scale = MetadataValue.value(0); }
                case "int" -> { column.setPrecisionSize(10); column.setScaleSize(0); precision = MetadataValue.value(10); scale = MetadataValue.value(0); }
                case "smallint" -> { column.setPrecisionSize(5); column.setScaleSize(0); precision = MetadataValue.value(5); scale = MetadataValue.value(0); }
                case "tinyint" -> { column.setPrecisionSize(3); column.setScaleSize(0); precision = MetadataValue.value(3); scale = MetadataValue.value(0); }
                case "bit" -> { column.setPrecisionSize(1); column.setScaleSize(0); precision = MetadataValue.value(1); scale = MetadataValue.value(0); }
                case "money" -> { column.setPrecisionSize(19); column.setScaleSize(4); precision = MetadataValue.value(19); scale = MetadataValue.value(4); }
                case "smallmoney" -> { column.setPrecisionSize(10); column.setScaleSize(4); precision = MetadataValue.value(10); scale = MetadataValue.value(4); }
                case "real" -> { column.setPrecisionSize(7); column.setScaleSize(0); precision = MetadataValue.value(24); scale = MetadataValue.value(0); }
                case "date" -> { column.setPrecisionSize(10); column.setScaleSize(0); precision = MetadataValue.value(10); scale = MetadataValue.value(0); }
                case "datetime" -> { column.setPrecisionSize(23); column.setScaleSize(3); precision = MetadataValue.value(23); scale = MetadataValue.value(3); }
                case "smalldatetime" -> { column.setPrecisionSize(16); column.setScaleSize(0); precision = MetadataValue.value(16); scale = MetadataValue.value(0); }
                case "uniqueidentifier" -> { column.setPrecisionSize(36); column.setScaleSize(0); precision = MetadataValue.value(0); scale = MetadataValue.value(0); }
                case "xml", "ntext" -> { column.setPrecisionSize(1073741823); column.setScaleSize(0); precision = MetadataValue.value(0); scale = MetadataValue.value(0); collation = base.equals("ntext") ? MetadataValue.value("SQL_Latin1_General_CP1_CI_AS") : MetadataValue.absent(); }
                case "text" -> { column.setPrecisionSize(2147483647); column.setScaleSize(0); precision = MetadataValue.value(0); scale = MetadataValue.value(0); collation = MetadataValue.value("SQL_Latin1_General_CP1_CI_AS"); }
                case "image" -> { column.setPrecisionSize(2147483647); column.setScaleSize(0); precision = MetadataValue.value(0); scale = MetadataValue.value(0); }
                case "timestamp" -> { column.setPrecisionSize(8); column.setScaleSize(0); precision = MetadataValue.value(0); scale = MetadataValue.value(0); }
                default -> { column.setPrecisionSize(0); column.setScaleSize(0); }
            }
        } else if (product == SYBASE) {
            var decimal = SYBASE_DECIMAL.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            var text = SYBASE_TEXT.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            var binary = SYBASE_BINARY.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            if (decimal.matches()) {
                int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
                column.setPrecisionSize(p); column.setScaleSize(s);
                precision = MetadataValue.value(p); scale = MetadataValue.value(s);
            } else if (text.matches() || binary.matches()) {
                long n = Long.parseLong((text.matches() ? text : binary).group(2));
                column.setPrecisionSize(0); column.setScaleSize(0);
                length = MetadataValue.value(n);
                precision = MetadataValue.value(0); scale = MetadataValue.value(0);
            } else switch (base) {
                case "tinyint" -> { column.setPrecisionSize(3); column.setScaleSize(0); }
                case "smallint" -> { column.setPrecisionSize(5); column.setScaleSize(0); }
                case "int", "integer" -> { column.setPrecisionSize(10); column.setScaleSize(0); }
                case "bigint" -> { column.setPrecisionSize(19); column.setScaleSize(0); }
                case "bit" -> { column.setPrecisionSize(1); column.setScaleSize(0); }
                case "money" -> { column.setPrecisionSize(19); column.setScaleSize(4); precision = MetadataValue.value(19); scale = MetadataValue.value(4); }
                case "smallmoney" -> { column.setPrecisionSize(10); column.setScaleSize(4); precision = MetadataValue.value(10); scale = MetadataValue.value(4); }
                case "float", "double precision" -> { column.setPrecisionSize(15); column.setScaleSize(0); }
                case "real" -> { column.setPrecisionSize(7); column.setScaleSize(0); }
                case "date" -> { column.setPrecisionSize(0); column.setScaleSize(0); }
                case "time" -> { column.setPrecisionSize(3); column.setScaleSize(0); }
                case "datetime" -> { column.setPrecisionSize(3); column.setScaleSize(0); }
                case "smalldatetime" -> { column.setPrecisionSize(0); column.setScaleSize(0); }
                case "bigdatetime" -> { column.setPrecisionSize(6); column.setScaleSize(6); }
                case "bigtime" -> { column.setPrecisionSize(6); column.setScaleSize(6); }
                default -> { column.setPrecisionSize(0); column.setScaleSize(0); }
            }
        } else {
            var decimal = DB2_DECIMAL.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            var sized = DB2_SIZED.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            var lob = DB2_LOB.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            var temporal = DB2_TEMPORAL.matcher(nativeType.toLowerCase(java.util.Locale.ROOT));
            if (decimal.matches()) {
                int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
                column.setPrecisionSize(p); column.setScaleSize(s);
                precision = MetadataValue.value(p); scale = MetadataValue.value(s);
            } else if (sized.matches()) {
                long n = Long.parseLong(sized.group(2));
                column.setPrecisionSize((int) n); column.setScaleSize(0);
                length = MetadataValue.value(n);
                if (sized.group(3) == null)
                    charset = MetadataValue.value(sized.group(1).startsWith("g") ? "1200" : "1208");
                precision = MetadataValue.value(0); scale = MetadataValue.value(0);
            } else if (lob.matches()) {
                long n = Long.parseLong(lob.group(2));
                column.setPrecisionSize((int) n); column.setScaleSize(0);
                precision = MetadataValue.value(0); scale = MetadataValue.value(0);
                if (!lob.group(1).equals("blob")) charset = MetadataValue.value(lob.group(1).equals("dbclob") ? "1200" : "1208");
            } else if (temporal.matches()) {
                int s = Integer.parseInt(temporal.group(1));
                column.setPrecisionSize(19 + s); column.setScaleSize(s);
                precision = MetadataValue.value(0); scale = MetadataValue.value(0);
            } else switch (base) {
                case "smallint" -> { column.setPrecisionSize(5); column.setScaleSize(0); }
                case "integer" -> { column.setPrecisionSize(10); column.setScaleSize(0); }
                case "bigint" -> { column.setPrecisionSize(19); column.setScaleSize(0); }
                case "boolean" -> { column.setPrecisionSize(1); column.setScaleSize(0); }
                case "real" -> { column.setPrecisionSize(7); column.setScaleSize(0); }
                case "double", "float" -> { column.setPrecisionSize(15); column.setScaleSize(0); }
                case "decfloat" -> { column.setPrecisionSize(16); column.setScaleSize(0); }
                case "date" -> { column.setPrecisionSize(10); column.setScaleSize(0); }
                case "time" -> { column.setPrecisionSize(8); column.setScaleSize(0); }
                case "long varchar" -> { column.setPrecisionSize(32700); column.setScaleSize(0); length = MetadataValue.value(32700L); charset = MetadataValue.value("1208"); }
                default -> { column.setPrecisionSize(0); column.setScaleSize(0); }
            }
        }
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(product), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(true), precision, scale, length, MetadataValue.absent(),
                MetadataValue.absent(), charset, collation, MetadataValue.absent()));
        return column;
    }

    static ColumnDescription withFacts(ColumnDescription column, java.util.function.UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    static MetadataProvenance provenance(ProductTypeEnum product) {
        return switch (product) {
            case SQLSERVER -> new MetadataProvenance(SQLSERVER, "Microsoft SQL Server", "16.00.4295",
                    "SQLSERVER16_CATALOG_V1", Instant.EPOCH);
            case SYBASE -> new MetadataProvenance(SYBASE, "Adaptive Server Enterprise",
                    "Adaptive Server Enterprise/16.0 SP02 PL02/EBF 25320 SMP/P/x86_64/Enterprise Linux/ase160sp02plx/2492/64-bit/FBO/Sat Nov 21 04:05:39 2015",
                    "SYBASE16_CATALOG_V1", Instant.EPOCH);
            case DB2 -> new MetadataProvenance(DB2, "DB2/LINUXX8664", "DB2 v12.1.5.0", "DB2_1215_CATALOG_V1", Instant.EPOCH);
            default -> throw new IllegalArgumentException(product.name());
        };
    }

    static int jdbc(ProductTypeEnum product, String nativeType) {
        String base = base(nativeType);
        if (product == SQLSERVER) return switch (base) {
            case "bigint" -> Types.BIGINT; case "int" -> Types.INTEGER; case "smallint" -> Types.SMALLINT;
            case "tinyint" -> Types.TINYINT; case "bit" -> Types.BIT;
            case "decimal" -> Types.DECIMAL; case "numeric" -> Types.NUMERIC;
            case "money", "smallmoney" -> Types.DECIMAL;
            case "float" -> nativeType.matches("float\\(([0-9]+)\\)") && Integer.parseInt(nativeType.substring(6, nativeType.length() - 1)) <= 24 ? Types.REAL : Types.DOUBLE;
            case "real" -> Types.REAL; case "date" -> Types.DATE; case "datetimeoffset" -> -155;
            case "datetime2", "datetime", "smalldatetime" -> Types.TIMESTAMP;
            case "time" -> Types.TIME;
            case "char" -> Types.CHAR; case "varchar" -> Types.VARCHAR; case "nchar" -> Types.NCHAR; case "nvarchar" -> Types.NVARCHAR;
            case "binary", "timestamp" -> Types.BINARY; case "varbinary", "hierarchyid" -> Types.VARBINARY;
            case "image" -> Types.LONGVARBINARY; case "text" -> Types.LONGVARCHAR; case "ntext", "xml" -> Types.LONGNVARCHAR;
            case "uniqueidentifier" -> Types.CHAR;
            default -> Types.OTHER;
        };
        if (product == SYBASE) return switch (base) {
            case "tinyint" -> Types.TINYINT; case "smallint" -> Types.SMALLINT; case "int", "integer" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT; case "bit" -> Types.BIT;
            case "decimal" -> Types.DECIMAL; case "numeric" -> Types.NUMERIC; case "money", "smallmoney" -> Types.DECIMAL;
            case "float", "double precision" -> Types.DOUBLE; case "real" -> Types.REAL;
            case "date" -> Types.DATE; case "time", "bigtime" -> Types.TIME; case "datetime", "smalldatetime", "bigdatetime" -> Types.TIMESTAMP;
            case "char", "unichar" -> Types.CHAR; case "varchar", "univarchar" -> Types.VARCHAR;
            case "text", "unitext" -> Types.LONGVARCHAR; case "binary" -> Types.BINARY; case "varbinary" -> Types.VARBINARY;
            case "image" -> Types.LONGVARBINARY;
            default -> Types.OTHER;
        };
        return switch (base) {
            case "smallint" -> Types.SMALLINT; case "integer" -> Types.INTEGER; case "bigint" -> Types.BIGINT;
            case "boolean" -> Types.BOOLEAN;
            case "decimal" -> Types.DECIMAL; case "real" -> Types.REAL; case "double", "float" -> Types.DOUBLE;
            case "decfloat" -> Types.OTHER; case "date" -> Types.DATE; case "time" -> Types.TIME;
            case "timestamp" -> Types.TIMESTAMP;
            case "character", "graphic" -> Types.CHAR; case "varchar", "vargraphic" -> Types.VARCHAR;
            case "character for bit data" -> Types.BINARY; case "varchar for bit data" -> Types.VARBINARY;
            case "long varchar" -> Types.LONGVARCHAR; case "clob", "dbclob" -> Types.CLOB; case "blob" -> Types.BLOB;
            case "xml" -> Types.SQLXML;
            default -> Types.OTHER;
        };
    }

    private static String className(ProductTypeEnum product, String nativeType) {
        String base = base(nativeType);
        if (product == SQLSERVER) return switch (base) {
            case "bigint" -> "java.lang.Long"; case "int" -> "java.lang.Integer"; case "tinyint", "smallint" -> "java.lang.Short";
            case "bit" -> "java.lang.Boolean";
            case "decimal", "numeric", "money", "smallmoney" -> "java.math.BigDecimal";
            case "float" -> "java.lang.Double"; case "real" -> "java.lang.Float";
            case "date" -> "java.sql.Date"; case "datetime2", "datetime", "smalldatetime" -> "java.sql.Timestamp";
            case "time" -> "java.sql.Time"; case "datetimeoffset" -> "microsoft.sql.DateTimeOffset";
            case "binary", "varbinary", "image", "timestamp", "hierarchyid", "geometry", "geography" -> "[B";
            case "uniqueidentifier", "char", "varchar", "nchar", "nvarchar", "text", "ntext", "xml" -> "java.lang.String";
            default -> "java.lang.Object";
        };
        if (product == SYBASE) return switch (base) {
            case "bigint" -> "java.lang.Long"; case "tinyint", "smallint", "int", "integer" -> "java.lang.Integer";
            case "bit" -> "java.lang.Boolean";
            case "decimal", "numeric", "money", "smallmoney" -> "java.math.BigDecimal";
            case "float", "double precision" -> "java.lang.Double"; case "real" -> "java.lang.Float";
            case "date" -> "java.sql.Date"; case "datetime", "smalldatetime", "bigdatetime" -> "com.sybase.jdbc4.tds.SybTimestamp";
            case "time", "bigtime" -> "java.sql.Time";
            case "char", "varchar", "unichar", "univarchar", "text", "unitext" -> "java.lang.String";
            case "binary", "varbinary", "image" -> "[B";
            default -> "java.lang.Object";
        };
        return switch (base) {
            case "bigint" -> "java.lang.Long"; case "smallint", "integer" -> "java.lang.Integer";
            case "boolean" -> "java.lang.Boolean";
            case "decimal" -> "java.math.BigDecimal"; case "real" -> "java.lang.Float"; case "double", "float", "decfloat" -> "java.lang.Double";
            case "date" -> "java.sql.Date"; case "time" -> "java.sql.Time"; case "timestamp" -> "java.sql.Timestamp";
            case "character", "varchar", "graphic", "vargraphic", "long varchar" -> "java.lang.String";
            case "character for bit data", "varchar for bit data", "blob" -> "[B";
            case "clob", "dbclob" -> "java.sql.Clob";
            default -> "java.lang.Object";
        };
    }

    private static String base(String nativeType) {
        String base = nativeType.replaceAll("\\([^)]*\\)", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (base.endsWith(" for bit data")) base = base.substring(0, base.length() - " for bit data".length()).trim() + " for bit data";
        return base;
    }
}
