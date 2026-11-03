package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseInfluxdbImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InfluxIntegerProjectionTest {
    private final DatabaseInfluxdbImpl database = new DatabaseInfluxdbImpl();

    @ParameterizedTest @ValueSource(longs = {0, 4, 12, 4294967291L, -4294967301L})
    void onlyExactNativeInt64WitnessIsAccepted(long code) throws Exception {
        var rows = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata); when(metadata.getColumnType(2)).thenReturn(Types.BIGINT);
        when(rows.getLong(2)).thenReturn(code);
        assertEquals(0, database.integerKeyProjection("k", "id").jdbcType(rows, 2));
        verify(rows, never()).getInt(2);
    }

    @Test void nullWitnessCannotBorrowThePreviousNativeType() throws Exception {
        var rows = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata); when(metadata.getColumnType(2)).thenReturn(Types.BIGINT);
        when(rows.getLong(2)).thenReturn((long) Types.BIGINT); when(rows.wasNull()).thenReturn(true);
        assertEquals(0, database.integerKeyProjection("k", "id").jdbcType(rows, 2));
    }

    @Test void metadataAloneCannotGrantIntegerIdentity() throws Exception {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnType(2)).thenReturn(Types.BIGINT); when(metadata.isSigned(2)).thenReturn(true);
        assertEquals(0, database.integerKeyProjection("k", "id").jdbcType(metadata, 2));
    }

    @Test void wrongWitnessMetadataCannotCoerceTextIntoProof() throws Exception {
        var rows = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata); when(metadata.getColumnType(2)).thenReturn(Types.VARCHAR);
        assertEquals(0, database.integerKeyProjection("k", "id").jdbcType(rows, 2));
        verify(rows, never()).getLong(2);
    }

    @Test void unqualifiedProjectionAndBoundedComponentHaveDistinctNullSafePayloads() {
        var integer = database.integerKeyProjection(null, "id");
        var component = database.keyComponentProjection(null, "id", 21);
        assertTrue(integer.valueSql().contains("AS BIGINT"));
        assertTrue(component.valueSql().contains("AS VARCHAR"));
        assertEquals(integer.typeSql(), component.typeSql());
        assertTrue(integer.requiresRowTypeWitness()); assertTrue(integer.requiresExplicitOuterLimit());
        assertFalse(component.valueSql().contains("null."));
    }

    @Test void identifiersAndByteBudgetsAreValidatedBeforeSql() {
        assertThrows(IllegalArgumentException.class, () -> database.integerKeyProjection("k; DROP", "id"));
        assertThrows(IllegalArgumentException.class, () -> database.integerKeyProjection(null, "id\""));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id", 0));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id", 257));
    }
}
