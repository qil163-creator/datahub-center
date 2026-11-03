package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionDiagnosticsAutoConfigurationTest {
    @Test void existingRuntimeBeanOwnsDiagnosticLifecycleAndRejectsCallsAfterContextCloses() {
        var reference = new AtomicReference<DbSwitchRuntime>();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DbSwitchRuntime.class);
                    var runtime = context.getBean(DbSwitchRuntime.class); reference.set(runtime);
                    // 非法产品 URL 在 CONFIG 阶段拒绝且不访问网络；验证真实组件装配与生命周期。
                    var result = runtime.diagnoseConnection(ProductTypeEnum.POSTGRESQL, null,
                            "jdbc:invalid:diagnostic", null, null, ConnectionDiagnosticOptions.defaults());
                    assertFalse(result.connectionSuccessful()); assertEquals("FAIL", result.status());
                });
        assertThrows(IllegalStateException.class, () -> reference.get().diagnoseConnection(ProductTypeEnum.POSTGRESQL,
                null, "jdbc:invalid:diagnostic", null, null, ConnectionDiagnosticOptions.defaults()));
    }
}
