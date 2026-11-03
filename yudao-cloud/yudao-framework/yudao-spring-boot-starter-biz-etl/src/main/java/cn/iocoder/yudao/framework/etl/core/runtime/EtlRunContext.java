package cn.iocoder.yudao.framework.etl.core.runtime;

import cn.iocoder.yudao.framework.etl.core.connection.EtlApiPort;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionResolver;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次流程执行的运行时上下文：数据通道、节点结果、停止标记
 *
 * @author Li
 */
@Getter
public class EtlRunContext {

    private final cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime databaseRuntime;

    private cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits databaseBatchLimits =
            cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits.defaults();

    private final Map<String, cn.iocoder.yudao.framework.dbswitch.data.domain.TableBatchProgress> databaseProgress = new ConcurrentHashMap<>();

    private final Map<String, cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary> databaseCommits = new ConcurrentHashMap<>();

    private final EtlDag dag;

    private final EtlConnectionResolver connectionResolver;

    private final EtlRunListener listener;

    private final EtlVariableContext variables;

    @Setter
    private cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner scriptRunner =
            new cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner(
                    cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.disabled());

    /**
     * API 数据端口（Rest API 输入节点用，可空）
     */
    @Setter
    private EtlApiPort apiPort;

    @Setter
    private cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort rowPolicyPort;

    @Setter
    private cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort readPolicyPort;

    @lombok.Getter
    @lombok.Setter
    private cn.iocoder.yudao.framework.etl.core.connection.EtlStagedOutputPort stagedOutputPort;

    @Setter
    private EtlRowObserver rowObserver;

    @Setter
    private EtlFileOutputs fileOutputs = new EtlFileOutputs(EtlFileOutputPolicy.disabled());

    /** 键：sourceNo->targetNo */
    private final Map<String, DataChannel> channels = new ConcurrentHashMap<>();

    /** 节点完成 Future：true 表示成功 */
    private final Map<String, CompletableFuture<Boolean>> nodeFutures = new ConcurrentHashMap<>();

    private final Map<String, Map<String, Long>> nodePortCounts = new ConcurrentHashMap<>();

    private volatile boolean terminationUnconfirmed;

    /**
     * 设置终止未确认。
     */
    public void markTerminationUnconfirmed() {
        terminationUnconfirmed = true;
    }

    private volatile boolean stopRequested = false;

    private final java.util.concurrent.atomic.AtomicReference<String> firstFailure = new java.util.concurrent.atomic.AtomicReference<>();

    private final java.util.concurrent.atomic.AtomicLong transferredRows = new java.util.concurrent.atomic.AtomicLong();

    private final java.util.concurrent.atomic.AtomicLong transferredBytes = new java.util.concurrent.atomic.AtomicLong();

    private final java.util.concurrent.atomic.AtomicLong bufferedBytes = new java.util.concurrent.atomic.AtomicLong();

    private final java.util.concurrent.atomic.AtomicLong peakBufferedBytes = new java.util.concurrent.atomic.AtomicLong();

    private cn.iocoder.yudao.framework.etl.core.engine.EtlRunOptions limits;

    /**
     * 设置资源限制。
     */
    public void setLimits(cn.iocoder.yudao.framework.etl.core.engine.EtlRunOptions value) {
        limits = java.util.Objects.requireNonNull(value);
        var requested = java.util.Objects.requireNonNull(value.getDatabaseBatchLimits());
        long bytes = Math.min(requested.maxBatchBytes(),
                Math.min(value.getMaxBytes(), databaseRuntime.options().maxCacheBytes()));
        databaseBatchLimits = new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(
                requested.maxRows(), bytes, Math.min(requested.maxCellBytes(), Math.min(bytes, value.getMaxRowBytes())),
                requested.maxBindParameters(), requested.maxBatchAgeMillis());
    }

