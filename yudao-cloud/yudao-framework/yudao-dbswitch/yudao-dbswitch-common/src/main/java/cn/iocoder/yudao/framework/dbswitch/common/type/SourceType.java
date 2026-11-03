package cn.iocoder.yudao.framework.dbswitch.common.type;

/**
 * @ClassName TaskType
 * @Author zrx
 */
public enum SourceType {

    /**
     * 指定表
     */
    TABLE(1, "指定表"),
    /**
     * SQL
     */
    SQL(2, "SQL");


    private Integer code;

    private String name;

    /**
     * 初始化 {@code SourceType}。
     */
    SourceType(Integer code, String name) {
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
