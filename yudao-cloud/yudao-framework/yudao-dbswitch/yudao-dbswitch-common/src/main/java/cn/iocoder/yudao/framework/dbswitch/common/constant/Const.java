package cn.iocoder.yudao.framework.dbswitch.common.constant;

/**
 * 常量定义
 *
 * @author jrl
 */
public final class Const {

    /**
     * 当前操作系统的文件系统路径分隔符。
     */
    public static final String FILE_SEPARATOR = System.getProperty("file.separator");

    /**
     * 当前操作系统的路径列表分隔符。
     */
    public static final String PATH_SEPARATOR = System.getProperty("path.separator");

    /**
     * CR：当前操作系统使用的回车符
     */
    public static final String CR = System.getProperty("line.separator");

    /**
     * DOSCR：MS-DOS 使用的回车符
     */
    public static final String DOSCR = "\n\r";

    /**
     * 空字符串（""）。
     */
    public static final String EMPTY_STRING = "";

    /**
     * Java 运行时版本
     */
    public static final String JAVA_VERSION = System.getProperty("java.vm.version");

    /**
     * 建表语句的前缀字符串
     */
    public static final String CREATE_TABLE = "CREATE TABLE ";

    /**
     * 删除表语句的前缀字符串
     */
    public static final String DROP_TABLE = "DROP TABLE ";

    /**
     * 固定关键字字符串
     */
    public static final String IF_NOT_EXISTS = "IF NOT EXISTS ";

    /**
     * 固定关键字字符串
     */
    public static final String IF_EXISTS = "IF EXISTS ";

    /**
     * 构造方法
     */
    private Const() {
    }

}
