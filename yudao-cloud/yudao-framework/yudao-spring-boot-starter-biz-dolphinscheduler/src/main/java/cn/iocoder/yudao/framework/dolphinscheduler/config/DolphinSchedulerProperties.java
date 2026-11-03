package cn.iocoder.yudao.framework.dolphinscheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DolphinScheduler REST 配置。
 * <p>
 * 对应 OpenAPI {@code servers[0].url}，默认包含 context-path {@code /dolphinscheduler}。
 */
@ConfigurationProperties(prefix = DolphinSchedulerProperties.PREFIX)
public class DolphinSchedulerProperties {

    public static final String PREFIX = "dolphinscheduler";

    /**
     * API 根地址，需包含 context-path，例如 {@code http://localhost:12345/dolphinscheduler}。
     */
    private String serverUrl = "http://localhost:12345/dolphinscheduler";

    /**
     * 访问令牌，请求头 {@code token}。优先于会话 Cookie。
     */
    private String token = "";

    /**
     * 登录用户名。配置后可调用 {@code loginApi.login} 换取会话。
     */
    private String username = "";

    /**
     * 登录密码。
     */
    private String password = "";

    /**
     * HTTP 超时，毫秒。
     */
    private int timeoutMillis = 30_000;

    /**
     * 当响应 {@code success=false} 或 {@code code != 0} 时是否抛出异常。
     */
    private boolean throwOnFailed = true;

    /**
     * 返回服务端地址。
     */
    public String getServerUrl() {
        return serverUrl;
    }

    /**
     * 设置服务端地址。
     */
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * 返回令牌。
     */
    public String getToken() {
        return token;
    }

    /**
     * 设置令牌。
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 返回用户名。
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置用户名。
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 返回密码。
     */
    public String getPassword() {
        return password;
    }

    /**
     * 设置密码。
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 返回超时毫秒数。
     */
    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 设置超时毫秒数。
     */
    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 返回抛出回调失败。
     */
    public boolean isThrowOnFailed() {
        return throwOnFailed;
    }

    /**
     * 设置抛出回调失败。
     */
    public void setThrowOnFailed(boolean throwOnFailed) {
        this.throwOnFailed = throwOnFailed;
    }

}
