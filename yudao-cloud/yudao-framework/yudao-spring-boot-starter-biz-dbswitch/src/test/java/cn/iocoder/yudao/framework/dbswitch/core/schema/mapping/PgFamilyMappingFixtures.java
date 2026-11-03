package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.sql.Types;
import java.time.Instant;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;

/**
 * Column fixtures with the facts the probed HighGo 6.0.4.2 / UXsinoDB 2.1.0.3 / Greenplum 7.1.0 and PostgreSQL 16
 * catalogs and drivers report (banners, server_version, JDBC types, information_schema facts), shared by the three
 * pg-family golden test classes. Expected values come from the real servers, not from the PostgreSQL mapper.
 */
final class PgFamilyMappingFixtures {
    private static final Pattern NUMERIC = Pattern.compile("numeric\\(([0-9]+),(-?[0-9]+)\\)");
    private static final Pattern SIZED = Pattern.compile("(?:character varying|character|varchar|char|bit|bit varying)\\(([0-9]+)\\)");

    private PgFamilyMappingFixtures() { }

    /** A non-character column exactly as the native reader reports it for the product. */
    static ColumnDescription family(ProductTypeEnum product, String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name); column.setDbType(product); column.setNullable(true); column.setFieldType(jdbc(product, nativeType));
        MetadataValue<Integer> precision = MetadataValue.absent(), scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        var numeric = NUMERIC.matcher(nativeType);
        if (numeric.matches()) {
            int p = Integer.parseInt(numeric.group(1)), s = Integer.parseInt(numeric.group(2));
            column.setPrecisionSize(p); column.setScaleSize(s);
            precision = MetadataValue.value(p); scale = MetadataValue.value(s);
        } else {
            String base = base(nativeType);
            var sized = SIZED.matcher(nativeType);
            if (sized.matches() && (base.startsWith("character") || base.equals("varchar") || base.equals("char")))
                length = MetadataValue.value(Long.parseLong(sized.group(1)));
            switch (base) {
                case "smallint" -> { column.setPrecisionSize(5); precision = MetadataValue.value(16); scale = MetadataValue.value(0); }
                case "integer" -> { column.setPrecisionSize(10); precision = MetadataValue.value(32); scale = MetadataValue.value(0); }
                case "bigint" -> { column.setPrecisionSize(19); precision = MetadataValue.value(64); scale = MetadataValue.value(0); }
                case "real" -> { column.setPrecisionSize(8); precision = MetadataValue.value(24); }
                case "double precision" -> { column.setPrecisionSize(17); precision = MetadataValue.value(53); }
                case "boolean", "bit" -> column.setPrecisionSize(1);
                case "numeric" -> { column.setPrecisionSize(0); column.setScaleSize(0); }
                default -> { }
            }
        }
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(product), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(true), precision, scale, length, MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.unknown()));
        return column;
    }

    /** A character column carrying the explicit collation the catalog reports (or none for the type default). */
    static ColumnDescription text(ProductTypeEnum product, String name, String nativeType, String collation) {
        var column = new ColumnDescription();
        column.setFieldName(name); column.setDbType(product); column.setNullable(true);
        column.setFieldType(base(nativeType).equals("character") ? Types.CHAR : product == HIGHGO && base(nativeType).equals("text")
                ? Types.LONGVARCHAR : Types.VARCHAR);
        var sized = SIZED.matcher(nativeType);
        MetadataValue<Long> length = sized.matches() ? MetadataValue.value(Long.parseLong(sized.group(1))) : MetadataValue.absent();
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(product), MetadataValue.value(nativeType),
                MetadataValue.absent(), MetadataValue.value(true), MetadataValue.absent(), MetadataValue.absent(), length,
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                collation == null ? MetadataValue.absent() : MetadataValue.value(collation), MetadataValue.unknown()));
        return column;
    }

    static ColumnDescription notNull(ColumnDescription column) {
        return withFacts(column, f -> new ColumnMetadataEvidence(f.provenance(), f.nativeType(), f.defaultExpression(),
                MetadataValue.value(false), f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(),
                f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
    }

    static ColumnDescription withFacts(ColumnDescription column, java.util.function.UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    static MetadataProvenance provenance(ProductTypeEnum product) {
        return switch (product) {
            case HIGHGO -> new MetadataProvenance(HIGHGO, "HighGo Database 6 Release 6.0.4.2-64-bit Production", "12.7", "HIGHGO_CATALOG_V1", Instant.EPOCH);
            case UXDB -> new MetadataProvenance(UXDB, "UXsinoDB 2.1.0.3 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 7.3.0, 64-bit", "10.0", "UXDB_CATALOG_V1", Instant.EPOCH);
            case GREENPLUM -> new MetadataProvenance(GREENPLUM, "PostgreSQL 12.12 (Greenplum Database 7.1.0 build dev) on x86_64-pc-linux-gnu, compiled by gcc (Ubuntu 11.4.0-1ubuntu1~22.04.3) 11.4.0, 64-bit", "12.12", "GREENPLUM_CATALOG_V1", Instant.EPOCH);
            case POSTGRESQL -> new MetadataProvenance(POSTGRESQL, "PostgreSQL", "16.13 (Debian 16.13-1.pgdg13+1)", "NATIVE_CATALOG_V1", Instant.EPOCH);
            default -> throw new IllegalArgumentException(product.name());
        };
    }

    static int jdbc(ProductTypeEnum product, String nativeType) {
        String base = base(nativeType);
        return switch (base) {
            case "smallint" -> Types.SMALLINT;
            case "integer" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "numeric" -> Types.NUMERIC;
            case "real" -> Types.REAL;
            case "double precision" -> Types.DOUBLE;
            case "boolean" -> Types.BIT;
            case "character varying", "varchar" -> Types.VARCHAR;
            case "character", "char" -> Types.CHAR;
            case "text" -> product == HIGHGO ? Types.LONGVARCHAR : Types.VARCHAR;
            case "bytea" -> Types.BINARY;
            case "date" -> Types.DATE;
            case "bit" -> Types.BIT;
            case "xml" -> Types.SQLXML;
            case "money" -> Types.DOUBLE;
            case "time with time zone", "time without time zone" -> Types.TIME;
            case "timestamp with time zone", "timestamp without time zone" -> Types.TIMESTAMP;
            default -> nativeType.endsWith("[]") ? Types.ARRAY : Types.OTHER;
        };
    }

    private static String base(String nativeType) {
        String base = nativeType.replaceAll("\\([^)]*\\)", "");
        if (base.startsWith("time")) return base.contains("time zone") ? base : "time without time zone";
        if (base.startsWith("timestamp")) return base.contains("time zone") ? base : "timestamp without time zone";
        return base;
    }

    /** Fidelity, target type and the LOSS+BLOCKER codes (the shared test keeps the JVM-zone codec out of outcomes). */
    static String outcome(ColumnTypeMapping mapping) {
        var codes = new TreeSet<String>(mapping.codes(TypeMappingIssue.Severity.LOSS));
        codes.addAll(mapping.codes(TypeMappingIssue.Severity.BLOCKER));
        codes.remove("TIMESTAMP_JVM_ZONE_DST_SHIFT");
        return mapping.fidelity() + "|" + (mapping.fidelity() == TypeFidelity.UNSUPPORTED || mapping.targetNativeType() == null
                ? "-" : mapping.targetNativeType()) + "|" + String.join(",", codes);
    }
}
