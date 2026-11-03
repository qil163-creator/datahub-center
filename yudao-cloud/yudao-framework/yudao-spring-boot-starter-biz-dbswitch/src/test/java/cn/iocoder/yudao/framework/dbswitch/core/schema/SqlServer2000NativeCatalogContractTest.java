package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqlServer2000NativeCatalog;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Failure contracts only; actual catalog facts are established by SqlServer2000NativeCatalogTest. */
class SqlServer2000NativeCatalogContractTest {
    @Test void modernOrOtherServerNeverReachesLegacyCatalog() throws Exception {
        for (String version : new String[]{"16.0.1000", "8.00.761", "8.00.760-compatible", ""}) {
            var connection = connection(version);
            assertUnsupported(() -> NativeSchemaReader.read(connection, ProductTypeEnum.SQLSERVER2000, "dbo", "table", 5));
            verify(connection, never()).createStatement(); verify(connection, never()).prepareStatement(anyString());
        }
    }

    @Test void invalidTimeoutNeverTouchesConnection() {
        var connection = mock(Connection.class);
        for (int timeout : new int[]{-1, 0, 121})
            assertThrows(IllegalArgumentException.class, () -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", timeout));
        verifyNoInteractions(connection);
    }

    @Test void nativeVersionMismatchCannotUseDriverFacade() throws Exception {
        var connection = connection("8.00.760"); visibility(connection, "16.0.1000", 1, 1);
        assertUnsupported(() -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5));
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test void unobservedOrNonOwnerVisibilityCannotBecomeAbsentFacts() throws Exception {
        var connection = connection("8.00.760"); visibility(connection, "8.00.760", 0, 0);
        assertUnsupported(() -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5));
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).commit(); verify(connection, never()).rollback();
        verify(connection, never()).setAutoCommit(anyBoolean()); verify(connection, never()).close();
    }

    @Test void additionalObjectReadFailureNeverFallsBackToEmptyCatalog() throws Exception {
        var connection = ordinary(Map.of());
        var denied = new SQLException("synthetic catalog denial", "42000");
        when(connection.prepareStatement(startsWith("SELECT xtype FROM sysobjects"))).thenThrow(denied);
        assertSame(denied, assertThrows(SQLException.class, () -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5)));
    }

    @Test void aliasIdentityComputedRuleOrOpaqueDefaultAreRejectedBeforeShell() throws Exception {
        for (var override : java.util.List.of(Map.of("xusertype", 257), Map.of("is_identity", 1),
                Map.of("iscomputed", 1), Map.of("domain", 7), Map.of("opaque_default", 1), Map.of("cdefault", 7))) {
            var connection = ordinary(override);
            assertUnsupported(() -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5));
            verify(connection, never()).prepareStatement(startsWith("SELECT TOP 0"));
        }
    }

    @Test void unknownRequiredNativeFactsNeverBecomeZeroOrAbsent() throws Exception {
        var connection = ordinary(Map.of());
        var columns = connection.prepareStatement("FROM syscolumns fixture").executeQuery();
        when(columns.wasNull()).thenReturn(true);
        assertUnsupported(() -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5));
    }

    @Test void nativeCharPaddingIsLimitedToTheUserTableEnum() throws Exception {
        var connection = ordinary(Map.of("is_identity", 1));
        var rows = connection.prepareStatement("FROM sysobjects o JOIN sysusers fixture").executeQuery();
        when(rows.getString("xtype")).thenReturn("U ");
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains("alias, identity, generated, rule"));
    }

    @Test void nonzeroOrUnknownReplicationFlagsCannotBecomeAbsent() throws Exception {
        for (int flags : new int[]{1, 128, 256, -1}) {
            var connection = ordinary(Map.of());
            var rows = connection.prepareStatement("FROM sysobjects o JOIN sysusers fixture").executeQuery();
            when(rows.getInt("replinfo")).thenReturn(flags);
            var failure = assertThrows(SQLFeatureNotSupportedException.class,
                    () -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5));
            assertEquals("0A000", failure.getSQLState());
            assertTrue(failure.getMessage().contains("special table metadata"));
        }
        var connection = ordinary(Map.of());
        var rows = connection.prepareStatement("FROM sysobjects o JOIN sysusers fixture").executeQuery();
        var unknown = new java.util.concurrent.atomic.AtomicBoolean();
        when(rows.getInt(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0); unknown.set(name.equals("replinfo")); return name.equals("id") ? 1 : 0;
        });
        when(rows.wasNull()).thenAnswer(ignored -> unknown.get());
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains("required catalog fact"));
    }

    @Test void visiblePrimaryKeyIndexCannotSilentlyLoseItsColumnOrder() throws Exception {
        var connection = ordinary(Map.of()); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getInt("primary_key")).thenReturn(1); when(rows.getInt("clustered")).thenReturn(1);
        when(connection.prepareStatement(contains("FROM sysindexes i"))).thenReturn(statement);
        assertUnsupported(() -> SqlServer2000NativeCatalog.read(connection, "dbo", "table", 5));
        verify(connection, never()).prepareStatement(startsWith("SELECT TOP 0"));
    }

    private static Connection connection(String version) throws Exception {
        var connection = mock(Connection.class); var jdbc = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(jdbc);
        when(jdbc.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(jdbc.getDatabaseProductVersion()).thenReturn(version); return connection;
    }
    private static void visibility(Connection connection, String version, int admin, int owner) throws Exception {
        var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn(version);
        when(rows.getInt(2)).thenReturn(admin); when(rows.getInt(3)).thenReturn(owner);
    }
    private static Connection ordinary(Map<String, Integer> override) throws Exception {
        var connection = connection("8.00.760"); visibility(connection, "8.00.760", 1, 1);
        var table = mock(PreparedStatement.class); var tableRows = mock(ResultSet.class);
        when(table.executeQuery()).thenReturn(tableRows); when(tableRows.next()).thenReturn(true, false);
        when(tableRows.getString("xtype")).thenReturn("U"); when(tableRows.getInt("id")).thenReturn(1);
        when(tableRows.getTimestamp("crdate")).thenReturn(Timestamp.valueOf("2026-10-08 12:00:00"));
        var columns = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(columns.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString("name")).thenReturn("id"); when(rows.getString("type_name")).thenReturn("int");
        var values = new java.util.HashMap<>(Map.of("colid", 1, "xtype", 56, "xusertype", 56, "length", 4, "prec", 10));
        values.putAll(override); when(rows.getInt(anyString())).thenAnswer(invocation -> values.getOrDefault(invocation.getArgument(0), 0));
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("FROM syscolumns") ? columns : sql.contains("FROM sysobjects o JOIN sysusers") ? table
                    : emptyStatement();
        }); return connection;
    }
    private static PreparedStatement emptyStatement() throws Exception {
        var statement = mock(PreparedStatement.class); when(statement.executeQuery()).thenReturn(mock(ResultSet.class)); return statement;
    }
    private static void assertUnsupported(org.junit.jupiter.api.function.Executable call) {
        assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class, call).getSQLState());
    }
}
