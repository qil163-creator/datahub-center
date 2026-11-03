package cn.iocoder.yudao.framework.dbswitch.driver;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InfluxFlightJdbcAdapterTest {
    private static final Instant EXPECTED = Instant.parse("2026-01-01T00:00:00.123456789Z");

    @Test void unsigned64TextIsExactAndNarrowingCannotWrapOrUseUnreliableSignedMetadata()throws Exception {
        var fixture=fixture("18446744073709551615",Types.BIGINT);
        var rows=fixture.connection.createStatement().executeQuery("SELECT u");
        var maximum=new java.math.BigInteger("18446744073709551615");
        assertEquals(maximum,rows.getObject(1));assertEquals(maximum,rows.getObject("time",java.math.BigInteger.class));
        assertEquals(new java.math.BigDecimal(maximum),rows.getBigDecimal(1));assertEquals(maximum.toString(),rows.getString("time"));
        assertEquals("22003",assertThrows(SQLException.class,()->rows.getLong(1)).getSQLState());
        assertThrows(SQLException.class,()->rows.getObject("time",Long.class));assertThrows(SQLException.class,()->rows.getInt(1));
        when(fixture.rows.getString(1)).thenReturn(Long.toString(Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE,rows.getObject(1));assertEquals(Long.MIN_VALUE,rows.getLong("time"));
        when(fixture.rows.getString(1)).thenReturn("127");assertEquals((byte)127,rows.getByte("time"));
        when(fixture.rows.getString(1)).thenReturn("128");assertThrows(SQLException.class,()->rows.getByte(1));
        verify(fixture.rows,never()).getObject(anyInt());verify(fixture.rows,never()).getLong(anyInt());
    }

    @Test void exactIntegerNullAndMalformedValuesPreserveNullStateAndSanitizeErrors()throws Exception {
        var fixture=fixture(null,Types.BIGINT);when(fixture.rows.wasNull()).thenReturn(true);
        var rows=fixture.connection.createStatement().executeQuery("SELECT u");
        assertNull(rows.getObject(1));assertNull(rows.getBigDecimal("time"));assertNull(rows.getObject(1,Long.class));
        assertEquals(0L,rows.getLong(1));assertTrue(rows.wasNull());
        for(String invalid:List.of("18446744073709551616","-9223372036854775809","secret-invalid-number","1.1")) {
            when(fixture.rows.getString(1)).thenReturn(invalid);
            var error=assertThrows(SQLException.class,()->rows.getObject(1));assertEquals("22003",error.getSQLState());assertNull(error.getCause());assertFalse(error.getMessage().contains(invalid));
        }
    }

    @Test void nativeDeadlineAndPhysicalExitCapabilitiesAreForwardedWithoutExposingTheDriver()throws Exception {
        var source=mock(Connection.class,withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        var nativeSource=(DriverNativeAccess.ConnectionAccess)source;
        var exited=new java.util.concurrent.CompletableFuture<Void>();
        when(nativeSource.physicalTermination()).thenReturn(exited.minimalCompletionStage());
        when(nativeSource.requestClose()).thenReturn(exited.minimalCompletionStage());
        when(nativeSource.invokeNative(any(),eq(120L))).thenAnswer(call->((java.util.concurrent.Callable<?>)call.getArgument(0)).call());
        var wrapped=InfluxFlightJdbcAdapter.wrap(source,getClass().getClassLoader());
        var access=(DriverNativeAccess.ConnectionAccess)wrapped;
        var observed=access.physicalTermination();assertFalse(observed.toCompletableFuture().isDone());
        assertEquals("accepted",access.invokeNative(()->"accepted",120L));
        verify(nativeSource).invokeNative(any(),eq(120L));
        var closing = DriverNativeAccess.requestClose(wrapped); assertFalse(closing.toCompletableFuture().isDone());
        verify(nativeSource).requestClose();
        exited.complete(null);assertNull(observed.toCompletableFuture().get());
        assertNull(closing.toCompletableFuture().get());
        assertThrows(SQLFeatureNotSupportedException.class,()->access.unwrapNative(VendorConnection.class.getName()));
        verify(nativeSource,never()).unwrapNative(any());
    }

    @Test void allTemporalGettersUseTheSameAbsoluteInstantByIndexOrLabelWithoutChangingCalendar() throws Exception {
        var fixture = fixture("2026-01-01 00:00:00.123456789", Types.TIMESTAMP);
        ResultSet rows = fixture.connection.createStatement().executeQuery("SELECT time");
        assertEquals(EXPECTED, rows.getTimestamp(1).toInstant());
        assertEquals(EXPECTED, rows.getTimestamp("time").toInstant());
        assertEquals(EXPECTED, ((Timestamp) rows.getObject(1)).toInstant());
        assertEquals(EXPECTED, ((Timestamp) rows.getObject("time")).toInstant());
        assertEquals(EXPECTED, rows.getObject(1, Instant.class));
        assertEquals(EXPECTED.atOffset(ZoneOffset.UTC), rows.getObject("time", OffsetDateTime.class));
        assertEquals(LocalDateTime.ofInstant(EXPECTED, ZoneOffset.UTC), rows.getObject(1, LocalDateTime.class));
        assertEquals(EXPECTED, rows.getObject(1, Timestamp.class).toInstant());
        assertEquals("2026-01-01 00:00:00.123456789", rows.getString("time"));
        for (String zone : List.of("UTC", "Asia/Shanghai", "America/New_York")) {
            var calendar = Calendar.getInstance(TimeZone.getTimeZone(zone)); var copy = calendar.clone();
            assertEquals(EXPECTED, rows.getTimestamp(1, calendar).toInstant());
            assertEquals(EXPECTED, rows.getTimestamp("time", calendar).toInstant());
            assertEquals(copy, calendar);
        }
        assertThrows(SQLFeatureNotSupportedException.class, () -> rows.getObject(1, Long.class));
        verify(fixture.rows, never()).getTimestamp(anyInt()); verify(fixture.rows, never()).getObject(anyInt());
    }

    @Test void nullAndMalformedValuesCannotBecomeEpochOrLeakRawData() throws Exception {
        var fixture = fixture(null, Types.TIMESTAMP_WITH_TIMEZONE); when(fixture.rows.wasNull()).thenReturn(true);
        ResultSet rows = fixture.connection.createStatement().executeQuery("SELECT time");
        assertNull(rows.getObject(1)); assertNull(rows.getTimestamp("time")); assertNull(rows.getString(1)); assertTrue(rows.wasNull());
        when(fixture.rows.getString(1)).thenReturn("sensitive-invalid-timestamp");
        var failure = assertThrows(SQLException.class, () -> rows.getObject(1));
        assertEquals("22007", failure.getSQLState()); assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("sensitive-invalid-timestamp"));
    }

    @Test void offsetTextIsNormalizedExactlyAndNonTimestampColumnsRetainTheirDriverContract() throws Exception {
        var offset = fixture("2026-01-01T08:00:00.123456789+08:00", Types.TIMESTAMP_WITH_TIMEZONE);
        assertEquals(EXPECTED, offset.connection.createStatement().executeQuery("SELECT time").getObject(1, Instant.class));
        var ordinary = fixture("2026-01-01 00:00:00", Types.VARCHAR);
        when(ordinary.rows.getObject(1)).thenReturn("unaltered");
        assertEquals("unaltered", ordinary.connection.createStatement().executeQuery("SELECT text").getObject(1));
    }

    @Test void reverseReferencesUnwrapAndNativeAccessCannotExposeUnadaptedJdbcObjects() throws Exception {
        var fixture = fixture("2026-01-01 00:00:00.123456789", Types.TIMESTAMP);
        var statement = fixture.connection.createStatement(); var rows = statement.executeQuery("SELECT time");
        assertSame(fixture.connection, statement.getConnection()); assertSame(statement, rows.getStatement());
        assertSame(fixture.connection, fixture.connection.getMetaData().getConnection());
        assertSame(fixture.connection, fixture.connection.unwrap(Connection.class));
        assertSame(statement, statement.unwrap(Statement.class)); assertSame(rows, rows.unwrap(ResultSet.class));
        assertFalse(fixture.connection.isWrapperFor(VendorConnection.class));
        assertThrows(SQLFeatureNotSupportedException.class, () -> fixture.connection.unwrap(VendorConnection.class));
        var access = (DriverNativeAccess.ConnectionAccess) fixture.connection;
        assertSame(fixture.connection, access.unwrapNative(Connection.class.getName()));
        assertThrows(SQLFeatureNotSupportedException.class, () -> access.unwrapNative(VendorConnection.class.getName()));
        assertEquals(EXPECTED, fixture.connection.prepareStatement("SELECT time").executeQuery().getObject(1, Instant.class));
        rows.close(); statement.close(); fixture.connection.close();
        verify(fixture.rows).close(); verify(fixture.source).close();
    }

    private Fixture fixture(String text, int type) throws Exception {
        var connection = mock(VendorConnection.class); var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class); var columns = mock(ResultSetMetaData.class); var metadata = mock(DatabaseMetaData.class);
        when(connection.createStatement()).thenReturn(statement); when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata); when(metadata.getConnection()).thenReturn(connection);
        when(statement.getConnection()).thenReturn(connection); when(rows.getStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows); when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(columns); when(columns.getColumnType(1)).thenReturn(type);
        when(rows.findColumn("time")).thenReturn(1); when(rows.getString(1)).thenReturn(text);
        return new Fixture(connection, rows, InfluxFlightJdbcAdapter.wrap(connection, getClass().getClassLoader()));
    }
    interface VendorConnection extends Connection { }
    record Fixture(Connection source, ResultSet rows, Connection connection) { }
}
