package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.sql.Types;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;

/**
 * Column fixtures with the facts the probed GBase 8a 8.6.2.43-R7-free and Doris 4.0.8 catalogs and drivers report
 * (banners, JDBC types, information_schema facts), shared by the two MySQL-protocol-family golden test classes.
 * Every value comes from the real servers; nothing is copied from the MySQL 8.4 family fixtures.
 */
final class MysqlFamilyExtendedFixtures {
    private MysqlFamilyExtendedFixtures() { }

    /** A non-character column exactly as the native reader reports it for the product. */
    static ColumnDescription family(ProductTypeEnum product, String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name); column.setDbType(product); column.setNullable(true);
        MetadataValue<Integer> precision = MetadataValue.absent(), scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        String type = nativeType.toLowerCase(Locale.ROOT);
        if (product == GBASE8A) {
            var integer = Pattern.compile("(tinyint|smallint|mediumint|int|bigint)(?:\\([0-9]+\\))?").matcher(type);
            var decimal = Pattern.compile("decimal\\(([0-9]+),([0-9]+)\\)").matcher(type);
            column.setSigned(true);
            if (type.equals("tinyint(1)")) {
                column.setFieldType(Types.BIT); column.setFiledTypeClassName("java.lang.Boolean"); column.setPrecisionSize(1);
                precision = MetadataValue.value(3); scale = MetadataValue.value(0);
            } else if (integer.matches()) {
                String base = integer.group(1);
                column.setFieldType(switch (base) { case "tinyint" -> Types.TINYINT; case "smallint" -> Types.SMALLINT;
                        case "bigint" -> Types.BIGINT; default -> Types.INTEGER; });
                column.setPrecisionSize(switch (base) { case "tinyint" -> 4; case "smallint" -> 6; case "mediumint" -> 9;
                        case "int" -> 11; default -> 20; });
                precision = MetadataValue.value(switch (base) { case "tinyint" -> 3; case "smallint" -> 5; case "mediumint" -> 8;
                        case "int" -> 10; default -> 20; });
                scale = MetadataValue.value(0);
            } else if (decimal.matches()) {
                int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
                column.setFieldType(Types.DECIMAL); column.setPrecisionSize(p); column.setScaleSize(s);
                precision = MetadataValue.value(p); scale = MetadataValue.value(s);
            } else {
                String base = type.replaceAll("\\(.*", "");
                column.setFieldType(switch (base) {
                    case "float" -> Types.REAL; case "double" -> Types.DOUBLE; case "binary" -> Types.BINARY;
                    case "varbinary" -> Types.VARBINARY; case "tinyblob", "blob" -> Types.LONGVARBINARY;
                    case "tinytext", "text" -> Types.LONGVARCHAR; case "date", "year" -> Types.DATE;
                    case "datetime", "timestamp" -> Types.TIMESTAMP; case "time" -> Types.TIME; case "json" -> Types.LONGVARCHAR;
                    case "char" -> Types.CHAR; case "varchar" -> Types.VARCHAR; default -> Types.CHAR;
                });
                if (base.equals("year")) column.setPrecisionSize(4);
                if (base.equals("datetime") || base.equals("timestamp") || base.equals("time")) column.setPrecisionSize(26);
                if (base.equals("float")) { column.setPrecisionSize(12); precision = MetadataValue.value(12); }
                if (base.equals("double")) { column.setPrecisionSize(22); precision = MetadataValue.value(22); }
                if (base.equals("text") || base.equals("blob")) column.setPrecisionSize(32767);
                var sized = Pattern.compile("(?:binary|varbinary)\\(([0-9]+)\\)").matcher(type);
                if (sized.matches()) { int n = Integer.parseInt(sized.group(1)); column.setPrecisionSize(n); length = MetadataValue.value((long) n); }
                if (base.equals("text")) length = MetadataValue.value(8191L);
                if (base.equals("blob")) length = MetadataValue.value(32767L);
                if (base.equals("tinytext")) length = MetadataValue.value(255L);
                var chars = Pattern.compile("(?:char|varchar)\\(([0-9]+)\\)").matcher(type);
                if (chars.matches()) { int n = Integer.parseInt(chars.group(1)); column.setPrecisionSize(n); length = MetadataValue.value((long) n); }
            }
        } else { // DORIS
            var integer = Pattern.compile("(tinyint|smallint|int|bigint)\\([0-9]+\\)").matcher(type);
            var decimal = Pattern.compile("(?:decimal|decimalv3)\\(([0-9]+),\\s*([0-9]+)\\)").matcher(type);
            column.setSigned(true);
            if (type.equals("tinyint(1)")) {
                column.setFieldType(Types.BIT); column.setFiledTypeClassName("java.lang.Boolean"); column.setPrecisionSize(1);
                scale = MetadataValue.value(0);
            } else if (integer.matches()) {
                String base = integer.group(1);
                column.setFieldType(switch (base) { case "tinyint" -> Types.TINYINT; case "smallint" -> Types.SMALLINT;
                        case "bigint" -> Types.BIGINT; default -> Types.INTEGER; });
                column.setPrecisionSize(switch (base) { case "tinyint" -> 3; case "smallint" -> 5; case "int" -> 10; default -> 19; });
                precision = MetadataValue.value(column.getPrecisionSize()); scale = MetadataValue.value(0);
            } else if (decimal.matches()) {
                int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
                column.setFieldType(Types.DECIMAL); column.setPrecisionSize(p); column.setScaleSize(s);
                precision = MetadataValue.value(p); scale = MetadataValue.value(s);
            } else {
                String base = type.replaceAll("\\(.*", "");
                column.setFieldType(switch (base) {
                    case "float" -> Types.REAL; case "double" -> Types.DOUBLE;
                    case "largeint", "char", "varchar", "array" -> Types.CHAR; case "string" -> Types.VARCHAR;
                    case "json" -> Types.LONGVARCHAR; case "date", "datev2" -> Types.DATE;
                    case "datetime", "datetimev2" -> Types.TIMESTAMP; default -> Types.CHAR;
                });
                if (base.equals("largeint")) { column.setPrecisionSize(85); column.setFiledTypeClassName("java.lang.String"); precision = MetadataValue.value(39); }
                if (base.equals("float")) { column.setPrecisionSize(12); precision = MetadataValue.value(7); scale = MetadataValue.value(7); }
                if (base.equals("double")) { column.setPrecisionSize(22); precision = MetadataValue.value(15); scale = MetadataValue.value(15); }
                if (base.equals("datetime") || base.equals("datetimev2")) { column.setPrecisionSize(6); column.setFiledTypeClassName("java.time.LocalDateTime"); }
                if (base.equals("char") || base.equals("varchar") || base.equals("string") || base.equals("array") || base.equals("json"))
                    column.setPrecisionSize(85);
                var sized = Pattern.compile("(?:char|varchar)\\(([0-9]+)\\)").matcher(type);
                if (sized.matches()) length = MetadataValue.value(Long.parseLong(sized.group(1)));
                if (base.equals("string")) length = MetadataValue.value(2147483643L);
            }
        }
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(product), MetadataValue.value(type),
                MetadataValue.absent(), MetadataValue.value(true), precision, scale, length, MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.unknown()));
        return column;
    }

    /** A character column carrying the charset/collation facts the catalog reports. */
    static ColumnDescription text(ProductTypeEnum product, String name, String nativeType, String collation) {
        var column = family(product, name, nativeType);
        if (collation == null) return column;
        String charset = collation.substring(0, collation.indexOf('_'));
        column.setMetadataEvidence(new ColumnMetadataEvidence(column.getMetadataEvidence().provenance(),
                MetadataValue.value(nativeType.toLowerCase(Locale.ROOT)), MetadataValue.absent(), MetadataValue.value(true),
                MetadataValue.absent(), MetadataValue.absent(), column.getMetadataEvidence().characterLength(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(charset), MetadataValue.value(collation),
                MetadataValue.unknown()));
        return column;
    }

    static ColumnDescription withFacts(ColumnDescription column, java.util.function.UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    /** pgjdbc 42.7.13 column type for a PostgreSQL native type text (same mapping as the shared test). */
    static int pgJdbc(String type) {
        if (type.endsWith("[]")) return Types.ARRAY;
        return switch (type.replaceAll("\\(.*", "")) {
            case "smallint" -> Types.SMALLINT; case "integer" -> Types.INTEGER; case "bigint" -> Types.BIGINT; case "numeric" -> Types.NUMERIC;
            case "real" -> Types.REAL; case "double precision" -> Types.DOUBLE; case "boolean" -> Types.BIT;
            case "character varying", "text" -> Types.VARCHAR; case "character" -> Types.CHAR; case "bytea" -> Types.BINARY;
            case "date" -> Types.DATE; case "time" -> Types.TIME; case "timestamp" -> Types.TIMESTAMP; case "xml" -> Types.SQLXML;
            default -> type.startsWith("time") ? (type.startsWith("timestamp") ? Types.TIMESTAMP : Types.TIME) : Types.OTHER;
        };
    }

    static MetadataProvenance provenance(ProductTypeEnum product) {
        return switch (product) {
            case GBASE8A -> new MetadataProvenance(GBASE8A, "GBase", "8.6.2.43-R7-free.110605", "GBASE8A_CATALOG_V1", Instant.EPOCH);
            case DORIS -> new MetadataProvenance(DORIS, "Doris", "doris-4.0.8-rc02-bc8ea1bac6d", "DORIS_CATALOG_V1", Instant.EPOCH);
            default -> throw new IllegalArgumentException(product.name());
        };
    }
}
