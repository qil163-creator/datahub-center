package cn.iocoder.yudao.framework.etl.core.executor;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.DataChannel;
import cn.iocoder.yudao.framework.etl.core.runtime.DataPacket;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ETL 节点执行器基类：统一生命周期（控制依赖等待 → 执行 → END/ERROR 传播 → 结果回调）
 *
 * 参考 srt-cloud 的 EtlEngine 模板体系，数据在节点间以行为单位流式传递
 *
 * @author Li
 */
@Slf4j
public abstract class AbstractEtlNodeExecutor {

    private static final long CHANNEL_WAIT_MILLIS = 100L;

    @Getter
    protected final EtlNodeDef node;

    @Getter
    protected final EtlRunContext context;

    protected final AtomicLong readCount = new AtomicLong();

    protected final AtomicLong writeCount = new AtomicLong();

    private final Map<String, Long> portCounts = new java.util.LinkedHashMap<>();

    private long startMillis;

    private JSONObject config;

    protected cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin currentRowOrigin;

    /**
     * 初始化 {@code AbstractEtlNodeExecutor}。
     */
    protected AbstractEtlNodeExecutor(EtlNodeDef node, EtlRunContext context) {
        this.node = node;
        this.context = context;
    }

    /** 运行中的实时读取行数，供观测采样；终态计数以 onNodeMetrics 为准。 */
    public long currentReadCount() {
        return readCount.get();
    }

    /** 运行中的实时输出行数，供观测采样；终态计数以 onNodeMetrics 为准。 */
    public long currentWriteCount() {
        return writeCount.get();
    }

    /**
     * 节点执行入口（由引擎在独立线程调用）
     */
    public void run() {
        startMillis = System.currentTimeMillis();
        boolean success = false;
        try {
            waitControlUpstreams();
            checkStop();
            context.getListener().onNodeStart(node.getNo());
            logLine(String.format("节点【%s】开始执行", node.getName()));
            execute();
            pushToAllDownstreams(DataPacket.end());
            success = true;
            logLine(String.format("节点【%s】执行成功，读取 %s 行，输出 %s 行",
                    node.getName(), readCount.get(), writeCount.get()));
            context.getListener().onNodeSuccess(node.getNo(), readCount.get(), writeCount.get());
        } catch (Throwable ex) {
            String message = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
            // 控制脚本显式 failGoOn 允许清理分支继续；数据链失败必须释放阻塞的上游。
            if (!node.isFailGoOn() || !context.getAllDownstreamChannels(node.getNo()).isEmpty()
                    || !context.getDag().getDataUpstreamList(node.getNo()).isEmpty()) {
                context.fail(message);
            }
            log.error("[run][ETL 节点({}) 执行失败]", node.getName(), ex);
            logLine(String.format("节点【%s】执行失败：%s", node.getName(), message));
            pushToAllDownstreamsQuietly(DataPacket.error(
                    String.format("上游节点【%s】执行失败：%s", node.getName(), message)));
            context.getListener().onNodeFail(node.getNo(),
                    StrUtil.maxLength(ExceptionUtil.stacktraceToString(ex), 4000));
        } finally {
            Map<String, Long> snapshot = Map.copyOf(portCounts);
            context.getNodePortCounts().put(node.getNo(), snapshot);
            try {
                context.getListener().onNodeMetrics(node.getNo(), readCount.get(), writeCount.get(), snapshot);
            } catch (Throwable ex) {
                success = false;
                context.fail("节点统计回调失败");
                log.warn("[run][ETL 节点统计回调失败 nodeNo={}]", node.getNo());
            } finally {
                context.getNodeFuture(node.getNo()).complete(success);
            }
        }
    }

    /**
     * 节点业务逻辑（子类实现）
     */
    protected abstract void execute() throws Exception;

    // ==================== 生命周期辅助 ====================

    /**
     * 等待全部控制上游完成；上游失败且未标记 failGoOn 时本节点失败
     */
    private void waitControlUpstreams() throws Exception {
        for (String upstreamNo : context.getDag().getControlUpstreams(node.getNo())) {
            Boolean success = context.getNodeFuture(upstreamNo).get();
            EtlNodeDef upstream = context.getDag().getNode(upstreamNo);
            if (!Boolean.TRUE.equals(success) && (upstream == null || !upstream.isFailGoOn())) {
                throw new IllegalStateException(
                        String.format("上游节点【%s】执行失败", upstream == null ? upstreamNo : upstream.getName()));
            }
        }
    }

    /**
     * 检查停止。
     */
    protected void checkStop() {
        if (context.isStopRequested()) {
            throw new IllegalStateException("任务已被停止");
        }
        if (node.getOverTimeMinutes() != null && node.getOverTimeMinutes() > 0
                && System.currentTimeMillis() - startMillis > node.getOverTimeMinutes() * 60_000L) {
            throw new IllegalStateException(
                    String.format("节点执行超时（超过 %s 分钟）", node.getOverTimeMinutes()));
        }
    }

    // ==================== 数据通道辅助 ====================

