package cn.iocoder.yudao.framework.dbswitch.data.util;

/**
 * 显式写入模式。null/空保持历史默认（可 truncate、目标存在则覆盖）。
 * 生命周期新路径必须传 CREATE_NEW 或 STRICT_COPY。
 */
public final class MigrationWriteModes {

    public static final String CREATE_NEW = "CREATE_NEW";

    public static final String STRICT_COPY = "STRICT_COPY";

    /**
     * 初始化 {@code MigrationWriteModes}。
     */
    private MigrationWriteModes() {
    }

    /**
     * 判断当前配置是否由调用方显式指定。
     */
    public static boolean isExplicit(String writeMode) {
        return CREATE_NEW.equals(writeMode) || STRICT_COPY.equals(writeMode);
    }

    /**
     * 返回目标对象已存在时是否拒绝继续的配置。
     */
    public static boolean failIfTargetExists(String writeMode) {
        return isExplicit(writeMode);
    }

    /**
     * 说明当前目标计划是否允许清空表数据。
     */
    public static boolean allowTruncate(Boolean targetTruncateData, String writeMode) {
        if (isExplicit(writeMode)) {
            return false;
        }
        return Boolean.TRUE.equals(targetTruncateData);
    }

    /**
     * 说明当前目标计划是否允许删除目标对象。
     */
    public static boolean allowDrop(Boolean targetDrop, String writeMode) {
        if (isExplicit(writeMode)) {
            return false;
        }
        return Boolean.TRUE.equals(targetDrop);
    }

    /**
     * 返回源对象不存在时是否拒绝继续的配置。
     */
    public static boolean failOnMissingSource(String writeMode) {
        return isExplicit(writeMode);
    }

    /** 兼容访问器：字段读取失败在所有模式下都属于致命错误，包括旧版 false 输入。 */
    public static boolean failOnFieldReadError(String writeMode, Boolean flag) {
        return true;
    }

    /**
     * 返回列无法匹配时是否拒绝继续的配置。
     */
    public static boolean failOnUnmatchedColumns(String writeMode) {
        return STRICT_COPY.equals(writeMode);
    }

}
