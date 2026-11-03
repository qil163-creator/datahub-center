package cn.iocoder.yudao.framework.dbswitch.common.util;

import com.google.common.base.Preconditions;

import java.util.Collection;

/**
 * 参数校验工具类,基于 Guava Preconditions 封装非空、非空集合、条件断言等检查,供组件内各处做入参合法性校验。
 */
public final class ExamineUtils {

    /**
     * 检查非空。
     */
    public static void checkNotNull(Object object, String elem) {
        Preconditions.checkNotNull(object, "The '%s' can't be null", elem);
    }

    /**
     * 检查非空。
     */
    public static void checkNotNull(Object object, String elem, String owner) {
        Preconditions.checkNotNull(object,
                "The '%s' of '%s' can't be null",
                elem, owner);
    }

    /**
     * 检查非空。
     */
    public static void checkNotEmpty(Collection<?> collection, String elem) {
        Preconditions.checkArgument(!collection.isEmpty(),
                "The '%s' can't be empty", elem);
    }

    /**
     * 检查非空。
     */
    public static void checkNotEmpty(Collection<?> collection, String elem, String owner) {
        Preconditions.checkArgument(!collection.isEmpty(),
                "The '%s' of '%s' can't be empty",
                elem, owner);
    }

    /**
     * 检查参数。
     */
    public static void checkArgument(boolean expression,
            String message,
            Object... args) {
        Preconditions.checkArgument(expression, message, args);
    }

    /**
     * 检查参数非空。
     */
    public static void checkArgumentNotNull(Object object,
            String message,
            Object... args) {
        Preconditions.checkArgument(object != null, message, args);
    }

    /**
     * 检查状态。
     */
    public static void checkState(boolean expression,
            String message,
            Object... args) {
        Preconditions.checkState(expression, message, args);
    }

    /**
     * 检查表达式、消息、参数是否满足当前操作的约定。
     */
    public static void check(boolean expression, String message, Object... args) {
        if (!expression) {
            throw new RuntimeException(String.format(message, args));
        }
    }

}
