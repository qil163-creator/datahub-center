package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * createWorkflowDefinition 请求参数。
 */
public class CreateWorkflowDefinitionRequest {

    private String name;

    private String description;

    private String globalParams;

    private String locations;

    private Integer timeout;

    private String taskRelationJson;

    private String taskDefinitionJson;

    private String otherParamsJson;

    private String executionType;

    /**
     * 返回名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 设置名称。
     */
    public CreateWorkflowDefinitionRequest setName(String name) {
        this.name = name;
        return this;
    }

    /**
     * 返回描述。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置描述。
     */
    public CreateWorkflowDefinitionRequest setDescription(String description) {
        this.description = description;
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
    public CreateWorkflowDefinitionRequest setGlobalParams(String globalParams) {
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
    public CreateWorkflowDefinitionRequest setLocations(String locations) {
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
    public CreateWorkflowDefinitionRequest setTimeout(Integer timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 返回任务依赖关系 JSON。
     */
    public String getTaskRelationJson() {
        return taskRelationJson;
    }

    /**
     * 设置任务依赖关系 JSON。
     */
    public CreateWorkflowDefinitionRequest setTaskRelationJson(String taskRelationJson) {
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
    public CreateWorkflowDefinitionRequest setTaskDefinitionJson(String taskDefinitionJson) {
        this.taskDefinitionJson = taskDefinitionJson;
        return this;
    }

    /**
     * 返回其他参数JSON。
     */
    public String getOtherParamsJson() {
        return otherParamsJson;
    }

    /**
     * 设置其他参数JSON。
     */
    public CreateWorkflowDefinitionRequest setOtherParamsJson(String otherParamsJson) {
        this.otherParamsJson = otherParamsJson;
        return this;
    }

    /**
     * 返回执行类型。
     */
    public String getExecutionType() {
        return executionType;
    }

    /**
     * 设置执行类型。
     */
    public CreateWorkflowDefinitionRequest setExecutionType(String executionType) {
        this.executionType = executionType;
        return this;
    }

    /**
     * 将请求参数转换为远端接口使用的参数映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        if (description != null) {
            params.put("description", description);
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
        if (taskRelationJson != null) {
            params.put("taskRelationJson", taskRelationJson);
        }
        if (taskDefinitionJson != null) {
            params.put("taskDefinitionJson", taskDefinitionJson);
        }
        if (otherParamsJson != null) {
            params.put("otherParamsJson", otherParamsJson);
        }
        if (executionType != null) {
            params.put("executionType", executionType);
        }
        return params;
    }

}
