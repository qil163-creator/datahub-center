package cn.iocoder.yudao.framework.dbswitch.config;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证厂商驱动不会覆盖应用的日志 API，并经过真实 Boot 日志初始化。 */
class DbSwitchLoggingBootstrapTest {
    @Test
    void vendorDriversDoNotBundleAnotherSlf4jApi() throws Exception {
        var definitions = Collections.list(getClass().getClassLoader().getResources("org/slf4j/LoggerFactory.class"));
        assertThat(definitions).as("应用类路径只能有一份 SLF4J API，包含内嵌类的厂商驱动必须隔离加载").hasSize(1);
    }

    @Test
    void springApplicationInitializesLogbackAndDbSwitch() {
        var application = new SpringApplication(YudaoDbSwitchAutoConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        try (var context = application.run("--spring.config.location=optional:classpath:/logging-bootstrap-fixture/")) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBean(DbSwitchRuntime.class)).isNotNull();
            assertThat(LoggerFactory.getILoggerFactory().getClass().getName())
                    .isEqualTo("ch.qos.logback.classic.LoggerContext");
        }
    }
}
