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

    @Test
    void shouldRegisterRequiredSystemFeignClients() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("cn.iocoder.yudao.module.dataforge.framework.rpc.config");
            context.refresh();

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
