package cn.iocoder.yudao.framework.dbswitch.dbchange;

/**
 * 记录变化状态枚举类
 *
 * @author jrl
 */
public enum RecordChangeTypeEnum {
    /**
     * 未变标识
     */
    VALUE_IDENTICAL(0, "identical"),

    /**
     * 更新标识
     */
    VALUE_CHANGED(1, "update"),

    /**
     * 插入标识
     */
    VALUE_INSERT(2, "insert"),

    /**
     * 删除标识
     */
    VALUE_DELETED(3, "delete");

    /**
     * 索引
     */
    private Integer index;

    /**
     * 状态标记
     */
    private String status;

    /**
     * 初始化 {@code RecordChangeTypeEnum}。
     */
    RecordChangeTypeEnum(int idx, String flag) {
        this.index = idx;
        this.status = flag;
    }

    /**
     * 返回索引。
     */
    public int getIndex() {
        return index;
    }

    /**
     * 返回状态。
     */
    public String getStatus() {
        return this.status;
    }

}