    /** 共享中间缓存预算；派生结果和索引在保留前申请。 */
    void reserveBufferedBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("缓存字节数无效");
        }
        long maximum = limits == null ? 64L * 1024 * 1024 : limits.getMaxBytes();
        while (true) {
            long current = bufferedBytes.get();
            if (bytes > maximum - current) {
                throw new IllegalStateException("ETL 中间缓存超过字节预算");
            }
            if (bufferedBytes.compareAndSet(current, current + bytes)) {
                peakBufferedBytes.accumulateAndGet(current + bytes, Math::max);
                return;
            }
        }
    }

    /**
     * 释放已缓冲字节数。
     */
    void releaseBufferedBytes(long bytes) {
        bufferedBytes.addAndGet(-bytes);
    }

    /** 累计传输预算也约束排序/聚合所能接收的数据，避免只有队列有界。 */
    public void accountRow(Map<String, Object> row) {
        if (limits == null) {
            return;
        }
        long bytes = estimateRowBytes(row);
        if (bytes > limits.getMaxRowBytes()
                || transferredRows.incrementAndGet() > limits.getMaxRows()
                || transferredBytes.addAndGet(bytes) > limits.getMaxBytes()) {
            throw new IllegalStateException("流程超过轻量 ETL 的行数或字节预算，请缩小输入或使用外部计算引擎");
        }
    }

    /**
     * 根据字段名和值的结构估算当前数据行内存字节数。
     */
    public static long estimateRowBytes(Map<String, Object> row) {
        long bytes = 64;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            bytes += 64L + entry.getKey().length() * 2L;
            Object value = entry.getValue();
            bytes += value instanceof byte[] data ? data.length
                    : value == null ? 0 : String.valueOf(value).length() * 2L + 32;
        }
        return bytes;
    }

    /**
     * 将数据行来源信息的估算大小计入整体字节预算。
     */
    public void accountOrigin(EtlRowOrigin origin) {
        if (origin != null && limits != null && transferredBytes.addAndGet(
                origin.estimatedBytes()) > limits.getMaxBytes()) {
            throw new IllegalStateException("流程来源证明超过字节预算");
        }
    }

    /**
     * 标记当前操作失败并调用其失败处理逻辑。
     */
    public void fail(String message) {
        firstFailure.compareAndSet(null, message);
        requestStop();
    }

    /**
     * 返回当前失败状态中的消息摘要。
     */
    public String failureMessage() {
        return firstFailure.get();
    }

    /**
     * 使用给定参数初始化 {@code EtlRunContext}。
     */
    public EtlRunContext(EtlDag dag, EtlConnectionResolver connectionResolver,
            EtlRunListener listener, EtlVariableContext variables) {
        this(dag, connectionResolver, listener, variables,
                cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone());
    }

    /**
     * 校验构造参数并初始化 {@code EtlRunContext}。
     */
    public EtlRunContext(EtlDag dag, EtlConnectionResolver connectionResolver,
            EtlRunListener listener, EtlVariableContext variables,
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime databaseRuntime) {
        this.databaseRuntime = java.util.Objects.requireNonNull(databaseRuntime);
        this.dag = dag;
        this.connectionResolver = connectionResolver;
        this.listener = listener;
        this.variables = variables;
        // 为每条数据边创建通道，为每个节点创建完成 Future
        dag.getNodes().forEach(node -> {
            nodeFutures.put(node.getNo(), new CompletableFuture<>());
            for (String target : dag.getAllDataDownstreams(node.getNo())) {
                channels.put(channelKey(node.getNo(), target), new DataChannel());
            }
        });
    }

    /**
     * 根据源节点和目标节点编号生成通道标识。
     */
    public static String channelKey(String sourceNo, String targetNo) {
        return sourceNo + "->" + targetNo;
    }

    /**
     * 返回通道。
     */
    public DataChannel getChannel(String sourceNo, String targetNo) {
        return channels.get(channelKey(sourceNo, targetNo));
    }

    /**
     * 取得指定节点对应的异步执行结果。
     */
    public CompletableFuture<Boolean> getNodeFuture(String nodeNo) {
        return nodeFutures.get(nodeNo);
    }

    /**
     * 设置停止已请求。
     */
    public void requestStop() {
        this.stopRequested = true;
    }

    /**
     * 判断停止已请求。
     */
    public boolean isStopRequested() {
        return stopRequested || Thread.currentThread().isInterrupted();
    }

    /**
     * 指定输出端口的下游通道列表
     */
    public List<DataChannel> getDownstreamChannels(String nodeNo, String outputPort) {
        return dag.getDataDownstreams(nodeNo, outputPort).stream()
                .map(target -> getChannel(nodeNo, target))
                .toList();
    }

    /**
     * 全部下游通道（所有端口）
     */
    public List<DataChannel> getAllDownstreamChannels(String nodeNo) {
        return dag.getAllDataDownstreams(nodeNo).stream()
                .map(target -> getChannel(nodeNo, target))
                .toList();
    }

}
