package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.DataChannel;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 构造运行观测快照；只由引擎控制线程调用，不在节点线程上计时或加锁。 */
final class EtlRuntimeSampler {

    private final EtlRunContext context;

    private final List<AbstractEtlNodeExecutor> executors;

    private final long startedNanos;

    private final Series series = new Series(EtlRuntimeSample.MAX_POINTS);

    /**
     * 初始化 {@code EtlRuntimeSampler}。
     */
    EtlRuntimeSampler(EtlRunContext context, List<AbstractEtlNodeExecutor> executors, long startedNanos) {
        this.context = context;
        this.executors = List.copyOf(executors);
        this.startedNanos = startedNanos;
    }

    /**
     * 返回当前样本。
     */
    EtlRuntimeSample sample(boolean finished) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        long rows = context.getTransferredRows().get();
        long bytes = context.getTransferredBytes().get();
        long buffered = Math.max(0, context.getBufferedBytes().get());
        series.append(new EtlRuntimeSample.Point(elapsed, rows, bytes, buffered));
        List<EtlRuntimeSample.Node> nodes = executors.stream().map(executor -> new EtlRuntimeSample.Node(
                executor.getNode().getNo(), executor.currentReadCount(), executor.currentWriteCount())).toList();
        List<EtlRuntimeSample.Edge> edges = new ArrayList<>();
        for (EtlNodeDef node : context.getDag().getNodes()) {
            for (String target : context.getDag().getAllDataDownstreams(node.getNo())) {
                DataChannel channel = context.getChannel(node.getNo(), target);
                if (channel == null || edges.size() >= EtlRuntimeSample.MAX_EDGES) {
                    continue;
                }
                edges.add(new EtlRuntimeSample.Edge(node.getNo(), target, channel.queued(), channel.capacity(),
                        channel.delivered(), channel.blockedMillis(), channel.starvedMillis()));
            }
        }
        return new EtlRuntimeSample(elapsed, finished, rows, bytes, buffered, nodes, List.copyOf(edges),
                series.points());
    }

    /** 有界趋势序列：超出上限时保留首尾、其余隔一取一，使序列始终覆盖整个运行期。 */
    static final class Series {

        private final int maxPoints;

        private final List<EtlRuntimeSample.Point> points = new ArrayList<>();

        /**
         * 校验构造参数并初始化 {@code Series}。
         */
        Series(int maxPoints) {
            if (maxPoints < 3) {
                throw new IllegalArgumentException("趋势点上限至少为 3");
            }
            this.maxPoints = maxPoints;
        }

        /**
         * 将给定内容追加到当前缓冲区，并更新累计大小。
         */
        void append(EtlRuntimeSample.Point point) {
            points.add(point);
            if (points.size() <= maxPoints) {
                return;
            }
            List<EtlRuntimeSample.Point> kept = new ArrayList<>(maxPoints / 2 + 2);
            for (int i = 0; i < points.size() - 1; i += 2) {
                kept.add(points.get(i));
            }
            kept.add(points.getLast());
            points.clear();
            points.addAll(kept);
        }

        /**
         * 返回当前点集合。
         */
        List<EtlRuntimeSample.Point> points() {
            return List.copyOf(points);
        }
    }

}
