package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务组接口，对应 OpenAPI tag：task group related operation。
 */
public class TaskGroupApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code TaskGroupApi}。
     */
    public TaskGroupApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * update
     * <p>更新任务组</p>
     * <p>{@code POST /task-group/update}</p>
     * @param id 必填，任务组编号
     * @param name 必填，任务组名称
     * @param description 必填，任务组描述
     * @param groupSize 必填，任务组大小
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateTaskGroup(Integer id, String name, String description, Integer groupSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (id != null) {
            params.put("id", id);
        }
        if (name != null) {
            params.put("name", name);
        }
        if (description != null) {
            params.put("description", description);
        }
        if (groupSize != null) {
            params.put("groupSize", groupSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/update", pathVars);
        return http.postForm(path, params);
    }

    /**
     * startTaskGroup
     * <p>启动任务组</p>
     * <p>{@code POST /task-group/start-task-group}</p>
     * @param id 必填，任务组编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject startTaskGroup(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (id != null) {
            params.put("id", id);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/start-task-group", pathVars);
        return http.postForm(path, params);
    }

    /**
     * modifyPriority
     * <p>修改任务组优先级</p>
     * <p>{@code POST /task-group/modifyPriority}</p>
     * @param queueId 必填，任务组队列编号
     * @param priority 必填，任务组队列优先级
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject modifyPriority(Integer queueId, Integer priority) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (queueId != null) {
            params.put("queueId", queueId);
        }
        if (priority != null) {
            params.put("priority", priority);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/modifyPriority", pathVars);
        return http.postForm(path, params);
    }

    /**
     * forceStart
     * <p>强制启动任务组</p>
     * <p>{@code POST /task-group/forceStart}</p>
     * @param queueId 必填，任务组队列编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject forceStart(Integer queueId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (queueId != null) {
            params.put("queueId", queueId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/forceStart", pathVars);
        return http.postForm(path, params);
    }

    /**
     * create
     * <p>创建任务组</p>
     * <p>{@code POST /task-group/create}</p>
     * @param name 必填，名称
     * @param projectCode 可选，项目编码
     * @param description 必填，任务组描述
     * @param groupSize 必填，任务组大小
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createTaskGroup(String name, Long projectCode, String description, Integer groupSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        if (description != null) {
            params.put("description", description);
        }
        if (groupSize != null) {
            params.put("groupSize", groupSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/create", pathVars);
        return http.postForm(path, params);
    }

    /**
     * closeTaskGroup
     * <p>关闭任务组</p>
     * <p>{@code POST /task-group/close-task-group}</p>
     * @param id 必填，编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject closeTaskGroup(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (id != null) {
            params.put("id", id);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/close-task-group", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryTaskGroupByStatus
     * <p>按状态查询任务组列表</p>
     * <p>{@code GET /task-group/query-list-by-status}</p>
     * @param pageNo 必填，页码
     * @param status 必填，任务组状态
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTaskGroupByStatus(Integer pageNo, Integer status, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (status != null) {
            params.put("status", status);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/query-list-by-status", pathVars);
        return http.get(path, params);
    }

    /**
     * queryTaskGroupByName
     * <p>按项目编号查询任务组列表</p>
     * <p>{@code GET /task-group/query-list-by-projectCode}</p>
     * @param pageNo 必填，页码
     * @param projectCode 必填，项目编码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTaskGroupByCode(Integer pageNo, String projectCode, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/query-list-by-projectCode", pathVars);
        return http.get(path, params);
    }

    /**
     * queryTaskGroupQueuesByGroupId
     * <p>按任务组编号查询队列</p>
     * <p>{@code GET /task-group/query-list-by-group-id}</p>
     * @param groupId 可选，组编号
     * @param taskInstanceName 可选，任务实例名称
     * @param workflowInstanceName 可选，工作流实例名称
     * @param status 可选，任务组状态
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTaskGroupQueues(Integer groupId, String taskInstanceName, String workflowInstanceName,
            Integer status, Integer pageNo, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (groupId != null) {
            params.put("groupId", groupId);
        }
        if (taskInstanceName != null) {
            params.put("taskInstanceName", taskInstanceName);
        }
        if (workflowInstanceName != null) {
            params.put("workflowInstanceName", workflowInstanceName);
        }
        if (status != null) {
            params.put("status", status);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/query-list-by-group-id", pathVars);
        return http.get(path, params);
    }

    /**
     * list-paging
     * <p>查询所有任务组</p>
     * <p>{@code GET /task-group/list-paging}</p>
     * @param name 可选，任务组名称
     * @param status 可选，状态
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAllTaskGroup(String name, Integer status, Integer pageNo, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        if (status != null) {
            params.put("status", status);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/task-group/list-paging", pathVars);
        return http.get(path, params);
    }

}
