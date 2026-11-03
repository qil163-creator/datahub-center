package cn.iocoder.yudao.framework.dbswitch.data.query;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** 固定维度的操作指标：租户、SQL、URL 和执行标识都不能作为指标标签。 */
public final class QueryMetrics {

    public enum Phase {QUEUE, CONNECT, METADATA, EXECUTE, FETCH, CANCEL_TO_EXIT, CLEANUP}

    public enum Outcome {SUCCESS, FAILURE}

    public record Snapshot(long count, long nanos) {

    }

    private final Map<Phase, Map<Outcome, Counter>> counters = new EnumMap<>(Phase.class);

    private final LongAdder rejected = new LongAdder();

    /**
     * 初始化 {@code QueryMetrics}。
     */
    public QueryMetrics() {
        for (Phase phase : Phase.values()) {
            var outcomes = new EnumMap<Outcome, Counter>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                outcomes.put(outcome, new Counter());
            }
            counters.put(phase, outcomes);
        }
    }

    /**
     * 记录当前操作的状态或结果。
     */
    void record(Phase phase, boolean success, long started) {
        if (phase == null) {
            return;
        }
        var counter = counters.get(phase).get(success ? Outcome.SUCCESS : Outcome.FAILURE);
        counter.nanos.add(Math.max(0, System.nanoTime() - started));
        counter.count.increment();
    }

    /**
     * 累加当前查询准入拒绝计数。
     */
    void rejected() {
        rejected.increment();
    }

    /**
     * 返回当前查询准入拒绝次数。
     */
    public long rejectedAdmissions() {
        return rejected.sum();
    }

    /**
     * 返回快照。
     */
    public Snapshot snapshot(Phase phase, Outcome outcome) {
        var counter = counters.get(phase).get(outcome);
        return new Snapshot(counter.count.sum(), counter.nanos.sum());
    }

    private static final class Counter {

        final LongAdder count = new LongAdder(), nanos = new LongAdder();
    }
}
