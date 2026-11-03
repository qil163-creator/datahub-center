package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class OceanbaseDiagnosticQuotingTest {
    private static final String MODE_SQL = "show global variables where variable_name = 'ob_compatibility_mode'";
    private static final String URL = "jdbc:oceanbase://fixture/sample?socketFactory=unverified";

    @ParameterizedTest
    @ValueSource(strings = {"ORACLE", "MYSQL"})
    void diagnosticsUsesActualBorrowedConnectionMode(String mode) throws Exception {
        var fixture = fixture(mode, "orders");
        var priorConnection = mock(Connection.class);
        var priorStatement = mock(PreparedStatement.class);
        var priorRows = mock(ResultSet.class);
        when(priorConnection.prepareStatement(MODE_SQL)).thenReturn(priorStatement);
        when(priorStatement.executeQuery()).thenReturn(priorRows);
        when(priorRows.next()).thenReturn(true, false);
        when(priorRows.getString(2)).thenReturn("ORACLE".equals(mode) ? "MYSQL" : "ORACLE");
        fixture.database.prepareDialect(priorConnection);
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics);
            assertEquals("PASS", check(result, "COLUMNS").status());
            assertEquals("PASS", check(result, "ZERO_ROW_READ").status());
            String quote = "ORACLE".equals(mode) ? "\"" : "`";
            verify(fixture.connection, times(2)).prepareStatement(
                    "SELECT * FROM " + quote + "sample" + quote + "." + quote + "orders" + quote + " WHERE 1=0");
            verify(fixture.modeStatement).setMaxRows(2);
            verify(fixture.modeStatement).setQueryTimeout(anyInt());
            verify(fixture.modeRows).close();
            verify(fixture.modeStatement).close();
            verify(fixture.sampleStatement, times(2)).close();
            verify(fixture.sampleRows).close();
            verify(fixture.connection).close();
            verify(fixture.connection, never()).getMetaData();
            verifyNoInteractions(fixture.write);
            assertEquals("ORACLE".equals(mode), fixture.database.observedMysqlMode());
            verify(priorConnection, never()).close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORACLE", "MYSQL"})
    void complexPhysicalNamesRemainFailClosed(String mode) throws Exception {
        var fixture = fixture(mode, "orders\";drop table x;--");
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics);
            assertEquals("SKIPPED", check(result, "ZERO_ROW_READ").status());
            assertEquals("SKIPPED", check(result, "COLUMNS").status());
            verify(fixture.connection, never()).prepareStatement(startsWith("SELECT * FROM"));
            verify(fixture.connection).close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", ""})
    void unknownModeCannotFallBackToMysql(String mode) throws Exception {
        var fixture = fixture(mode, "orders");
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics);
            assertEquals("SKIPPED", check(result, "ZERO_ROW_READ").status());
            verify(fixture.connection, never()).prepareStatement(startsWith("SELECT * FROM"));
            verify(fixture.modeStatement).close();
            verify(fixture.modeRows).close();
            verify(fixture.connection).close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrAmbiguousModeCannotEnableSampling(boolean ambiguous) throws Exception {
        var fixture = fixture("ORACLE", "orders");
        when(fixture.modeRows.next()).thenReturn(ambiguous);
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics);
            assertEquals("SKIPPED", check(result, "ZERO_ROW_READ").status());
            verify(fixture.connection, never()).prepareStatement(startsWith("SELECT * FROM"));
            verify(fixture.modeRows).close();
            verify(fixture.modeStatement).close();
            verify(fixture.connection).close();
        }
    }

    @Test
    void failedModeReadClosesAllOwnedResourcesWithoutSampling() throws Exception {
        var fixture = fixture("ORACLE", "orders");
        when(fixture.modeRows.next()).thenThrow(new SQLException("private mode failure", "08006"));
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics);
            assertEquals("SKIPPED", check(result, "ZERO_ROW_READ").status());
            verify(fixture.modeRows).close();
            verify(fixture.modeStatement).close();
            verify(fixture.connection).close();
            verify(fixture.connection, never()).prepareStatement(startsWith("SELECT * FROM"));
        }
    }

    @Test
    void failedZeroRowReadClosesStatementAndConnection() throws Exception {
        var fixture = fixture("ORACLE", "orders");
        when(fixture.sampleStatement.executeQuery()).thenThrow(new SQLException("private read failure", "42501"));
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics);
            assertEquals("FAIL", check(result, "ZERO_ROW_READ").status());
            verify(fixture.sampleStatement, times(2)).close();
            verify(fixture.connection).close();
        }
    }

    private static Fixture fixture(String mode, String tableName) throws Exception {
        var connection = mock(Connection.class, withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        var access = (DriverNativeAccess.ConnectionAccess) connection;
        when(access.invokeNative(any(), anyLong())).thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        when(access.requestClose()).thenAnswer(call -> {
            connection.close();
            return CompletableFuture.completedFuture(null);
        });
        when(connection.isClosed()).thenReturn(true);
        var database = spy(new DatabaseOceanbaseImpl());
        doNothing().when(database).probeConnection(eq(connection), anyInt());
        doNothing().when(database).probeNamespace(eq(connection), eq("sample"), anyInt());
        doNothing().when(database).probeAccountPrivileges(eq(connection), eq("sample"), anyInt());
        doReturn(List.of("sample")).when(database).querySchemaList(connection);
        doReturn(List.of()).when(database).queryTablePrimaryKeys(eq(connection), anyString(), anyString());
        var table = new TableDescription();
        table.setSchemaName("sample"); table.setTableName(tableName); table.setTableType("TABLE");
        doReturn(List.of(table)).when(database).queryCatalogTableList(eq(connection), eq("sample"), eq(1000), anyInt());
        var modeStatement = mock(PreparedStatement.class);
        var modeRows = mock(ResultSet.class);
        when(connection.prepareStatement(MODE_SQL)).thenReturn(modeStatement);
        when(modeStatement.executeQuery()).thenReturn(modeRows);
        when(modeRows.next()).thenReturn(true, false);
        when(modeRows.getString(2)).thenReturn(mode);
        var sampleStatement = mock(PreparedStatement.class);
        var sampleRows = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(connection.prepareStatement(startsWith("SELECT * FROM"))).thenReturn(sampleStatement);
        when(sampleStatement.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnName(1)).thenReturn("id");
        when(metadata.getColumnTypeName(1)).thenReturn("INTEGER");
        when(metadata.getColumnType(1)).thenReturn(java.sql.Types.INTEGER);
        when(sampleStatement.executeQuery()).thenReturn(sampleRows);
        var read = mock(JdbcConnectionProvider.class);
        var write = mock(JdbcConnectionProvider.class);
        when(read.open(eq(URL), anyString(), anyString())).thenReturn(connection);
        return new Fixture(database, connection, read, write, modeStatement, modeRows, sampleStatement, sampleRows);
    }

    private static ConnectionDiagnosticResult.Check check(ConnectionDiagnosticResult result, String key) {
        return result.checks().stream().filter(check -> key.equals(check.key())).findFirst().orElseThrow();
    }

    private record Fixture(DatabaseOceanbaseImpl database, Connection connection, JdbcConnectionProvider read,
            JdbcConnectionProvider write, PreparedStatement modeStatement, ResultSet modeRows,
            PreparedStatement sampleStatement, ResultSet sampleRows) {
        ConnectionDiagnosticResult run(ConnectionDiagnostics diagnostics) {
            return diagnostics.diagnose(ProductTypeEnum.OCEANBASE, database, read, write, URL, "fixture", "fixture",
                    new ConnectionDiagnosticOptions("sample", false, 500));
        }
    }
}
