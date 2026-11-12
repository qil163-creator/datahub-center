package cn.iocoder.yudao.framework.dbswitch.core.lineage;

/**
 * 字段血缘的变换类型，分类与 OpenLineage 字段血缘规范一致。
 * <p>
 * DIRECT 表示下游值由上游值推导而来；INDIRECT 表示上游字段影响结果但不是值的来源。
 */
public enum LineageTransform {

    /** 原样取值。 */
    IDENTITY(true, 0),
    /** 同一行内的计算、函数或类型转换。 */
    TRANSFORMATION(true, 1),
    /** 跨多行聚合。 */
    AGGREGATION(true, 2),
    /** 用于关联条件。 */
    JOIN(false, 0),
    /** 用于过滤条件（WHERE、HAVING、QUALIFY）。 */
    FILTER(false, 0),
    /** 用于分组。 */
    GROUP_BY(false, 0),
    /** 用于排序。 */
    SORT(false, 0),
    /** 用于窗口分区或窗口排序。 */
    WINDOW(false, 0),
    /** 用于 CASE、IF 等条件判断。 */
    CONDITIONAL(false, 0);

    private final boolean direct;
    private final int rank;

    /**
     * 初始化变换类型。
     */
    LineageTransform(boolean direct, int rank) {
        this.direct = direct;
        this.rank = rank;
    }

    /**
     * 是否为 DIRECT 类变换。
     */
    public boolean direct() {
        return direct;
    }

    /**
     * 返回 OpenLineage 的大类：DIRECT 或 INDIRECT。
     */
    public String type() {
        return direct ? "DIRECT" : "INDIRECT";
    }

    /**
     * 组合外层上下文与内层来源的变换：外层为 INDIRECT 时保持外层；否则取更强的 DIRECT 变换。
     */
    public static LineageTransform compose(LineageTransform outer, LineageTransform inner) {
        if (!outer.direct) {
            return outer;
        }
        if (!inner.direct) {
            return inner;
        }
        return outer.rank >= inner.rank ? outer : inner;
    }
}
