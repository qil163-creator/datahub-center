package cn.iocoder.yudao.framework.dbswitch.common.type;

/**
 * 表索引类型（移植自 dbswitch 2.0.2）
 *
 * @author yudao
 */
public enum TableIndexEnum {

    /** 普通索引 */
    NORMAL("普通索引"),
    /** 唯一索引 */
    UNIQUE("唯一索引"),
    ;

    private final String description;

    /**
     * 初始化 {@code TableIndexEnum}。
     */
    TableIndexEnum(String description) {
        this.description = description;
    }

    /**
     * 返回描述。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 判断唯一。
     */
    public boolean isUnique() {
        return this == UNIQUE;
    }
}
