package cn.iocoder.yudao.module.asset.framework.rpc.config;

import cn.iocoder.yudao.module.asset.controller.admin.market.MarketAccessController;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessService;
import cn.iocoder.yudao.module.asset.service.market.MarketConsumptionService;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import cn.iocoder.yudao.module.supply.api.app.SupplyQuotaApi;
import feign.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RpcConfigurationTest {

    @Test
    void registersSupplyClientsAndInjectsMarketConsumersWithoutConnectingToSupply() {
        new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class))
                .withUserConfiguration(RpcConfiguration.class, MarketAccessController.class,
                        MarketConsumptionService.class)
                .withBean(MarketAccessService.class, () -> mock(MarketAccessService.class))
                .withPropertyValues(
                        "spring.cloud.openfeign.client.config.supply-consumption-quota-v1.url=https://localhost:1",
                        "spring.cloud.openfeign.client.config.supply-grant-source-v2.url=https://localhost:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SupplyQuotaApi.class);
                    assertThat(context).hasSingleBean(SupplyGrantSourceApi.class);
                    SupplyQuotaApi quotaApi = context.getBean(SupplyQuotaApi.class);
                    SupplyGrantSourceApi grantSourceApi = context.getBean(SupplyGrantSourceApi.class);
                    assertThat(Proxy.isProxyClass(quotaApi.getClass())).isTrue();
                    assertThat(Proxy.isProxyClass(grantSourceApi.getClass())).isTrue();
                    assertThat(ReflectionTestUtils.getField(context.getBean(MarketAccessController.class), "quotaApi"))
                            .isSameAs(quotaApi);
                    assertThat(ReflectionTestUtils.getField(context.getBean(MarketConsumptionService.class), "quotaApi"))
                            .isSameAs(quotaApi);

                    FeignClientFactory clients = context.getBean(FeignClientFactory.class);
                    Client quotaClient = clients.getInstance("supply-consumption-quota-v1", Client.class);
                    Client grantSourceClient = clients.getInstance("supply-grant-source-v2", Client.class);
                    assertThat(quotaClient).isNotNull().isNotSameAs(grantSourceClient);
                    assertThat(grantSourceClient).isNotNull();
                    assertThat(context).doesNotHaveBean("supplyGrantSourceFeignClient");
                });
    }
}
