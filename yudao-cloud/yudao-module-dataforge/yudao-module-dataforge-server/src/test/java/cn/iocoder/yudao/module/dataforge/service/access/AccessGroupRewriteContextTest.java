package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.access.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import javax.sql.DataSource;
import java.security.SecureRandom;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证真实配置绑定和服务构造，不连接平台或目标数据库。 */
class AccessGroupRewriteContextTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Fixture.class);
    private static final String PREFIX = "yudao.dataforge.access.group-rewrite.";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AccessGroupRewriteProperties.class)
    @Import(AccessGroupRewriteCoordinator.class)
    static class Fixture {
        @Bean DataSource control() { return mock(DataSource.class); }
        @Bean DbSwitchRuntime runtime() { return mock(DbSwitchRuntime.class); }
    }

    @Test void defaultDisabledContextStartsAndDoesNotTouchEitherDatabase() {
        context.run(app -> {
            assertThat(app).hasNotFailed();
            assertFalse(app.getBean(AccessGroupRewriteProperties.class).isEnabled());
            assertNull(app.getBean(AccessGroupRewriteCoordinator.class).prepare(null, null, null, null, tdTarget(), null, null));
            verifyNoInteractions(app.getBean(DataSource.class), app.getBean(DbSwitchRuntime.class));
        });
    }

    @Test void springBindsBase64KeyringAndExactDeploymentTaskAllowlistWithoutExposingSecrets() {
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String encoded = Base64.getEncoder().encodeToString(secret);
        context.withPropertyValues(PREFIX + "enabled=true", PREFIX + "current-key-id=fixture",
                PREFIX + "keyring.fixture=" + encoded, PREFIX + "exclusive-tasks[0]=10",
                PREFIX + "control-schema=datahub-center", PREFIX + "transaction-timeout-seconds=7").run(app -> {
            assertThat(app).hasNotFailed();
            var properties = app.getBean(AccessGroupRewriteProperties.class);
            var deployment = properties.authorize(10);
            assertArrayEquals(secret, deployment.keys().get("fixture").getEncoded());
            assertEquals("datahub-center", deployment.schema()); assertEquals(7, deployment.timeout());
            assertThrows(IllegalArgumentException.class, () -> properties.authorize(20));
            assertThrows(IllegalArgumentException.class, () -> properties.authorize(11));
            assertFalse(properties.toString().contains(encoded)); assertFalse(deployment.toString().contains(encoded));
            String serialized = JsonUtils.toJsonString(properties);
            assertFalse(serialized.contains(encoded)); assertFalse(serialized.contains("keyring"));
            verifyNoInteractions(app.getBean(DataSource.class), app.getBean(DbSwitchRuntime.class));
        });
        Arrays.fill(secret, (byte) 0);
    }

    @Test void enabledInvalidKeyCannotProduceAnAuthorizedDeploymentSnapshot() {
        context.withPropertyValues(PREFIX + "enabled=true", PREFIX + "current-key-id=fixture",
                PREFIX + "keyring.fixture=not-valid-base64", PREFIX + "exclusive-tasks[0]=10").run(app -> {
            assertThat(app).hasNotFailed();
            var failure = assertThrows(IllegalArgumentException.class, () -> app.getBean(AccessGroupRewriteProperties.class).authorize(10));
            assertFalse(failure.getMessage().contains("not-valid-base64"));
            verifyNoInteractions(app.getBean(DataSource.class), app.getBean(DbSwitchRuntime.class));
        });
    }

    @Test void runtimeProviderNeverBecomesBusinessConfigOrRunSnapshotJson() {
        var saved = new AccessTaskConfig();
        saved.setSource(List.of(new AccessTaskConfig.Source()));
        var request = AccessTaskConfigMapper.toRuntime(saved, 30L);
        request.getTarget().setGroupRewriteBindingProvider((schema, table, logical, physical, structure) -> {
            throw new AssertionError("Serialization must never resolve a runtime provider");
        });
        String targetJson = JsonUtils.toJsonString(request.getTarget());
        assertFalse(targetJson.contains("groupRewriteBinding"));
        var task = new DataAccessDO(); task.setId(10L); task.setDataAccessJson(saved);
        String businessJson = AccessRunSnapshot.freeze(task);
        assertFalse(businessJson.contains("groupRewriteBinding")); assertFalse(businessJson.contains("keyring"));
        assertFalse(businessJson.contains("ownerToken")); assertFalse(businessJson.contains("currentKeyId"));
    }
    private TargetDataSourceProperties tdTarget() {
        var target = new TargetDataSourceProperties(); target.setTargetProductType(ProductTypeEnum.TDENGINE);
        target.setChangeDataSync(true); target.setChangeDataSyncType("PK_ORDER_COMPARE"); return target;
    }
}
