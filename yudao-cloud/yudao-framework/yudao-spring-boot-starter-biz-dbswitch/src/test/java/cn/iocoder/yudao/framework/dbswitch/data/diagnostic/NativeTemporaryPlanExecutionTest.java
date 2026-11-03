package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseStarrocksImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只模拟执行边界与预算；产品 SQL 的执行正确性由真实 Doris/PG/MySQL 测试负责。 */
@Timeout(15)
class NativeTemporaryPlanExecutionTest {
    private static final String NAME = "dbswitch_diag_0123456789abcdef0123456789abcdef";
    private static final String URL = "jdbc:mysql:loadbalance://fixture/";

    @Test
    void nativeVersionValidationAndNamespaceMustPrecedeDdlWithoutMysqlVersionOrJdbcTransactions() throws Exception {
        var f = fixture(new DatabaseDorisImpl().temporaryTableDiagnosticPlan(NAME));
        var result = f.run(5000);
        for (String key : List.of("TEMP_CREATE", "MERGE", "TRANSACTION", "DROP_TEMP", "WRITE_CLEANUP")) {
            assertEquals("PASS", check(result, key).status(), result.toString());
        }
        var order = inOrder(f.database, f.writer, f.sql);
        order.verify(f.database).validateTemporaryTableDiagnosticConnection(eq(f.writer), anyInt());
        order.verify(f.database).probeNamespace(eq(f.writer), eq("target"), anyInt());
        order.verify(f.sql).executeUpdate(startsWith("CREATE TEMPORARY TABLE"));
        order.verify(f.sql).executeUpdate("BEGIN");
        order.verify(f.sql).executeUpdate(contains("VALUES (9,'rollback')"));
        order.verify(f.sql).executeUpdate("ROLLBACK");
        verify(f.writer, never()).getMetaData();
        verify(f.writer, never()).setAutoCommit(anyBoolean());
        verify(f.writer, never()).rollback();
        verify(f.sql, never()).executeUpdate(isNull());
        assertEquals("NOT_APPLICABLE", check(result, "ALTER_TEMP").reasonCode());
        assertEquals("NOT_APPLICABLE", check(result, "RENAME_TEMP").reasonCode());
    }

    @Test
    void productVersionRejectionClosesWriteSessionBeforeAnyDdl() throws Exception {
        var f = fixture(new DatabaseDorisImpl().temporaryTableDiagnosticPlan(NAME));
        doThrow(new SQLFeatureNotSupportedException("private version detail", "0A000"))
                .when(f.database).validateTemporaryTableDiagnosticConnection(eq(f.writer), anyInt());
        var result = f.run(5000);
        assertEquals("SKIPPED", check(result, "TEMP_CREATE").status());
        verifyNoInteractions(f.sql);
        verify((DriverNativeAccess.ConnectionAccess) f.writer).requestClose();
        assertEquals("PASS", check(result, "WRITE_CLEANUP").status());
    }

    @Test
    void starrocksDoesNotInventTransactionOrAlterSupport() throws Exception {
        var f = fixture(new DatabaseStarrocksImpl().temporaryTableDiagnosticPlan(NAME));
        var result = f.run(5000);
        for (String key : List.of("TRANSACTION", "ALTER_TEMP", "RENAME_TEMP", "MERGE")) {
            assertEquals("NOT_APPLICABLE", check(result, key).reasonCode(), result.toString());
        }
        verify(f.writer, never()).setAutoCommit(anyBoolean());
        verify(f.writer, never()).rollback();
        verify(f.sql, never()).executeUpdate(isNull());
        assertEquals("PASS", check(result, "DROP_TEMP").status());
    }

    @Test
    void oldConstructorsRetainJdbcTransactionAndUnimplementedOperationDefaults() throws Exception {
        var four = new TemporaryTableDiagnosticPlan("create", "table", "alter", "drop");
        var eleven = new TemporaryTableDiagnosticPlan("create", "table", "alter", "drop", null, null,
                15, null, null, null, null);
        for (var plan : List.of(four, eleven)) {
            assertTrue(plan.transactionSupported());
            assertFalse(plan.unsupportedOperationsNotApplicable());
            assertNull(plan.beginSql());
            assertNull(plan.rollbackSql());
        }
        var legacy = new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl();
        legacy.validateTemporaryTableDiagnosticConnection(null, 0);
    }

