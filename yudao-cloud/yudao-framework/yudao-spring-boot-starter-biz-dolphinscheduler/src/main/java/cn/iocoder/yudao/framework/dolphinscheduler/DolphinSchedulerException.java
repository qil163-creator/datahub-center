package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONObject;

/**
 * DolphinScheduler REST 调用失败。
 */
public class DolphinSchedulerException extends RuntimeException {

    private final Integer code;

    private final JSONObject result;

    /**
     * 使用给定参数初始化 {@code DolphinSchedulerException}。
     */
    public DolphinSchedulerException(String message) {
        this(message, null, null);
    }

    /**
     * 初始化 {@code DolphinSchedulerException}及其父类状态。
     */
    public DolphinSchedulerException(String message, Integer code, JSONObject result) {
        super(message);
        this.code = code;
        this.result = result;
    }

    /**
     * 返回编码。
     */
    public Integer getCode() {
        return code;
    }

    /**
     * 返回结果。
     */
    public JSONObject getResult() {
        return result;
    }

}
