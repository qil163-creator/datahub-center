package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import java.util.List;

/**
 * 脚本血缘分析结果。
 *
 * @param status         整体状态
 * @param statementCount 非空语句数
 * @param readTables     脚本读取的持久表（不含脚本内临时表）
 * @param writeTables    脚本写入的持久表
 * @param tableEdges     表级血缘边
 * @param columnEdges    字段级血缘边
 * @param issues         问题清单
 */
public record SqlLineageResult(SqlLineageStatus status, int statementCount, List<LineageTable> readTables,
                               List<LineageTable> writeTables, List<LineageTableEdge> tableEdges,
                               List<LineageColumnEdge> columnEdges, List<LineageIssue> issues) {

    /**
     * 复制为不可变集合。
     */
    public SqlLineageResult {
        readTables = List.copyOf(readTables);
        writeTables = List.copyOf(writeTables);
        tableEdges = List.copyOf(tableEdges);
        columnEdges = List.copyOf(columnEdges);
        issues = List.copyOf(issues);
    }

    /**
     * 构造仅含状态与问题的结果。
     */
    static SqlLineageResult empty(SqlLineageStatus status, int statementCount, List<LineageIssue> issues) {
        return new SqlLineageResult(status, statementCount, List.of(), List.of(), List.of(), List.of(), issues);
    }
}
