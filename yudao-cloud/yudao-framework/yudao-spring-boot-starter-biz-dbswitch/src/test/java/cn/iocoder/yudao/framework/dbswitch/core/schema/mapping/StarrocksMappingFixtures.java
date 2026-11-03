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

/**
 * Column fixtures for the probed StarRocks 3.5.21-85fccf8 catalog and Connector/J 9.7.0.
 * JDBC precision, classes and information_schema shapes are the values read from that server.
 */
final class StarrocksMappingFixtures {
    private StarrocksMappingFixtures() { }

    static ColumnDescription column(String name, String nativeType) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(ProductTypeEnum.STARROCKS);
        column.setNullable(true);
        String type = nativeType.toLowerCase(Locale.ROOT);
        MetadataValue<Integer> precision = MetadataValue.absent();
        MetadataValue<Integer> scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        column.setSigned(false);
        switch (type) {
            case "tinyint(4)", "smallint(6)", "int(11)", "bigint(20)" -> {
                int digits = switch (type) { case "tinyint(4)" -> 3; case "smallint(6)" -> 5; case "int(11)" -> 10; default -> 19; };
                int jdbc = switch (type) { case "tinyint(4)" -> Types.TINYINT; case "smallint(6)" -> Types.SMALLINT; case "bigint(20)" -> Types.BIGINT; default -> Types.INTEGER; };
                integer(column, jdbc, digits, type.equals("bigint(20)") ? "java.lang.Long" : "java.lang.Integer");
                precision = MetadataValue.value(digits);
                scale = MetadataValue.value(0);
            }
            case "bigint(20) unsigned" -> {
                column.setFieldType(Types.CHAR);
                column.setPrecisionSize(13);
                column.setFiledTypeClassName("java.lang.String");
                precision = MetadataValue.value(39);
            }
            case "tinyint(1)" -> {
                column.setFieldType(Types.BIT);
                column.setPrecisionSize(1);
                column.setFiledTypeClassName("java.lang.Boolean");
                scale = MetadataValue.value(0);
            }
            case "float" -> {
                column.setFieldType(Types.REAL);
                column.setPrecisionSize(12);
                column.setSigned(true);
                column.setFiledTypeClassName("java.lang.Float");
                precision = MetadataValue.value(7);
                scale = MetadataValue.value(7);
            }
            case "double" -> {
                column.setFieldType(Types.DOUBLE);
                column.setPrecisionSize(22);
                column.setSigned(true);
                column.setFiledTypeClassName("java.lang.Double");
                precision = MetadataValue.value(15);
                scale = MetadataValue.value(15);
            }
            case "json" -> {
                column.setFieldType(Types.CHAR);
                column.setPrecisionSize(20);
                column.setFiledTypeClassName("java.lang.String");
            }
            case "varbinary" -> {
                column.setFieldType(Types.VARCHAR);
                column.setPrecisionSize(192);
                column.setFiledTypeClassName("java.lang.String");
            }
            case "date" -> {
                column.setFieldType(Types.DATE);
                column.setPrecisionSize(10);
            }
            case "datetime" -> {
                column.setFieldType(Types.TIMESTAMP);
                column.setPrecisionSize(19);
                column.setFiledTypeClassName("java.time.LocalDateTime");
            }
            default -> {
                var decimal = Pattern.compile("decimal\\(([0-9]+),\\s*([0-9]+)\\)").matcher(type);
                var chars = Pattern.compile("(char|varchar)\\(([0-9]+)\\)").matcher(type);
                if (decimal.matches()) {
                    int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
                    column.setFieldType(Types.DECIMAL);
                    column.setPrecisionSize(p + 2);
                    column.setScaleSize(s);
                    column.setSigned(true);
                    precision = MetadataValue.value(p);
                    scale = MetadataValue.value(s);
                } else if (chars.matches()) {
                    long n = Long.parseLong(chars.group(2));
                    column.setFieldType(Types.VARCHAR);
                    column.setPrecisionSize(chars.group(1).equals("char") ? 64 : (int) n);
                    column.setFiledTypeClassName("java.lang.String");
                    length = MetadataValue.value(n);
                } else if (type.startsWith("array<")) {
                    column.setFieldType(Types.VARCHAR);
                    column.setPrecisionSize(20);
                    column.setFiledTypeClassName("java.lang.String");
                } else {
                    column.setFieldType(Types.CHAR);
                    column.setFiledTypeClassName("java.lang.String");
                }
            }
        }
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(), MetadataValue.value(type),
                MetadataValue.absent(), MetadataValue.value(true), precision, scale, length, MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.unknown()));
        return column;
    }

    static ColumnDescription withFacts(ColumnDescription column, java.util.function.UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(ProductTypeEnum.STARROCKS, "StarRocks", "3.5.21-85fccf8", "STARROCKS_CATALOG_V1", Instant.EPOCH);
    }

    private static void integer(ColumnDescription column, int jdbc, int precision, String className) {
        column.setFieldType(jdbc);
        column.setPrecisionSize(precision);
        column.setSigned(true);
        column.setFiledTypeClassName(className);
    }
}
