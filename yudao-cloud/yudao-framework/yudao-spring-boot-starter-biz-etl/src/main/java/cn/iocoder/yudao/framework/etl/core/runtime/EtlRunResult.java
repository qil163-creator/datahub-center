package cn.iocoder.yudao.framework.etl.core.runtime;

import lombok.Data;

/**
 * 流程执行结果汇总
 *
 * @author Li
 */
@Data
public class EtlRunResult {

    /** 输入确认及有界回执样本；二者都不代表受影响行数或副本可见性。 */
    private java.util.Map<String, cn.iocoder.yudao.framework.dbswitch.data.domain.TableBatchProgress> databaseProgress = java.util.Map.of();

    private java.util.Map<String, cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary> databaseCommits = java.util.Map.of();

    /** 至少一个节点未确认退出，调用方必须保留未知状态。 */
    private boolean terminationUnconfirmed;

    /** 文件节点的发布/丢弃/清理状态；不能从节点成功推断最终文件已发布。 */
    private java.util.Map<String, String> fileOutputStates = java.util.Map.of();

    private java.util.Map<String, java.util.Map<String, Long>> nodePortCounts = java.util.Map.of();

    private long transferredRows;

    private long transferredBytes;

    private long peakBufferedBytes;

    /**
     * 全部节点是否成功
     */
    private boolean success;

    /**
     * 是否被停止
     */
    private boolean stopped;

    /**
     * 失败节点数
     */
    private int failedNodeCount;

    /**
     * 首个失败原因
     */
    private String firstError;

}
