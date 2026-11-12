package cn.iocoder.yudao.framework.dbswitch.core.lineage;

/**
 * 血缘分析整体状态。
 */
public enum SqlLineageStatus {
    /** 全部语句均已完整分析。 */
    SUCCESS,
    /** 有结果，但存在降级问题（如字段来源不确定、表结构缺失）。 */
    PARTIAL,
    /** 没有任何语句可被分析。 */
    FAILED,
    /** 数据库产品未进入已验证的血缘支持矩阵。 */
    UNSUPPORTED
}
