package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.etl.core.engine.EtlPreviewEngine;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.EtlPreviewProperties;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlPreviewSessionServiceTest {
    EtlDesignService design; ExecutionAccessService access; EtlPreviewSessionService service;
    MockedStatic<SecurityFrameworkUtils> security; AtomicLong actor;
    long time = 1_800_000_000_000L;
    @BeforeEach void setup() {
        actor = new AtomicLong(7); security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenAnswer(call -> actor.get());
        TenantContextHolder.setTenantId(1L);
        design = mock(EtlDesignService.class); access = mock(ExecutionAccessService.class);
        service = new EtlPreviewSessionService(design, access, new EtlPreviewProperties());
        clock(time);
        doAnswer(call -> {
            ((EtlPreviewResultGuard) call.getArgument(3)).currentAccessCheck(() -> {});
            return success();
        }).when(design).preview(any(), anyLong(), any(), any());
    }
    @AfterEach void close() { service.close(); security.close(); TenantContextHolder.clear(); }
    @Test void creatorOwnershipAndBootIdentityApplyToEveryReadAndCancel() throws Exception {
        var request = request(); service.create(request); waitFor(() -> service.get(key(request)).workerExited());
        assertEquals("SUCCEEDED", service.get(key(request)).status());
        actor.set(8); assertThrows(ServiceException.class, () -> service.get(key(request)));
        assertEquals("CANCELLED", service.cancel(key(request)).status()); // Own empty key cannot affect another owner's session.
        actor.set(7); assertEquals("SUCCEEDED", service.get(key(request)).status());
        TenantContextHolder.setTenantId(1L); var wrong = key(request); wrong.setInstanceId(UUID.randomUUID().toString());
        assertThrows(ServiceException.class, () -> service.get(wrong));
    }
    @Test void duplicateCreationReplaysSameReceiptAndPayloadConflictDoesNotExecuteAgain() throws Exception {
        var request = request(); var first = service.create(request); waitFor(() -> service.get(key(request)).workerExited());
        assertEquals(first.requestId(), service.create(request).requestId());
        request.setLimit(2); assertThrows(ServiceException.class, () -> service.create(request));
        verify(design, times(1)).preview(any(), eq(7L), any(), any());
    }
    @Test void cancellationThatOvertakesCreationPreventsLateWorkFromStarting() {
        var request = request();
        assertEquals("CANCELLED", service.cancel(key(request)).status());
        assertEquals("CANCELLED", service.create(request).status());
        verifyNoInteractions(design);
    }
    @Test void cancellationReceiptsHaveCreatorAndPlatformQuotasWithoutBlockingExistingKeys() {
        var retained = request();
        for (long user = 7; user <= 10; user++) {
            actor.set(user);
            for (int i = 0; i < 8; i++) service.cancel(key(user == 7 && i == 0 ? retained : request()));
            assertThrows(ServiceException.class, () -> service.cancel(key(request())));
        }
        actor.set(7);
        assertEquals("CANCELLED", service.get(key(retained)).status());
        assertEquals("CANCELLED", service.cancel(key(retained)).status());
        assertEquals("CANCELLED", service.create(retained).status());
        actor.set(11);
        assertThrows(ServiceException.class, () -> service.cancel(key(request())));
        assertThrows(ServiceException.class, () -> service.cancel(key(request())));
        verifyNoInteractions(design);
    }
    @Test void cancellationWaitsForLateResourceExitAndHoldsTheCreatorSlot() throws Exception {
        AtomicBoolean resourceExit = new AtomicBoolean(); CountDownLatch registered = new CountDownLatch(1);
        doAnswer(call -> {
            ((EtlPreviewResultGuard) call.getArgument(3)).currentAccessCheck(() -> {});
            ((EtlExecutionControl) call.getArgument(2)).register(() -> {}, resourceExit::get); registered.countDown();
            return success();
        }).when(design).preview(any(), anyLong(), any(), any());
        var request = request(); service.create(request); assertTrue(registered.await(2, TimeUnit.SECONDS));
        waitFor(() -> service.get(key(request)).workerExited());
        assertEquals("CANCEL_REQUESTED", service.cancel(key(request)).status());
        clock(time + 6_000); service.maintenanceTick();
        var unconfirmed = service.get(key(request)); assertEquals("TERMINATION_UNCONFIRMED", unconfirmed.status());
        assertEquals("UNCONFIRMED", unconfirmed.cleanupStatus()); assertNull(unconfirmed.result());
        assertThrows(ServiceException.class, () -> service.create(request()));
        resourceExit.set(true); assertEquals("CANCELLED", service.get(key(request)).status());
        assertEquals("CONFIRMED", service.get(key(request)).cleanupStatus());
        assertDoesNotThrow(() -> service.create(request()));
    }
    @Test void lateResultAfterCancellationIsNeverReturned() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); AtomicBoolean finish = new AtomicBoolean();
        doAnswer(call -> {
            entered.countDown(); while (!finish.get()) { try { Thread.sleep(5); } catch (InterruptedException ignored) {} }
            return success();
        }).when(design).preview(any(), anyLong(), any(), any());
        var request = request(); service.create(request); assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals("CANCEL_REQUESTED", service.cancel(key(request)).status());
        finish.set(true); waitFor(() -> service.get(key(request)).workerExited());
        assertNull(service.get(key(request)).result()); assertEquals("CANCELLED", service.get(key(request)).status());
    }
    @Test void expiryClearsResultsAndOldCreationCannotResurrectAfterReceiptPurge() throws Exception {
        var request = request(); service.create(request); waitFor(() -> service.get(key(request)).workerExited());
        clock(time + 121_000); service.maintenanceTick();
        assertEquals("EXPIRED", service.get(key(request)).status()); assertNull(service.get(key(request)).result());
        clock(time + 181_000); service.maintenanceTick();
        assertThrows(ServiceException.class, () -> service.get(key(request)));
        assertThrows(ServiceException.class, () -> service.create(request));
        verify(design, times(1)).preview(any(), anyLong(), any(), any());
    }
    @Test void permissionChangeClearsCachedResultAndNeverFallsBackToOldRows() throws Exception {
        AtomicBoolean permitted = new AtomicBoolean(true);
        doAnswer(call -> {
            ((EtlPreviewResultGuard) call.getArgument(3)).currentAccessCheck(() -> { if (!permitted.get()) throw new SecurityException(); });
            return success();
        }).when(design).preview(any(), anyLong(), any(), any());
        var request = request(); service.create(request); waitFor(() -> service.get(key(request)).workerExited());
        permitted.set(false); assertThrows(ServiceException.class, () -> service.get(key(request)));
        permitted.set(true); assertEquals("RESULT_RESTRICTED", service.get(key(request)).status()); assertNull(service.get(key(request)).result());
    }
    @Test void pollingRetainsIndependentComparisonDataAndCancelClearsTheEntireResult() throws Exception {
        var request = request();
        assertNull(service.create(request).result());
        waitFor(() -> service.get(key(request)).workerExited());
        var result = service.get(key(request)).result();
        assertNotNull(result); assertEquals("INDEPENDENT_SAMPLES", result.comparisonMode());
        assertEquals(2, result.targetInputRows());
        assertEquals("upstream", result.inputSamples().getFirst().sourceNodeNo());
        assertEquals(List.of(Map.of("v", "safe-input")), result.inputSamples().getFirst().rows());
        assertTrue(result.inputSamples().getFirst().truncated());
        assertNull(service.cancel(key(request)).result()); assertNull(service.get(key(request)).result());
    }
    @Test void capabilityAndReadRequireCurrentRunAndQueryPermissions() {
        doThrow(new SecurityException()).when(access).requireUserPermission(7L, "dataforge:etl:run");
        assertThrows(SecurityException.class, service::capabilities); verifyNoInteractions(design);
    }
    private EtlPreviewSessionReqVO request() {
        var caps = service.capabilities(); var req = new EtlPreviewSessionReqVO(); req.setInstanceId(caps.instanceId());
        req.setIssuedAt(caps.issuedAt()); req.setRequestId(UUID.randomUUID().toString()); req.setTargetNodeId("s"); req.setLimit(10);
        req.setNodes(List.of(Map.of("id", "s", "properties", Map.of("toolCode", "generate-records", "toolType", 1)))); req.setEdges(List.of());
        return req;
    }
    private EtlPreviewSessionKeyReqVO key(EtlPreviewSessionReqVO request) { var key = new EtlPreviewSessionKeyReqVO(); key.setInstanceId(request.getInstanceId()); key.setRequestId(request.getRequestId()); return key; }
    private void clock(long millis) { ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)); }
    private static EtlPreviewEngine.Result success() {
        var input = new EtlPreviewEngine.InputSample("upstream", "upstream", "main", "in", List.of(),
                List.of(Map.of("v", "safe-input")), 2, true);
        return new EtlPreviewEngine.Result(true, List.of(), List.of(Map.of("v", "safe")), 2, 1, List.of(), false,
                Map.of("main", 1L), List.of("main"), UUID.randomUUID().toString(), List.of(), null,
                2, List.of(input), "INDEPENDENT_SAMPLES");
    }
    private static void waitFor(BooleanSupplier condition) throws InterruptedException { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5); assertTrue(condition.getAsBoolean()); }
}
