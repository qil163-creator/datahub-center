package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOracleImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Oracle 私有临时表不得建立持久定义，也不得把 ALTER/RENAME 伪装为支持。 */
class OracleTemporaryDiagnosticContractTest {
    private final DatabaseOracleImpl database = new DatabaseOracleImpl();
    @Test void privateSessionPlanHasNoUnsupportedConstraints() throws Exception {
        var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE PRIVATE TEMPORARY TABLE \"ORA$PTT_"));
        assertTrue(plan.createSql().endsWith("ON COMMIT PRESERVE DEFINITION")); assertFalse(plan.createSql().contains("PRIMARY KEY"));
        assertTrue(plan.dropSql().contains("USER_PRIVATE_TEMP_TABLES")); assertTrue(plan.dropSql().contains("EXECUTE IMMEDIATE"));
        assertTrue(plan.upsertSql().startsWith("MERGE INTO ")); assertTrue(plan.mergeSql().contains("FROM DUAL UNION ALL"));
        assertEquals(0, plan.mergeMinimumMajorVersion()); assertNotNull(plan.truncateSql()); assertTrue(plan.transactionSupported());
        assertNull(plan.alterSql()); assertNull(plan.renameSql()); assertNull(plan.dropRenamedSql()); assertTrue(plan.unsupportedOperationsNotApplicable());
    }
    @Test void invalidNameCannotBecomeSql() { assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("business")); }
    @Test void native19AndDefaultPrefixAreRequired() throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata); when(metadata.getDatabaseProductName()).thenReturn("Oracle"); when(metadata.getDatabaseMajorVersion()).thenReturn(19);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("ORA$PTT_");
        database.validateTemporaryTableDiagnosticConnection(connection, 3);
        verify(statement).setQueryTimeout(3); verify(statement).setMaxRows(2); verify(statement).close(); verify(rows).close(); verify(connection, never()).createStatement(); verify(connection, never()).commit();
    }
    @Test void oldVersionFailsBeforeAnySql() throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata); when(metadata.getDatabaseProductName()).thenReturn("Oracle"); when(metadata.getDatabaseMajorVersion()).thenReturn(12);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 3)); verify(connection, never()).prepareStatement(anyString()); verify(connection, never()).createStatement();
    }
    @Test void invalidBudgetDoesNotTouchJdbc() { var connection = mock(Connection.class); assertThrows(IllegalArgumentException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 0)); verifyNoInteractions(connection); }
}
