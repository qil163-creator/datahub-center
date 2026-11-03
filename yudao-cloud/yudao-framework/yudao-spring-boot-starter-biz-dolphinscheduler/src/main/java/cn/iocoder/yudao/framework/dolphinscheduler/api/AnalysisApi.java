package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统计分析接口，对应 OpenAPI tag：analysis related operation of task state。
 */
public class AnalysisApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code AnalysisApi}。
     */
    public AnalysisApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * countWorkflowInstanceState
     * <p>统计流程实例状态</p>
     * <p>{@code GET /projects/analysis/workflow-state-count}</p>
     * @param startDate 可选，开始日期
     * @param endDate 可选，结束日期
     * @param projectCode 可选，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getWorkflowInstanceStateCount(String startDate, String endDate, Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (startDate != null) {
            params.put("startDate", startDate);
        }
        if (endDate != null) {
            params.put("endDate", endDate);
        }
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/analysis/workflow-state-count", pathVars);
        return http.get(path, params);
    }

    /**
     * countTaskState
     * <p>统计任务状态</p>
     * <p>{@code GET /projects/analysis/task-state-count}</p>
     * @param startDate 可选，开始日期
     * @param endDate 可选，结束日期
     * @param projectCode 可选，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getTaskInstanceStateCount(String startDate, String endDate, Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (startDate != null) {
            params.put("startDate", startDate);
        }
        if (endDate != null) {
            params.put("endDate", endDate);
        }
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/analysis/task-state-count", pathVars);
        return http.get(path, params);
    }

    /**
     * countQueueState
     * <p>统计队列中任务的运行状态</p>
     * <p>{@code GET /projects/analysis/queue-count}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject countQueueState() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/analysis/queue-count", pathVars);
        return http.get(path, params);
    }

    /**
     * listErrorCommand
     * <p>分页查询错误命令列表</p>
     * <p>{@code GET /projects/analysis/listErrorCommand}</p>
     * @param projectCode 可选，项目编码
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @param searchVal 可选，搜索值
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listErrorCommand(Long projectCode, Integer pageNo, Integer pageSize, String searchVal) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/analysis/listErrorCommand", pathVars);
        return http.get(path, params);
    }

    /**
     * listPendingCommands
     * <p>查询待处理命令列表</p>
     * <p>{@code GET /projects/analysis/listCommand}</p>
     * @param projectCode 可选，项目编码
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @param searchVal 可选，搜索值
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listPaging(Long projectCode, Integer pageNo, Integer pageSize, String searchVal) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/analysis/listCommand", pathVars);
        return http.get(path, params);
    }

    /**
     * countDefinitionByUser
     * <p>按用户统计流程定义</p>
     * <p>{@code GET /projects/analysis/define-user-count}</p>
     * @param projectCode 可选，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject countDefinitionByUser(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/analysis/define-user-count", pathVars);
        return http.get(path, params);
    }

    /**
     * countCommandState
     * <p>统计命令状态</p>
     * <p>{@code GET /projects/analysis/command-state-count}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject countCommandState() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/analysis/command-state-count", pathVars);
        return http.get(path, params);
    }

}
