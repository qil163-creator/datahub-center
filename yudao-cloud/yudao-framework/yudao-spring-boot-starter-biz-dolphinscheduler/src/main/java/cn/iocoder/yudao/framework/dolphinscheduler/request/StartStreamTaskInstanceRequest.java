package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * startTaskInstance 请求参数。
 */
public class StartStreamTaskInstanceRequest {

    private Integer version;

    private Integer warningGroupId;

    private String workerGroup;

    private String tenantCode;

    private Long environmentCode;

    private String startParams;

    private Integer dryRun;

    private String failureStrategy;

    private String execType;

    private String warningType;

    private Integer timeout;

    /**
     * 返回版本。
     */
    public Integer getVersion() {
        return version;
    }

    /**
     * 设置版本。
     */
    public StartStreamTaskInstanceRequest setVersion(Integer version) {
        this.version = version;
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
    public StartStreamTaskInstanceRequest setWarningGroupId(Integer warningGroupId) {
        this.warningGroupId = warningGroupId;
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
    public StartStreamTaskInstanceRequest setWorkerGroup(String workerGroup) {
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
    public StartStreamTaskInstanceRequest setTenantCode(String tenantCode) {
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
    public StartStreamTaskInstanceRequest setEnvironmentCode(Long environmentCode) {
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
    public StartStreamTaskInstanceRequest setStartParams(String startParams) {
        this.startParams = startParams;
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
    public StartStreamTaskInstanceRequest setDryRun(Integer dryRun) {
        this.dryRun = dryRun;
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
    public StartStreamTaskInstanceRequest setFailureStrategy(String failureStrategy) {
        this.failureStrategy = failureStrategy;
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
    public StartStreamTaskInstanceRequest setExecType(String execType) {
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
    public StartStreamTaskInstanceRequest setWarningType(String warningType) {
        this.warningType = warningType;
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
    public StartStreamTaskInstanceRequest setTimeout(Integer timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 将请求参数转换为远端接口使用的参数映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (version != null) {
            params.put("version", version);
        }
        if (warningGroupId != null) {
            params.put("warningGroupId", warningGroupId);
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
        if (dryRun != null) {
            params.put("dryRun", dryRun);
        }
        if (failureStrategy != null) {
            params.put("failureStrategy", failureStrategy);
        }
        if (execType != null) {
            params.put("execType", execType);
        }
        if (warningType != null) {
            params.put("warningType", warningType);
        }
        if (timeout != null) {
            params.put("timeout", timeout);
        }
        return params;
    }

}
