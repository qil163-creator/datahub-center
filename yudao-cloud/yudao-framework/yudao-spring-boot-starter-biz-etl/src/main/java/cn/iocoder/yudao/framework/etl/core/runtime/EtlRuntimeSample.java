package cn.iocoder.yudao.framework.etl.core.runtime;

import java.util.List;

/**
 * 运行观测快照：节点实时读写行数、连线队列与等待时间，以及有界的累计传输趋势。
 *
 * 计数是采样时刻的近似值，终态以节点终态回调为准；传输行数按节点产生行累计，与目标提交数无关。
 * series 最多保留 {@code MAX_POINTS} 个点并覆盖整个运行期，点间隔可能不均匀。
 *
 * @param elapsedMillis    自引擎启动节点起的毫秒数
 * @param finished         是否为流程结束后的最终快照
 * @param transferredRows  全部节点累计产生的行数
 * @param transferredBytes 全部节点累计产生行的估算字节
 * @param bufferedBytes    当前中间缓存估算字节
 */
public record EtlRuntimeSample(long elapsedMillis, boolean finished, long transferredRows, long transferredBytes,
                               long bufferedBytes, List<Node> nodes, List<Edge> edges, List<Point> series) {

    public static final int MAX_POINTS = 120;

    public static final int MAX_EDGES = 256;

    public record Node(String nodeNo, long readCount, long writeCount) {

    }

    /**
     * @param queued        采样时刻排队包数
     * @param delivered     已投递的数据行数
     * @param blockedMillis 上游因队列已满等待的累计时间，即背压
     * @param starvedMillis 下游因队列为空等待的累计时间
     */
    public record Edge(String sourceNo, String targetNo, int queued, int capacity, long delivered,
                       long blockedMillis, long starvedMillis) {

    }

    public record Point(long elapsedMillis, long transferredRows, long transferredBytes, long bufferedBytes) {

    }

}
