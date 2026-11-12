package cn.iocoder.yudao.framework.dbswitch.core.lineage;

/**
 * 分析上限，防止超大脚本或病态语句占满资源。
 *
 * @param maxScriptChars   脚本最大字符数
 * @param maxStatements    最大语句数
 * @param maxTableEdges    最大表级边数
 * @param maxColumnEdges   最大字段级边数
 * @param maxIssues        最多保留的问题条数
 * @param maxNodesPerStatement 单条语句最多访问的语法节点数
 */
public record SqlLineageLimits(int maxScriptChars, int maxStatements, int maxTableEdges, int maxColumnEdges,
                               int maxIssues, int maxNodesPerStatement) {

    /**
     * 校验上限均为正数。
     */
    public SqlLineageLimits {
        if (maxScriptChars <= 0 || maxStatements <= 0 || maxTableEdges <= 0 || maxColumnEdges <= 0
                || maxIssues <= 0 || maxNodesPerStatement <= 0) {
            throw new IllegalArgumentException("血缘分析上限必须为正数");
        }
    }

    /**
     * 默认上限：表级边与字段边上限与血缘上报契约一致。
     */
    public static SqlLineageLimits defaults() {
        return new SqlLineageLimits(2_000_000, 2_000, 2_000, 20_000, 200, 200_000);
    }
}
