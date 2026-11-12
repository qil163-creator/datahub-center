package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import java.util.List;

/**
 * 单条查询的字段来源分析结果，供 ETL 等以查询作为输入节点的场景使用。
 *
 * @param status          整体状态
 * @param columns         按输出顺序排列的字段及其来源
 * @param indirectSources 影响整体结果的 INDIRECT 来源（过滤、关联、分组等）
 * @param readTables      查询读取的物理表
 * @param issues          问题清单
 */
public record QueryLineageResult(SqlLineageStatus status, List<Column> columns,
                                 List<LineageColumnSource> indirectSources, List<LineageTable> readTables,
                                 List<LineageIssue> issues) {

    /**
     * 复制为不可变集合。
     */
    public QueryLineageResult {
        columns = List.copyOf(columns);
        indirectSources = List.copyOf(indirectSources);
        readTables = List.copyOf(readTables);
        issues = List.copyOf(issues);
    }

    /**
     * 查询输出字段。
     *
     * @param name     输出名；未命名表达式为 null；未展开的全列为 {@code *}
     * @param sources  DIRECT 来源
     * @param wildcard 是否为缺少表结构而未展开的 {@code *}
     */
    public record Column(String name, List<LineageColumnSource> sources, boolean wildcard) {

        /**
         * 复制为不可变集合。
         */
        public Column {
            sources = List.copyOf(sources);
        }
    }
}
