package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.Sqlserver2000LogicalTypeMapper;

import java.sql.Types;
import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * Column fixtures in the shape JDBC Driver 3.0.1301.101 reported for SQL Server 2000 8.00.760.
 * Type names are unsized. Character collation is the database collation; there is no column charset.
 */
final class SqlServer2000MappingFixtures {
    private static final long LOB = 2147483647L;

    private SqlServer2000MappingFixtures() { }

    static MetadataProvenance provenance() {
        return new MetadataProvenance(ProductTypeEnum.SQLSERVER2000, Sqlserver2000LogicalTypeMapper.SERVER_PRODUCT,
                Sqlserver2000LogicalTypeMapper.SERVER_VERSION, Sqlserver2000LogicalTypeMapper.METHOD, Instant.EPOCH);
    }

    static ColumnDescription bit(String name) {
        return fixed(name, "bit", Types.BIT, "bit", "java.lang.Boolean", false, 1, 0, 1, 1, 0);
    }

    static ColumnDescription tinyint(String name) {
        return fixed(name, "tinyint", Types.TINYINT, "tinyint", "java.lang.Short", false, 3, 0, 3, 3, 0);
    }

    static ColumnDescription smallint(String name) {
        return fixed(name, "smallint", Types.SMALLINT, "smallint", "java.lang.Short", true, 5, 0, 6, 5, 0);
    }

    static ColumnDescription integer(String name) {
        return fixed(name, "int", Types.INTEGER, "int", "java.lang.Integer", true, 10, 0, 11, 10, 0);
    }

    static ColumnDescription bigint(String name) {
        return fixed(name, "bigint", Types.BIGINT, "bigint", "java.lang.Long", true, 19, 0, 20, 19, 0);
    }

    static ColumnDescription money(String name) {
        return fixed(name, "money", Types.DECIMAL, "money", "java.math.BigDecimal", true, 19, 4, 21, 19, 4);
    }

    static ColumnDescription datetime(String name) {
        return fixed(name, "datetime", Types.TIMESTAMP, "datetime", "java.sql.Timestamp", false, 23, 3, 23, 23, 3);
    }

    static ColumnDescription smalldatetime(String name) {
        return fixed(name, "smalldatetime", Types.TIMESTAMP, "smalldatetime", "java.sql.Timestamp", false, 16, 0, 16, 16, 0);
    }

    static ColumnDescription decimal(String name, int precision, int scale) {
        return decimal(name, "decimal", precision, scale);
    }

    static ColumnDescription decimalSized(String name, int precision, int scale) {
        return decimal(name, "decimal(" + precision + "," + scale + ")", precision, scale);
    }

    static ColumnDescription varchar(String name, int length) {
        return characters(name, "varchar", Types.VARCHAR, "varchar", length, length);
    }

    static ColumnDescription varcharSized(String name, int length) {
        return characters(name, "varchar(" + length + ")", Types.VARCHAR, "varchar", length, length);
    }

    static ColumnDescription nvarchar(String name, int length) {
        return characters(name, "nvarchar", Types.NVARCHAR, "nvarchar", length, length);
    }

    static ColumnDescription text(String name) {
        return characters(name, "text", Types.LONGVARCHAR, "text", (int) LOB, LOB);
    }

    static ColumnDescription image(String name) {
        var column = base(name, "image", Types.LONGVARBINARY, "image", "[B", false, (int) LOB, 0, (int) LOB);
        column.setMetadataEvidence(evidence("image", MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(LOB),
                MetadataValue.absent()));
        return column;
    }

    static ColumnDescription datetime2(String name) {
        return base(name, "datetime2", Types.TIMESTAMP, "datetime2", "java.sql.Timestamp", false, 27, 7, 27);
    }

    static ColumnDescription withFacts(ColumnDescription column, UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
        return column;
    }

    private static ColumnDescription decimal(String name, String nativeType, int precision, int scale) {
        var column = base(name, nativeType, Types.DECIMAL, "decimal", "java.math.BigDecimal", true, precision, scale,
                precision == 18 && scale == 4 ? 20 : 0);
        column.setMetadataEvidence(evidence(nativeType, MetadataValue.value(precision), MetadataValue.value(scale),
                MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }

    private static ColumnDescription characters(String name, String nativeType, int jdbc, String typeName, int precision, long length) {
        var column = base(name, nativeType, jdbc, typeName, "java.lang.String", false, precision, 0, precision);
        column.setMetadataEvidence(evidence(nativeType, MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(length),
                MetadataValue.value(Sqlserver2000LogicalTypeMapper.COLLATION)));
        return column;
    }

    private static ColumnDescription fixed(String name, String nativeType, int jdbc, String typeName, String className,
                                           boolean signed, int precision, int scale, int display, int catalogPrecision, int catalogScale) {
        var column = base(name, nativeType, jdbc, typeName, className, signed, precision, scale, display);
        column.setMetadataEvidence(evidence(nativeType, MetadataValue.value(catalogPrecision), MetadataValue.value(catalogScale),
                MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }

    private static ColumnDescription base(String name, String nativeType, int jdbc, String typeName, String className,
                                          boolean signed, int precision, int scale, int display) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(ProductTypeEnum.SQLSERVER2000);
        column.setNullable(true);
        column.setFieldType(jdbc);
        column.setFieldTypeName(typeName);
        column.setFiledTypeClassName(className);
        column.setSigned(signed);
        column.setPrecisionSize(precision);
        column.setScaleSize(scale);
        column.setDisplaySize(display);
        column.setMetadataEvidence(evidence(nativeType, MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent()));
        return column;
    }

    private static ColumnMetadataEvidence evidence(String nativeType, MetadataValue<Integer> precision, MetadataValue<Integer> scale,
                                                   MetadataValue<Long> length, MetadataValue<String> collation) {
        return new ColumnMetadataEvidence(provenance(), MetadataValue.value(nativeType), MetadataValue.absent(),
                MetadataValue.value(true), precision, scale, length, MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent(), collation, MetadataValue.absent());
    }
}
