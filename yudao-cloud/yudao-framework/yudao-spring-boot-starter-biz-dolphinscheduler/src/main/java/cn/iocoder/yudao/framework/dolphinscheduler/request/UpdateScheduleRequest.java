package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * updateSchedule 请求参数。
 */
public class UpdateScheduleRequest {

    private String schedule;

    private String warningType;

    private Integer warningGroupId;

    private String failureStrategy;

    private String workerGroup;

    private String tenantCode;

    private Long environmentCode;

    private String workflowInstancePriority;

    /**
     * 返回调度配置。
     */
    public String getSchedule() {
        return schedule;
    }

    /**
     * 设置调度配置。
     */
    public UpdateScheduleRequest setSchedule(String schedule) {
        this.schedule = schedule;
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
    public UpdateScheduleRequest setWarningType(String warningType) {
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
    public UpdateScheduleRequest setWarningGroupId(Integer warningGroupId) {
        this.warningGroupId = warningGroupId;
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
    public UpdateScheduleRequest setFailureStrategy(String failureStrategy) {
        this.failureStrategy = failureStrategy;
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
    public UpdateScheduleRequest setWorkerGroup(String workerGroup) {
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
    public UpdateScheduleRequest setTenantCode(String tenantCode) {
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
    public UpdateScheduleRequest setEnvironmentCode(Long environmentCode) {
        this.environmentCode = environmentCode;
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
    public UpdateScheduleRequest setWorkflowInstancePriority(String workflowInstancePriority) {
        this.workflowInstancePriority = workflowInstancePriority;
        return this;
    }

    /**
     * 将请求参数转换为远端接口使用的参数映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (schedule != null) {
            params.put("schedule", schedule);
        }
        if (warningType != null) {
            params.put("warningType", warningType);
        }
        if (warningGroupId != null) {
            params.put("warningGroupId", warningGroupId);
        }
        if (failureStrategy != null) {
            params.put("failureStrategy", failureStrategy);
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
        if (workflowInstancePriority != null) {
            params.put("workflowInstancePriority", workflowInstancePriority);
        }
        return params;
    }

}
