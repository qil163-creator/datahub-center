package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * updateWorkflowInstance 请求参数。
 */
public class UpdateWorkflowInstanceRequest {

    private String taskRelationJson;

    private String taskDefinitionJson;

    private String scheduleTime;

    private Boolean syncDefine;

    private String globalParams;

    private String locations;

    private Integer timeout;

    /**
     * 返回任务依赖关系 JSON。
     */
    public String getTaskRelationJson() {
        return taskRelationJson;
    }

    /**
     * 设置任务依赖关系 JSON。
     */
    public UpdateWorkflowInstanceRequest setTaskRelationJson(String taskRelationJson) {
        this.taskRelationJson = taskRelationJson;
        return this;
    }

    /**
     * 返回任务定义 JSON。
     */
    public String getTaskDefinitionJson() {
        return taskDefinitionJson;
    }

    /**
     * 设置任务定义 JSON。
     */
    public UpdateWorkflowInstanceRequest setTaskDefinitionJson(String taskDefinitionJson) {
        this.taskDefinitionJson = taskDefinitionJson;
        return this;
    }

    /**
     * 返回调度时间。
     */
    public String getScheduleTime() {
        return scheduleTime;
    }

    /**
     * 设置调度时间。
     */
    public UpdateWorkflowInstanceRequest setScheduleTime(String scheduleTime) {
        this.scheduleTime = scheduleTime;
        return this;
    }

    /**
     * 返回同步定义标记。
     */
    public Boolean getSyncDefine() {
        return syncDefine;
    }

    /**
     * 设置同步定义标记。
     */
    public UpdateWorkflowInstanceRequest setSyncDefine(Boolean syncDefine) {
        this.syncDefine = syncDefine;
        return this;
    }

    /**
     * 返回全局参数。
     */
    public String getGlobalParams() {
        return globalParams;
    }

    /**
     * 设置全局参数。
     */
    public UpdateWorkflowInstanceRequest setGlobalParams(String globalParams) {
        this.globalParams = globalParams;
        return this;
    }

    /**
     * 返回节点坐标配置。
     */
    public String getLocations() {
        return locations;
    }

    /**
     * 设置节点坐标配置。
     */
    public UpdateWorkflowInstanceRequest setLocations(String locations) {
        this.locations = locations;
        return this;
    }

    /**
     * 返回超时设置。
     */
    public Integer getTimeout() {
        return timeout;
    }

    /**
     * 设置超时设置。
     */
    public UpdateWorkflowInstanceRequest setTimeout(Integer timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 将请求参数转换为远端接口使用的参数映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (taskRelationJson != null) {
            params.put("taskRelationJson", taskRelationJson);
        }
        if (taskDefinitionJson != null) {
            params.put("taskDefinitionJson", taskDefinitionJson);
        }
        if (scheduleTime != null) {
            params.put("scheduleTime", scheduleTime);
        }
        if (syncDefine != null) {
            params.put("syncDefine", syncDefine);
        }
        if (globalParams != null) {
            params.put("globalParams", globalParams);
        }
        if (locations != null) {
            params.put("locations", locations);
        }
        if (timeout != null) {
            params.put("timeout", timeout);
        }
        return params;
    }

}