    @Test
    void invalidNativeTransactionCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class, () -> new TemporaryTableDiagnosticPlan("create", "t", null,
                "drop", null, null, 0, null, null, null, null, "BEGIN", null, true, true));
    }

    @Test
    void exhaustedDiagnosticBudgetStillDropsAndWaitsForPhysicalCloseIndependently() throws Exception {
        var f = fixture(new DatabaseDorisImpl().temporaryTableDiagnosticPlan(NAME));
        when(f.sql.executeUpdate(contains("VALUES (1,'diagnostic')"))).thenAnswer(call -> {
            Thread.sleep(110);
            return 1;
        });
        var completion = new CompletableFuture<Void>();
        doAnswer(call -> {
            CompletableFuture.delayedExecutor(40, TimeUnit.MILLISECONDS).execute(() -> completion.complete(null));
            return completion;
        }).when((DriverNativeAccess.ConnectionAccess) f.writer).requestClose();
        var result = f.run(80);
        assertEquals("PASS", check(result, "DROP_TEMP").status(), result.toString());
        assertEquals("PASS", check(result, "WRITE_CLEANUP").status(), result.toString());
        verify(f.sql).executeUpdate(startsWith("DROP TEMPORARY TABLE IF EXISTS"));
    }

    @Test
    void ddlTimeoutStillWaitsForPhysicalCloseWithIndependentBudget() throws Exception {
        var f = fixture(new DatabaseDorisImpl().temporaryTableDiagnosticPlan(NAME));
        when(f.sql.executeUpdate(startsWith("CREATE TEMPORARY TABLE"))).thenAnswer(call -> {
            Thread.sleep(110);
            throw new SQLTimeoutException("private timeout");
        });
        var completion = new CompletableFuture<Void>();
        doAnswer(call -> {
            CompletableFuture.delayedExecutor(40, TimeUnit.MILLISECONDS).execute(() -> completion.complete(null));
            return completion;
        }).when((DriverNativeAccess.ConnectionAccess) f.writer).requestClose();
        var result = f.run(80);
        assertEquals("FAIL", check(result, "TEMP_CREATE").status());
        assertEquals("PASS", check(result, "WRITE_CLEANUP").status(), result.toString());
    }

    @Test
    void ddlDriverLinkageFailureStillClosesTheOwnedWriteSession() throws Exception {
        var f = fixture(new DatabaseDorisImpl().temporaryTableDiagnosticPlan(NAME));
        when(f.sql.executeUpdate(startsWith("CREATE TEMPORARY TABLE"))).thenThrow(new AbstractMethodError("private driver failure"));
        var result = f.run(5000);
        assertEquals("FAIL", check(result, "TEMP_CREATE").status());
        verify((DriverNativeAccess.ConnectionAccess) f.writer).requestClose();
        assertEquals("PASS", check(result, "WRITE_CLEANUP").status());
    }

    @Test
    void failedNativeInsertMustRollbackBeforeFurtherDdlAndNeverCommitViaJdbc() throws Exception {
        var f = fixture(new DatabaseDorisImpl().temporaryTableDiagnosticPlan(NAME));
        when(f.sql.executeUpdate(contains("VALUES (9,'rollback')"))).thenThrow(new SQLException("private failure"));
        var result = f.run(5000);
        assertEquals("FAIL", check(result, "TRANSACTION").status());
        var order = inOrder(f.sql);
        order.verify(f.sql).executeUpdate("BEGIN");
        order.verify(f.sql).executeUpdate(contains("VALUES (9,'rollback')"));
        order.verify(f.sql).executeUpdate("ROLLBACK");
        order.verify(f.sql).executeUpdate(startsWith("DROP TEMPORARY TABLE"));
        verify(f.writer, never()).setAutoCommit(anyBoolean());
    }

    private static ConnectionDiagnosticResult.Check check(ConnectionDiagnosticResult result, String key) {
        return ConnectionDiagnosticsTest.check(result, key);
    }

    private static Fixture fixture(TemporaryTableDiagnosticPlan plan) throws Exception {
        var database = mock(AbstractDatabase.class);
        var reader = controlled();
        var writer = controlled();
        var sql = mock(Statement.class);
        var readProvider = mock(JdbcConnectionProvider.class);
        var writeProvider = mock(JdbcConnectionProvider.class);
        when(readProvider.open(anyString(), anyString(), anyString())).thenReturn(reader);
        when(writeProvider.open(anyString(), anyString(), anyString())).thenReturn(writer);
        when(database.querySchemaList(reader)).thenReturn(List.of("target"));
        when(database.queryCatalogTableList(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(database.temporaryTableDiagnosticPlan(anyString())).thenReturn(plan);
        when(writer.getAutoCommit()).thenReturn(true);
        when(writer.createStatement()).thenReturn(sql);
        when(sql.executeUpdate(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).startsWith("MERGE") ? 2 : 1);
        when(sql.executeQuery(anyString())).thenAnswer(call -> {
            String query = call.getArgument(0);
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true);
            int count = query.contains("probe_id IN (2,3)") || query.contains("probe_id=5 AND") ? 2
                    : query.contains("probe_value='updated'") || query.contains("probe_value='upserted'") ? 1 : 0;
            when(rows.getInt(1)).thenReturn(count);
            return rows;
        });
        var prepared = mock(PreparedStatement.class);
        when(writer.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeBatch()).thenReturn(new int[]{1, 1});
        return new Fixture(database, writer, sql, readProvider, writeProvider);
    }

    private static Connection controlled() throws Exception {
        var connection = mock(Connection.class, withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        when(((DriverNativeAccess.ConnectionAccess) connection).invokeNative(any(), anyLong()))
                .thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        when(((DriverNativeAccess.ConnectionAccess) connection).requestClose())
                .thenReturn(CompletableFuture.completedFuture(null));
        return connection;
    }

    private record Fixture(AbstractDatabase database, Connection writer, Statement sql,
                           JdbcConnectionProvider readProvider, JdbcConnectionProvider writeProvider) {
        ConnectionDiagnosticResult run(long totalMillis) {
            try (var diagnostics = new ConnectionDiagnostics(new DiagnosticIo(1, 1), totalMillis)) {
                return diagnostics.diagnose(ProductTypeEnum.DORIS, database, readProvider, writeProvider,
                        URL, "fixture", "", new ConnectionDiagnosticOptions("target", true, 500));
            }
        }
    }
}
