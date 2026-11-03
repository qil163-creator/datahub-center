package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Connection;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Product adapters are real; each test retains ownership of its existing JDBC fixture. */
final class QualityRuntimeFixture {
    private QualityRuntimeFixture() { }

    static QualityTaskExecutor executor() {
        var executor = new QualityTaskExecutor();
        var runtime = mock(DbSwitchRuntime.class);
        when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        return executor;
    }

    /** Ordinary quality runs explicitly have no ETL target-batch scope. */
    static QualityAuditLogDO executeRule(QualityTaskExecutor executor, Long instanceId, QualityTaskDO task,
                                         Connection connection, MasterDatabaseResolver.ConnectionInfo route,
                                         QualityCheckObjectDO object, QualityRuleDO rule) {
        return ReflectionTestUtils.invokeMethod(executor, "executeRule", instanceId, task, connection,
                route, object, rule, (Object) null);
    }

    static void executeObject(QualityTaskExecutor executor, Long instanceId, QualityTaskDO task,
                              MasterDatabaseResolver.ConnectionInfo route, QualityCheckObjectDO object,
                              List<QualityRuleDO> rules, List<QualityAuditLogDO> results) {
        ReflectionTestUtils.invokeMethod(executor, "executeObject", instanceId, task, route,
                object, rules, results, (Object) null);
    }
}
