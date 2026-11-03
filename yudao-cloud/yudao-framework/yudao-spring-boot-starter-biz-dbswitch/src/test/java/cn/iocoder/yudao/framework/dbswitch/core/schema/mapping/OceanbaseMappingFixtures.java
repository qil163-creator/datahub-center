package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.sql.Types;
import java.time.Instant;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OCEANBASE;

/**
 * Column facts measured on OceanBase CE 4.3.5.6 MySQL mode through Connector/J 2.4.12.
 * Widths, classes and the double precision mismatch are the probed values, not MySQL 8.4 catalog digits.
 */
final class OceanbaseMappingFixtures {
    static final String VERSION = "5.7.25-OceanBase_CE-v4.3.5.6";

    private OceanbaseMappingFixtures() { }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(OCEANBASE, "OceanBase", VERSION, "OCEANBASE_CATALOG_V1", Instant.EPOCH);
    }

    static ColumnDescription column(String name, String nativeType) {
        var column = base(name);
        switch (nativeType) {
            case "tinyint(4)" -> integer(column, Types.TINYINT, "TINYINT", "java.lang.Integer", 4, true);
            case "tinyint(1)" -> integer(column, Types.BIT, "TINYINT", "java.lang.Boolean", 1, true);
            case "tinyint(3) unsigned" -> integer(column, Types.TINYINT, "TINYINT UNSIGNED", "java.lang.Integer", 3, false);
            case "tinyint(2)" -> integer(column, Types.TINYINT, "TINYINT", "java.lang.Integer", 2, true);
            case "smallint(6)" -> integer(column, Types.SMALLINT, "SMALLINT", "java.lang.Integer", 6, true);
            case "smallint(5) unsigned" -> integer(column, Types.SMALLINT, "SMALLINT UNSIGNED", "java.lang.Integer", 5, false);
            case "mediumint(9)" -> integer(column, Types.INTEGER, "MEDIUMINT", "java.lang.Integer", 9, true);
            case "mediumint(8) unsigned" -> integer(column, Types.INTEGER, "MEDIUMINT UNSIGNED", "java.lang.Integer", 8, false);
            case "int(11)" -> integer(column, Types.INTEGER, "INT", "java.lang.Integer", 11, true);
            case "int(10) unsigned" -> integer(column, Types.INTEGER, "INT UNSIGNED", "java.lang.Long", 10, false);
            case "bigint(20)" -> integer(column, Types.BIGINT, "BIGINT", "java.lang.Long", 20, true);
            case "bigint(20) unsigned" -> integer(column, Types.BIGINT, "BIGINT UNSIGNED", "java.math.BigInteger", 20, false);
            case "decimal(10,2)" -> decimal(column, 10, 2);
            case "decimal(38,10)" -> decimal(column, 38, 10);
            case "decimal(65,30)" -> decimal(column, 65, 30);
            case "decimal(65,31)" -> decimal(column, 65, 31);
            case "decimal(66,0)" -> decimal(column, 66, 0);
            case "float" -> floating(column, Types.REAL, "FLOAT", "java.lang.Float", 12, 12);
            case "double" -> floating(column, Types.DOUBLE, "DOUBLE", "java.lang.Double", 23, 22);
            case "date" -> temporal(column, Types.DATE, "DATE", "java.sql.Date", 10);
            case "datetime(6)" -> temporal(column, Types.TIMESTAMP, "DATETIME", "java.sql.Timestamp", 26);
            case "timestamp(6)" -> temporal(column, Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", 26);
            case "time(6)" -> temporal(column, Types.TIME, "TIME", "java.sql.Time", 17);
            case "json" -> json(column);
            case "binary(4)" -> binary(column, Types.BINARY, "BINARY", 4L);
            case "varbinary(16)" -> binary(column, Types.VARBINARY, "VARBINARY", 16L);
            case "blob" -> binary(column, Types.LONGVARBINARY, "BLOB", 65535L);
            default -> throw new IllegalArgumentException(nativeType);
        }
        evidence(column, nativeType, MetadataValue.absent(), MetadataValue.absent(), column.getMetadataEvidence().numericPrecision(),
                column.getMetadataEvidence().numericScale(), column.getMetadataEvidence().characterLength());
        return column;
    }

    /** Character column. Collation is one of the two collations created in the probe. */
    static ColumnDescription text(String name, String nativeType, String collation) {
        var column = base(name);
        long length;
        int jdbc;
        String typeName;
        if (nativeType.equals("text")) {
            length = 65535L;
            jdbc = Types.LONGVARCHAR;
            typeName = "VARCHAR";
        } else if (nativeType.startsWith("char(")) {
            length = lengthOf(nativeType);
            jdbc = Types.CHAR;
            typeName = "CHAR";
        } else if (nativeType.startsWith("varchar(")) {
            length = lengthOf(nativeType);
            jdbc = Types.VARCHAR;
            typeName = "VARCHAR";
        } else {
            throw new IllegalArgumentException(nativeType);
        }
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setFiledTypeClassName("java.lang.String");
        column.setPrecisionSize((int) length);
        column.setScaleSize(0);
        column.setSigned(true);
        String charset = collation.substring(0, collation.indexOf('_'));
        evidence(column, nativeType, MetadataValue.value(charset), MetadataValue.value(collation),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(length));
        return column;
    }

    static ColumnDescription driverPrecision(ColumnDescription column, int precision) {
        column.setPrecisionSize(precision);
        return column;
    }

    static ColumnDescription provenance(ColumnDescription column, MetadataProvenance provenance) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance, facts.nativeType(), facts.defaultExpression(),
                facts.nullable(), facts.numericPrecision(), facts.numericScale(), facts.characterLength(),
                facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
        return column;
    }

    private static ColumnDescription base(String name) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(OCEANBASE);
        column.setNullable(true);
        return column;
    }

    private static void integer(ColumnDescription column, int jdbc, String typeName, String className, int precision, boolean signed) {
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setFiledTypeClassName(className);
        column.setPrecisionSize(precision);
        column.setScaleSize(0);
        column.setSigned(signed);
        column.setMetadataEvidence(stub(MetadataValue.value(precision), MetadataValue.value(0), MetadataValue.absent()));
    }

    private static void decimal(ColumnDescription column, int precision, int scale) {
        column.setFieldType(Types.DECIMAL);
        column.setFieldTypeName("DECIMAL");
        column.setFiledTypeClassName("java.math.BigDecimal");
        column.setPrecisionSize(precision);
        column.setScaleSize(scale);
        column.setSigned(true);
        column.setMetadataEvidence(stub(MetadataValue.value(precision), MetadataValue.value(scale), MetadataValue.absent()));
    }

    private static void floating(ColumnDescription column, int jdbc, String typeName, String className, int driverPrecision, int catalogPrecision) {
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setFiledTypeClassName(className);
        column.setPrecisionSize(driverPrecision);
        column.setScaleSize(31);
        column.setSigned(true);
        column.setMetadataEvidence(stub(MetadataValue.value(catalogPrecision), MetadataValue.absent(), MetadataValue.absent()));
    }

    private static void temporal(ColumnDescription column, int jdbc, String typeName, String className, int precision) {
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setFiledTypeClassName(className);
        column.setPrecisionSize(precision);
        column.setScaleSize(0);
        column.setSigned(true);
        column.setMetadataEvidence(stub(MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
    }

    private static void json(ColumnDescription column) {
        column.setFieldType(Types.VARCHAR);
        column.setFieldTypeName("JSON");
        column.setFiledTypeClassName("java.lang.String");
        column.setPrecisionSize(-1);
        column.setScaleSize(0);
        column.setSigned(true);
        column.setMetadataEvidence(stub(MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(536870911L)));
    }

    private static void binary(ColumnDescription column, int jdbc, String typeName, long length) {
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setFiledTypeClassName("[B");
        column.setPrecisionSize((int) length);
        column.setScaleSize(0);
        column.setSigned(true);
        column.setMetadataEvidence(stub(MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(length)));
    }

    private static ColumnMetadataEvidence stub(MetadataValue<Integer> precision, MetadataValue<Integer> scale, MetadataValue<Long> length) {
        return new ColumnMetadataEvidence(provenance(), MetadataValue.value("pending"), MetadataValue.absent(),
                MetadataValue.value(true), precision, scale, length, MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
    }

    private static void evidence(ColumnDescription column, String nativeType, MetadataValue<String> charset, MetadataValue<String> collation,
                                 MetadataValue<Integer> precision, MetadataValue<Integer> scale, MetadataValue<Long> length) {
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType), MetadataValue.absent(),
                MetadataValue.value(true), precision, scale, length, MetadataValue.absent(), MetadataValue.absent(),
                charset, collation, MetadataValue.absent()));
    }

    private static long lengthOf(String nativeType) {
        int open = nativeType.indexOf('(');
        return Long.parseLong(nativeType.substring(open + 1, nativeType.indexOf(')')));
    }
}
