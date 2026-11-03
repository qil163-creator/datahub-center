package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 监控接口，对应 OpenAPI tag：monitor related operation。
 */
public class MonitorApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code MonitorApi}。
     */
    public MonitorApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * listServer
     * <p>查询服务器列表</p>
     * <p>{@code GET /monitor/{nodeType}}</p>
     * @param nodeType 必填，
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listServer(String nodeType) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("nodeType", nodeType);
        String path = DolphinSchedulerHttp.fillPath("/monitor/{nodeType}", pathVars);
        return http.get(path, params);
    }

    /**
     * queryTaskExecutors
     * <p>{@code GET /monitor/workers/task-executors}</p>
     * @param serverAddress 必填，服务器地址
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTaskExecutors(String serverAddress) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (serverAddress != null) {
            params.put("serverAddress", serverAddress);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/monitor/workers/task-executors", pathVars);
        return http.get(path, params);
    }

    /**
     * queryWorkflowExecutors
     * <p>{@code GET /monitor/masters/workflow-executors}</p>
     * @param masterAddress 必填，主节点地址
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowExecutors(String masterAddress) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (masterAddress != null) {
            params.put("masterAddress", masterAddress);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/monitor/masters/workflow-executors", pathVars);
        return http.get(path, params);
    }

    /**
     * queryDatabaseState
     * <p>查询数据库状态</p>
     * <p>{@code GET /monitor/databases}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryDatabaseState() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/monitor/databases", pathVars);
        return http.get(path, params);
    }

}
