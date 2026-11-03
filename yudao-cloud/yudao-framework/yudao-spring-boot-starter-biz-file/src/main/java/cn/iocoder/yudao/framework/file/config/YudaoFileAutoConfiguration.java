package cn.iocoder.yudao.framework.file.config;

import cn.iocoder.yudao.framework.file.core.client.DefaultFileUrlResolver;
import cn.iocoder.yudao.framework.file.core.client.FileClientFactory;
import cn.iocoder.yudao.framework.file.core.client.FileClientFactoryImpl;
import cn.iocoder.yudao.framework.file.core.client.FileClientProvider;
import cn.iocoder.yudao.framework.file.core.client.FileUrlResolver;
import cn.iocoder.yudao.framework.file.core.remote.DefaultRemoteFileClientFactory;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClientFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 文件存储与远程目录协议自动配置；启动时仅注册工厂，不创建任何远程连接。 */
@AutoConfiguration
@EnableConfigurationProperties(FileProperties.class)
public class YudaoFileAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RemoteFileClientFactory.class)
    @ConditionalOnProperty(prefix = "yudao.file", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RemoteFileClientFactory remoteFileClientFactory() {
        return new DefaultRemoteFileClientFactory();
    }

    @Bean
    @ConditionalOnMissingBean(FileUrlResolver.class)
    @ConditionalOnProperty(prefix = "yudao.file", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FileUrlResolver fileUrlResolver() {
        return new DefaultFileUrlResolver();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(FileClientFactory.class)
    @ConditionalOnProperty(prefix = "yudao.file", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FileClientFactory fileStorageClientFactory(ObjectProvider<FileClientProvider> providers,
            FileUrlResolver urlResolver, RemoteFileClientFactory remoteFactory) {
        return new FileClientFactoryImpl(providers.orderedStream().toList(), urlResolver, remoteFactory);
    }
}