    /**
     * 从唯一数据上游读取下一行；上游正常结束返回 null，上游异常抛出
     */
    protected Map<String, Object> readNextRow() throws InterruptedException {
        currentRowOrigin = null;
        String upstreamNo = context.getDag().getDataUpstream(node.getNo());
        if (upstreamNo == null) {
            return null;
        }
        DataChannel channel = context.getChannel(upstreamNo, node.getNo());
        while (true) {
            checkStop();
            DataPacket packet = channel.poll(CHANNEL_WAIT_MILLIS);
            if (packet == null) {
                // 兜底：上游已结束且通道确认为空（如错误包投递超时被丢弃），按上游结果收尾避免悬挂
                if (context.getNodeFuture(upstreamNo).isDone()) {
                    packet = channel.poll(CHANNEL_WAIT_MILLIS);
                    if (packet == null) {
                        if (Boolean.TRUE.equals(context.getNodeFuture(upstreamNo).getNow(false))) {
                            return null;
                        }
                        throw new IllegalStateException(String.format("上游节点【%s】执行失败",
                                context.getDag().getNode(upstreamNo).getName()));
                    }
                } else {
                    continue;
                }
            }
            switch (packet.getType()) {
                case DATA -> {
                    readCount.incrementAndGet();
                    currentRowOrigin = packet.getOrigin();
                    return packet.getRow();
                }
                case END -> {
                    return null;
                }
                case ERROR -> throw new IllegalStateException(packet.getErrorMessage());
            }
        }
    }

    /**
     * 读取全部数据上游到结束（多输入节点用），返回 上游编号 → 行列表
     */
    protected Map<String, List<Map<String, Object>>> readAllUpstreams() throws InterruptedException {
        List<String> upstreams = context.getDag().getDataUpstreamList(node.getNo());
        Map<String, List<Map<String, Object>>> result = new java.util.LinkedHashMap<>();
        Map<String, Boolean> finished = new java.util.HashMap<>();
        upstreams.forEach(no -> {
            result.put(no, new java.util.ArrayList<>());
            finished.put(no, false);
        });
        while (finished.containsValue(false)) {
            checkStop();
            boolean received = false;
            for (String upstreamNo : upstreams) {
                if (finished.get(upstreamNo)) {
                    continue;
                }
                DataChannel channel = context.getChannel(upstreamNo, node.getNo());
                DataPacket packet = channel.poll(50L);
                if (packet == null) {
                    // 兜底：上游已结束且通道为空
                    if (context.getNodeFuture(upstreamNo).isDone()
                            && (packet = channel.poll(50L)) == null) {
                        if (Boolean.TRUE.equals(context.getNodeFuture(upstreamNo).getNow(false))) {
                            finished.put(upstreamNo, true);
                            continue;
                        }
                        throw new IllegalStateException(String.format("上游节点【%s】执行失败",
                                context.getDag().getNode(upstreamNo).getName()));
                    }
                    if (packet == null) {
                        continue;
                    }
                }
                received = true;
                switch (packet.getType()) {
                    case DATA -> {
                        readCount.incrementAndGet();
                        result.get(upstreamNo).add(packet.getRow());
                    }
                    case END -> finished.put(upstreamNo, true);
                    case ERROR -> throw new IllegalStateException(packet.getErrorMessage());
                }
            }
            if (!received) {
                checkStop();
            }
        }
        return result;
    }

    /**
     * 向 main 端口的全部下游推送一行数据
     */
    protected void pushRow(Map<String, Object> row) throws InterruptedException {
        pushRow(row, EtlEdgeDef.PORT_MAIN);
    }

    /**
     * 检查停止及资源预算后记录当前输出行并推送到下游。
     */
    protected void pushRow(Map<String, Object> row, String outputPort) throws InterruptedException {
        pushRow(row, outputPort, null);
    }

    /**
     * 检查停止及资源预算后记录当前输出行并推送到下游。
     */
    protected void pushRow(Map<String, Object> row, String outputPort,
            cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin origin) throws InterruptedException {
        checkStop();
        context.accountRow(row);
        context.accountOrigin(origin);
        portCounts.merge(outputPort, 1L, Long::sum);
        if (context.getRowObserver() != null) {
            context.getRowObserver().onRow(node.getNo(), outputPort,
                    java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row)));
        }
        List<DataChannel> channels = context.getDownstreamChannels(node.getNo(), outputPort);
        for (DataChannel channel : channels) {
            while (!channel.push(DataPacket.data(row, origin), CHANNEL_WAIT_MILLIS)) {
                checkStop();
            }
        }
        writeCount.incrementAndGet();
    }

    /**
     * 向全部下游通道推送当前数据包，并在等待时响应停止请求。
     */
    private void pushToAllDownstreams(DataPacket packet) throws InterruptedException {
        for (DataChannel channel : context.getAllDownstreamChannels(node.getNo())) {
            while (!channel.push(packet, CHANNEL_WAIT_MILLIS)) {
                checkStop();
            }
        }
    }

    /**
     * 尽力投递错误包，超时放弃（下游会随停止标记结束）。
     */
    private void pushToAllDownstreamsQuietly(DataPacket packet) {
        try {
            for (DataChannel channel : context.getAllDownstreamChannels(node.getNo())) {
                // 尽力投递错误包，超时放弃（下游会随停止标记结束）
                channel.push(packet, CHANNEL_WAIT_MILLIS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 配置辅助 ====================

    /**
     * 节点配置（已完成流程变量替换）
     */
    protected JSONObject config() {
        if (config == null) {
            String json = context.getVariables().resolveJson(StrUtil.blankToDefault(node.getNodeJson(), "{}"));
            config = JSONUtil.parseObj(json);
        }
        return config;
    }

    /**
     * 校验并取得配置。
     */
    protected String requireConfig(String key, String label) {
        String value = config().getStr(key);
        if (StrUtil.isBlank(value)) {
            throw new IllegalStateException(String.format("节点【%s】缺少配置：%s", node.getName(), label));
        }
        return value;
    }

    /**
     * 向当前受控日志输出端写入一行文本。
     */
    protected void logLine(String message) {
        context.getListener().onNodeLog(node.getNo(), String.format("[%s] %s",
                DateUtil.format(new Date(), DatePattern.NORM_DATETIME_PATTERN), message));
    }

}
