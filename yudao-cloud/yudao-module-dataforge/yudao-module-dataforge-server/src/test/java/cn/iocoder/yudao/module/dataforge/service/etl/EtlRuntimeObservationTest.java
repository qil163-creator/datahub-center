package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlRuntimeObservationTest {

    private static final EtlRuntimeSample SAMPLE = new EtlRuntimeSample(6000, false, 1500, 96_000, 0,
            List.of(new EtlRuntimeSample.Node("source", 0, 1500), new EtlRuntimeSample.Node("sink", 500, 0)),
            List.of(new EtlRuntimeSample.Edge("source", "sink", 1000, 1000, 1500, 4200, 3)),
            List.of(new EtlRuntimeSample.Point(3000, 900, 57_600, 0), new EtlRuntimeSample.Point(6000, 1500, 96_000, 0)));

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void samplesAreWrittenInsideTheExecutionFenceOnlyForTheOwningActiveRun() {
        EtlFileSourceProofTest.initializeRecordTable();
        EtlPipelineExecutor executor = new EtlPipelineExecutor();
        EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
        EtlRecordMapper records = mock(EtlRecordMapper.class);
        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(executor, "recordMapper", records);
        when(coordinator.writeOwned(eq(42L), eq("owner"), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(2).run(); return true;
        });
        try {
            EtlRunListener listener = ReflectionTestUtils.invokeMethod(executor, "buildListener", 42L, "owner",
                    new StringBuilder(), Map.of(), Map.of());
            assertNotNull(listener);
            listener.onRuntimeSample(SAMPLE);
            ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(records).update(isNull(), captor.capture());
            LambdaUpdateWrapper<EtlRecordDO> wrapper = captor.getValue();
            assertTrue(wrapper.getSqlSet().contains("runtime_metrics_json"), wrapper.getSqlSet());
            assertTrue(wrapper.getSqlSet().contains("JsonbStringTypeHandler"), wrapper.getSqlSet());
            String where = wrapper.getCustomSqlSegment();
            assertTrue(where.contains("execution_token") && where.contains("run_status"), where);
            var values = wrapper.getParamNameValuePairs().values();
            assertTrue(values.contains("owner"));
            assertTrue(values.containsAll(List.of(EtlRecordDO.RUN_STATUS_WAITING, EtlRecordDO.RUN_STATUS_RUNNING)));
            String json = values.stream().filter(value -> value instanceof String text && text.startsWith("{"))
                    .map(String.class::cast).findFirst().orElseThrow();
            assertEquals(SAMPLE, JsonUtils.parseObject(json, EtlRuntimeSample.class));
            verify(coordinator).writeOwned(eq(42L), eq("owner"), any());
        } finally {
            executor.destroy();
        }
    }

    @Test
    void lostExecutionOwnershipNeverWritesTheSample() {
        EtlPipelineExecutor executor = new EtlPipelineExecutor();
        EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
        EtlRecordMapper records = mock(EtlRecordMapper.class);
        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(executor, "recordMapper", records);
        try {
            EtlRunListener listener = ReflectionTestUtils.invokeMethod(executor, "buildListener", 42L, "stale",
                    new StringBuilder(), Map.of(), Map.of());
            assertNotNull(listener);
            assertThrows(java.util.concurrent.CancellationException.class, () -> listener.onRuntimeSample(SAMPLE));
            verifyNoInteractions(records);
        } finally {
            executor.destroy();
        }
    }

    @Test
    void recordLogReturnsSnapshotWithNumericWireCountsAndTreatsMissingOrCorruptJsonAsUnrecorded() throws Exception {
        var service = new EtlPipelineServiceImpl();
        var records = mock(EtlRecordMapper.class);
        ReflectionTestUtils.setField(service, "recordMapper", records);
        when(records.selectById(42L)).thenReturn(EtlRecordDO.builder().id(42L)
                .runStatus(EtlRecordDO.RUN_STATUS_RUNNING).build());
        when(records.selectRuntimeMetricsJson(42L)).thenReturn(JsonUtils.toJsonString(SAMPLE));
        var vo = service.getRecordLog(42L);
        assertEquals(SAMPLE, vo.getRuntimeMetrics());
        assertFalse(vo.getEnd());
        var mapper = tools.jackson.databind.json.JsonMapper.builder()
                .addModule(new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration()
                        .timestampSupportModuleBean()).build();
        var wire = mapper.readTree(mapper.writeValueAsBytes(vo)).get("runtimeMetrics");
        assertTrue(wire.get("edges").get(0).get("blockedMillis").isNumber());
        assertEquals(4200, wire.get("edges").get(0).get("blockedMillis").asLong());
        assertEquals("sink", wire.get("edges").get(0).get("targetNo").asString());
        assertEquals(2, wire.get("series").size());
        assertFalse(wire.get("finished").asBoolean());

        when(records.selectRuntimeMetricsJson(42L)).thenReturn("{\"nodes\":\"not-an-array\"");
        assertNull(service.getRecordLog(42L).getRuntimeMetrics());
        when(records.selectRuntimeMetricsJson(42L)).thenReturn(null);
        assertNull(service.getRecordLog(42L).getRuntimeMetrics());
    }

}
