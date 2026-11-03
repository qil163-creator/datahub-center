package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

/**
 * 跨产品计划未获接受时，在执行 DDL 前抛出。消息只列元数据
 * （列、产品、原生及逻辑类型、原因代码），绝不包含行值；{@link #plan()} 包含所有列。
 */
public final class TypeMappingRejectedException extends UnsupportedOperationException {

    private static final int LISTED_COLUMNS = 20;

    private final transient TableTypeMappingPlan plan;

    /**
     * 初始化 {@code TypeMappingRejectedException}及其父类状态。
     */
    public TypeMappingRejectedException(TableTypeMappingPlan plan) {
        super(message(plan));
        this.plan = plan;
    }

    /**
     * 返回计划。
     */
    public TableTypeMappingPlan plan() {
        return plan;
    }

    /**
     * 返回消息。
     */
    private static String message(TableTypeMappingPlan plan) {
        var rejected = plan.rejectedColumns();
        StringBuilder text = new StringBuilder("Cross-product column mapping to ").append(plan.targetProduct())
                .append(" rejected before DDL: ").append(rejected.size()).append(" column(s)");
        for (int i = 0; i < Math.min(LISTED_COLUMNS, rejected.size()); i++) {
            var column = rejected.get(i);
            text.append("; ").append(column.describe());
            if (column.fidelity() == TypeFidelity.LOSSY) {
                text.append(" not accepted");
            }
        }
        if (rejected.size() > LISTED_COLUMNS) {
            text.append("; ... ").append(rejected.size() - LISTED_COLUMNS).append(" more");
        }
        return text.toString();
    }
}
