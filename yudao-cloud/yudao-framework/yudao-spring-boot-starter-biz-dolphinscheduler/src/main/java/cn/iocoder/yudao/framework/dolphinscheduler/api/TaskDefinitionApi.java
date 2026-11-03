package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务定义接口，对应 OpenAPI tag：task definition related operation。
 */
public class TaskDefinitionApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code TaskDefinitionApi}。
     */
    public TaskDefinitionApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * updateWithUpstream
     * <p>更新任务定义</p>
     * <p>{@code PUT /projects/{projectCode}/task-definition/{code}/with-upstream}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，任务定义编码
     * @param taskDefinitionJsonObj 必填，任务定义 JSON
     * @param upstreamCodes 可选，上游编码列表
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateTaskWithUpstream(Long projectCode, Long code, String taskDefinitionJsonObj,
            String upstreamCodes) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (taskDefinitionJsonObj != null) {
            params.put("taskDefinitionJsonObj", taskDefinitionJsonObj);
        }
        if (upstreamCodes != null) {
            params.put("upstreamCodes", upstreamCodes);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-definition/{code}/with-upstream",
                pathVars);
        return http.putForm(path, params);
    }

    /**
     * releaseTaskDefinition
     * <p>发布任务定义</p>
     * <p>{@code POST /projects/{projectCode}/task-definition/{code}/release}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，任务定义编码
     * @param releaseState 必填，工作流定义发布状态，取值：OFFLINE / ONLINE
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject releaseTaskDefinition(Long projectCode, Long code, String releaseState) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (releaseState != null) {
            params.put("releaseState", releaseState);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-definition/{code}/release", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryTaskDefinitionByCode
     * <p>查询任务定义详情</p>
     * <p>{@code GET /projects/{projectCode}/task-definition/{code}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，任务定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTaskDefinitionDetail(Long projectCode, Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-definition/{code}", pathVars);
        return http.get(path, params);
    }

    /**
     * queryVersions
     * <p>查询任务定义版本列表</p>
     * <p>{@code GET /projects/{projectCode}/task-definition/{code}/versions}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，任务定义编码
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTaskDefinitionVersions(Long projectCode, Long code, Integer pageNo, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-definition/{code}/versions",
                pathVars);
        return http.get(path, params);
    }

    /**
     * switchVersion
     * <p>切换任务定义版本</p>
     * <p>{@code GET /projects/{projectCode}/task-definition/{code}/versions/{version}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，任务定义编码
     * @param version 必填，版本
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject switchTaskDefinitionVersion(Long projectCode, Long code, Integer version) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        pathVars.put("version", version);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-definition/{code}/versions/{version}",
                pathVars);
        return http.get(path, params);
    }

    /**
     * deleteVersion
     * <p>删除任务定义版本</p>
     * <p>{@code DELETE /projects/{projectCode}/task-definition/{code}/versions/{version}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，任务定义编码
     * @param version 必填，版本
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteTaskDefinitionVersion(Long projectCode, Long code, Integer version) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        pathVars.put("version", version);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-definition/{code}/versions/{version}",
                pathVars);
        return http.delete(path, params);
    }

    /**
     * genTaskCodeList
     * <p>查询任务编码列表</p>
     * <p>{@code GET /projects/{projectCode}/task-definition/gen-task-codes}</p>
     * @param projectCode 必填，项目编码
     * @param genNum 必填，数量
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject genTaskCodeList(Long projectCode, Integer genNum) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (genNum != null) {
            params.put("genNum", genNum);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-definition/gen-task-codes", pathVars);
        return http.get(path, params);
    }

}
