package cn.iocoder.yudao.framework.dbswitch.core.lineage;

/**
 * 表级血缘边：上游表的数据流向下游表。
 *
 * @param upstream       上游表
 * @param downstream     下游表
 * @param statementIndex 产生该边的语句序号（从 1 开始）；跨临时表折叠时为最终写入语句
 */
public record LineageTableEdge(LineageTable upstream, LineageTable downstream, int statementIndex) {
}
