package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic;
import cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class ConnectionDiagnosticsTest {
    private static final String URL = "jdbc:postgresql://fixture/secret-url?socketFactory=unverified", USER = "secret-user", PASSWORD = "secret-password";

    @Test void readonlyEmptyNamespaceDoesNotInventColumnKeyReadOrWriteSuccess() throws Exception {
        var fixture = fixture();
        when(fixture.database.queryCatalogTableList(any(), eq("sample"), eq(1000), anyInt())).thenReturn(List.of());
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            assertTrue(result.connectionSuccessful()); assertEquals("PASS", result.status());
            assertEquals("PASS", check(result, "TABLES").status());
            for (String key : List.of("COLUMNS", "PRIMARY_KEYS", "ZERO_ROW_READ", "INSERT", "TRANSACTION")) {
                assertEquals("SKIPPED", check(result, key).status());
            }
            verifyNoInteractions(fixture.write);
            verify(fixture.connection).close();
            verify(fixture.connection, never()).createStatement();
            verify(fixture.database).probeConnection(eq(fixture.connection), anyInt());
        }
    }

    @Test void skippedChecksExposeStableReasonsWithoutParsingVendorMessages() throws Exception {
        var fixture = fixture();
        when(fixture.database.queryCatalogTableList(any(), eq("sample"), eq(1000), anyInt())).thenReturn(List.of());
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            var mapper = new ObjectMapper();
            assertTrue(mapper.writeValueAsString(check(result, "TEMP_CREATE"))
                    .contains("\"reasonCode\":\"NOT_AUTHORIZED\""));
            assertTrue(mapper.writeValueAsString(check(result, "COLUMNS"))
                    .contains("\"reasonCode\":\"NO_SAMPLE\""));
        }
    }

    @Test void catalogFailureMustNotMasqueradeAsAnEmptyDatabase() throws Exception {
        var fixture = fixture();
        when(fixture.database.queryCatalogTableList(any(), anyString(), anyInt(), anyInt()))
                .thenThrow(new SQLException("private vendor message", "42501"));
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            assertEquals("FAIL", check(result, "TABLES").status());
            assertEquals("PREREQUISITE_FAILED", check(result, "COLUMNS").reasonCode());
            assertEquals("PREREQUISITE_FAILED", check(result, "ZERO_ROW_READ").reasonCode());
        }
    }

    @Test void metadataAndReadUseEscapedZeroRowSqlAndNoCallerValidationSql() throws Exception {
        var fixture = fixture();
        var table = new TableDescription(); table.setSchemaName("sample"); table.setTableName("orders\";drop table x;--"); table.setTableType("TABLE");
        when(fixture.database.queryCatalogTableList(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of(table));
        when(fixture.database.queryTablePrimaryKeys(any(), anyString(), anyString())).thenReturn(List.of("id"));
        var statement = mock(PreparedStatement.class); var metadata = mock(ResultSetMetaData.class); var rows = mock(ResultSet.class);
        when(fixture.connection.prepareStatement("SELECT * FROM \"sample\".\"orders\"\";drop table x;--\" WHERE 1=0")).thenReturn(statement);
        when(statement.getMetaData()).thenReturn(metadata); when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnName(anyInt())).thenReturn("field"); when(metadata.getColumnTypeName(anyInt())).thenReturn("integer");
        when(metadata.getColumnType(anyInt())).thenReturn(java.sql.Types.INTEGER);
        when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(false);
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            assertEquals("PASS", check(result, "COLUMNS").status());
            assertEquals("PASS", check(result, "PRIMARY_KEYS").status());
            assertEquals("PASS", check(result, "ZERO_ROW_READ").status());
            assertEquals("PASS", check(result, "EXTENDED_PROPERTIES").status());
            verify(rows, never()).getObject(anyInt()); verify(rows).close();
            verify(statement, times(2)).close(); verifyNoInteractions(fixture.write);
        }
    }

    @Test void absentNamespaceCannotBecomeConnectionSuccessEvenAfterAuthentication() throws Exception {
        var fixture = fixture(); when(fixture.database.querySchemaList(any())).thenReturn(List.of("other"));
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, true);
            assertFalse(result.connectionSuccessful()); assertEquals("FAIL", check(result, "SCHEMA").status());
            assertEquals("3F000", check(result, "SCHEMA").sqlState());
            verifyNoInteractions(fixture.write);
        }
    }

    @Test void rawDriverMessagesAndCredentialsNeverEnterSerializedResult() throws Exception {
        var fixture = fixture();
        when(fixture.read.open(any(), any(), any())).thenThrow(new SQLException(URL + USER + PASSWORD, "28P01", 71));
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, true);
            String json = new ObjectMapper().writeValueAsString(result);
            assertFalse(json.contains("secret-")); assertFalse(result.connectionSuccessful());
            assertEquals("28P01", check(result, "AUTH").sqlState()); assertEquals(71, check(result, "AUTH").vendorCode());
            assertTrue(check(result, "AUTH").message().contains("认证")); verifyNoInteractions(fixture.write);
        }
    }

    @Test void nativeInapplicabilityIsDifferentFromAMissingDiagnosticImplementation() throws Exception {
        var fixture = fixture();
        doAnswer(call -> {
            new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl()
                    .probeAccountPrivileges(fixture.connection, "main", 1);
            return null;
        }).when(fixture.database).probeAccountPrivileges(any(), anyString(), anyInt());
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            assertEquals("SKIPPED", check(result, "PRIVILEGES").status());
            assertEquals("NOT_APPLICABLE", check(result, "PRIVILEGES").reasonCode(), result.toString());
            assertFalse(check(result, "PRIVILEGES").message().contains("尚未支持"));
            assertTrue(result.connectionSuccessful());
        }
    }

    @Test void nativeInapplicabilityNeverExposesItsExceptionMessage() throws Exception {
        var fixture = fixture();
        doThrow(new cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException(
                URL + USER + PASSWORD)).when(fixture.database).probeAccountPrivileges(any(), anyString(), anyInt());
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            assertEquals("NOT_APPLICABLE", check(result, "PRIVILEGES").reasonCode(), result.toString());
            assertFalse(new ObjectMapper().writeValueAsString(result).contains("secret-"));
        }
    }

    @Test void unavailableProductWritePlanIsSkippedWithoutOpeningWriteSession() throws Exception {
        var fixture = fixture();
        when(fixture.database.temporaryTableDiagnosticPlan(anyString())).thenThrow(new SQLFeatureNotSupportedException("raw"));
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, true);
            assertTrue(result.connectionSuccessful()); assertEquals("SKIPPED", check(result, "TEMP_CREATE").status());
            assertEquals("NOT_IMPLEMENTED", check(result, "TEMP_CREATE").reasonCode());
            verifyNoInteractions(fixture.write);
        }
    }

    @Test void exhaustedTotalBudgetStartsNoFollowupJdbcProbes() throws Exception {
        var fixture = fixture();
        doAnswer(call -> { Thread.sleep(70); throw new SQLTimeoutException("raw timeout"); })
                .when(fixture.database).probeConnection(any(), anyInt());
        try (var diagnostics = new ConnectionDiagnostics(new DiagnosticIo(1, 1), 50)) {
            var result = fixture.run(diagnostics, false);
            assertEquals("FAIL", check(result, "VALIDATION").status());
            assertEquals("SKIPPED", check(result, "SCHEMA").status());
            verify(fixture.database, never()).querySchemaList(any()); verifyNoInteractions(fixture.write);
            verify((DriverNativeAccess.ConnectionAccess) fixture.connection).requestClose();
        }
    }

    @Test void saturatedNetworkQueueCannotPreventAlreadyOwnedSessionCleanup() throws Exception {
        var fixture = fixture(); var release = new java.util.concurrent.CountDownLatch(1);
        try (var io = new DiagnosticIo(1, 1); var diagnostics = new ConnectionDiagnostics(io, 3000)) {
            doAnswer(call -> {
                assertThrows(SQLTimeoutException.class, () -> io.call(20, () -> { release.await(); return true; }, ignored -> { }));
                assertThrows(SQLTimeoutException.class, () -> io.call(20, () -> true, ignored -> { }));
                return null;
            }).when(fixture.database).probeConnection(any(), anyInt());
            var result = fixture.run(diagnostics, false);
            assertEquals("PASS", check(result, "CLEANUP").status());
            verify((DriverNativeAccess.ConnectionAccess) fixture.connection).requestClose();
        } finally { release.countDown(); }
    }

    @Test void unconfirmedCleanupIsVisibleWithoutChangingSuccessfulConnectionSemantics() throws Exception {
        var fixture = fixture(); doThrow(new SQLException(URL + PASSWORD)).when(fixture.connection).close();
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            assertTrue(result.connectionSuccessful()); assertEquals("WARN", result.status());
            assertEquals("WARN", check(result, "CLEANUP").status());
            assertFalse(check(result, "CLEANUP").message().contains(PASSWORD));
        }
    }

    @Test void interruptedCleanupPreservesCallerCancellationAfterRequestingPhysicalClose() throws Exception {
        var fixture = fixture();
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var completion = new java.util.concurrent.CompletableFuture<Void>() {
            @Override public Void get(long timeout, java.util.concurrent.TimeUnit unit)
                    throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
                waiting.countDown(); return super.get(timeout, unit);
            }
        };
        // doReturn 避免重配既有 Answer 时执行其关闭副作用；实际中断发生在未完成 future 的等待边界。
        doReturn(completion).when((DriverNativeAccess.ConnectionAccess) fixture.connection).requestClose();
        var observed = new java.util.concurrent.atomic.AtomicReference<ConnectionDiagnosticResult>();
        var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try (var diagnostics = new ConnectionDiagnostics()) {
            var caller = Thread.ofPlatform().inheritInheritableThreadLocals(false).start(() -> {
                try { observed.set(fixture.run(diagnostics, false)); interrupted.set(Thread.currentThread().isInterrupted()); }
                catch (Throwable failure) { failed.set(failure); }
            });
            try {
                assertTrue(waiting.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(completion.isDone()); caller.interrupt(); caller.join(2000);
                assertFalse(caller.isAlive()); assertNull(failed.get());
                var result = observed.get(); assertNotNull(result); assertTrue(result.connectionSuccessful());
                assertEquals("WARN", check(result, "CLEANUP").status());
                assertTrue(interrupted.get(), "清理等待不能吞掉调用方取消信号");
                verify((DriverNativeAccess.ConnectionAccess) fixture.connection).requestClose();
            } finally { completion.complete(null); caller.join(1000); }
        }
    }

    @Test void parserNeverProbesUnprovenMultiHostProxyOrDifferentProductTargets() {
        assertEquals(5432, DiagnosticEndpoint.parse(ProductTypeEnum.POSTGRESQL, "jdbc:postgresql://localhost/test?sslmode=require").orElseThrow().port());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.POSTGRESQL, "jdbc:postgresql://host1,host2/test").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.POSTGRESQL, "jdbc:postgresql://host/test?socketFactory=custom").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.MYSQL, "jdbc:mysql:loadbalance://host/test").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.POSTGRESQL, "jdbc:mysql://host/test").isEmpty());
        assertTrue(DiagnosticEndpoint.parse(ProductTypeEnum.POSTGRESQL, "jdbc:postgresql://user:secret@host/test").isEmpty());
    }

    @Test void optionsRejectUnboundedDeadlines() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionDiagnosticOptions(null, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionDiagnosticOptions(null, false, 5001));
        assertFalse(ConnectionDiagnosticOptions.defaults().writeChecks());
    }

    @Test void invalidProductProtocolStopsBeforeNetworkOrConnectionCreation() throws Exception {
        var fixture = fixture();
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = diagnostics.diagnose(ProductTypeEnum.POSTGRESQL, fixture.database, fixture.read, fixture.write,
                    "jdbc:mysql://fixture/wrong-product", USER, PASSWORD, ConnectionDiagnosticOptions.defaults());
            assertEquals("FAIL", check(result, "CONFIG").status());
            assertEquals("SKIPPED", check(result, "AUTH").status()); assertFalse(result.connectionSuccessful());
            verifyNoInteractions(fixture.read, fixture.write);
        }
    }

    @Test void cdcPrerequisiteFailureIsWarningAndDoesNotRewriteConnectionSuccess() throws Exception {
        var fixture = fixture();
        when(fixture.database.probeCdcReadiness(any(), anyInt())).thenReturn(new CdcReadinessDiagnostic(false, false));
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            assertTrue(result.connectionSuccessful()); assertEquals("WARN", check(result, "CDC").status());
            assertTrue(check(result, "CDC").message().contains("均未满足"));
            verifyNoInteractions(fixture.write);
        }
    }

    @Test void emptyPrimaryKeyIsAValidObservationWhileMissingColumnTypesRemainWarning() throws Exception {
        var fixture = fixture();
        var table = new TableDescription(); table.setSchemaName("sample"); table.setTableName("keyless"); table.setTableType("TABLE");
        when(fixture.database.queryCatalogTableList(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of(table));
        when(fixture.database.queryTablePrimaryKeys(any(), anyString(), anyString())).thenReturn(List.of());
        var statement = mock(PreparedStatement.class); var metadata = mock(ResultSetMetaData.class); var rows = mock(ResultSet.class);
        when(fixture.connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.getMetaData()).thenReturn(metadata); when(metadata.getColumnCount()).thenReturn(1);
        when(statement.executeQuery()).thenReturn(rows);
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, false);
            assertEquals("PASS", check(result, "PRIMARY_KEYS").status());
            assertTrue(check(result, "PRIMARY_KEYS").message().contains("未声明主键"));
            assertEquals("WARN", check(result, "COLUMNS").status());
        }
    }

    @Test void olderPostgresSkipsMergeBeforeExecutionButStillExercisesOtherTemporaryOperations() throws Exception {
        var fixture = fixture();
        var postgres = new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl();
        when(fixture.database.temporaryTableDiagnosticPlan(anyString())).thenAnswer(call -> postgres.temporaryTableDiagnosticPlan(call.getArgument(0)));
        var write = mock(Connection.class, withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        when(((DriverNativeAccess.ConnectionAccess) write).invokeNative(any(), anyLong()))
                .thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        when(((DriverNativeAccess.ConnectionAccess) write).requestClose()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(fixture.write.open(URL, USER, PASSWORD)).thenReturn(write); when(write.getAutoCommit()).thenReturn(true);
        var version = mock(java.sql.DatabaseMetaData.class); when(write.getMetaData()).thenReturn(version);
        when(version.getDatabaseMajorVersion()).thenReturn(14);
        var statement = mock(java.sql.Statement.class); when(write.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(anyString())).thenReturn(1);
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0); var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true);
            int count = sql.contains("probe_id IN (2,3)") ? 2 : sql.contains("probe_value='updated'") || sql.contains("probe_value='upserted'") ? 1 : 0;
            when(rows.getInt(1)).thenReturn(count); return rows;
        });
        var prepared = mock(PreparedStatement.class); when(write.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeBatch()).thenReturn(new int[]{1, 1}); when(prepared.executeQuery()).thenReturn(mock(ResultSet.class));
        try (var diagnostics = new ConnectionDiagnostics()) {
            var result = fixture.run(diagnostics, true);
            assertEquals("SKIPPED", check(result, "MERGE").status());
            assertTrue(check(result, "MERGE").message().contains("服务器版本"));
            for (String key : List.of("UPSERT", "TRUNCATE", "RENAME_TEMP", "DROP_TEMP")) assertEquals("PASS", check(result, key).status(), key);
            verify(statement, never()).executeUpdate(startsWith("MERGE"));
            verify(statement, atLeastOnce()).executeUpdate(startsWith("INSERT INTO pg_temp."));
        }
    }

    private static Fixture fixture() throws Exception {
        var connection = mock(Connection.class, withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        when(((DriverNativeAccess.ConnectionAccess) connection).invokeNative(any(), anyLong()))
                .thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        when(((DriverNativeAccess.ConnectionAccess) connection).requestClose()).thenAnswer(call -> {
            connection.close(); return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        when(connection.isClosed()).thenReturn(true);
        var database = mock(AbstractDatabase.class);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.POSTGRESQL);
        when(database.diagnosticTableReference(any(), anyString(), anyString(), anyInt())).thenCallRealMethod();
        when(database.querySchemaList(connection)).thenReturn(List.of("sample"));
        when(database.queryCatalogTableList(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(database.probeCdcReadiness(any(), anyInt())).thenReturn(new CdcReadinessDiagnostic(true, true));
        when(database.probeExtendedProperties(any(), anyString(), anyString(), anyInt())).thenReturn(new ExtendedPropertyDiagnostic(2, false, 0));
        var read = mock(JdbcConnectionProvider.class); var write = mock(JdbcConnectionProvider.class);
        when(read.open(URL, USER, PASSWORD)).thenReturn(connection);
        return new Fixture(database, connection, read, write);
    }
    private record Fixture(AbstractDatabase database, Connection connection, JdbcConnectionProvider read, JdbcConnectionProvider write) {
        ConnectionDiagnosticResult run(ConnectionDiagnostics diagnostics, boolean writeChecks) {
            return diagnostics.diagnose(ProductTypeEnum.POSTGRESQL, database, read, write, URL, USER, PASSWORD,
                    new ConnectionDiagnosticOptions("sample", writeChecks, 500));
        }
    }
    static ConnectionDiagnosticResult.Check check(ConnectionDiagnosticResult result, String key) {
        return result.checks().stream().filter(check -> key.equals(check.key())).findFirst().orElseThrow();
    }
}
