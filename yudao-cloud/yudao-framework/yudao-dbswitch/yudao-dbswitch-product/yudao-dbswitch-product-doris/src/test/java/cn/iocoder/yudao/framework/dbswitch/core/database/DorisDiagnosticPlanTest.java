package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DorisDiagnosticPlanTest {
    @Test void usesNativeTemporaryUniqueMowTableAndVerifiedOperations() throws Exception {
        var plan = new DatabaseDorisImpl().temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE TEMPORARY TABLE `dbswitch_diag_"));
        assertFalse(plan.createSql().contains("IF NOT EXISTS"));
        assertTrue(plan.dropSql().startsWith("DROP TEMPORARY TABLE IF EXISTS "));
        assertTrue(plan.createSql().contains("UNIQUE KEY(`probe_id`)"));
        assertTrue(plan.createSql().contains("\"enable_unique_key_merge_on_write\" = \"true\""));
        assertNotNull(plan.upsertSql());
        assertNotNull(plan.mergeSql());
        assertNotNull(plan.truncateSql());
        assertNull(plan.alterSql());
        assertNull(plan.renameSql());
    }

    @Test void rejectsNonRandomNamesBeforeProducingSql() {
        for (String name : new String[]{"orders", "dbswitch_diag_" + "a".repeat(31), "dbswitch_diag_" + "a".repeat(32) + "`"}) {
            assertThrows(IllegalArgumentException.class, () -> new DatabaseDorisImpl().temporaryTableDiagnosticPlan(name));
        }
    }

    @Test void checksNativeBinlogSwitchInsteadOfMysqlProtocolVersion() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var frontends = mock(ResultSet.class);
        var config = mock(ResultSet.class);
        var grants = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(frontends);
        when(frontends.next()).thenReturn(true, false);
        when(frontends.getString("Version")).thenReturn("doris-4.0.8-rc02-bc8ea1bac6d");
        when(statement.execute("ADMIN SHOW FRONTEND CONFIG LIKE 'experimental_enable_feature_binlog'")).thenReturn(true);
        when(statement.getResultSet()).thenReturn(config);
        when(config.next()).thenReturn(true, false);
        when(config.getString("Key")).thenReturn("experimental_enable_feature_binlog");
        when(config.getString("Value")).thenReturn("false");
        when(statement.executeQuery("SHOW GRANTS")).thenReturn(grants);
        when(grants.next()).thenReturn(true, false);
        when(grants.getString("GlobalPrivs")).thenReturn("Node_priv,Admin_priv");
        var result = new DatabaseDorisImpl().probeCdcReadiness(connection, 3);
        assertFalse(result.logConfigurationReady());
        assertTrue(result.accountPrivilegeReady());
        verify(connection, never()).getMetaData();
        verify(connection, never()).close();
    }
}
