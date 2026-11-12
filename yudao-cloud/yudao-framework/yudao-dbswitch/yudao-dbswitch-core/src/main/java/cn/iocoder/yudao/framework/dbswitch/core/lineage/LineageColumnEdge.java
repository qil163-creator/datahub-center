package cn.iocoder.yudao.framework.dbswitch.core.lineage;

/**
 * 字段级血缘边。
 * <p>
 * INDIRECT 类边（关联、过滤、分组、排序、窗口、条件）以下游虚拟列 {@code *} 记录，
 * 表示它影响整张下游表而非某个字段，避免输出与输入字段的笛卡尔积。
 *
 * @param upstream         上游表
 * @param upstreamColumn   上游字段；{@code *} 表示未展开的全列
 * @param downstream       下游表
 * @param downstreamColumn 下游字段；{@code *} 表示整表级依赖或未展开的全列
 * @param transform        变换类型
 * @param ambiguous        字段归属不唯一
 * @param masking          不暴露原值的变换
 * @param statementIndex   语句序号（从 1 开始）
 */
public record LineageColumnEdge(LineageTable upstream, String upstreamColumn, LineageTable downstream,
                                String downstreamColumn, LineageTransform transform, boolean ambiguous,
                                boolean masking, int statementIndex) {
}
