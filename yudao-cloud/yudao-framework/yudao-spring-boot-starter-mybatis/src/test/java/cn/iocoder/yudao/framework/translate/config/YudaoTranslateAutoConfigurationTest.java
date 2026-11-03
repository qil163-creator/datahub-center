package cn.iocoder.yudao.framework.translate.config;

import cn.iocoder.yudao.framework.translate.core.TranslateUtils;
import org.dromara.trans.config.EasyTransMybatisPlusConfig;
import org.dromara.trans.config.TransServiceConfig;
import org.dromara.trans.service.impl.TransService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class YudaoTranslateAutoConfigurationTest {

    @Test
    void initializesAdapterAfterEasyTransRegistersItsService() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoTranslateAutoConfiguration.class,
                        EasyTransMybatisPlusConfig.class, TransServiceConfig.class))
                .withPropertyValues("easy-trans.is-enable-auto=false", "easy-trans.is-enable-redis=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TransService.class);
                    assertThat(context).hasSingleBean(TranslateUtils.class);
                });
    }

    @Test
    void leavesAdapterDisabledWhenEasyTransServiceIsAbsent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoTranslateAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TranslateUtils.class);
                });
    }

}
