package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageAnalyzer;

/**
 * 各来源的解析器版本。
 * <p>
 * 版本 = 框架分析规则版本 × 100 + 本模块适配规则版本。任一方规则变化导致结果不同时递增，
 * 上报任务据此对全部来源重新解析；governance 按（修订号, 解析器版本）判定新旧。
 */
public final class LineageReportVersions {

    /** 开发任务适配规则版本。 */
    private static final int DEV_TASK_ADAPTER = 1;
    /** 画布（ETL 流程、集成任务）适配规则版本。 */
    private static final int GRAPH_ADAPTER = 1;
    /** 数据同步适配规则版本。 */
    private static final int DATA_ACCESS_ADAPTER = 1;

    /** 开发任务的解析器版本。 */
    public static final int DEV_TASK = SqlLineageAnalyzer.VERSION * 100 + DEV_TASK_ADAPTER;
    /** 可视化 ETL 流程的解析器版本。 */
    public static final int ETL_PIPELINE = SqlLineageAnalyzer.VERSION * 100 + GRAPH_ADAPTER;
    /** 集成任务的解析器版本。 */
    public static final int INTEGRATION_TASK = SqlLineageAnalyzer.VERSION * 100 + GRAPH_ADAPTER;
    /** 数据同步的解析器版本。 */
    public static final int DATA_ACCESS = SqlLineageAnalyzer.VERSION * 100 + DATA_ACCESS_ADAPTER;

    /**
     * 初始化 {@code LineageReportVersions}。
     */
    private LineageReportVersions() {
    }

    /**
     * 返回上报标记。
     */
    public static String key(long sourceRevision, int analyzerVersion) {
        return sourceRevision + ":" + analyzerVersion;
    }
}
