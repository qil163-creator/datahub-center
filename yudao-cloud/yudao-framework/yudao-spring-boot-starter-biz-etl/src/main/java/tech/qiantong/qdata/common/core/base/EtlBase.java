package tech.qiantong.qdata.common.core.base;

import com.alibaba.fastjson2.JSONObject;

/** 原站公开脚本接口的最小兼容合同；只在隔离工作进程中交给用户源码使用。 */
public interface EtlBase {
    String execute(JSONObject data, Object logParams) throws Exception;

    /** 日志仍进入工作进程原有的有界丢弃 sink，不向引擎或平台传递敏感内容。 */
    default void writeLog(Object logParams, String message) { System.out.println(message); }
}
