package cn.iocoder.yudao.module.dataforge.framework.rpc.config;

import cn.iocoder.yudao.module.governance.api.identify.SafetyIdentifyApi;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class RpcConfigurationTest {

    @Test void pushAlertFeignReusesProviderContractWithIsolatedBoundedOptions() {
        var type = cn.iocoder.yudao.module.dataforge.framework.rpc.PushAlertNotifyClient.class;
        var annotation = type.getAnnotation(org.springframework.cloud.openfeign.FeignClient.class);
        assertThat(annotation.primary()).isFalse();
        assertThat(annotation.contextId()).isEqualTo("dataforge-push-alert");
        assertThat(cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi.class.isAssignableFrom(type)).isTrue();
        var configuration = new cn.iocoder.yudao.module.dataforge.framework.rpc.PushAlertNotifyClient.Configuration();
        assertThat(configuration.pushAlertOptions().connectTimeoutMillis()).isEqualTo(5000);
        assertThat(configuration.pushAlertOptions().readTimeoutMillis()).isEqualTo(10000);
        assertThat(configuration.pushAlertOptions().isFollowRedirects()).isFalse();
        assertThat(configuration.pushAlertRetryer()).isSameAs(feign.Retryer.NEVER_RETRY);
        var metadata = new org.springframework.cloud.openfeign.support.SpringMvcContract().parseAndValidateMetadata(type);
        assertThat(metadata).hasSize(2);
        assertThat(metadata).anySatisfy(method -> assertThat(method.template().url())
                .isEqualTo(cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi.PREFIX + "/send-single-admin"));
    }

    @Test
    void shouldRegisterRequiredSystemFeignClients() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("cn.iocoder.yudao.module.dataforge.framework.rpc.config");
            context.refresh();

            assertThat(context.getBeanFactory().getBeanNamesForType(cn.iocoder.yudao.module.dataforge.framework.rpc.PushAlertNotifyClient.class, true, false))
                    .containsExactly(cn.iocoder.yudao.module.dataforge.framework.rpc.PushAlertNotifyClient.class.getName());

            assertThat(context.getBeanFactory().getBeanNamesForType(PermissionApi.class, true, false)).containsExactly(PermissionApi.class.getName());
            assertThat(context.getBeanFactory().getBeanNamesForType(QualityTaskApi.class, true, false)).containsExactly(QualityTaskApi.class.getName());
            assertThat(context.getBeanFactory().getBeanNamesForType(DeptApi.class, true, false))
                    .containsExactly(DeptApi.class.getName());
            assertThat(context.getBeanFactory().getBeanNamesForType(AdminUserApi.class, true, false))
                    .containsExactly(AdminUserApi.class.getName());
            assertThat(context.getBeanFactory().getBeanNamesForType(SafetyIdentifyApi.class, true, false))
                    .isEmpty();

        }
    }

}
