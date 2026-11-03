package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlNodeRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlNodeRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EtlNodeDiagnosticsTest {
    @Test
    void terminalMetricsUseExecutionFenceAndPersistPartialCounts() {
        EtlPipelineExecutor executor = new EtlPipelineExecutor();
        EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
        EtlNodeRecordMapper nodes = mock(EtlNodeRecordMapper.class);
        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(executor, "nodeRecordMapper", nodes);
        when(coordinator.writeOwned(eq(42L), eq("owner"), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(2).run(); return true;
        });
        try {
            EtlRunListener listener = ReflectionTestUtils.invokeMethod(executor, "buildListener", 42L, "owner",
                    new StringBuilder(), Map.of("branch", new StringBuilder()), Map.of("branch", 77L));
            assertNotNull(listener);
            listener.onNodeMetrics("branch", 3, 2, Map.of("pass", 1L, "reject", 1L));
            var capture = org.mockito.ArgumentCaptor.forClass(EtlNodeRecordDO.class);
            verify(nodes).updateById(capture.capture());
            assertEquals(77L, capture.getValue().getId());
            assertEquals(3L, capture.getValue().getReadCount());
            assertEquals(2L, capture.getValue().getWriteCount());
            assertEquals(1L, JSONUtil.parseObj(capture.getValue().getPortCountsJson()).getLong("reject"));
            verify(coordinator).writeOwned(eq(42L), eq("owner"), any());
        } finally { executor.destroy(); }
    }

    @Test
    void lostExecutionOwnershipCannotWriteMetrics() {
        EtlPipelineExecutor executor = new EtlPipelineExecutor();
        EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
        EtlNodeRecordMapper nodes = mock(EtlNodeRecordMapper.class);
        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(executor, "nodeRecordMapper", nodes);
        try {
            EtlRunListener listener = ReflectionTestUtils.invokeMethod(executor, "buildListener", 42L, "stale",
                    new StringBuilder(), Map.of(), Map.of("branch", 77L));
            assertNotNull(listener);
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> listener.onNodeMetrics("branch", 0, 1, Map.of("pass", 1L)));
            verifyNoInteractions(nodes);
        } finally { executor.destroy(); }
    }

    @Test
    void nodeResponseDistinguishesHistoricalUnknownAndEmptyPortsAndKeepsSubflowIdentity() {
        var service = new EtlPipelineServiceImpl();
        var records = mock(EtlRecordMapper.class);
        var nodes = mock(EtlNodeRecordMapper.class);
        ReflectionTestUtils.setField(service, "recordMapper", records);
        ReflectionTestUtils.setField(service, "nodeRecordMapper", nodes);
        when(records.selectById(42L)).thenReturn(EtlRecordDO.builder().id(42L).configJson("""
                {"sourceMap":{"call:branch":{"callPath":"call","originalNodeNo":"branch","designNodeNo":"call"}}}
                """).build());
        when(nodes.selectListByRecordId(42L)).thenReturn(List.of(
                EtlNodeRecordDO.builder().nodeNo("old").build(),
                EtlNodeRecordDO.builder().nodeNo("empty").portCountsJson("{}").build(),
                EtlNodeRecordDO.builder().nodeNo("call:branch").portCountsJson("{\"reject\":9007199254740993}")
                        .fileOutputState("DISCARDED").build()));
        var response = service.getNodeRecords(42L);
        assertNull(response.get(0).getPortCounts());
        assertEquals(Map.of(), response.get(1).getPortCounts());
        assertEquals(9_007_199_254_740_993L, response.get(2).getPortCounts().get("reject"));
        assertEquals("call", response.get(2).getDesignNodeNo());
        assertEquals("branch", response.get(2).getOriginalNodeNo());
        assertEquals("DISCARDED", response.get(2).getFileOutputState());
        response.get(2).setId(9_007_199_254_740_993L);
        response.get(2).setStartTime(java.time.LocalDateTime.of(2026, 10, 7, 12, 0));
        var mapper = tools.jackson.databind.json.JsonMapper.builder()
                .addModule(new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration()
                        .timestampSupportModuleBean()).build();
        var wire = mapper.readTree(mapper.writeValueAsBytes(response.get(2)));
        assertTrue(wire.get("portCounts").get("reject").isString());
        assertEquals("9007199254740993", wire.get("portCounts").get("reject").asString());
        assertEquals("9007199254740993", wire.get("id").asString());
        assertTrue(wire.get("startTime").isNumber());
    }
}
