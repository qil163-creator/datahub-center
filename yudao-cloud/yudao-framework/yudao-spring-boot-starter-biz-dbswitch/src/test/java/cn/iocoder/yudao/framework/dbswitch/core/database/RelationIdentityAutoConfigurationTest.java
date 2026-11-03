package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RelationIdentityAutoConfigurationTest {
    @Test
    void twoContextsRetainIndependentRuntimeOptionsAndRegistries() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class));
        runner.withPropertyValues("yudao.dbswitch.migration.query-timeout-seconds=17")
                .run(first -> {
                    assertThat(first).hasNotFailed();
                    var one = first.getBean(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.class);
                    runner.withPropertyValues("yudao.dbswitch.migration.query-timeout-seconds=29")
                            .run(second -> {
                                assertThat(second).hasNotFailed();
                                var two = second.getBean(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.class);
                                assertThat(one.options().queryTimeoutSeconds()).isEqualTo(17);
                                assertThat(two.options().queryTimeoutSeconds()).isEqualTo(29);
                                assertThat(one.products()).isNotSameAs(two.products());
                                assertThat(one.drivers()).isNotSameAs(two.drivers());
                            });
                    assertThat(one.options().queryTimeoutSeconds()).isEqualTo(17);
                });
    }

    @Test
    void invalidRuntimeLimitsFailApplicationStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withPropertyValues("yudao.dbswitch.migration.max-cache-bytes=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productRegistryIsUsableWithoutADatasourceBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DbSwitchProperties.class);
                    var registry = context.getBean(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.class);
                    assertThat(registry.metadata(ProductTypeEnum.MYSQL).getDatabaseType())
                            .isEqualTo(ProductTypeEnum.MYSQL);
                    assertThat(registry.metadata(ProductTypeEnum.POSTGRESQL).getDatabaseType())
                            .isEqualTo(ProductTypeEnum.POSTGRESQL);
                });
    }

    @Test
    void physicalConnectionBudgetBindsAndInvalidReservationFailsStartup() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class));
        runner.withPropertyValues("yudao.dbswitch.pool.maximum-physical-connections=8",
                        "yudao.dbswitch.pool.metadata-reserved-connections=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var budget = context.getBean(cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers.class)
                            .runtimes().connections().snapshot();
                    assertThat(budget.limit()).isEqualTo(8);
                    assertThat(budget.metadataReserved()).isEqualTo(2);
                    assertThat(budget.active()).isZero();
                });
        runner.withPropertyValues("yudao.dbswitch.pool.maximum-physical-connections=4",
                        "yudao.dbswitch.pool.metadata-reserved-connections=4")
                .run(context -> assertThat(context).hasFailed());
    }
}
