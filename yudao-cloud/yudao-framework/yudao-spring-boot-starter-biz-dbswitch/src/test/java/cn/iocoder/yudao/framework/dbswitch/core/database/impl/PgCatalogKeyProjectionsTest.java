package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.ResultSetMetaData;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PgCatalogKeyProjectionsTest {
    @ParameterizedTest
    @CsvSource({"varchar,12,12,12", "text,12,-1,-1", "bpchar,1,0,0", "varchar,1,0,0",
            "numeric,2,0,0", "int2,5,0,5", "int4,4,0,4", "int8,-5,0,-5", "int8,4,0,0", "uuid,1111,0,0"})
    void typeQualificationUsesUncastNativeMetadata(String nativeType, int jdbc, int text, int mixed) throws Exception {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnTypeName(2)).thenReturn(nativeType);
        when(metadata.getColumnType(2)).thenReturn(jdbc);
        when(metadata.isSigned(2)).thenReturn(true);
        for (var product : new ProductTypeEnum[]{ProductTypeEnum.OPENGAUSS, ProductTypeEnum.GREENPLUM}) {
            assertEquals(text, PgCatalogKeyProjections.text(product, "s", "id", 256).jdbcType(metadata, 2));
            assertEquals(mixed, PgCatalogKeyProjections.mixed(product, "s", "id", 256).jdbcType(metadata, 2));
        }
        when(metadata.isSigned(2)).thenReturn(false);
        assertEquals(0, PgCatalogKeyProjections.signedIntegerType(metadata, 2));
    }

    @ParameterizedTest @EnumSource(ProductTypeEnum.class)
    void productsAreExplicitNotProtocolOrInheritanceDerived(ProductTypeEnum product) {
        if (product == ProductTypeEnum.GREENPLUM || product == ProductTypeEnum.OPENGAUSS) {
            assertNotNull(PgCatalogKeyProjections.text(product, null, "id", 256));
        } else {
            assertThrows(UnsupportedOperationException.class, () -> PgCatalogKeyProjections.text(product, null, "id", 256));
        }
    }

    @Test void legacySqlBytesAndExactEqualityStayStableAndBounded() {
        var projection = PgCatalogKeyProjections.text(ProductTypeEnum.OPENGAUSS, "s", "id", 256);
        assertEquals("(SELECT \"s\".\"id\" WHERE FALSE)", projection.typeSql());
        assertEquals("CASE WHEN pg_catalog.octet_length(pg_catalog.convert_to(CAST(\"s\".\"id\" AS pg_catalog.text), 'UTF8')) <= 256"
                + " THEN pg_catalog.convert_to(CAST(\"s\".\"id\" AS pg_catalog.text), 'UTF8') ELSE NULL END", projection.valueSql());
        assertEquals(projection.valueSql(), projection.groupSql());
        assertEquals("pg_catalog.convert_to(CAST(\"s\".\"id\" AS pg_catalog.text), 'UTF8') = pg_catalog.decode('61275c', 'hex')",
                projection.equalitySql("a'\\"));
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql("中".repeat(86)));
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql(null));
        assertThrows(IllegalArgumentException.class, () -> PgCatalogKeyProjections.text(ProductTypeEnum.GREENPLUM, null, "id", 0));
        assertThrows(IllegalArgumentException.class, () -> PgCatalogKeyProjections.text(ProductTypeEnum.GREENPLUM, null, "id", 4097));
        assertThrows(IllegalArgumentException.class, () -> PgCatalogKeyProjections.text(ProductTypeEnum.GREENPLUM, "s;DROP TABLE x", "id", 256));
    }
}
