package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流实例接口，对应 OpenAPI tag：process instance related operation。
 */
public class WorkflowInstanceApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code WorkflowInstanceApi}。
     */
    public WorkflowInstanceApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryWorkflowInstanceById
     * <p>按流程实例编号查询流程实例</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/{id}}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，流程实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowInstanceById(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/{id}", pathVars);
        return http.get(path, params);
    }

    /**
     * updateWorkflowInstance
     * <p>更新流程实例</p>
     * <p>{@code PUT /projects/{projectCode}/workflow-instances/{id}}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，流程实例编号
     * @param taskRelationJson 必填，任务关系 JSON
     * @param taskDefinitionJson 必填，任务定义 JSON
     * @param scheduleTime 可选，调度时间，空字符串表示当天
     * @param syncDefine 必填，是否将流程实例的信息更新到流程定义
     * @param globalParams 可选，工作流全局参数
     * @param locations 可选，流程实例节点位置信息（JSON 格式）
     * @param timeout 可选，工作流超时时间
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateWorkflowInstance(Long projectCode, Integer id, UpdateWorkflowInstanceRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/{id}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * deleteWorkflowInstanceById
     * <p>按流程实例编号删除流程实例</p>
     * <p>{@code DELETE /projects/{projectCode}/workflow-instances/{id}}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，流程实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteWorkflowInstanceById(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/{id}", pathVars);
        return http.delete(path, params);
    }

    /**
     * batchDeleteWorkflowInstanceByIds
     * <p>按工作流编号批量删除工作流实例</p>
     * <p>{@code POST /projects/{projectCode}/workflow-instances/batch-delete}</p>
     * @param projectCode 必填，项目编码
     * @param workflowInstanceIds 必填，工作流实例编号，多个编号以 "," 分隔
     * @return DolphinScheduler 统一 {@code Result}
     * @deprecated 项目编码是 64 位整数，使用 {@link #batchDeleteWorkflowInstanceByIds(Long, String)}
     */
    @Deprecated
    public JSONObject batchDeleteWorkflowInstanceByIds(Integer projectCode, String workflowInstanceIds) {
        return batchDeleteWorkflowInstanceByIds(projectCode == null ? null : projectCode.longValue(),
                workflowInstanceIds);
    }

    /**
     * 批量删除流程实例。DS 3.4.2 Controller 的 projectCode 是 long，OpenAPI int32 注解有误。
     * @param projectCode 必填，64 位项目编码
     * @param workflowInstanceIds 必填，逗号分隔的流程实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject batchDeleteWorkflowInstanceByIds(Long projectCode, String workflowInstanceIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowInstanceIds != null) {
            params.put("workflowInstanceIds", workflowInstanceIds);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/batch-delete",
                pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryWorkflowInstanceListPaging
     * <p>查询流程实例列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances}</p>
     * @param projectCode 必填，项目编码
     * @param workflowDefinitionCode 可选，工作流定义编码
     * @param searchVal 可选，搜索值
     * @param executorName 可选，执行人名称
     * @param stateType 可选，工作流和任务节点的运行状态，取值：SUBMITTED_SUCCESS / RUNNING_EXECUTION / READY_PAUSE / PAUSE / READY_STOP / STOP / FAILURE / SUCCESS / SERIAL_WAIT / FAILOVER
     * @param host 可选，运行任务的 IP 地址
     * @param startDate 可选，开始日期
     * @param endDate 可选，结束日期
     * @param otherParamsJson 可选，其他参数 JSON
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowInstanceList(Long projectCode, QueryWorkflowInstanceListRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances", pathVars);
        return http.get(path, params);
    }

    /**
     * viewVariables
     * <p>查询流程实例的全局变量和局部变量</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/{id}/view-variables}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，流程实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject viewVariables(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/{id}/view-variables",
                pathVars);
        return http.get(path, params);
    }

    /**
     * vieGanttTree
     * <p>查看甘特图</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/{id}/view-gantt}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，流程实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject viewTree(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/{id}/view-gantt",
                pathVars);
        return http.get(path, params);
    }

    /**
     * queryTaskListByWorkflowInstanceId
     * <p>按流程实例编号查询任务列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/{id}/tasks}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，流程实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTaskListByWorkflowInstanceId(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/{id}/tasks", pathVars);
        return http.get(path, params);
    }

    /**
     * queryWorkflowInstanceListByTrigger
     * <p>按触发条件查询工作流实例</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/trigger}</p>
     * @param projectCode 必填，项目编码
     * @param triggerCode 必填，触发编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowInstancesByTriggerCode(Long projectCode, Long triggerCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (triggerCode != null) {
            params.put("triggerCode", triggerCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/trigger", pathVars);
        return http.get(path, params);
    }

    /**
     * queryTopNLongestRunningWorkflowInstance
     * <p>查询运行时间最长的前 N 个工作流实例</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/top-n}</p>
     * @param projectCode 必填，项目编码
     * @param size 必填，工作流实例数量
     * @param startTime 必填，工作流实例开始时间
     * @param endTime 必填，工作流实例结束时间
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTopNLongestRunningWorkflowInstance(Long projectCode, Integer size, String startTime,
            String endTime) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (size != null) {
            params.put("size", size);
        }
        if (startTime != null) {
            params.put("startTime", startTime);
        }
        if (endTime != null) {
            params.put("endTime", endTime);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/top-n", pathVars);
        return http.get(path, params);
    }

    /**
     * querySubWorkflowInstanceByTaskCode
     * <p>按任务编码查询子工作流实例</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/query-sub-by-parent}</p>
     * @param projectCode 必填，项目编码
     * @param taskId 必填，任务编号
     * @param taskCode 必填，任务编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject querySubWorkflowInstanceByTaskId(Long projectCode, Integer taskId, Long taskCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (taskId != null) {
            params.put("taskId", taskId);
        }
        if (taskCode != null) {
            params.put("taskCode", taskCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/query-sub-by-parent",
                pathVars);
        return http.get(path, params);
    }

    /**
     * queryParentInstanceBySubId
     * <p>按子流程实例编号查询父流程实例信息</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/query-parent-by-sub}</p>
     * @param projectCode 必填，项目编码
     * @param subId 必填，子流程实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryParentInstanceBySubId(Long projectCode, Integer subId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (subId != null) {
            params.put("subId", subId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-instances/query-parent-by-sub",
                pathVars);
        return http.get(path, params);
    }

    /**
     * queryDynamicSubWorkflowInstances
     * <p>按任务编码查询动态子工作流实例</p>
     * <p>{@code GET /projects/{projectCode}/workflow-instances/query-dynamic-sub-workflows}</p>
     * @param projectCode 必填，项目编码
     * @param taskId 必填，任务实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryDynamicSubWorkflowInstances(Long projectCode, Integer taskId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (taskId != null) {
            params.put("taskId", taskId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath(
                "/projects/{projectCode}/workflow-instances/query-dynamic-sub-workflows", pathVars);
        return http.get(path, params);
    }

}
