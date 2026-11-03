package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseKingbaseImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kingbase 仅接受现有固定版本 Oracle/enable_ci 模式。 */
class KingbaseTemporaryDiagnosticContractTest {
    private final DatabaseKingbaseImpl database = new DatabaseKingbaseImpl();
    @Test void planUsesSessionNamespaceAndOracleStyleMerge() throws Exception {
        var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE TEMPORARY TABLE pg_temp."));
        assertTrue(plan.dropSql().startsWith("DROP TABLE IF EXISTS pg_temp.")); assertTrue(plan.dropRenamedSql().startsWith("DROP TABLE IF EXISTS pg_temp."));
        assertTrue(plan.upsertSql().startsWith("MERGE INTO ")); assertTrue(plan.mergeSql().contains("USING (SELECT 5"));
        assertEquals(0, plan.mergeMinimumMajorVersion()); assertTrue(plan.transactionSupported());
        assertNotNull(plan.alterSql()); assertNotNull(plan.truncateSql()); assertNotNull(plan.renameSql());
    }
    @Test void invalidNameCannotBecomeSql() { assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("business")); }
    @Test void fixedOracleModeIsAdmittedWithoutChangingDdlState() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("KingbaseES V008R006C009B0014 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 4.8.5 20150623 (Red Hat 4.8.5-28), 64-bit"); when(rows.getString(2)).thenReturn("oracle"); when(rows.getString(3)).thenReturn("on");
        database.validateTemporaryTableDiagnosticConnection(connection, 3);
        verify(statement).setQueryTimeout(3); verify(statement).setMaxRows(2); verify(statement).close(); verify(rows).close(); verify(connection, never()).createStatement(); verify(connection, never()).commit();
    }
    @Test void wrongModeFailsBeforeDdl() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false); when(rows.getString(2)).thenReturn("pg");
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 3)); verify(connection, never()).createStatement();
    }
    @Test void invalidBudgetDoesNotTouchJdbc() { var connection = mock(Connection.class); assertThrows(IllegalArgumentException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 0)); verifyNoInteractions(connection); }
}
