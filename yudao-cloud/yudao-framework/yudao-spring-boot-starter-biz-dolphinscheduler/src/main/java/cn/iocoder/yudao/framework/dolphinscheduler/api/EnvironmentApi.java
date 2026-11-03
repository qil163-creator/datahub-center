package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环境接口，对应 OpenAPI tag：environment related operation。
 */
public class EnvironmentApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code EnvironmentApi}。
     */
    public EnvironmentApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * verifyEnvironment
     * <p>校验环境</p>
     * <p>{@code POST /environment/verify-environment}</p>
     * @param environmentName 必填，环境名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyEnvironment(String environmentName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (environmentName != null) {
            params.put("environmentName", environmentName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/environment/verify-environment", pathVars);
        return http.postForm(path, params);
    }

    /**
     * updateEnvironment
     * <p>更新环境</p>
     * <p>{@code POST /environment/update}</p>
     * @param code 必填，环境编码
     * @param name 必填，环境名称
     * @param config 必填，环境配置
     * @param description 可选，环境描述
     * @param workerGroups 可选，工作组列表
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateEnvironment(Long code, String name, String config, String description,
            String workerGroups) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (code != null) {
            params.put("code", code);
        }
        if (name != null) {
            params.put("name", name);
        }
        if (config != null) {
            params.put("config", config);
        }
        if (description != null) {
            params.put("description", description);
        }
        if (workerGroups != null) {
            params.put("workerGroups", workerGroups);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/environment/update", pathVars);
        return http.postForm(path, params);
    }

    /**
     * deleteEnvironmentByCode
     * <p>按编码删除环境</p>
     * <p>{@code POST /environment/delete}</p>
     * @param environmentCode 必填，环境编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteEnvironment(Long environmentCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (environmentCode != null) {
            params.put("environmentCode", environmentCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/environment/delete", pathVars);
        return http.postForm(path, params);
    }

    /**
     * createEnvironment
     * <p>创建环境</p>
     * <p>{@code POST /environment/create}</p>
     * @param name 必填，环境名称
     * @param config 必填，环境配置
     * @param description 可选，环境描述
     * @param workerGroups 可选，工作组列表
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createEnvironment(String name, String config, String description, String workerGroups) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        if (config != null) {
            params.put("config", config);
        }
        if (description != null) {
            params.put("description", description);
        }
        if (workerGroups != null) {
            params.put("workerGroups", workerGroups);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/environment/create", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryAllEnvironmentList
     * <p>查询所有环境</p>
     * <p>{@code GET /environment/query-environment-list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAllEnvironmentList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/environment/query-environment-list", pathVars);
        return http.get(path, params);
    }

    /**
     * queryEnvironmentByCode
     * <p>按编码查询环境</p>
     * <p>{@code GET /environment/query-by-code}</p>
     * @param environmentCode 必填，环境编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryEnvironmentByCode(Long environmentCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (environmentCode != null) {
            params.put("environmentCode", environmentCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/environment/query-by-code", pathVars);
        return http.get(path, params);
    }

    /**
     * queryEnvironmentListPaging
     * <p>分页查询环境列表</p>
     * <p>{@code GET /environment/list-paging}</p>
     * @param searchVal 可选，搜索值
     * @param pageSize 必填，每页条数
     * @param pageNo 必填，页码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryEnvironmentListPaging(String searchVal, Integer pageSize, Integer pageNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/environment/list-paging", pathVars);
        return http.get(path, params);
    }

}
