package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.controller.admin.access.DataAccessController;
import cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessCommandControllerTest {
    private Object request(String name) throws Exception {
        try {
            return Class.forName("cn.iocoder.yudao.module.dataforge.controller.admin.access.vo." + name).getConstructor().newInstance();
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Missing validated ACCESS command request " + name, missing);
        }
    }
    @Test void runCommandRequiresPositiveTaskAndStableRequestId() throws Exception {
        var req = request("AccessRunCommandReqVO");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(req).isEmpty());
            ReflectionTestUtils.setField(req, "taskId", 1L);
            ReflectionTestUtils.setField(req, "requestId", "client-stable-001");
            assertTrue(factory.getValidator().validate(req).isEmpty());
            ReflectionTestUtils.setField(req, "requestId", " ");
            assertFalse(factory.getValidator().validate(req).isEmpty());
        }
    }
    @Test void stopCommandIsRecordScopedAndValidated() throws Exception {
        var req = request("AccessStopCommandReqVO");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(req).isEmpty());
            ReflectionTestUtils.setField(req, "recordId", 2L);
            ReflectionTestUtils.setField(req, "requestId", "stop-001");
            ReflectionTestUtils.setField(req, "reason", "requested by operator");
            assertTrue(factory.getValidator().validate(req).isEmpty());
            ReflectionTestUtils.setField(req, "recordId", -1L);
            assertFalse(factory.getValidator().validate(req).isEmpty());
        }
    }
    @Test void resolutionRequiresExplicitEvidenceVersionAndOutcome() throws Exception {
        var req = request("AccessResolveCommandReqVO");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(req).isEmpty());
            ReflectionTestUtils.setField(req, "recordId", 2L);
            ReflectionTestUtils.setField(req, "requestId", "resolve-001");
            ReflectionTestUtils.setField(req, "expectedVersion", 1L);
            ReflectionTestUtils.setField(req, "outcome", "STOPPED");
            ReflectionTestUtils.setField(req, "confirmedStopped", true);
            ReflectionTestUtils.setField(req, "outputReviewed", true);
            ReflectionTestUtils.setField(req, "reason", "worker and writes verified");
            ReflectionTestUtils.setField(req, "evidenceRef", "audit:123");
            assertTrue(factory.getValidator().validate(req).isEmpty());
            ReflectionTestUtils.setField(req, "expectedVersion", -1L);
            assertFalse(factory.getValidator().validate(req).isEmpty());
        }
    }
    @Test void groupRecoveryRequiresVersionEvidenceAndIndependentPermissionWithoutClientDrainAssertion() throws Exception {
        var req = new cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.AccessGroupRecoverCommandReqVO();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(req).isEmpty());
            req.setRecordId(2L); req.setRequestId("recover-1"); req.setExpectedVersion(3L);
            req.setReason("recover interrupted group"); req.setEvidenceRef("audit:group");
            assertTrue(factory.getValidator().validate(req).isEmpty());
            req.setExpectedVersion(-1L); assertFalse(factory.getValidator().validate(req).isEmpty());
            req.setExpectedVersion(3L);
        }
        assertThrows(NoSuchFieldException.class, () -> req.getClass().getDeclaredField("confirmedStopped"));
        var method = DataAccessController.class.getMethod("groupRecoverCommand", req.getClass());
        assertEquals("@ss.hasPermission('dataforge:access:resolve')",
                method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        var coordinator = mock(AccessRunCoordinator.class); var controller = new DataAccessController();
        ReflectionTestUtils.setField(controller, "runCoordinator", coordinator);
        var receipt = new RunReceipt(2L, false, 5, false);
        when(coordinator.recoverGroups(2L, "recover-1", 3L, req.getReason(), req.getEvidenceRef())).thenReturn(receipt);
        assertSame(receipt, controller.groupRecoverCommand(req).getData());
    }

    @Test void controllerReturnsDurableReceiptAndKeepsLegacyEndpoints() throws Exception {
        var req = request("AccessRunCommandReqVO");
        ReflectionTestUtils.setField(req, "taskId", 1L);
        ReflectionTestUtils.setField(req, "requestId", "stable-1");
        var coordinator = mock(AccessRunCoordinator.class);
        var receipt = new RunReceipt(2L, false, 1, false);
        when(coordinator.enqueueManual(1L, "stable-1")).thenReturn(receipt);
        var controller = new DataAccessController();
        ReflectionTestUtils.setField(controller, "runCoordinator", coordinator);
        cn.iocoder.yudao.framework.common.pojo.CommonResult<?> response =
                ReflectionTestUtils.invokeMethod(controller, "runCommand", req);
        assertSame(receipt, response.getData());
        assertNotNull(DataAccessController.class.getMethod("runDataAccess", Long.class));
        assertNotNull(DataAccessController.class.getMethod("stopDataAccess", Long.class));
    }
}
