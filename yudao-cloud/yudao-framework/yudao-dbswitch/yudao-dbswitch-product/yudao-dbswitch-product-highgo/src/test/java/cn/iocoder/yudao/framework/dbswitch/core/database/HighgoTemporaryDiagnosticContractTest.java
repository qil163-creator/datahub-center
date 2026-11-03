package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHighgoImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 瀚高会话临时对象契约；不将 SQL 输出测试表述为实库验证。 */
class HighgoTemporaryDiagnosticContractTest {
    private final DatabaseHighgoImpl database = new DatabaseHighgoImpl();
    @Test void isolatedPlanUsesConflictNotPg15Merge() throws Exception {
        var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE TEMPORARY TABLE pg_temp."));
        assertTrue(plan.dropSql().startsWith("DROP TABLE IF EXISTS pg_temp."));
        assertTrue(plan.dropRenamedSql().startsWith("DROP TABLE IF EXISTS pg_temp."));
        assertTrue(plan.upsertSql().contains("ON CONFLICT (probe_id) DO UPDATE"));
        assertNull(plan.mergeSql()); assertTrue(plan.unsupportedOperationsNotApplicable());
        assertNotNull(plan.alterSql()); assertNotNull(plan.truncateSql()); assertNotNull(plan.renameSql());
        assertTrue(plan.transactionSupported());
    }
    @Test void invalidNameCannotBecomeSql() {
        assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("business"));
    }
    @Test void versionAdmissionIsReadOnlyAndBounded() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("HighGo Database 6 Release 6.0.4.2"); when(rows.getString(2)).thenReturn("12.7");
        database.validateTemporaryTableDiagnosticConnection(connection, 3);
        verify(statement).setQueryTimeout(3); verify(statement).setMaxRows(2); verify(statement).close(); verify(rows).close();
        verify(connection, never()).createStatement(); verify(connection, never()).commit(); verify(connection, never()).close();
    }
    @Test void wrongVersionFailsBeforeDdl() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("PostgreSQL 15");
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 3));
        verify(connection, never()).createStatement(); verify(statement, never()).execute();
    }
    @Test void invalidBudgetDoesNotTouchJdbc() {
        var connection = mock(Connection.class);
        assertThrows(IllegalArgumentException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 0)); verifyNoInteractions(connection);
    }
}
