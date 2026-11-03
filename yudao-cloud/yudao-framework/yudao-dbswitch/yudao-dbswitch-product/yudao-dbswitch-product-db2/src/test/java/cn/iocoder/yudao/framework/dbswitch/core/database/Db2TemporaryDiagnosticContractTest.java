package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDB2Impl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DB2 DGTT 的 SESSION 限定不足以保护不存在的临时对象，清理先重新声明再删除。 */
class Db2TemporaryDiagnosticContractTest {
    private final DatabaseDB2Impl database = new DatabaseDB2Impl();
    @Test void declaredPlanNeverCreatesPersistentDefinition() throws Exception {
        var plan = database.temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("DECLARE GLOBAL TEMPORARY TABLE SESSION.")); assertTrue(plan.createSql().contains("ON COMMIT PRESERVE ROWS LOGGED"));
        assertFalse(plan.createSql().contains("PRIMARY KEY")); assertNull(plan.alterSql()); assertNull(plan.renameSql());
        assertTrue(plan.dropSql().startsWith("BEGIN ATOMIC DECLARE GLOBAL TEMPORARY TABLE SESSION."));
        assertTrue(plan.dropSql().contains("WITH REPLACE")); assertTrue(plan.dropSql().contains("; DROP TABLE SESSION."));
        assertNotNull(plan.upsertSql()); assertNotNull(plan.mergeSql()); assertNotNull(plan.truncateSql()); assertTrue(plan.transactionSupported()); assertTrue(plan.unsupportedOperationsNotApplicable());
    }
    @Test void invalidNameCannotBecomeSql() { assertThrows(IllegalArgumentException.class, () -> database.temporaryTableDiagnosticPlan("business")); }
    @Test void nativeLuw12OnlyIsAdmitted() throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata); when(metadata.getDatabaseProductName()).thenReturn("DB2/LINUXX8664"); when(metadata.getDatabaseMajorVersion()).thenReturn(12);
        database.validateTemporaryTableDiagnosticConnection(connection, 3); verify(connection, never()).createStatement(); verify(connection, never()).commit(); verify(connection, never()).close();
    }
    @Test void zosIsRejectedBeforeSql() throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class); when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("DB2"); when(metadata.getDatabaseMajorVersion()).thenReturn(12);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 3)); verify(connection, never()).createStatement(); verify(connection, never()).prepareStatement(anyString());
    }
    @Test void invalidBudgetDoesNotTouchJdbc() { var connection = mock(Connection.class); assertThrows(IllegalArgumentException.class, () -> database.validateTemporaryTableDiagnosticConnection(connection, 0)); verifyNoInteractions(connection); }
}
