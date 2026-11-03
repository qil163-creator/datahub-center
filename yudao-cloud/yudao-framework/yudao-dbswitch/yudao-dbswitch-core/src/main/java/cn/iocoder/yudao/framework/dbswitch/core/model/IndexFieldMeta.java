package cn.iocoder.yudao.framework.dbswitch.core.model;

/**
 * 索引中的单个键（移植自 dbswitch 2.0.2）。
 * <p>
 * fieldName 是驱动返回的 JDBC COLUMN_NAME：函数/表达式键可能为 null 或表达式文本，不能假定一定是表列；
 * ascending 为 null 表示驱动未声明排序方向。
 */
public record IndexFieldMeta(String fieldName, int ordinalPosition, Boolean ascending) {

    /**
     * 校验构造参数并初始化 {@code IndexFieldMeta}。
     */
    public IndexFieldMeta {
        if (ordinalPosition < 1) {
            throw new IllegalArgumentException("Index key ordinal must be positive");
        }
    }

    /**
     * 判断当前索引字段是否采用降序排列。
     */
    public boolean descending() {
        return Boolean.FALSE.equals(ascending);
    }
}
