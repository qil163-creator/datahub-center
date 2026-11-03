package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** TiDB 本地临时表独立契约；不声明全局临时表和 MySQL 引擎语义。 */
class TidbTemporaryDiagnosticContractTest {
    private final DatabaseTidbImpl database = new DatabaseTidbImpl();
    @Test void planUsesOnlySessionTemporaryObjects() throws Exception {
        var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE TEMPORARY TABLE `")); assertFalse(plan.createSql().contains("ENGINE"));
        assertTrue(plan.dropSql().startsWith("DROP TEMPORARY TABLE IF EXISTS `")); assertTrue(plan.upsertSql().contains("ON DUPLICATE KEY UPDATE"));
        assertNull(plan.mergeSql()); assertNull(plan.alterSql()); assertNull(plan.renameSql()); assertNull(plan.dropRenamedSql());
        assertTrue(plan.unsupportedOperationsNotApplicable()); assertTrue(plan.transactionSupported());
    }
    @Test void invalidNameCannotBecomeSql() { assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("business")); }
    @Test void nativeVersionIsReadOnlyAndBounded() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("Release Version: v8.5.6\nEdition: Community\nStore: tikv");
        when(rows.getString(2)).thenReturn("leader"); when(rows.getString(3)).thenReturn("0"); when(rows.getString(4)).thenReturn("");
        database.validateTemporaryTableDiagnosticConnection(connection, 3);
        verify(statement).setQueryTimeout(3); verify(statement).setMaxRows(2); verify(statement).close(); verify(rows).close(); verify(connection, never()).createStatement(); verify(connection, never()).close();
    }
    @Test void wrongProductFailsBeforeDdl() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("8.0 MySQL");
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 3)); verify(connection, never()).createStatement();
    }
    @Test void invalidBudgetDoesNotTouchJdbc() { var connection = mock(Connection.class); assertThrows(IllegalArgumentException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 0)); verifyNoInteractions(connection); }
}
