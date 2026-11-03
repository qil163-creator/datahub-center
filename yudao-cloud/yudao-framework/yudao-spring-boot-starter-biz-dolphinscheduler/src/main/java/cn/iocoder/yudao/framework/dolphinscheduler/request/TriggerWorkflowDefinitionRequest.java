package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * startWorkflowInstance 请求参数。
 */
public class TriggerWorkflowDefinitionRequest {

    private Long workflowDefinitionCode;

    private String scheduleTime;

    private String failureStrategy;

    private String startNodeList;

    private String taskDependType;

    private String execType;

    private String warningType;

    private Integer warningGroupId;

    private String runMode;

    private String workflowInstancePriority;

    private String workerGroup;

    private String tenantCode;

    private Long environmentCode;

    private String startParams;

    private Integer expectedParallelismNumber;

    private Integer dryRun;

    private String complementDependentMode;

    private Boolean allLevelDependent;

    private String executionOrder;

    private Integer timeout;

    /**
     * 返回工作流定义编码。
     */
    public Long getWorkflowDefinitionCode() {
        return workflowDefinitionCode;
    }

    /**
     * 设置工作流定义编码。
     */
    public TriggerWorkflowDefinitionRequest setWorkflowDefinitionCode(Long workflowDefinitionCode) {
        this.workflowDefinitionCode = workflowDefinitionCode;
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
    public TriggerWorkflowDefinitionRequest setScheduleTime(String scheduleTime) {
        this.scheduleTime = scheduleTime;
        return this;
    }

    /**
     * 返回失败处理策略。
     */
    public String getFailureStrategy() {
        return failureStrategy;
    }

    /**
     * 设置失败处理策略。
     */
    public TriggerWorkflowDefinitionRequest setFailureStrategy(String failureStrategy) {
        this.failureStrategy = failureStrategy;
        return this;
    }

    /**
     * 返回起始节点列表。
     */
    public String getStartNodeList() {
        return startNodeList;
    }

    /**
     * 设置起始节点列表。
     */
    public TriggerWorkflowDefinitionRequest setStartNodeList(String startNodeList) {
        this.startNodeList = startNodeList;
        return this;
    }

    /**
     * 返回任务依赖类型。
     */
    public String getTaskDependType() {
        return taskDependType;
    }

    /**
     * 设置任务依赖类型。
     */
    public TriggerWorkflowDefinitionRequest setTaskDependType(String taskDependType) {
        this.taskDependType = taskDependType;
        return this;
    }

    /**
     * 返回执行类型。
     */
    public String getExecType() {
        return execType;
    }

    /**
     * 设置执行类型。
     */
    public TriggerWorkflowDefinitionRequest setExecType(String execType) {
        this.execType = execType;
        return this;
    }

    /**
     * 返回告警类型。
     */
    public String getWarningType() {
        return warningType;
    }

    /**
     * 设置告警类型。
     */
    public TriggerWorkflowDefinitionRequest setWarningType(String warningType) {
        this.warningType = warningType;
        return this;
    }

    /**
     * 返回告警组编号。
     */
    public Integer getWarningGroupId() {
        return warningGroupId;
    }

    /**
     * 设置告警组编号。
     */
    public TriggerWorkflowDefinitionRequest setWarningGroupId(Integer warningGroupId) {
        this.warningGroupId = warningGroupId;
        return this;
    }

    /**
     * 返回运行模式。
     */
    public String getRunMode() {
        return runMode;
    }

    /**
     * 设置运行模式。
     */
    public TriggerWorkflowDefinitionRequest setRunMode(String runMode) {
        this.runMode = runMode;
        return this;
    }

    /**
     * 返回工作流实例优先级。
     */
    public String getWorkflowInstancePriority() {
        return workflowInstancePriority;
    }

    /**
     * 设置工作流实例优先级。
     */
    public TriggerWorkflowDefinitionRequest setWorkflowInstancePriority(String workflowInstancePriority) {
        this.workflowInstancePriority = workflowInstancePriority;
        return this;
    }

    /**
     * 返回Worker 分组。
     */
    public String getWorkerGroup() {
        return workerGroup;
    }

    /**
     * 设置Worker 分组。
     */
    public TriggerWorkflowDefinitionRequest setWorkerGroup(String workerGroup) {
        this.workerGroup = workerGroup;
        return this;
    }

    /**
     * 返回DolphinScheduler 执行租户编码。
     */
    public String getTenantCode() {
        return tenantCode;
    }

    /**
     * 设置DolphinScheduler 执行租户编码。
     */
    public TriggerWorkflowDefinitionRequest setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
        return this;
    }

