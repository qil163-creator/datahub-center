package cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions;

/**
 * 值处理器未注册异常,按 Java 类型查找不到对应的值处理器时抛出。
 */
public class ValueHandlerNotRegisteredException extends RuntimeException {

    /**
     * 初始化 {@code ValueHandlerNotRegisteredException}及其父类状态。
     */
    public ValueHandlerNotRegisteredException(String message) {
        super(message);
    }

    /**
     * 初始化 {@code ValueHandlerNotRegisteredException}。
     */
    public ValueHandlerNotRegisteredException() {
    }

    /**
     * 初始化 {@code ValueHandlerNotRegisteredException}及其父类状态。
     */
    public ValueHandlerNotRegisteredException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 初始化 {@code ValueHandlerNotRegisteredException}及其父类状态。
     */
    public ValueHandlerNotRegisteredException(Throwable cause) {
        super(cause);
    }

    /**
     * 初始化 {@code ValueHandlerNotRegisteredException}及其父类状态。
     */
    public ValueHandlerNotRegisteredException(String message, Throwable cause,
            boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
