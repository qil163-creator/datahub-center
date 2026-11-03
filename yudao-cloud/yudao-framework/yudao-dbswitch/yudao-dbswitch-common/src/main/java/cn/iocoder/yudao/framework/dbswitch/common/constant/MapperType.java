package cn.iocoder.yudao.framework.dbswitch.common.constant;

/**
 * @ClassName TaskType
 * @Author zrx
 */
public enum MapperType {

    /**
     * 全量映射
     */
    ALL(1, "统一映射"),
    /**
     * 单独映射
     */
    SINGLE(2, "单独映射");


    private Integer code;

    private String name;

    /**
     * 初始化 {@code MapperType}。
     */
    MapperType(Integer code, String name) {
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
