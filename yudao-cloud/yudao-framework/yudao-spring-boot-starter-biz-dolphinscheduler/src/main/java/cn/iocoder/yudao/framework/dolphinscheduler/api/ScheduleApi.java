package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调度接口，对应 OpenAPI tag：schedule related operation。
 */
public class ScheduleApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code ScheduleApi}。
     */
    public ScheduleApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * updateSchedule
     * <p>更新调度配置</p>
     * <p>{@code PUT /projects/{projectCode}/schedules/{id}}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，调度编号
     * @param schedule 必填，调度配置
     * @param warningType 可选，告警类型（发送策略），取值：NONE / SUCCESS / FAILURE / ALL
     * @param warningGroupId 可选，告警组编号
     * @param failureStrategy 可选，失败策略，取值：END / CONTINUE
     * @param workerGroup 可选，工作组
     * @param tenantCode 可选，操作系统租户编码
     * @param environmentCode 可选，环境编码
     * @param workflowInstancePriority 可选，工作流实例优先级，取值：HIGHEST / HIGH / MEDIUM / LOW / LOWEST
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateSchedule(Long projectCode, Integer id, UpdateScheduleRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules/{id}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * deleteScheduleById
     * <p>按编号删除调度配置</p>
     * <p>{@code DELETE /projects/{projectCode}/schedules/{id}}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，调度编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteScheduleById(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules/{id}", pathVars);
        return http.delete(path, params);
    }

    /**
     * updateScheduleByWorkflowDefinitionCode
     * <p>按工作流定义编码更新调度配置</p>
     * <p>{@code PUT /projects/{projectCode}/schedules/update/{code}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，
     * @param schedule 必填，调度配置
     * @param warningType 可选，告警类型（发送策略），取值：NONE / SUCCESS / FAILURE / ALL
     * @param warningGroupId 可选，告警组编号
     * @param failureStrategy 可选，失败策略，取值：END / CONTINUE
     * @param workerGroup 可选，工作组
     * @param tenantCode 可选，操作系统租户编码
     * @param environmentCode 可选，环境编码
     * @param workflowInstancePriority 可选，工作流实例优先级，取值：HIGHEST / HIGH / MEDIUM / LOW / LOWEST
     * @param workflowDefinitionCode 必填，工作流定义编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateScheduleByWorkflowDefinitionCode(Long projectCode, Long code,
            UpdateScheduleByWorkflowDefinitionCodeRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules/update/{code}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * queryScheduleListPaging
     * <p>分页查询调度配置列表</p>
     * <p>{@code GET /projects/{projectCode}/schedules}</p>
     * @param projectCode 必填，项目编码
     * @param workflowDefinitionCode 可选，工作流定义编码
     * @param searchVal 可选，搜索值
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryScheduleListPaging(Long projectCode, Long workflowDefinitionCode, String searchVal,
            Integer pageNo, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowDefinitionCode != null) {
            params.put("workflowDefinitionCode", workflowDefinitionCode);
        }
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules", pathVars);
        return http.get(path, params);
    }

    /**
     * createSchedule
     * <p>创建调度配置</p>
     * <p>{@code POST /projects/{projectCode}/schedules}</p>
     * @param projectCode 必填，项目编码
     * @param workflowDefinitionCode 必填，工作流定义编码
     * @param schedule 必填，调度配置
     * @param warningType 可选，告警类型（发送策略），取值：NONE / SUCCESS / FAILURE / ALL
     * @param warningGroupId 可选，告警组编号
     * @param failureStrategy 可选，失败策略，取值：END / CONTINUE
     * @param workerGroup 可选，工作组
     * @param tenantCode 可选，操作系统租户编码
     * @param environmentCode 可选，环境编码
     * @param workflowInstancePriority 可选，工作流实例优先级，取值：HIGHEST / HIGH / MEDIUM / LOW / LOWEST
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createSchedule(Long projectCode, CreateScheduleRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules", pathVars);
        return http.postForm(path, params);
    }

    /**
     * online
     * <p>上线调度配置</p>
     * <p>{@code POST /projects/{projectCode}/schedules/{id}/online}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，调度编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject publishScheduleOnline(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules/{id}/online", pathVars);
        return http.postForm(path, params);
    }

    /**
     * offline
     * <p>下线调度配置</p>
     * <p>{@code POST /projects/{projectCode}/schedules/{id}/offline}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，调度编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject offlineSchedule(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules/{id}/offline", pathVars);
        return http.postForm(path, params);
    }

    /**
     * previewSchedule
     * <p>预览调度计划</p>
     * <p>{@code POST /projects/{projectCode}/schedules/preview}</p>
     * @param projectCode 必填，项目编码
     * @param schedule 必填，调度配置
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject previewSchedule(Long projectCode, String schedule) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (schedule != null) {
            params.put("schedule", schedule);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules/preview", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryScheduleList
     * <p>查询调度配置列表</p>
     * <p>{@code POST /projects/{projectCode}/schedules/list}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryScheduleList(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/schedules/list", pathVars);
        return http.postForm(path, params);
    }

}
