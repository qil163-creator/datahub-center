package cn.iocoder.yudao.framework.dbswitch.common.type;

/**
 * 数据库表类型:视图表、物理表
 *
 * @author jrl
 */
public enum DBTableType {
    /**
     * 物理表
     */
    TABLE(0),

    /**
     * 视图表
     */
    VIEW(1),

    /**
     * 自定义sql
     */
    SQL(2);

    private int index;

    /**
     * 初始化 {@code DBTableType}。
     */
    DBTableType(int idx) {
        this.index = idx;
    }

    /**
     * 返回索引。
     */
    public int getIndex() {
        return index;
    }
}
