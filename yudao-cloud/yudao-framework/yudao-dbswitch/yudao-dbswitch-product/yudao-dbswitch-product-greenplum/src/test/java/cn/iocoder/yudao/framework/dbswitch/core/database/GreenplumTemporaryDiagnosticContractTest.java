package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGreenplumImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Greenplum 临时堆表与分布键契约；不借用 PostgreSQL 15 MERGE。 */
class GreenplumTemporaryDiagnosticContractTest {
    private final DatabaseGreenplumImpl database = new DatabaseGreenplumImpl();
    @Test void planPinsHeapStorageAndDistribution() throws Exception {
        var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE TEMPORARY TABLE pg_temp."));
        assertTrue(plan.createSql().contains("WITH (appendoptimized=false)")); assertTrue(plan.createSql().endsWith("DISTRIBUTED BY (probe_id)"));
        assertTrue(plan.dropSql().startsWith("DROP TABLE IF EXISTS pg_temp.")); assertTrue(plan.dropRenamedSql().startsWith("DROP TABLE IF EXISTS pg_temp."));
        assertTrue(plan.upsertSql().contains("ON CONFLICT (probe_id) DO UPDATE")); assertNull(plan.mergeSql()); assertTrue(plan.unsupportedOperationsNotApplicable());
        assertNotNull(plan.alterSql()); assertNotNull(plan.truncateSql()); assertNotNull(plan.renameSql()); assertTrue(plan.transactionSupported());
    }
    @Test void invalidNameCannotBecomeSql() { assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("business")); }
    @Test void onlyExplicitNativeBuildIsAdmitted() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("PostgreSQL 12.12 (Greenplum Database 7.1.0 build dev) on test"); when(rows.getString(2)).thenReturn("7.1.0 build dev");
        database.validateTemporaryTableDiagnosticConnection(connection, 3);
        verify(statement).setQueryTimeout(3); verify(statement).setMaxRows(2); verify(statement).close(); verify(rows).close(); verify(connection, never()).createStatement(); verify(connection, never()).commit();
    }
    @Test void wrongVersionFailsBeforeDdl() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("PostgreSQL 15");
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 3)); verify(connection, never()).createStatement();
    }
    @Test void invalidBudgetDoesNotTouchJdbc() { var connection = mock(Connection.class); assertThrows(IllegalArgumentException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 0)); verifyNoInteractions(connection); }
}
