package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目偏好接口，对应 OpenAPI tag：project preference related operation。
 */
public class ProjectPreferenceApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code ProjectPreferenceApi}。
     */
    public ProjectPreferenceApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryProjectPreferenceByProjectCode
     * <p>查询项目偏好配置</p>
     * <p>{@code GET /projects/{projectCode}/project-preference}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProjectPreferenceByProjectCode(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-preference", pathVars);
        return http.get(path, params);
    }

    /**
     * updateProjectPreference
     * <p>更新项目偏好配置</p>
     * <p>{@code PUT /projects/{projectCode}/project-preference}</p>
     * @param projectCode 必填，项目编码
     * @param projectPreferences 必填，项目偏好配置
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateProjectPreference(Long projectCode, String projectPreferences) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectPreferences != null) {
            params.put("projectPreferences", projectPreferences);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-preference", pathVars);
        return http.putForm(path, params);
    }

    /**
     * enableProjectPreference
     * <p>更新项目偏好配置的状态</p>
     * <p>{@code POST /projects/{projectCode}/project-preference}</p>
     * @param projectCode 必填，项目编码
     * @param state 必填，项目偏好配置的状态
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject enableProjectPreference(Long projectCode, String state) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (state != null) {
            params.put("state", state);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-preference", pathVars);
        return http.postForm(path, params);
    }

}