    /**
     * 返回执行环境编码。
     */
    public Long getEnvironmentCode() {
        return environmentCode;
    }

    /**
     * 设置执行环境编码。
     */
    public TriggerWorkflowDefinitionRequest setEnvironmentCode(Long environmentCode) {
        this.environmentCode = environmentCode;
        return this;
    }

    /**
     * 返回启动参数。
     */
    public String getStartParams() {
        return startParams;
    }

    /**
     * 设置启动参数。
     */
    public TriggerWorkflowDefinitionRequest setStartParams(String startParams) {
        this.startParams = startParams;
        return this;
    }

    /**
     * 返回预期并发度数值。
     */
    public Integer getExpectedParallelismNumber() {
        return expectedParallelismNumber;
    }

    /**
     * 设置预期并发度数值。
     */
    public TriggerWorkflowDefinitionRequest setExpectedParallelismNumber(Integer expectedParallelismNumber) {
        this.expectedParallelismNumber = expectedParallelismNumber;
        return this;
    }

    /**
     * 返回空跑标记。
     */
    public Integer getDryRun() {
        return dryRun;
    }

    /**
     * 设置空跑标记。
     */
    public TriggerWorkflowDefinitionRequest setDryRun(Integer dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /**
     * 返回补数依赖模式。
     */
    public String getComplementDependentMode() {
        return complementDependentMode;
    }

    /**
     * 设置补数依赖模式。
     */
    public TriggerWorkflowDefinitionRequest setComplementDependentMode(String complementDependentMode) {
        this.complementDependentMode = complementDependentMode;
        return this;
    }

    /**
     * 返回全部级别依赖。
     */
    public Boolean getAllLevelDependent() {
        return allLevelDependent;
    }

    /**
     * 设置全部级别依赖。
     */
    public TriggerWorkflowDefinitionRequest setAllLevelDependent(Boolean allLevelDependent) {
        this.allLevelDependent = allLevelDependent;
        return this;
    }

    /**
     * 返回执行顺序。
     */
    public String getExecutionOrder() {
        return executionOrder;
    }

    /**
     * 设置执行顺序。
     */
    public TriggerWorkflowDefinitionRequest setExecutionOrder(String executionOrder) {
        this.executionOrder = executionOrder;
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
    public TriggerWorkflowDefinitionRequest setTimeout(Integer timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 将请求参数转换为远端接口使用的参数映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowDefinitionCode != null) {
            params.put("workflowDefinitionCode", workflowDefinitionCode);
        }
        if (scheduleTime != null) {
            params.put("scheduleTime", scheduleTime);
        }
        if (failureStrategy != null) {
            params.put("failureStrategy", failureStrategy);
        }
        if (startNodeList != null) {
            params.put("startNodeList", startNodeList);
        }
        if (taskDependType != null) {
            params.put("taskDependType", taskDependType);
        }
        if (execType != null) {
            params.put("execType", execType);
        }
        if (warningType != null) {
            params.put("warningType", warningType);
        }
        if (warningGroupId != null) {
            params.put("warningGroupId", warningGroupId);
        }
        if (runMode != null) {
            params.put("runMode", runMode);
        }
        if (workflowInstancePriority != null) {
            params.put("workflowInstancePriority", workflowInstancePriority);
        }
        if (workerGroup != null) {
            params.put("workerGroup", workerGroup);
        }
        if (tenantCode != null) {
            params.put("tenantCode", tenantCode);
        }
        if (environmentCode != null) {
            params.put("environmentCode", environmentCode);
        }
        if (startParams != null) {
            params.put("startParams", startParams);
        }
        if (expectedParallelismNumber != null) {
            params.put("expectedParallelismNumber", expectedParallelismNumber);
        }
        if (dryRun != null) {
            params.put("dryRun", dryRun);
        }
        if (complementDependentMode != null) {
            params.put("complementDependentMode", complementDependentMode);
        }
        if (allLevelDependent != null) {
            params.put("allLevelDependent", allLevelDependent);
        }
        if (executionOrder != null) {
            params.put("executionOrder", executionOrder);
        }
        if (timeout != null) {
            params.put("timeout", timeout);
        }
        return params;
    }

}
