package cn.iocoder.yudao.module.bpm.api.task;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.bpm.framework.flowable.core.util.FlowableUtils;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BpmProcessInstanceTenantModeTest {

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void disabledModeQueriesWithoutTenantContext() {
        TenantContextHolder.clear();
        BpmProcessInstanceApiImpl api = new BpmProcessInstanceApiImpl();
        HistoryService history = mock(HistoryService.class);
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        ReflectionTestUtils.setField(api, "historyService", history);
        ReflectionTestUtils.setField(api, "tenantEnabled", false);
        when(history.createHistoricProcessInstanceQuery()).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        assertNull(api.getProcessInstanceIdByBusinessKey("master", "master-data:8").getCheckedData());
        assertNull(api.getProcessInstanceStatus("process-8").getCheckedData());
        verify(query, times(2)).processInstanceTenantId(ProcessEngineConfiguration.NO_TENANT_ID);
        verify(query).processInstanceBusinessKey("master-data:8");
    }

    @Test
    void enabledModeStillRequiresTenantContext() {
        TenantContextHolder.clear();
        assertThrows(NullPointerException.class, () -> FlowableUtils.getRequiredTenantId(true));
        TenantContextHolder.setTenantId(7L);
        assertEquals("7", FlowableUtils.getRequiredTenantId(true));
        assertEquals(ProcessEngineConfiguration.NO_TENANT_ID, FlowableUtils.getRequiredTenantId(false));
    }
}
