package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseUxdbImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** UXDB 必须使用 ux_temp；不能照搬 pg_temp 或 PostgreSQL 15 MERGE。 */
class UxdbTemporaryDiagnosticContractTest {
    private final DatabaseUxdbImpl database = new DatabaseUxdbImpl();
    @Test void planUsesNativeTemporaryNamespace() throws Exception {
        var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE TEMPORARY TABLE ux_temp."));
        assertTrue(plan.dropSql().startsWith("DROP TABLE IF EXISTS ux_temp."));
        assertTrue(plan.dropRenamedSql().startsWith("DROP TABLE IF EXISTS ux_temp."));
        assertTrue(plan.upsertSql().contains("ON CONFLICT (probe_id) DO UPDATE"));
        assertNull(plan.mergeSql()); assertTrue(plan.unsupportedOperationsNotApplicable());
        assertNotNull(plan.alterSql()); assertNotNull(plan.truncateSql()); assertNotNull(plan.renameSql()); assertTrue(plan.transactionSupported());
    }
    @Test void invalidNameCannotBecomeSql() {
        assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("public.business"));
    }
    @Test void nativeVersionAdmissionIsReadOnly() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("UXsinoDB 2.1.0.3 on test"); when(rows.getString(2)).thenReturn("10.0");
        database.validateTemporaryTableDiagnosticConnection(connection, 3);
        verify(connection).prepareStatement(contains("ux_catalog.version()")); verify(statement).setQueryTimeout(3); verify(statement).setMaxRows(2); verify(statement).close(); verify(rows).close(); verify(connection, never()).createStatement(); verify(connection, never()).close();
    }
    @Test void wrongProductFailsBeforeDdl() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("PostgreSQL 10");
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 3)); verify(connection, never()).createStatement();
    }
    @Test void invalidBudgetDoesNotTouchJdbc() {
        var connection = mock(Connection.class); assertThrows(IllegalArgumentException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 61)); verifyNoInteractions(connection);
    }
}
