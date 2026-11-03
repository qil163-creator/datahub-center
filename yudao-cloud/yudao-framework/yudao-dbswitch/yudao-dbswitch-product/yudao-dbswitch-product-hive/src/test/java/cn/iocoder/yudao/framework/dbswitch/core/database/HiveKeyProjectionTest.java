package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HiveKeyProjectionTest {
    private final DatabaseHiveImpl database = new DatabaseHiveImpl();
    @ParameterizedTest @CsvSource({"smallint,5,5", "SMALLINT,5,5", "int,4,4", "bigint,-5,-5",
            "bigint,4,0", "decimal,3,0", "string,12,0", "char,1,0", "tinyint,-6,0", "double,8,0"})
    void nativeMetadataNotConvertedValuesDeterminesEligibility(String name, int jdbc, int expected) throws Exception {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnTypeName(2)).thenReturn(name); when(metadata.getColumnType(2)).thenReturn(jdbc);
        when(metadata.isSigned(2)).thenReturn(true);
        assertEquals(expected, database.integerKeyProjection("k", "id").jdbcType(metadata, 2));
        when(metadata.isSigned(2)).thenReturn(false);
        assertEquals(0, database.integerKeyProjection("k", "id").jdbcType(metadata, 2));
    }
    @Test void binaryPayloadHasNoStringFallbackAndKeepsNull() throws Exception {
        var rows = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata); when(metadata.getColumnType(1)).thenReturn(Types.BINARY);
        var projection = database.keyComponentProjection("k", "id", 21);
        assertNull(projection.readBytes(rows, 1));
        byte[] exact = "9007199254740993".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(rows.getObject(1)).thenReturn(exact); assertArrayEquals(exact, projection.readBytes(rows, 1));
        when(rows.getObject(1)).thenReturn("9007199254740993"); assertThrows(SQLException.class, () -> projection.readBytes(rows, 1));
        when(rows.getObject(1)).thenReturn(new byte[22]); assertThrows(SQLException.class, () -> projection.readBytes(rows, 1));
        verify(rows, never()).getString(1); verify(rows, never()).getBytes(1); verify(rows, never()).next(); verify(rows, never()).close();
    }
    @Test void malformedPayloadMetadataIsRejectedBeforeReadingObject() throws Exception {
        var rows = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata); when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        assertThrows(SQLException.class, () -> database.keyComponentProjection(null, "id", 21).readBytes(rows, 1));
        verify(rows, never()).getObject(1);
    }
    @Test void addedSpiPreservesOtherDriversGetBytesContract() throws Exception {
        var projection = mock(BoundedKeyComponentProjection.class, CALLS_REAL_METHODS);
        var rows = mock(ResultSet.class); var bytes = new byte[]{1, 2}; when(rows.getBytes(3)).thenReturn(bytes);
        assertSame(bytes, projection.readBytes(rows, 3)); verify(rows, never()).getObject(3);
    }
    @Test void ordinaryIdentifiersAndBoundsAreValidatedBeforeRendering() {
        assertThrows(IllegalArgumentException.class, () -> database.integerKeyProjection("k; DROP", "id"));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id`", 21));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection("k", "id", 0));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection("k", "id", 257));
        var projection = database.integerKeyProjection(null, "id");
        assertEquals("NULLIF(`id`, `id`)", projection.typeSql()); assertTrue(projection.requiresExplicitOuterLimit());
        assertFalse(projection.valueSql().contains("null."));
    }
    @Test void metadataIdentifiersAreQuotedWithoutChangingTheirContent() {
        assertEquals("`sch`.`ta``ble`", database.getQuotedSchemaTableCombination("sch", "ta`ble"));
        assertThrows(IllegalArgumentException.class, () -> database.getQuotedSchemaTableCombination(null, "sample"));
        assertThrows(IllegalArgumentException.class, () -> database.getQuotedSchemaTableCombination("schema", "a\0b"));
    }
}
