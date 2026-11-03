package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 访问令牌接口，对应 OpenAPI tag：access token related operation。
 */
public class AccessTokenApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code AccessTokenApi}。
     */
    public AccessTokenApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * updateToken
     * <p>更新指定用户的访问令牌</p>
     * <p>{@code PUT /access-tokens/{id}}</p>
     * @param id 必填，访问令牌编号
     * @param userId 必填，用户编号
     * @param expireTime 必填，令牌过期时间
     * @param token 可选，访问令牌字符串，未提供时自动生成
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateToken(Integer id, Integer userId, String expireTime, String token) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (expireTime != null) {
            params.put("expireTime", expireTime);
        }
        if (token != null) {
            params.put("token", token);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/access-tokens/{id}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * deleteToken
     * <p>删除访问令牌</p>
     * <p>{@code DELETE /access-tokens/{id}}</p>
     * @param id 必填，
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject delAccessTokenById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/access-tokens/{id}", pathVars);
        return http.delete(path, params);
    }

    /**
     * queryAccessTokenList
     * <p>分页查询访问令牌列表</p>
     * <p>{@code GET /access-tokens}</p>
     * @param pageNo 必填，页码
     * @param searchVal 可选，搜索值
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAccessTokenList(Integer pageNo, String searchVal, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/access-tokens", pathVars);
        return http.get(path, params);
    }

    /**
     * createToken
     * <p>为指定用户创建访问令牌</p>
     * <p>{@code POST /access-tokens}</p>
     * @param userId 必填，用户编号
     * @param expireTime 必填，令牌过期时间
     * @param token 可选，访问令牌字符串，未提供时自动生成
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createToken(Integer userId, String expireTime, String token) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (expireTime != null) {
            params.put("expireTime", expireTime);
        }
        if (token != null) {
            params.put("token", token);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/access-tokens", pathVars);
        return http.postForm(path, params);
    }

    /**
     * generateToken
     * <p>{@code POST /access-tokens/generate}</p>
     * @param userId 必填，用户编号
     * @param expireTime 必填，过期时间
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject generateToken(Integer userId, String expireTime) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (expireTime != null) {
            params.put("expireTime", expireTime);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/access-tokens/generate", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryAccessTokenByUser
     * <p>查询指定用户的访问令牌</p>
     * <p>{@code GET /access-tokens/user/{userId}}</p>
     * @param userId 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAccessTokenByUser(Integer userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("userId", userId);
        String path = DolphinSchedulerHttp.fillPath("/access-tokens/user/{userId}", pathVars);
        return http.get(path, params);
    }

}
