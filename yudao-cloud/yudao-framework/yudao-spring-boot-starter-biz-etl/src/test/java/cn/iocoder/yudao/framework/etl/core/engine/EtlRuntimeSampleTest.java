package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.DataChannel;
import cn.iocoder.yudao.framework.etl.core.runtime.DataPacket;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.etl.core.engine.EtlGovernanceNodeTest.edge;
import static cn.iocoder.yudao.framework.etl.core.engine.EtlGovernanceNodeTest.node;
import static org.junit.jupiter.api.Assertions.*;

class EtlRuntimeSampleTest {

    @Test
    void channelMeasuresDeliveredRowsBackpressureAndStarvation() throws Exception {
        DataChannel channel = new DataChannel(2);
        assertTrue(channel.push(DataPacket.data(Map.of("id", 1)), 10));
        assertTrue(channel.push(DataPacket.data(Map.of("id", 2)), 10));
        assertEquals(2, channel.queued()); assertEquals(2, channel.capacity());
        assertEquals(0, channel.blockedMillis(), "未满时不计背压");
        assertFalse(channel.push(DataPacket.data(Map.of("id", 3)), 40));
        assertTrue(channel.blockedMillis() >= 30, "blocked=" + channel.blockedMillis());
        assertEquals(2, channel.delivered(), "写入超时的行不计入投递");
        assertNotNull(channel.poll(10)); assertNotNull(channel.poll(10));
        assertEquals(0, channel.starvedMillis(), "有数据时不计下游等待");
        assertNull(channel.poll(40));
        assertTrue(channel.starvedMillis() >= 30, "starved=" + channel.starvedMillis());
        assertTrue(channel.push(DataPacket.end(), 10));
        assertEquals(2, channel.delivered(), "结束包不计入投递行数");
        assertThrows(IllegalArgumentException.class, () -> new DataChannel(0));
    }

    @Test
    void seriesStaysBoundedAndKeepsFirstLastAndOrder() {
        var series = new EtlRuntimeSampler.Series(10);
        for (int i = 0; i <= 1000; i++) series.append(new EtlRuntimeSample.Point(i * 100L, i, i * 10L, 0));
        var points = series.points();
        assertTrue(points.size() <= 10, "size=" + points.size());
        assertEquals(0, points.getFirst().elapsedMillis());
        assertEquals(100_000, points.getLast().elapsedMillis());
        for (int i = 1; i < points.size(); i++)
            assertTrue(points.get(i).elapsedMillis() > points.get(i - 1).elapsedMillis());
        assertThrows(IllegalArgumentException.class, () -> new EtlRuntimeSampler.Series(2));
    }

    @Test
    void periodicAndFinalSamplesExposeLiveCountsAndDownstreamBackpressure() {
        var registry = new EtlExecutorRegistry();
        registry.register("slow-sink", SlowSink::new);
        var dag = EtlDag.build(List.of(source(1500), node("sink", "slow-sink", 3, "{}")),
                List.of(edge("source", "sink", null)));
        List<EtlRuntimeSample> samples = new CopyOnWriteArrayList<>();
        EtlRunResult result;
        try (var engine = new EtlPipelineEngine(registry)) {
            result = engine.execute(dag, EtlRunOptions.builder().runtimeSampleMillis(500).listener(new EtlRunListener() {
                @Override
                public void onRuntimeSample(EtlRuntimeSample sample) { samples.add(sample); }
            }).build());
        }
        assertTrue(result.isSuccess(), result.getFirstError());
        assertTrue(samples.size() >= 2, "samples=" + samples.size());
        assertTrue(samples.subList(0, samples.size() - 1).stream().noneMatch(EtlRuntimeSample::finished));
        var last = samples.getLast();
        assertTrue(last.finished());
        assertEquals(Map.of("source", List.of(0L, 1500L), "sink", List.of(1500L, 0L)), last.nodes().stream().collect(
                Collectors.toMap(EtlRuntimeSample.Node::nodeNo, n -> List.of(n.readCount(), n.writeCount()))));
        assertEquals(1, last.edges().size());
        var edge = last.edges().getFirst();
        assertEquals("source", edge.sourceNo()); assertEquals("sink", edge.targetNo());
        assertEquals(1500, edge.delivered()); assertEquals(0, edge.queued()); assertEquals(1000, edge.capacity());
        assertTrue(edge.blockedMillis() > 0, "下游慢于上游时应记录上游等待");
        assertEquals(result.getTransferredRows(), last.transferredRows());
        assertEquals(last.transferredRows(), last.series().getLast().transferredRows());
        assertTrue(last.series().size() >= 2 && last.series().size() <= EtlRuntimeSample.MAX_POINTS);
        for (int i = 1; i < samples.size(); i++)
            assertTrue(samples.get(i).elapsedMillis() >= samples.get(i - 1).elapsedMillis());
    }

    @Test
    void failingObservationListenerDoesNotChangeTheRunResult() {
        AtomicInteger calls = new AtomicInteger();
        EtlRunResult result;
        try (var engine = new EtlPipelineEngine(new EtlExecutorRegistry())) {
            result = engine.execute(EtlDag.build(List.of(source(3)), List.of()), EtlRunOptions.builder()
                    .runtimeSampleMillis(500).listener(new EtlRunListener() {
                        @Override
                        public void onRuntimeSample(EtlRuntimeSample sample) {
                            calls.incrementAndGet();
                            throw new IllegalStateException("observation store unavailable");
                        }
                    }).build());
        }
        assertTrue(result.isSuccess(), result.getFirstError());
        assertTrue(calls.get() >= 1, "最终快照仍应回调一次");
    }

    @Test
    void samplingIsOffByDefaultAndRejectsUnsafeIntervals() {
        var dag = EtlDag.build(List.of(source(3)), List.of());
        AtomicInteger calls = new AtomicInteger();
        var listener = new EtlRunListener() {
            @Override
            public void onRuntimeSample(EtlRuntimeSample sample) { calls.incrementAndGet(); }
        };
        try (var engine = new EtlPipelineEngine(new EtlExecutorRegistry())) {
            assertTrue(engine.execute(dag, EtlRunOptions.builder().listener(listener).build()).isSuccess());
            assertEquals(0, calls.get());
            for (long interval : List.of(-1L, 1L, 499L, 60_001L))
                assertThrows(IllegalArgumentException.class, () -> engine.execute(dag,
                        EtlRunOptions.builder().runtimeSampleMillis(interval).listener(listener).build()), "interval=" + interval);
            assertTrue(engine.execute(dag, EtlRunOptions.builder().listener(listener).build()).isSuccess(),
                    "拒绝无效间隔不能占用运行名额");
        }
    }

    private static EtlNodeDef source(int count) {
        return node("source", "generate-records", 1,
                "{\"count\":" + count + ",\"fields\":[{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"7\"}]}");
    }

    /** 每行自旋约 1 毫秒的慢速下游，不依赖平台的 sleep 精度。 */
    static final class SlowSink extends AbstractEtlNodeExecutor {
        SlowSink(EtlNodeDef node, EtlRunContext context) { super(node, context); }

        @Override
        protected void execute() throws Exception {
            while (readNextRow() != null) {
                long until = System.nanoTime() + 1_000_000;
                while (System.nanoTime() < until) Thread.onSpinWait();
            }
        }
    }

}
