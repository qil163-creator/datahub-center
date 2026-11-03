package cn.iocoder.yudao.framework.dbswitch.common.constant;

/**
 * @ClassName TaskType
 * @Author zrx
 */
public enum ChangeDataSyncType {

    /**
     * 主键排序比对
     */
    PK_ORDER_COMPARE(1, "主键排序比对"),
    /**
     * SQL
     */
    INCREASE_COLUMN(2, "增量字段");


    private Integer code;

    private String name;

    /**
     * 初始化 {@code ChangeDataSyncType}。
     */
    ChangeDataSyncType(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 返回编码。
     */
    public Integer getCode() {
        return code;
    }

    /**
     * 返回名称。
     */
    public String getName() {
        return name;
    }
}
