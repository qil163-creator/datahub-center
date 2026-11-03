package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录接口，对应 OpenAPI tag：User login related operations。
 */
public class LoginApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code LoginApi}。
     */
    public LoginApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * signOut
     * <p>退出登录</p>
     * <p>{@code POST /signOut}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject signOut() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/signOut", pathVars);
        return http.postForm(path, params);
    }

    /**
     * login
     * <p>用户登录</p>
     * <p>{@code POST /login}</p>
     * @param userName 必填，用户名
     * @param userPassword 必填，用户密码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject login(String userName, String userPassword) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userName != null) {
            params.put("userName", userName);
        }
        if (userPassword != null) {
            params.put("userPassword", userPassword);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/login", pathVars);
        return http.postForm(path, params);
    }

    /**
     * 使用配置项 {@code dolphinscheduler.username/password} 登录。
     */
    public JSONObject login() {
        return login(http.getProperties().getUsername(), http.getProperties().getPassword());
    }

    /**
     * redirectToOauth2
     * <p>重定向至 OAuth2 登录</p>
     * <p>{@code GET /redirect/login/oauth2}</p>
     * @param code 必填，授权码
     * @param provider 必填，提供方
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject loginByAuth2(String code, String provider) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (code != null) {
            params.put("code", code);
        }
        if (provider != null) {
            params.put("provider", provider);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/redirect/login/oauth2", pathVars);
        return http.get(path, params);
    }

    /**
     * getOidcProviders
     * <p>获取 OIDC 提供方列表</p>
     * <p>{@code GET /oidc-providers}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject oidcProviders() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/oidc-providers", pathVars);
        return http.get(path, params);
    }

    /**
     * redirectToOidc
     * <p>重定向至 OIDC 登录</p>
     * <p>{@code GET /oauth2/authorization/{providerId}}</p>
     * @param providerId 必填，
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject redirectToOidc(String providerId) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("providerId", providerId);
        String path = DolphinSchedulerHttp.fillPath("/oauth2/authorization/{providerId}", pathVars);
        return http.get(path, params);
    }

    /**
     * getOauth2Provider
     * <p>获取 OAuth2 提供方</p>
     * <p>{@code GET /oauth2-provider}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject oauth2Provider() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/oauth2-provider", pathVars);
        return http.get(path, params);
    }

    /**
     * 单点登录
     * <p>用户单点登录</p>
     * <p>{@code GET /login/sso}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject ssoLogin() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/login/sso", pathVars);
        return http.get(path, params);
    }

    /**
     * handleOidcCallback
     * <p>处理 OIDC 回调</p>
     * <p>{@code GET /login/oauth2/code/{providerId}}</p>
     * @param providerId 必填，
     * @param code 可选，授权码
     * @param error 可选，错误信息
     * @param state 必填，状态
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject handleOidcCallback(String providerId, String code, String error, String state) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (code != null) {
            params.put("code", code);
        }
        if (error != null) {
            params.put("error", error);
        }
        if (state != null) {
            params.put("state", state);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("providerId", providerId);
        String path = DolphinSchedulerHttp.fillPath("/login/oauth2/code/{providerId}", pathVars);
        return http.get(path, params);
    }

    /**
     * clearCookieSessionId
     * <p>{@code DELETE /cookies}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject clearCookieSessionId() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cookies", pathVars);
        return http.delete(path, params);
    }

}
