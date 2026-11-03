package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DorisIntegerProjectionTest {
    @Test void nativeIntegerMetadataMustMatchBothJdbcAndVendorType() throws Exception {
        var projection = new DatabaseDorisImpl().integerKeyProjection("k", "id");
        assertFalse(projection.requiresExplicitOuterLimit());
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
        when(metadata.getColumnTypeName(1)).thenReturn("BIGINT");
        assertEquals(0, projection.jdbcType(metadata, 1));
        when(metadata.isSigned(1)).thenReturn(true);
        assertEquals(Types.BIGINT, projection.jdbcType(metadata, 1));
        when(metadata.getColumnTypeName(1)).thenReturn("LARGEINT");
        assertEquals(0, projection.jdbcType(metadata, 1));
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        assertEquals(0, projection.jdbcType(metadata, 1));
    }

    @Test void inheritedDorisClassCannotEnableOtherProducts() {
        var database = mock(DatabaseDorisImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.STARROCKS);
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection("k", "id", 256));
    }
}
