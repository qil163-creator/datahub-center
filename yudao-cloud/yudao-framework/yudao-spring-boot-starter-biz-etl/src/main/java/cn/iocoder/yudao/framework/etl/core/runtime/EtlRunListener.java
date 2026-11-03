package cn.iocoder.yudao.framework.etl.core.runtime;

/**
 * 流程执行监听器：业务侧实现以持久化流程/节点级日志与状态
 *
 * 所有回调可能来自不同节点线程，业务实现需保证线程安全
 *
 * @author Li
 */
public interface EtlRunListener {

    /**
     * 流程级日志
     */
    default void onPipelineLog(String line) {
    }

    /**
     * 节点开始执行
     */
    default void onNodeStart(String nodeNo) {
    }

    /**
     * 节点日志
     */
    default void onNodeLog(String nodeNo, String line) {
    }

    /**
     * 节点执行成功
     *
     * @param readCount  读取/接收行数
     * @param writeCount 输出/下发行数
     */
    default void onNodeSuccess(String nodeNo, long readCount, long writeCount) {
    }

    /**
     * 节点执行失败
     */
    default void onNodeFail(String nodeNo, String errorMessage) {
    }

    /** 终态计数，成功与失败均报告。端口数按产生行计量，广播到多个下游只记一次。 */
    default void onNodeMetrics(String nodeNo, long readCount, long writeCount, java.util.Map<String, Long> portCounts) {
    }

    /**
     * 运行观测快照：仅当 runtimeSampleMillis 大于 0 时按间隔回调，流程结束后再以 finished=true 回调一次。
     * 在引擎控制线程同步调用，实现须快速返回；抛出的异常只记录日志，不改变流程结果。
     */
    default void onRuntimeSample(EtlRuntimeSample sample) {
    }

}
