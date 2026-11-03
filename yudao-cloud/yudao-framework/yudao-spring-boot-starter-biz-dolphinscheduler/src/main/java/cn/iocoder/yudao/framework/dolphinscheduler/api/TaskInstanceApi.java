package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务实例接口，对应 OpenAPI tag：task instance related operation。
 */
public class TaskInstanceApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code TaskInstanceApi}。
     */
    public TaskInstanceApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * stop
     * <p>停止任务实例</p>
     * <p>{@code POST /projects/{projectCode}/task-instances/{id}/stop}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，任务实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject stopTask(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-instances/{id}/stop", pathVars);
        return http.postForm(path, params);
    }

    /**
     * savepoint
     * <p>创建任务保存点</p>
     * <p>{@code POST /projects/{projectCode}/task-instances/{id}/savepoint}</p>
     * @param projectCode 必填，项目编码
     * @param id 必填，任务实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject taskSavePoint(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-instances/{id}/savepoint", pathVars);
        return http.postForm(path, params);
    }

    /**
     * force-success
     * <p>强制将任务设为成功</p>
     * <p>{@code POST /projects/{projectCode}/task-instances/{id}/force-success}</p>
     * @param projectCode 必填，
     * @param id 必填，任务实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject forceTaskSuccess(Long projectCode, Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-instances/{id}/force-success",
                pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryTaskListPaging
     * <p>分页查询任务实例列表</p>
     * <p>{@code GET /projects/{projectCode}/task-instances}</p>
     * @param projectCode 必填，项目编码
     * @param workflowInstanceId 可选，流程实例编号
     * @param workflowInstanceName 可选，工作流实例名称
     * @param workflowDefinitionName 可选，工作流定义名称
     * @param searchVal 可选，搜索值
     * @param taskName 可选，任务实例名称
     * @param taskCode 可选，任务编码
     * @param executorName 可选，执行人名称
     * @param stateType 可选，工作流和任务节点的运行状态，取值：SUBMITTED_SUCCESS / RUNNING_EXECUTION / PAUSE / FAILURE / SUCCESS / NEED_FAULT_TOLERANCE / KILL / DELAY_EXECUTION / FORCED_SUCCESS / DISPATCH
     * @param host 可选，运行任务的 IP 地址
     * @param startDate 可选，开始日期
     * @param endDate 可选，结束日期
     * @param taskExecuteType 可选，任务执行类型，取值：BATCH / STREAM
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTaskListPaging(Long projectCode, QueryTaskListPagingRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/task-instances", pathVars);
        return http.get(path, params);
    }

}
