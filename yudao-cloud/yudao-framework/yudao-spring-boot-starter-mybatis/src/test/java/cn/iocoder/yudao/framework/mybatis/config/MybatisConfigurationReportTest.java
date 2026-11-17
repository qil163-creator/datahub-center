package cn.iocoder.yudao.framework.mybatis.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.ds.ItemDataSource;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisConfigurationReportTest {

    @Test
    @SuppressWarnings("deprecation")
    void keepsConfiguredExtensionsAndRuntimeReferences() {
        var properties = new MybatisPlusProperties();
        var original = properties.getGlobalConfig();
        var injector = new DefaultSqlInjector();
        IdentifierGenerator identifiers = entity -> 42L;
        var handler = new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
            }

            @Override
            public void updateFill(MetaObject metaObject) {
            }
        };
        var factory = new DefaultSqlSessionFactory(new Configuration());
        original.setSqlInjector(injector).setIdentifierGenerator(identifiers).setMetaObjectHandler(handler)
                .setSqlSessionFactory(factory).setEnableSqlRunner(true);
        original.getMapperRegistryCache().add("QA_CONFIGURED_MAPPER");
        original.getDbConfig().setSchema("qa_configured_schema");

        new YudaoMybatisAutoConfiguration().reportableGlobalConfigCustomizer().customize(properties);

        var current = properties.getGlobalConfig();
        assertThat(current).isNotSameAs(original);
        assertThat(current.getDbConfig()).isSameAs(original.getDbConfig());
        assertThat(current.getDbConfig().getSchema()).isEqualTo("qa_configured_schema");
        assertThat(current.getMapperRegistryCache()).isSameAs(original.getMapperRegistryCache());
        assertThat(current.getSqlInjector()).isSameAs(injector);
        assertThat(current.getIdentifierGenerator()).isSameAs(identifiers);
        assertThat(current.getMetaObjectHandler()).isSameAs(handler);
        assertThat(current.getSqlSessionFactory()).isSameAs(factory);
        assertThat(current.isEnableSqlRunner()).isTrue();
    }

    @Test
    @SuppressWarnings("deprecation")
    void repeatedConfigurationReportsDoNotBorrowConnectionsAndKeepTheRuntimeFactory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DynamicDataSourceAutoConfiguration.class,
                        YudaoMybatisAutoConfiguration.class, MybatisPlusAutoConfiguration.class))
                .withPropertyValues(
                        "yudao.info.base-package=cn.iocoder.yudao.framework.mybatis.config.empty",
                        "spring.datasource.dynamic.primary=master",
                        "spring.datasource.dynamic.druid.initial-size=1",
                        "spring.datasource.dynamic.druid.max-active=3",
                        "spring.datasource.dynamic.druid.max-wait=100",
                        "spring.datasource.dynamic.datasource.master.url=jdbc:mock:master",
                        "spring.datasource.dynamic.datasource.master.driver-class-name=com.alibaba.druid.mock.MockDriver",
                        "spring.datasource.dynamic.datasource.slave.url=jdbc:mock:slave",
                        "spring.datasource.dynamic.datasource.slave.driver-class-name=com.alibaba.druid.mock.MockDriver",
                        "mybatis-plus.global-config.banner=false",
                        "mybatis-plus.global-config.db-config.logic-delete-value=9")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var factory = context.getBean(SqlSessionFactory.class);
                    var global = context.getBean(MybatisPlusProperties.class).getGlobalConfig();
                    assertThat(global.getSqlSessionFactory()).isSameAs(factory);
                    assertThat(global.isBanner()).isFalse();
                    assertThat(global.getDbConfig().getLogicDeleteValue()).isEqualTo("9");
                    factory.getConfiguration().addMapper(ProbeMapper.class);

                    var routing = context.getBean(DynamicRoutingDataSource.class);
                    var pools = routing.getDataSources().values().stream()
                            .map(source -> (DruidDataSource) ((ItemDataSource) source).getRealDataSource()).toList();
                    var endpoint = new ConfigurationPropertiesReportEndpoint(List.of(), Show.NEVER);
                    endpoint.setApplicationContext(context.getSourceApplicationContext());
                    for (int iteration = 0; iteration < 25; iteration++) {
                        var report = endpoint.configurationPropertiesWithPrefix("mybatis-plus");
                        var beans = report.getContexts().get(context.getId()).getBeans();
                        assertThat(beans).hasSize(1);
                        var reportedGlobal = (Map<?, ?>) beans.values().iterator().next()
                                .getProperties().get("globalConfig");
                        assertThat(reportedGlobal.containsKey("sqlSessionFactory")).isFalse();
                        assertThat(reportedGlobal.containsKey("dbConfig")).isTrue();
                        for (var pool : pools) {
                            assertThat(pool.getActiveCount()).isZero();
                            assertThat(pool.getConnectCount()).isEqualTo(pool.getCloseCount());
                        }
                        try (var session = factory.openSession()) {
                            assertThat(session.getMapper(ProbeMapper.class).updateProbe()).isZero();
                        }
                        for (var pool : pools) {
                            assertThat(pool.getActiveCount()).isZero();
                            assertThat(pool.getConnectCount()).isEqualTo(pool.getCloseCount());
                        }
                    }
                    assertThat(global.getSqlSessionFactory()).isSameAs(factory);
                });
    }

    interface ProbeMapper {
        @Update("UPDATE qa_probe SET probe_value = 1")
        int updateProbe();
    }
}
