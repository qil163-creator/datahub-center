package cn.iocoder.yudao.framework.apilog.core.util;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.method.HandlerMethod;

import static cn.iocoder.yudao.framework.apilog.core.interceptor.ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD;

/** 统一访问日志、开发日志和异常日志对请求参数开关的解释。 */
public final class ApiLogUtils {

    private static final String ATTRIBUTE_REQUEST_LOG_DISABLED = ApiLogUtils.class.getName() + ".requestDisabled";

    private ApiLogUtils() {
    }

    /** 服务端入口过滤器可在身份校验和 HandlerMethod 建立前关闭敏感输入日志。 */
    public static void disableRequestLogging(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE_REQUEST_LOG_DISABLED, Boolean.TRUE);
    }

    public static boolean isRequestEnabled(HttpServletRequest request) {
        if (request != null && Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE_REQUEST_LOG_DISABLED))) {
            return false;
        }
        if (request == null || !(request.getAttribute(ATTRIBUTE_HANDLER_METHOD) instanceof HandlerMethod handler)) {
            return true;
        }
        ApiAccessLog annotation = handler.getMethodAnnotation(ApiAccessLog.class);
        return annotation == null || annotation.requestEnable();
    }

    /** 解析/校验异常可能包含请求原文；关闭参数日志时仅保留类型与调用位置。 */
    public static Throwable exceptionForLog(HttpServletRequest request, Throwable exception) {
        if (isRequestEnabled(request)) {
            return exception;
        }
        Throwable sanitized = new Throwable(exception.getClass().getName());
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }

}
