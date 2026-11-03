package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.governance.controller.admin.quality.QualityWorkOrderController;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证旧路由移除及现有转办请求映射；领域状态机由服务层回归测试覆盖。 */
class QualityWorkOrderRouteContractTest {

    @Test
    void removedPutRoutesNeverReachService() throws Exception {
        var service = mock(QualityWorkOrderService.class);
        var controller = new QualityWorkOrderController();
        ReflectionTestUtils.setField(controller, "workOrderService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(put("/governance/quality-work-order/process").param("id", "8"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/governance/quality-work-order/transfer")
                        .param("id", "8").param("newOwnerUserId", "9"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(service);
    }

    @Test
    void postTransferStillCarriesVersionAndRequestIdToCurrentService() throws Exception {
        var service = mock(QualityWorkOrderService.class);
        var controller = new QualityWorkOrderController();
        ReflectionTestUtils.setField(controller, "workOrderService", service);
        when(service.transfer(any())).thenReturn(new QualityWorkOrderRespVO());
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();

            mvc.perform(post("/governance/quality-work-order/transfer").contentType("application/json")
                            .content("{\"id\":8,\"expectedVersion\":3,\"requestId\":\"transfer-request\",\"ownerUserId\":9}"))
                    .andExpect(status().isOk());

            verify(service).transfer(argThat(request -> request.getId() == 8L
                    && request.getExpectedVersion() == 3 && request.getOwnerUserId() == 9L
                    && "transfer-request".equals(request.getRequestId())));
        }
    }
}
