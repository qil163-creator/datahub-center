package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDmImpl;
import org.junit.jupiter.api.Test;
import java.sql.SQLFeatureNotSupportedException;
import static org.junit.jupiter.api.Assertions.*;

/** DM 不为权限诊断创建持久化全局临时定义。 */
class DmTemporaryDiagnosticContractTest {
    @Test void persistentGlobalDefinitionIsExplicitlyBlocked() {
        var failure = assertThrows(SQLFeatureNotSupportedException.class, () -> new DatabaseDmImpl().temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32)));
        // DM 未实现临时表诊断，由组件默认拒绝；拒绝本身是契约，原因文本不是。
        assertEquals("0A000", failure.getSQLState());
    }
}
