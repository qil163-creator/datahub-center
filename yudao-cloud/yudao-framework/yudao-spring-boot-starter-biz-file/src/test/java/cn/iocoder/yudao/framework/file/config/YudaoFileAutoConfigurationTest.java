package cn.iocoder.yudao.framework.file.config;

import cn.iocoder.yudao.framework.file.core.client.FileClient;
import cn.iocoder.yudao.framework.file.core.client.FileClientFactory;
import cn.iocoder.yudao.framework.file.core.client.FileClientProvider;
import cn.iocoder.yudao.framework.file.core.client.FileUrlResolver;
import cn.iocoder.yudao.framework.file.core.client.local.LocalFileClientConfig;
import cn.iocoder.yudao.framework.file.core.remote.DefaultRemoteFileClientFactory;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClient;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通过标准 imports 发现自动配置，验证默认启用、关闭与用户工厂覆盖。 */
class YudaoFileAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestApplication.class);

    @Test
    void discoversFactoryByImportsWithoutOpeningRemoteConnection() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(YudaoFileAutoConfiguration.class)
                    .hasSingleBean(FileProperties.class).hasSingleBean(RemoteFileClientFactory.class)
                    .hasSingleBean(FileClientFactory.class).hasSingleBean(FileUrlResolver.class);
            assertThat(context.getBean(FileProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(RemoteFileClientFactory.class)).isInstanceOf(DefaultRemoteFileClientFactory.class);
            assertThat(context).doesNotHaveBean(RemoteFileClient.class);
            assertThat(context.getBean(FileUrlResolver.class).format(1L, "https://example.invalid/assets/", "目录/a b.txt"))
                    .isEqualTo("https://example.invalid/assets/%E7%9B%AE%E5%BD%95/a%20b.txt");
        });
    }

    @Test
    void explicitlyEnablesFactory() {
        runner.withPropertyValues("yudao.file.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RemoteFileClientFactory.class);
            assertThat(context.getBean(FileProperties.class).isEnabled()).isTrue();
        });
    }

    @Test
    void disablingDoesNotRegisterDefaultFactory() {
        runner.withPropertyValues("yudao.file.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(RemoteFileClientFactory.class)
                    .doesNotHaveBean(RemoteFileClient.class).doesNotHaveBean(FileClientFactory.class)
                    .doesNotHaveBean(FileUrlResolver.class);
            assertThat(context.getBean(FileProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    void userFactoryOverridesDefault() {
        runner.withUserConfiguration(CustomFactoryConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RemoteFileClientFactory.class);
            assertThat(context.getBean(RemoteFileClientFactory.class)).isSameAs(CustomFactoryConfiguration.FACTORY);
        });
    }

    @Test
    void disablingAutoConfigurationPreservesExplicitUserFactory() {
        runner.withPropertyValues("yudao.file.enabled=false")
                .withUserConfiguration(CustomFactoryConfiguration.class).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RemoteFileClientFactory.class);
                    assertThat(context.getBean(RemoteFileClientFactory.class)).isSameAs(CustomFactoryConfiguration.FACTORY);
                    assertThat(context.getBean(FileProperties.class).isEnabled()).isFalse();
                });
    }

    @Test
    void userStorageFactoryOverridesDefault() {
        FileClientFactory custom = mock(FileClientFactory.class);
        runner.withBean(FileClientFactory.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FileClientFactory.class);
            assertThat(context.getBean(FileClientFactory.class)).isSameAs(custom);
        });
    }

    @Test
    void coexistsWithLegacyInfraConfigurationAndFactoryBeanNames() {
        runner.withUserConfiguration(LegacyInfraConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(YudaoFileAutoConfiguration.class)
                    .hasSingleBean(FileClientFactory.class).hasSingleBean(RemoteFileClientFactory.class);
            assertThat(context.getBean("yudaoFileAutoConfiguration")).isInstanceOf(LegacyInfraConfiguration.class);
            assertThat(context.getBean("fileClientFactory")).isInstanceOf(LegacyInfraFileClientFactory.class);
            assertThat(context.getBean("fileStorageClientFactory")).isSameAs(context.getBean(FileClientFactory.class));
        });
    }

    @Test
    void injectsProviderAndUrlResolverAndClosesManagedClients() throws Exception {
        FileClientProvider provider = mock(FileClientProvider.class);
        LocalFileClientConfig config = new LocalFileClientConfig();
        config.setBasePath("unused-provider-path");
        config.setDomain("https://example.invalid");
        FileClient client = mock(FileClient.class);
        byte[] content = new byte[]{1, 2};
        when(client.getId()).thenReturn(1L);
        when(client.upload(content, "a.txt", "text/plain")).thenReturn("custom/a.txt");
        FileUrlResolver urls = (id, domain, path) -> "custom/" + path;
        when(provider.supports(99, config)).thenReturn(true);
        when(provider.create(1L, config, urls)).thenReturn(client);
        runner.withBean(FileClientProvider.class, () -> provider)
                .withBean(FileUrlResolver.class, () -> urls).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FileUrlResolver.class);
                    assertThat(context.getBean(FileUrlResolver.class)).isSameAs(urls);
                    FileClientFactory factory = context.getBean(FileClientFactory.class);
                    factory.createOrUpdateFileClient(1L, 99, config);
                    assertThat(factory.getFileClient(1L).getId()).isEqualTo(1L);
                    assertThat(factory.getFileClient(1L).upload(content, "a.txt", "text/plain")).isEqualTo("custom/a.txt");
                });
        verify(provider).create(1L, config, urls);
        verify(client).upload(content, "a.txt", "text/plain");
        verify(client).close();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication { }

    /** 仅模拟既有 infra 的默认 bean 名和不同工厂类型，不依赖业务模块。 */
    @Configuration(value = "yudaoFileAutoConfiguration", proxyBeanMethods = false)
    static class LegacyInfraConfiguration {

        @Bean
        LegacyInfraFileClientFactory fileClientFactory() {
            return new LegacyInfraFileClientFactory();
        }
    }

    static class LegacyInfraFileClientFactory { }

    @Configuration(proxyBeanMethods = false)
    static class CustomFactoryConfiguration {
        private static final RemoteFileClientFactory FACTORY = config -> {
            throw new AssertionError("容器启动不得打开远程文件连接");
        };

        @Bean
        RemoteFileClientFactory customRemoteFileClientFactory() {
            return FACTORY;
        }
    }
}
