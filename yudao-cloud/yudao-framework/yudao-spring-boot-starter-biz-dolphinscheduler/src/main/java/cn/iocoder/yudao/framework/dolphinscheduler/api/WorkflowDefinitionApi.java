package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流定义接口，对应 OpenAPI tag：workflow definition related operation。
 */
public class WorkflowDefinitionApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code WorkflowDefinitionApi}。
     */
    public WorkflowDefinitionApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryWorkflowDefinitionByCode
     * <p>按编码查询工作流定义</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/{code}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowDefinitionByCode(Long projectCode, Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/{code}", pathVars);
        return http.get(path, params);
    }

    /**
     * update
     * <p>更新流程定义</p>
     * <p>{@code PUT /projects/{projectCode}/workflow-definition/{code}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @param name 必填，流程定义名称
     * @param description 可选，工作流定义描述
     * @param globalParams 可选，全局参数
     * @param locations 必填，流程定义节点位置信息（JSON 格式）
     * @param timeout 可选，超时时间
     * @param taskRelationJson 必填，任务关系 JSON
     * @param taskDefinitionJson 必填，任务定义 JSON
     * @param executionType 可选，执行类型，取值：PARALLEL / SERIAL_WAIT / SERIAL_DISCARD / SERIAL_PRIORITY
     * @param releaseState 可选，工作流定义发布状态，取值：OFFLINE / ONLINE
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateWorkflowDefinition(Long projectCode, Long code, UpdateWorkflowDefinitionRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/{code}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * deleteByWorkflowDefinitionCode
     * <p>按流程定义编号删除流程定义</p>
     * <p>{@code DELETE /projects/{projectCode}/workflow-definition/{code}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     * @deprecated 工作流定义编码是 64 位整数，使用 {@link #deleteWorkflowDefinitionByCode(Long, Long)}
     */
    @Deprecated
    public JSONObject deleteWorkflowDefinitionByCode(Long projectCode, Integer code) {
        return deleteWorkflowDefinitionByCode(projectCode, code == null ? null : code.longValue());
    }

    /**
     * 删除工作流定义。DS 3.4.2 Controller 的 code 是 long，OpenAPI int32 注解有误。
     * @param projectCode 必填，项目编码
     * @param code 必填，64 位工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteWorkflowDefinitionByCode(Long projectCode, Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/{code}", pathVars);
        return http.delete(path, params);
    }

    /**
     * queryListPaging
     * <p>分页查询工作流定义列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition}</p>
     * @param projectCode 必填，项目编码
     * @param searchVal 可选，搜索值
     * @param otherParamsJson 可选，其他参数 JSON
     * @param userId 可选，用户编号
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowDefinitionListPaging(Long projectCode, String searchVal, String otherParamsJson,
            Integer userId, Integer pageNo, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (otherParamsJson != null) {
            params.put("otherParamsJson", otherParamsJson);
        }
        if (userId != null) {
            params.put("userId", userId);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition", pathVars);
        return http.get(path, params);
    }

    /**
     * createWorkflowDefinition
     * <p>创建流程定义</p>
     * <p>{@code POST /projects/{projectCode}/workflow-definition}</p>
     * @param projectCode 必填，项目编码
     * @param name 必填，流程定义名称
     * @param description 可选，工作流定义描述
     * @param globalParams 可选，全局参数
     * @param locations 必填，流程定义节点位置信息（JSON 格式）
     * @param timeout 可选，超时时间
     * @param taskRelationJson 必填，任务关系 JSON
     * @param taskDefinitionJson 必填，任务定义 JSON
     * @param otherParamsJson 可选，其他参数 JSON
     * @param executionType 可选，执行类型，取值：PARALLEL / SERIAL_WAIT / SERIAL_DISCARD / SERIAL_PRIORITY
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createWorkflowDefinition(Long projectCode, CreateWorkflowDefinitionRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition", pathVars);
        return http.postForm(path, params);
    }

    /**
     * release
     * <p>发布工作流定义</p>
     * <p>{@code POST /projects/{projectCode}/workflow-definition/{code}/release}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @param releaseState 必填，工作流定义发布状态，取值：OFFLINE / ONLINE
     * @param name 必填，流程定义名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject releaseWorkflowDefinition(Long projectCode, Long code, String releaseState, String name) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (releaseState != null) {
            params.put("releaseState", releaseState);
        }
        if (name != null) {
            params.put("name", name);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/{code}/release",
                pathVars);
        return http.postForm(path, params);
    }

    /**
     * batchMoveByCodes
     * <p>移动流程定义</p>
     * <p>{@code POST /projects/{projectCode}/workflow-definition/batch-move}</p>
     * @param projectCode 必填，项目编码
     * @param codes 必填，工作流定义编码列表
     * @param targetProjectCode 必填，目标项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject moveWorkflowDefinition(Long projectCode, String codes, Long targetProjectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (codes != null) {
            params.put("codes", codes);
        }
        if (targetProjectCode != null) {
            params.put("targetProjectCode", targetProjectCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/batch-move", pathVars);
        return http.postForm(path, params);
    }

    /**
     * batchDeleteByCodes
     * <p>按工作流定义编号批量删除工作流定义</p>
     * <p>{@code POST /projects/{projectCode}/workflow-definition/batch-delete}</p>
     * @param projectCode 必填，项目编码
     * @param codes 必填，工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject batchDeleteWorkflowDefinitionByCodes(Long projectCode, String codes) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (codes != null) {
            params.put("codes", codes);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/batch-delete",
                pathVars);
        return http.postForm(path, params);
    }

    /**
     * batchCopyByCodes
     * <p>复制流程定义</p>
     * <p>{@code POST /projects/{projectCode}/workflow-definition/batch-copy}</p>
     * @param projectCode 必填，项目编码
     * @param codes 必填，工作流定义编码列表
     * @param targetProjectCode 必填，目标项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject copyWorkflowDefinition(Long projectCode, String codes, Long targetProjectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (codes != null) {
            params.put("codes", codes);
        }
        if (targetProjectCode != null) {
            params.put("targetProjectCode", targetProjectCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/batch-copy", pathVars);
        return http.postForm(path, params);
    }

    /**
     * viewVariables
     * <p>查询工作流定义的全局变量和局部变量</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/{code}/view-variables}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject viewVariables_1(Long projectCode, Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/{code}/view-variables",
                pathVars);
        return http.get(path, params);
    }

    /**
     * viewTree
     * <p>查看树形结构</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/{code}/view-tree}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @param limit 必填，数量上限
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject viewTree_1(Long projectCode, Long code, Integer limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (limit != null) {
            params.put("limit", limit);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/{code}/view-tree",
                pathVars);
        return http.get(path, params);
    }

    /**
     * queryVersions
     * <p>查询流程定义版本</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/{code}/versions}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowDefinitionVersions(Long projectCode, Long code, Integer pageNo, Integer pageSize) {
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
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/{code}/versions",
                pathVars);
        return http.get(path, params);
    }

    /**
     * switchVersion
     * <p>切换流程定义版本</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/{code}/versions/{version}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @param version 必填，版本
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject switchWorkflowDefinitionVersion(Long projectCode, Long code, Integer version) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        pathVars.put("version", version);
        String path = DolphinSchedulerHttp.fillPath(
                "/projects/{projectCode}/workflow-definition/{code}/versions/{version}", pathVars);
        return http.get(path, params);
    }

    /**
     * deleteVersion
     * <p>删除流程定义版本</p>
     * <p>{@code DELETE /projects/{projectCode}/workflow-definition/{code}/versions/{version}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @param version 必填，版本
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteWorkflowDefinitionVersion(Long projectCode, Long code, Integer version) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        pathVars.put("version", version);
        String path = DolphinSchedulerHttp.fillPath(
                "/projects/{projectCode}/workflow-definition/{code}/versions/{version}", pathVars);
        return http.delete(path, params);
    }

    /**
     * getTasksByDefinitionCode
     * <p>按定义编码查询任务列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/{code}/tasks}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getNodeListByDefinitionCode(Long projectCode, Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/{code}/tasks",
                pathVars);
        return http.get(path, params);
    }

    /**
     * verify-name
     * <p>校验流程定义名称</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/verify-name}</p>
     * @param projectCode 必填，项目编码
     * @param name 必填，流程定义名称
     * @param workflowDefinitionCode 可选，工作流定义编码
     * @param code 可选，工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyWorkflowDefinitionName(Long projectCode, String name, Long workflowDefinitionCode,
            Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        if (workflowDefinitionCode != null) {
            params.put("workflowDefinitionCode", workflowDefinitionCode);
        }
        if (code != null) {
            params.put("code", code);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/verify-name",
                pathVars);
        return http.get(path, params);
    }

    /**
     * querySimpleList
     * <p>查询工作流定义简要列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/simple-list}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowDefinitionSimpleList(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/simple-list",
                pathVars);
        return http.get(path, params);
    }

    /**
     * getWorkflowListByProjectCode
     * <p>按项目编码查询流程定义列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/query-workflow-definition-list}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getWorkflowListByProjectCode(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath(
                "/projects/{projectCode}/workflow-definition/query-workflow-definition-list", pathVars);
        return http.get(path, params);
    }

    /**
     * getTaskListByWorkflowDefinitionCode
     * <p>按流程定义编码查询任务定义列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/query-task-definition-list}</p>
     * @param projectCode 必填，项目编码
     * @param workflowDefinitionCode 必填，工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getTaskListByWorkflowDefinitionCode(Long projectCode, Long workflowDefinitionCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowDefinitionCode != null) {
            params.put("workflowDefinitionCode", workflowDefinitionCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath(
                "/projects/{projectCode}/workflow-definition/query-task-definition-list", pathVars);
        return http.get(path, params);
    }

    /**
     * queryWorkflowDefinitionByName
     * <p>按名称查询流程定义</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/query-by-name}</p>
     * @param projectCode 必填，项目编码
     * @param name 必填，流程定义名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowDefinitionByName(Long projectCode, String name) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/query-by-name",
                pathVars);
        return http.get(path, params);
    }

    /**
     * queryList
     * <p>查询工作流定义列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/list}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkflowDefinitionList(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/list", pathVars);
        return http.get(path, params);
    }

    /**
     * getTaskListByDefinitionCodes
     * <p>按定义编码查询任务列表</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/batch-query-tasks}</p>
     * @param projectCode 必填，项目编码
     * @param codes 必填，工作流定义编码列表
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getNodeListMapByDefinitionCodes(Long projectCode, String codes) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (codes != null) {
            params.put("codes", codes);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/batch-query-tasks",
                pathVars);
        return http.get(path, params);
    }

    /**
     * queryAllByProjectCode
     * <p>按项目编码查询所有流程定义</p>
     * <p>{@code GET /projects/{projectCode}/workflow-definition/all}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAllWorkflowDefinitionByProjectCode(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/workflow-definition/all", pathVars);
        return http.get(path, params);
    }

}
