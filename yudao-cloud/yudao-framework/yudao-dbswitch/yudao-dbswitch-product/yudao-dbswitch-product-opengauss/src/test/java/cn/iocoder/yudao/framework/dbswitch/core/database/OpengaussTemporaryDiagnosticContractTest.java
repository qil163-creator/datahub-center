package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOpengaussImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** openGauss 只声明固定版本 A 模式会话临时表，不从 PostgreSQL 版本号推定 MERGE。 */
class OpengaussTemporaryDiagnosticContractTest {
    private final DatabaseOpengaussImpl database = new DatabaseOpengaussImpl();
    @Test void planUsesNativeConflictAndMergeSyntax() throws Exception {
        var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE TEMPORARY TABLE pg_temp."));
        assertTrue(plan.dropSql().startsWith("DROP TABLE IF EXISTS pg_temp.")); assertTrue(plan.dropRenamedSql().startsWith("DROP TABLE IF EXISTS pg_temp."));
        assertTrue(plan.upsertSql().contains("ON DUPLICATE KEY UPDATE")); assertTrue(plan.mergeSql().contains("USING (SELECT 5"));
        assertEquals(0, plan.mergeMinimumMajorVersion()); assertTrue(plan.transactionSupported());
        assertNotNull(plan.alterSql()); assertNotNull(plan.truncateSql()); assertNotNull(plan.renameSql());
    }
    @Test void invalidNameCannotBecomeSql() { assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("business")); }
    @Test void correctNativeModeIsAdmitted() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("(openGauss 7.0.0-RC3 build test)"); when(rows.getString(2)).thenReturn("A");
        database.validateTemporaryTableDiagnosticConnection(connection, 3);
        verify(statement).setQueryTimeout(3); verify(statement).setMaxRows(2); verify(statement).close(); verify(rows).close(); verify(connection, never()).createStatement(); verify(connection, never()).close();
    }
    @Test void wrongModeFailsBeforeDdl() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("(openGauss 7.0.0-RC3 build test)"); when(rows.getString(2)).thenReturn("B");
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 3)); verify(connection, never()).createStatement();
    }
    @Test void invalidBudgetDoesNotTouchJdbc() { var connection = mock(Connection.class); assertThrows(IllegalArgumentException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 0)); verifyNoInteractions(connection); }
}
