package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流血缘接口，对应 OpenAPI tag：work flow lineage related operation。
 */
public class LineageApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code LineageApi}。
     */
    public LineageApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * verifyTaskCanDelete
     * <p>校验任务是否可以删除</p>
     * <p>{@code POST /projects/{projectCode}/lineages/tasks/verify-delete}</p>
     * @param projectCode 必填，项目编码
     * @param workflowDefinitionCode 必填，工作流定义编码
     * @param taskCode 必填，任务定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyTaskCanDelete(Long projectCode, Long workflowDefinitionCode, Long taskCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowDefinitionCode != null) {
            params.put("workflowDefinitionCode", workflowDefinitionCode);
        }
        if (taskCode != null) {
            params.put("taskCode", taskCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/lineages/tasks/verify-delete", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryLineageByWorkFlowCode
     * <p>按编码列表查询工作流血缘</p>
     * <p>{@code GET /projects/{projectCode}/lineages/{workFlowCode}}</p>
     * @param projectCode 必填，项目编码
     * @param workFlowCode 必填，
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkFlowLineageByCode(Long projectCode, Long workFlowCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("workFlowCode", workFlowCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/lineages/{workFlowCode}", pathVars);
        return http.get(path, params);
    }

    /**
     * verifyTaskCanDelete
     * <p>校验任务是否可以删除</p>
     * <p>{@code GET /projects/{projectCode}/lineages/query-dependent-tasks}</p>
     * @param projectCode 必填，项目编码
     * @param workFlowCode 必填，工作流定义编码
     * @param taskCode 可选，任务编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryDependentTasks(Long projectCode, Long workFlowCode, Long taskCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workFlowCode != null) {
            params.put("workFlowCode", workFlowCode);
        }
        if (taskCode != null) {
            params.put("taskCode", taskCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/lineages/query-dependent-tasks", pathVars);
        return http.get(path, params);
    }

    /**
     * queryLineageByWorkFlowName
     * <p>按名称查询工作流血缘</p>
     * <p>{@code GET /projects/{projectCode}/lineages/query-by-name}</p>
     * @param projectCode 必填，项目编码
     * @param workflowDefinitionName 可选，工作流定义名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkFlowLineageByName(Long projectCode, String workflowDefinitionName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowDefinitionName != null) {
            params.put("workflowDefinitionName", workflowDefinitionName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/lineages/query-by-name", pathVars);
        return http.get(path, params);
    }

    /**
     * queryWorkFlowList
     * <p>查询工作流血缘</p>
     * <p>{@code GET /projects/{projectCode}/lineages/list}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkFlowLineage(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/lineages/list", pathVars);
        return http.get(path, params);
    }

}
