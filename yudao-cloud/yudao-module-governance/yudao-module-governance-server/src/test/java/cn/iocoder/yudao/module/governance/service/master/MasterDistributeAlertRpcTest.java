package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.module.governance.framework.rpc.MasterDistributeAlertNotifyClient;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class MasterDistributeAlertRpcTest {
    @Test void dedicatedClientInheritsProviderContractAndHasIsolatedBoundedOptions() {
        var type=MasterDistributeAlertNotifyClient.class;
        var annotation=type.getAnnotation(org.springframework.cloud.openfeign.FeignClient.class);
        assertThat(annotation.primary()).isFalse();
        assertThat(annotation.contextId()).isEqualTo("governance-master-distribute-alert");
        assertThat(NotifyMessageSendApi.class.isAssignableFrom(type)).isTrue();
        var config=new MasterDistributeAlertNotifyClient.Configuration();
        assertThat(config.masterDistributeAlertOptions().connectTimeoutMillis()).isEqualTo(5000);
        assertThat(config.masterDistributeAlertOptions().readTimeoutMillis()).isEqualTo(10000);
        assertThat(config.masterDistributeAlertOptions().isFollowRedirects()).isFalse();
        assertThat(config.masterDistributeAlertRetryer()).isSameAs(feign.Retryer.NEVER_RETRY);
        var contract=new org.springframework.cloud.openfeign.support.SpringMvcContract().parseAndValidateMetadata(type);
        assertThat(contract).hasSize(2);
        assertThat(contract).anySatisfy(method->assertThat(method.template().url()).isEqualTo(NotifyMessageSendApi.PREFIX+"/send-single-admin"));
    }

    @Test void registrationContextPreservesExistingPrimaryProviderAndScopesOptionsToNamedClient() {
        try(var context=new AnnotationConfigApplicationContext()){
            context.scan("cn.iocoder.yudao.module.governance.framework.rpc.config");context.refresh();
            assertThat(context.getBeanFactory().getBeanNamesForType(MasterDistributeAlertNotifyClient.class,true,false))
                    .containsExactly(MasterDistributeAlertNotifyClient.class.getName());
            assertThat(context.getBeanFactory().getBeanDefinition(NotifyMessageSendApi.class.getName()).isPrimary()).isTrue();
            assertThat(context.getBeanFactory().getBeanDefinition(MasterDistributeAlertNotifyClient.class.getName()).isPrimary()).isFalse();
            assertThat(context.getBeanFactory().getBeanNamesForType(feign.Request.Options.class,true,false)).isEmpty();
        }
    }
}
