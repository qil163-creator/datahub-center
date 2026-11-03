package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.*;
import cn.iocoder.yudao.module.dataforge.job.push.DataPushAlertJob;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataPushAlertJobTest {
    private DataPushAlertJob job;
    private DataPushAlertMapper mapper;
    private DataPushAlertService service;
    private DataPushRunCoordinator coordinator;
    private XxlJobContext context;

    @BeforeEach void setup() {
        job=new DataPushAlertJob(); mapper=mock(DataPushAlertMapper.class); service=mock(DataPushAlertService.class);
        coordinator=mock(DataPushRunCoordinator.class);
        ReflectionTestUtils.setField(job,"alerts",mapper); ReflectionTestUtils.setField(job,"service",service);
        ReflectionTestUtils.setField(job,"coordinator",coordinator);
        context=new XxlJobContext(7,"","77.log",0,1); XxlJobContext.setXxlJobContext(context);
        TenantContextHolder.setTenantId(99L); TenantContextHolder.setIgnore(true);
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); XxlJobContext.setXxlJobContext(null); }

    @Test void relayContinuesAfterOneRecordFails() {
        var one=new DataPushAlertDO(); one.setId(1L); var two=new DataPushAlertDO(); two.setId(2L); when(mapper.candidates()).thenAnswer(call->{assertTrue(TenantContextHolder.isIgnore());return List.of(one,two);});
        when(service.deliver(1L)).thenAnswer(call->{throw new IllegalStateException();});
        when(service.deliver(2L)).thenAnswer(call->{return true;});
        job.execute(); verify(service).deliver(2L); assertEquals(500,context.getHandleCode());
        assertEquals(99L,TenantContextHolder.getTenantId()); assertTrue(TenantContextHolder.isIgnore());
    }

    @Test void deadLettersMakeSchedulerReportFailureEvenWithNoCandidates() {
        when(mapper.deadCount()).thenReturn(1L); job.execute(); assertEquals(500,context.getHandleCode());
    }

    @Test void failedDeliveryReportsSchedulerFailure() {
        var row=new DataPushAlertDO(); row.setId(1L); when(mapper.candidates()).thenReturn(List.of(row)); job.execute(); verify(service).deliver(1L);
        assertEquals(500,context.getHandleCode());
    }

    @Test void manualDispositionUsesControlPlaneLogIdentity() {
        context=new XxlJobContext(7,"{\"alertId\":\"9007199254740993\",\"action\":\"RETRY\",\"reason\":\"CASE-1\"}","77.log",0,1);
        XxlJobContext.setXxlJobContext(context);
        doAnswer(call->{ return null;})
                .when(service).dispose(9007199254740993L,"RETRY","CASE-1","xxl-job:7:77");
        job.dispose(); verify(service).dispose(9007199254740993L,"RETRY","CASE-1","xxl-job:7:77");
        assertEquals(200,context.getHandleCode()); assertEquals(99L,TenantContextHolder.getTenantId());
    }

    @Test void missingControlPlaneIdentityRejectsManualRequest() {
        context=new XxlJobContext(0,"{\"alertId\":1,\"action\":\"RETRY\",\"reason\":\"CASE-1\"}","",0,1);
        XxlJobContext.setXxlJobContext(context); job.dispose(); verifyNoInteractions(service); assertEquals(500,context.getHandleCode());
    }

    @Test void staleRecoveryUsesDurableCoordinatorAndIsolatesFailure() {
        var ops=new DataPushOpsSupport(); var runs=mock(DataPushTaskLogMapper.class); var executor=mock(DataPushExecutor.class);
        var one=DataPushTaskLogDO.builder().id(1L).pushTaskId(10L).runStatus(2).build();var two=DataPushTaskLogDO.builder().id(2L).pushTaskId(20L).runStatus(2).build();when(runs.selectStaleRunning(10)).thenReturn(List.of(one,two));
        when(coordinator.recoverStale(one,10)).thenThrow(new IllegalStateException());
        when(coordinator.recoverStale(two,10)).thenAnswer(call->{return 1;});
        ReflectionTestUtils.setField(ops,"logMapper",runs); ReflectionTestUtils.setField(ops,"pushProperties",new DataPushProperties());
        ReflectionTestUtils.setField(ops,"runCoordinator",coordinator); ReflectionTestUtils.setField(ops,"pushExecutor",executor);
        ops.recoverStaleRunningLogs(); verify(executor).abortLocal(20L,2L); verify(executor,never()).abortLocal(10L,1L);
        verify(runs,never()).recoverStaleById(any(),anyInt());
    }
}
