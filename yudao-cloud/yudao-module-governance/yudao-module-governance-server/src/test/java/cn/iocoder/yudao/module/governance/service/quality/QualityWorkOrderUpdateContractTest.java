package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.QualityWorkOrderController;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wire/validation contract only; the PostgreSQL suite proves the actual version gate and transaction. */
class QualityWorkOrderUpdateContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1.5", "-0.5", "2147483646.5"})
    void jsonFractionalVersionIsNeverTruncatedIntoAnAcceptedInteger(String value) {
        assertThrows(Exception.class, () -> JsonUtils.getObjectMapper().readValue(
                body(value), QualityWorkOrderSaveReqVO.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "1e30"})
    void jsonVersionOutsideIntegerStorageCannotWrap(String value) {
        assertThrows(Exception.class, () -> JsonUtils.getObjectMapper().readValue(
                body(value), QualityWorkOrderSaveReqVO.class));
    }

    @Test
    void creationMayOmitVersionAndJsonNullRemainsMissingNotZero() throws Exception {
        var mapper = JsonUtils.getObjectMapper();
        var created = mapper.readValue("{\"orderName\":\"fixture\"}", QualityWorkOrderSaveReqVO.class);
        var missing = mapper.readValue(body("null"), QualityWorkOrderSaveReqVO.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(created).isEmpty());
            assertNull(created.getExpectedVersion());
            assertNull(missing.getExpectedVersion());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2147483646})
    void jsonPreservesIntegerVersionAndLongIdsExactly(int version) throws Exception {
        var req = JsonUtils.getObjectMapper().readValue(body(Integer.toString(version)),
                QualityWorkOrderSaveReqVO.class);
        assertEquals(version, req.getExpectedVersion());
        assertEquals(9007199254740993L, req.getId());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(req).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2147483647", "0.5", "-0.5", "2147483646.5", "2147483648"})
    void actualPutRejectsInvalidWireVersionBeforeService(String version) throws Exception {
        var service = mock(QualityWorkOrderService.class);
        var controller = new QualityWorkOrderController();
        ReflectionTestUtils.setField(controller, "workOrderService", service);
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
            mvc.perform(put("/governance/quality-work-order/update").contentType("application/json")
                    .content(body(version))).andExpect(status().isBadRequest());
            verifyNoInteractions(service);
        }
    }

    @Test
    void actualPutKeepsClientVersionAndBooleanReceiptWhileCreateNeedsNoVersion() throws Exception {
        var service = mock(QualityWorkOrderService.class);
        var controller = new QualityWorkOrderController();
        ReflectionTestUtils.setField(controller, "workOrderService", service);
        when(service.createWorkOrder(any())).thenReturn(42L);
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
            mvc.perform(put("/governance/quality-work-order/update").contentType("application/json")
                    .content(body("0"))).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
            verify(service).updateWorkOrder(argThat(r -> r.getExpectedVersion() == 0
                    && r.getId() == 9007199254740993L && r.getOrderName().equals("fixture")));
            mvc.perform(post("/governance/quality-work-order/create").contentType("application/json")
                    .content("{\"orderName\":\"fixture\"}")).andExpect(status().isOk());
            verify(service).createWorkOrder(argThat(r -> r.getExpectedVersion() == null));
        }
    }

    private static String body(String version) {
        return "{\"id\":\"9007199254740993\",\"orderName\":\"fixture\",\"expectedVersion\":" + version + "}";
    }
}
