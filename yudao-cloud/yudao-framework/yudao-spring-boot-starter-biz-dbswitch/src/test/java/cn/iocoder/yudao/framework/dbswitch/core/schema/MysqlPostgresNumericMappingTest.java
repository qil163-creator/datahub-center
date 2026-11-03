package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MysqlPostgresNumericMappingTest {
    @ParameterizedTest
    @CsvSource(delimiter=';', value={
            "tinyint; -6; 3; smallint; -128; 127", "tinyint unsigned; -6; 3; smallint; 0; 255",
            "smallint; 5; 5; smallint; ~; ~", "smallint unsigned; 5; 5; integer; 0; 65535",
            "mediumint; 4; 7; integer; -8388608; 8388607", "mediumint unsigned; 4; 8; integer; 0; 16777215",
            "int; 4; 10; integer; ~; ~", "int unsigned; 4; 10; bigint; 0; 4294967295",
            "bigint; -5; 19; bigint; ~; ~", "bigint unsigned; -5; 20; numeric(20,0); 0; 18446744073709551615"})
    void integerRangesAndPrimaryKeysSurviveTheActualCreateGenerator(String nativeType, int jdbcType, int precision,
            String targetType, String minimum, String maximum) {
        var column = column(nativeType, jdbcType, precision, 0);
        String sql = ddl(column);
        assertTrue(sql.contains("\"value\" " + targetType + " NOT NULL"), sql);
        assertTrue(sql.contains("PRIMARY KEY (\"value\")"));
        if (!minimum.equals("~")) assertTrue(sql.contains("CHECK (\"value\" BETWEEN " + minimum + " AND " + maximum + ")"), sql);
        else assertFalse(sql.contains("CHECK"));
    }

    @ParameterizedTest @ValueSource(strings={"decimal(65,30)", "decimal(20,0)", "decimal(12,2) unsigned"})
    void decimalRetainsExactPrecisionAndScaleEvenWhenItIsAPrimaryKey(String nativeType) {
        int precision = nativeType.startsWith("decimal(65") ? 65 : nativeType.startsWith("decimal(20") ? 20 : 12;
        int scale = precision == 65 ? 30 : precision == 20 ? 0 : 2;
        String sql = ddl(column(nativeType, Types.DECIMAL, precision, scale));
        assertTrue(sql.contains("\"value\" numeric(" + precision + "," + scale + ") NOT NULL"));
        assertEquals(nativeType.endsWith("unsigned"), sql.contains("CHECK (\"value\" >= 0)"));
    }

    @ParameterizedTest @ValueSource(strings={"default", "identity", "generated", "charset", "collation", "unknown-default"})
    void unmappedColumnSemanticsNeverDisappear(String property) {
        var column = column("int", Types.INTEGER, 10, 0);
        var f = column.getMetadataEvidence();
        column.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(), f.nativeType(),
                property.equals("default") ? MetadataValue.value("1") : property.equals("unknown-default") ? MetadataValue.unknown() : f.defaultExpression(),
                f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(),
                property.equals("identity") ? MetadataValue.value("AUTO_INCREMENT") : f.identity(),
                property.equals("generated") ? MetadataValue.value("1+1") : f.generatedExpression(),
                property.equals("charset") ? MetadataValue.value("utf8mb4") : f.charset(),
                property.equals("collation") ? MetadataValue.value("utf8mb4_bin") : f.collation()));
        assertThrows(UnsupportedOperationException.class, () -> ddl(column));
    }

    @ParameterizedTest @ValueSource(strings={"boolean-jdbc", "boolean-class", "signedness", "jdbc-precision", "catalog-scale", "catalog-precision"})
    void inconsistentDriverOrCatalogEvidenceCannotAuthorizeMapping(String fault) {
        var column = column("tinyint(1)", Types.TINYINT, 3, 0);
        if (fault.equals("boolean-jdbc")) column.setFieldType(Types.BIT);
        if (fault.equals("boolean-class")) column.setFiledTypeClassName("java.lang.Boolean");
        if (fault.equals("signedness")) column.setSigned(false);
        if (fault.equals("jdbc-precision")) column.setPrecisionSize(1);
        var f = column.getMetadataEvidence();
        column.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(), f.nativeType(), f.defaultExpression(), f.nullable(),
                fault.equals("catalog-precision") ? MetadataValue.unknown() : f.numericPrecision(),
                fault.equals("catalog-scale") ? MetadataValue.value(1) : f.numericScale(), f.characterLength(),
                f.identity(), f.generatedExpression(), f.charset(), f.collation()));
        assertThrows(UnsupportedOperationException.class, () -> ddl(column));
    }

    @ParameterizedTest @ValueSource(strings={"jdbc", "catalog"})
    void unsignedMediumIntChecksEachMetadataSourcesOwnPrecision(String fault) {
        var column = column("mediumint unsigned", Types.INTEGER, 8, 0);
        if (fault.equals("jdbc")) column.setPrecisionSize(7);
        else {
            var f = column.getMetadataEvidence();
            column.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(), f.nativeType(), f.defaultExpression(),
                    f.nullable(), MetadataValue.value(8), f.numericScale(), f.characterLength(),
                    f.identity(), f.generatedExpression(), f.charset(), f.collation()));
        }
        assertThrows(UnsupportedOperationException.class, () -> ddl(column));
    }

    @Test void nullableAndQuotedNameArePreservedWithoutSourceMutation() {
        var column = column("decimal(20,0)", Types.DECIMAL, 20, 0);
        column.setFieldName("Value\""); column.setNullable(true);
        var f = column.getMetadataEvidence();
        column.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(), f.nativeType(), f.defaultExpression(), MetadataValue.value(true),
                f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation()));
        var before = column.getMetadataEvidence();
        String sql = GenerateSqlUtils.getDDLCreateTableSQL(new DatabasePostgresImpl(), List.of(column), List.of(), "public", "copy", false);
        assertTrue(sql.contains("\"Value\"\"\" numeric(20,0)")); assertFalse(sql.contains("NOT NULL"));
        assertEquals(before, column.getMetadataEvidence());
    }

    @ParameterizedTest @ValueSource(strings={"unknown-precision", "native-precision", "jdbc-precision", "jdbc-scale", "approximate-jdbc"})
    void decimalMustAgreeAcrossNativeCatalogAndJdbcMetadata(String fault) {
        var column = column("decimal(20,4)", Types.DECIMAL, 20, 4);
        if (fault.equals("jdbc-precision")) column.setPrecisionSize(19);
        if (fault.equals("jdbc-scale")) column.setScaleSize(3);
        if (fault.equals("approximate-jdbc")) column.setFieldType(Types.DOUBLE);
        var f = column.getMetadataEvidence();
        column.setMetadataEvidence(new ColumnMetadataEvidence(f.provenance(),
                fault.equals("native-precision") ? MetadataValue.value("decimal(19,4)") : f.nativeType(), f.defaultExpression(), f.nullable(),
                fault.equals("unknown-precision") ? MetadataValue.unknown() : f.numericPrecision(), f.numericScale(), f.characterLength(),
                f.identity(), f.generatedExpression(), f.charset(), f.collation()));
        assertThrows(UnsupportedOperationException.class, () -> ddl(column));
    }

    @Test void missingCatalogColumnEvidenceCannotFallBackToPortableMetadata() throws Exception {
        var source = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var dialect = org.mockito.Mockito.mock(cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase.class);
        var products = org.mockito.Mockito.mock(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.class);
        org.mockito.Mockito.when(source.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(products.metadata(ProductTypeEnum.MYSQL)).thenReturn(dialect);
        var unknown = new ColumnDescription(); unknown.setFieldName("value"); unknown.setFieldType(Types.INTEGER);
        org.mockito.Mockito.when(dialect.queryTableColumnMeta(connection, "source", "table")).thenReturn(List.of(unknown));
        var provenance = new MetadataProvenance(ProductTypeEnum.MYSQL, "MySQL", "8.4", "TEST_NATIVE_CATALOG", Instant.now());
        var missing = new TableSchemaMetadata(List.of(unknown), List.of(), provenance, MetadataValue.value(true), MetadataValue.unknown());
        try (var reader = org.mockito.Mockito.mockStatic(NativeSchemaReader.class)) {
            reader.when(() -> NativeSchemaReader.read(connection, ProductTypeEnum.MYSQL, "source", "table", 10)).thenReturn(missing);
            var facade = new cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl(source, ProductTypeEnum.MYSQL, products);
            var failure = assertThrows(RuntimeException.class, () -> facade.queryTableColumnMetaWithNativeEvidence("source", "table"));
            assertInstanceOf(java.sql.SQLException.class, failure.getCause());
        }
        org.mockito.Mockito.verify(connection).close();
    }

    private static String ddl(ColumnDescription column) {
        return GenerateSqlUtils.getDDLCreateTableSQL(new DatabasePostgresImpl(), List.of(column), List.of("value"), "public", "copy", false);
    }
    private static ColumnDescription column(String nativeType, int jdbcType, int precision, int scale) {
        var column = new ColumnDescription(); column.setFieldName("value"); column.setDbType(ProductTypeEnum.MYSQL);
        column.setFieldType(jdbcType); column.setPrecisionSize(precision); column.setScaleSize(scale);
        column.setSigned(!nativeType.endsWith("unsigned")); column.setNullable(false);
        var provenance = new MetadataProvenance(ProductTypeEnum.MYSQL, "MySQL", "8.4", "TEST_NATIVE_CATALOG", Instant.now());
        int catalogPrecision = nativeType.equals("mediumint unsigned") ? 7 : precision;
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance, MetadataValue.value(nativeType), MetadataValue.absent(),
                MetadataValue.value(false), MetadataValue.value(catalogPrecision), MetadataValue.value(scale), MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }
}
