package cn.iocoder.yudao.module.modeling.framework.rpc.config;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.api.registration.RegistrationContextApiImpl;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.IndicatorModelController;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.DwLayerController;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelOverviewMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryDataDomainRelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DwLayerMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityService;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelServiceImpl;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.modeling.service.planning.DataDomainService;
import cn.iocoder.yudao.module.modeling.service.planning.DwLayerPresentationService;
import cn.iocoder.yudao.module.modeling.service.planning.DwLayerService;
import cn.iocoder.yudao.module.modeling.service.planning.SubjectDomainService;
import cn.iocoder.yudao.module.modeling.service.registration.RegistrationContextService;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClientFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 使用实际 Feign 注册和业务 Bean 验证启动装配，避免 Mockito 掩盖漏注册的 RPC 客户端。 */
class RpcConfigurationTest {

    @Test
    void registersSystemClientsAndWiresConsumers() {
        new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class))
                .withUserConfiguration(RpcConfiguration.class, IndicatorModelController.class,
                        IndicatorModelServiceImpl.class, DwLayerController.class,
                        DwLayerPresentationService.class, ModelHierarchyResolver.class,
                        RegistrationContextService.class, RegistrationContextApiImpl.class)
                // 只实例化客户端，不发送 HTTP 请求，也不依赖本机 Nacos 或业务数据库。
                .withPropertyValues("spring.cloud.openfeign.client.config.system-server.url=http://127.0.0.1:1")
                .withBean(IndicatorModelMapper.class, () -> mock(IndicatorModelMapper.class))
                .withBean(BusinessEntityMapper.class, () -> mock(BusinessEntityMapper.class))
                .withBean(LogicModelMapper.class, () -> mock(LogicModelMapper.class))
                .withBean(cn.iocoder.yudao.module.modeling.service.model.LogicModelColumnService.class, () -> mock(cn.iocoder.yudao.module.modeling.service.model.LogicModelColumnService.class))
                .withBean(ModelOverviewMapper.class, () -> mock(ModelOverviewMapper.class))
                .withBean(DwLayerMapper.class, () -> mock(DwLayerMapper.class))
                .withBean(DataDomainMapper.class, () -> mock(DataDomainMapper.class))
                .withBean(SubjectDomainMapper.class, () -> mock(SubjectDomainMapper.class))
                .withBean(BizCategoryMapper.class, () -> mock(BizCategoryMapper.class))
                .withBean(BizCategoryDataDomainRelMapper.class, () -> mock(BizCategoryDataDomainRelMapper.class))
                .withBean(SecurityFrameworkService.class, () -> mock(SecurityFrameworkService.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .withBean(DwLayerService.class, () -> mock(DwLayerService.class))
                .withBean(DataDomainService.class, () -> mock(DataDomainService.class))
                .withBean(SubjectDomainService.class, () -> mock(SubjectDomainService.class))
                .withBean(BusinessEntityService.class, () -> mock(BusinessEntityService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AdminUserApi.class)
                            .hasSingleBean(DictDataApi.class)
                            .hasSingleBean(IndicatorModelController.class)
                            .hasSingleBean(DwLayerController.class)
                            .hasSingleBean(DwLayerPresentationService.class)
                            .hasSingleBean(RegistrationContextApiImpl.class);
                    AdminUserApi userApi = context.getBean(AdminUserApi.class);
                    assertThat(context.getBean("&" + AdminUserApi.class.getName()))
                            .isInstanceOf(FeignClientFactoryBean.class);
                    assertThat(ReflectionTestUtils.getField(context.getBean(IndicatorModelServiceImpl.class), "userApi"))
                            .isSameAs(userApi);
                    assertThat(ReflectionTestUtils.getField(context.getBean(DwLayerPresentationService.class), "adminUserApi"))
                            .isSameAs(userApi);
                    DictDataApi dictDataApi = context.getBean(DictDataApi.class);
                    assertThat(context.getBean("&" + DictDataApi.class.getName()))
                            .isInstanceOf(FeignClientFactoryBean.class);
                    assertThat(ReflectionTestUtils.getField(context.getBean(RegistrationContextService.class), "dictionaries"))
                            .isSameAs(dictDataApi);
                });
    }
}
