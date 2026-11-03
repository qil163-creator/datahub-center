package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * queryTaskListPaging 请求参数。
 */
public class QueryTaskListPagingRequest {

    private Integer workflowInstanceId;

    private String workflowInstanceName;

    private String workflowDefinitionName;

    private String searchVal;

    private String taskName;

    private Long taskCode;

    private String executorName;

    private String stateType;

    private String host;

    private String startDate;

    private String endDate;

    private String taskExecuteType;

    private Integer pageNo;

    private Integer pageSize;

    /**
     * 返回工作流实例编号。
     */
    public Integer getWorkflowInstanceId() {
        return workflowInstanceId;
    }

    /**
     * 设置工作流实例编号。
     */
    public QueryTaskListPagingRequest setWorkflowInstanceId(Integer workflowInstanceId) {
        this.workflowInstanceId = workflowInstanceId;
        return this;
    }

    /**
     * 返回工作流实例名称。
     */
    public String getWorkflowInstanceName() {
        return workflowInstanceName;
    }

    /**
     * 设置工作流实例名称。
     */
    public QueryTaskListPagingRequest setWorkflowInstanceName(String workflowInstanceName) {
        this.workflowInstanceName = workflowInstanceName;
        return this;
    }

    /**
     * 返回工作流定义名称。
     */
    public String getWorkflowDefinitionName() {
        return workflowDefinitionName;
    }

    /**
     * 设置工作流定义名称。
     */
    public QueryTaskListPagingRequest setWorkflowDefinitionName(String workflowDefinitionName) {
        this.workflowDefinitionName = workflowDefinitionName;
        return this;
    }

    /**
     * 返回搜索值。
     */
    public String getSearchVal() {
        return searchVal;
    }

    /**
     * 设置搜索值。
     */
    public QueryTaskListPagingRequest setSearchVal(String searchVal) {
        this.searchVal = searchVal;
        return this;
    }

    /**
     * 返回任务名称。
     */
    public String getTaskName() {
        return taskName;
    }

    /**
     * 设置任务名称。
     */
    public QueryTaskListPagingRequest setTaskName(String taskName) {
        this.taskName = taskName;
        return this;
    }

    /**
     * 返回任务编码。
     */
    public Long getTaskCode() {
        return taskCode;
    }

    /**
     * 设置任务编码。
     */
    public QueryTaskListPagingRequest setTaskCode(Long taskCode) {
        this.taskCode = taskCode;
        return this;
    }

    /**
     * 返回执行器名称。
     */
    public String getExecutorName() {
        return executorName;
    }

    /**
     * 设置执行器名称。
     */
    public QueryTaskListPagingRequest setExecutorName(String executorName) {
        this.executorName = executorName;
        return this;
    }

    /**
     * 返回状态类型。
     */
    public String getStateType() {
        return stateType;
    }

    /**
     * 设置状态类型。
     */
    public QueryTaskListPagingRequest setStateType(String stateType) {
        this.stateType = stateType;
        return this;
    }

    /**
     * 返回主机。
     */
    public String getHost() {
        return host;
    }

    /**
     * 设置主机。
     */
    public QueryTaskListPagingRequest setHost(String host) {
        this.host = host;
        return this;
    }

    /**
     * 返回起点日期。
     */
    public String getStartDate() {
        return startDate;
    }

    /**
     * 设置起点日期。
     */
    public QueryTaskListPagingRequest setStartDate(String startDate) {
        this.startDate = startDate;
        return this;
    }

    /**
     * 返回终点日期。
     */
    public String getEndDate() {
        return endDate;
    }

    /**
     * 设置终点日期。
     */
    public QueryTaskListPagingRequest setEndDate(String endDate) {
        this.endDate = endDate;
        return this;
    }

    /**
     * 返回任务执行类型。
     */
    public String getTaskExecuteType() {
        return taskExecuteType;
    }

    /**
     * 设置任务执行类型。
     */
    public QueryTaskListPagingRequest setTaskExecuteType(String taskExecuteType) {
        this.taskExecuteType = taskExecuteType;
        return this;
    }

    /**
     * 返回页码。
     */
    public Integer getPageNo() {
        return pageNo;
    }

    /**
     * 设置页码。
     */
    public QueryTaskListPagingRequest setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
        return this;
    }

    /**
     * 返回每页条数。
     */
    public Integer getPageSize() {
        return pageSize;
    }

    /**
     * 设置每页条数。
     */
    public QueryTaskListPagingRequest setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /**
     * 将请求参数转换为远端接口使用的参数映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowInstanceId != null) {
            params.put("workflowInstanceId", workflowInstanceId);
        }
        if (workflowInstanceName != null) {
            params.put("workflowInstanceName", workflowInstanceName);
        }
        if (workflowDefinitionName != null) {
            params.put("workflowDefinitionName", workflowDefinitionName);
        }
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (taskName != null) {
            params.put("taskName", taskName);
        }
        if (taskCode != null) {
            params.put("taskCode", taskCode);
        }
        if (executorName != null) {
            params.put("executorName", executorName);
        }
        if (stateType != null) {
            params.put("stateType", stateType);
        }
        if (host != null) {
            params.put("host", host);
        }
        if (startDate != null) {
            params.put("startDate", startDate);
        }
        if (endDate != null) {
            params.put("endDate", endDate);
        }
        if (taskExecuteType != null) {
            params.put("taskExecuteType", taskExecuteType);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        return params;
    }

}
