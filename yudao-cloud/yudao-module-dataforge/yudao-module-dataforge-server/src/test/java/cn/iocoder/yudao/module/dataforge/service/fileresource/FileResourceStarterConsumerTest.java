package cn.iocoder.yudao.module.dataforge.service.fileresource;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.file.config.YudaoFileAutoConfiguration;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClient;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClientFactory;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILE_RESOURCE_DISABLED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证业务消费者通过正式自动配置获取客户端，并在关闭开关时仍可启动且不绕过开关。 */
class FileResourceStarterConsumerTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(YudaoFileAutoConfiguration.class))
            .withBean(DatabaseMapper.class, () -> mock(DatabaseMapper.class))
            .withBean(DatabaseTypeMapper.class, () -> mock(DatabaseTypeMapper.class))
            .withBean(FileResourceConnectionResolver.class)
            .withBean(FileResourceDiagnosis.class);

    @Test void defaultAutoConfigurationWiresConsumers() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(RemoteFileClientFactory.class));
            assertNotNull(context.getBean(FileResourceConnectionResolver.class));
            assertNotNull(context.getBean(FileResourceDiagnosis.class));
        });
    }

    @Test void disabledStarterLeavesConsumersAvailableButCannotCreateClients() {
        runner.withPropertyValues("yudao.file.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(RemoteFileClientFactory.class).isEmpty());
            var resolver = context.getBean(FileResourceConnectionResolver.class);
            var diagnosis = context.getBean(FileResourceDiagnosis.class);
            assertEquals(FILE_RESOURCE_DISABLED.getCode(),
                    assertThrows(ServiceException.class, () -> resolver.open(null, null, null)).getCode());
            assertEquals(FILE_RESOURCE_DISABLED.getCode(),
                    assertThrows(ServiceException.class, () -> diagnosis.diagnose(null, null, null, false)).getCode());
        });
    }

    @Test void resolverUsesTheInjectedExtensionInsteadOfConstructingItsOwnClient() throws Exception {
        var factory = mock(RemoteFileClientFactory.class);
        var client = mock(RemoteFileClient.class);
        when(factory.open(any())).thenReturn(client);
        runner.withBean(RemoteFileClientFactory.class, () -> factory).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(RemoteFileClientFactory.class).size());
            var database = DatabaseDO.builder().databaseIp("ftp.example").databasePort("21")
                    .userName("synthetic").extendConfig("{\"basePath\":\"/root\"}").build();
            var type = DatabaseTypeDO.builder().typeCode("FTP").build();
            try (RemoteFileClient opened = context.getBean(FileResourceConnectionResolver.class)
                    .open(database, type, "synthetic-secret")) {
                assertSame(client, opened);
            }
            var config = ArgumentCaptor.forClass(RemoteFileConfig.class);
            verify(factory).open(config.capture());
            assertEquals("/root", config.getValue().rootPath());
            assertEquals(RemoteFileConfig.Protocol.FTP, config.getValue().protocol());
            verify(client).close();
        });
    }
}
