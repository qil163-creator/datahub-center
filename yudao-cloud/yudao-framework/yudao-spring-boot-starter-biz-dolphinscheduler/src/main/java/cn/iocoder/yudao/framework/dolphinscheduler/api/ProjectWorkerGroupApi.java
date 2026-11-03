package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目 Worker 分组接口，对应 OpenAPI tag：PROJECT_WORKER_GROUP_TAG。
 */
public class ProjectWorkerGroupApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code ProjectWorkerGroupApi}。
     */
    public ProjectWorkerGroupApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryAssignedWorkerGroups
     * <p>查询工作组列表</p>
     * <p>{@code GET /projects/{projectCode}/worker-group}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAssignedWorkerGroups(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/worker-group", pathVars);
        return http.get(path, params);
    }

    /**
     * assignWorkerGroups
     * <p>分配工作组</p>
     * <p>{@code POST /projects/{projectCode}/worker-group}</p>
     * @param projectCode 必填，项目编码
     * @param workerGroups 可选，工作组列表
     * @param body 可选，JSON 请求体
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject assignWorkerGroups(Long projectCode, String workerGroups, Object body) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workerGroups != null) {
            params.put("workerGroups", workerGroups);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/worker-group", pathVars);
        return http.postJson(path, params, body);
    }

}
