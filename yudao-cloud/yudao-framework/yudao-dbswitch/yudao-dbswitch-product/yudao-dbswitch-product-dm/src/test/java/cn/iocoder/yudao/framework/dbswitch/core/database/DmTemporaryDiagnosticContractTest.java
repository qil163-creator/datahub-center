package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDmImpl;
import org.junit.jupiter.api.Test;
import java.sql.SQLFeatureNotSupportedException;
import static org.junit.jupiter.api.Assertions.*;

/** DM 不为权限诊断创建持久化全局临时定义。 */
class DmTemporaryDiagnosticContractTest {
    @Test void persistentGlobalDefinitionIsExplicitlyBlocked() {
        var failure = assertThrows(SQLFeatureNotSupportedException.class, () -> new DatabaseDmImpl().temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32)));
        assertEquals("0A000", failure.getSQLState()); assertTrue(failure.getMessage().contains("DM")); assertTrue(failure.getMessage().contains("持久"));
    }
}
