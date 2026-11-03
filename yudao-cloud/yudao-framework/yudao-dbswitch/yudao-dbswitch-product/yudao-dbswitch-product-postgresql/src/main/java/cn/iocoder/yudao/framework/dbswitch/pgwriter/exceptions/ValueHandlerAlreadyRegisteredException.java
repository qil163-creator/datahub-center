package cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions;

/**
 * 值处理器重复注册异常,对同一 Java 类型再次注册值处理器时抛出。
 */
public class ValueHandlerAlreadyRegisteredException extends RuntimeException {

    /**
     * 初始化 {@code ValueHandlerAlreadyRegisteredException}及其父类状态。
     */
    public ValueHandlerAlreadyRegisteredException(String message) {
        super(message);
    }

    /**
     * 初始化 {@code ValueHandlerAlreadyRegisteredException}。
     */
    public ValueHandlerAlreadyRegisteredException() {
    }

    /**
     * 初始化 {@code ValueHandlerAlreadyRegisteredException}及其父类状态。
     */
    public ValueHandlerAlreadyRegisteredException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 初始化 {@code ValueHandlerAlreadyRegisteredException}及其父类状态。
     */
    public ValueHandlerAlreadyRegisteredException(Throwable cause) {
        super(cause);
    }

    /**
     * 初始化 {@code ValueHandlerAlreadyRegisteredException}及其父类状态。
     */
    public ValueHandlerAlreadyRegisteredException(String message, Throwable cause,
            boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
