package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.governance.job.master.MasterDistributeAlertJob;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterDistributeAlertJobTest {
    private MasterDistributeAlertJob job;
    private MasterDistributeAlertMapper mapper;
    private MasterDistributeAlertService service;
    private MasterDistributeRunCoordinator coordinator;
    private XxlJobContext context;

    @BeforeEach void setup() {
        job = new MasterDistributeAlertJob();
        mapper = mock(MasterDistributeAlertMapper.class);
        service = mock(MasterDistributeAlertService.class);
        coordinator = mock(MasterDistributeRunCoordinator.class);
        ReflectionTestUtils.setField(job, "alerts", mapper);
        ReflectionTestUtils.setField(job, "service", service);
        ReflectionTestUtils.setField(job, "coordinator", coordinator);
        context = new XxlJobContext(7, "", "77.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);
        TenantContextHolder.clear();
    }

    @AfterEach void cleanup() {
        TenantContextHolder.clear();
        XxlJobContext.setXxlJobContext(null);
    }

    @Test void relayWithoutTenantContextContinuesAfterOneFailure() {
        var one = new MasterDistributeAlertDO(); one.setId(1L);
        var two = new MasterDistributeAlertDO(); two.setId(2L);
        when(mapper.candidates()).thenReturn(List.of(one, two));
        when(service.deliver(1L)).thenThrow(new IllegalStateException());
        when(service.deliver(2L)).thenReturn(true);
        job.execute();
        verify(service).deliver(2L);
        assertEquals(500, context.getHandleCode());
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test void deadLettersMakeSchedulerReportFailureEvenWithNoCandidates() {
        when(mapper.deadCount()).thenReturn(1L);
        job.execute();
        assertEquals(500, context.getHandleCode());
    }

    @Test void manualDispositionRequiresOnlyBusinessParametersAndControlPlaneIdentity() {
        context = new XxlJobContext(7,
                "{\"alertId\":\"9007199254740993\",\"action\":\"RETRY\",\"reason\":\"CASE-1\"}", "77.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);
        job.dispose();
        verify(service).dispose(9007199254740993L, "RETRY", "CASE-1", "xxl-job:7:77");
        assertEquals(200, context.getHandleCode());
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test void missingControlPlaneIdentityRejectsManualRequest() {
        context = new XxlJobContext(0, "{\"alertId\":1,\"action\":\"RETRY\",\"reason\":\"CASE-1\"}", "", 0, 1);
        XxlJobContext.setXxlJobContext(context);
        job.dispose();
        verifyNoInteractions(service);
        assertEquals(500, context.getHandleCode());
    }

    @Test void historicalBackfillContinuesAfterOneFailure() {
        var one = new MasterDistributeLogDO(); one.setId(1L);
        var two = new MasterDistributeLogDO(); two.setId(2L);
        when(mapper.missingTerminal()).thenReturn(List.of(one, two));
        doThrow(new IllegalStateException()).when(coordinator).reconcileAlert(one);
        job.execute();
        verify(coordinator).reconcileAlert(two);
        assertEquals(500, context.getHandleCode());
    }

    @Test void cleanupExecutesOneBoundedGlobalBatch() {
        when(mapper.cleanDelivered()).thenReturn(100);
        job.clean();
        verify(mapper, times(1)).cleanDelivered();
        assertEquals(200, context.getHandleCode());
    }

    @Test void healthyEmptyScanReportsSuccess() {
        job.execute();
        assertEquals(200, context.getHandleCode());
        verifyNoInteractions(service, coordinator);
    }
}