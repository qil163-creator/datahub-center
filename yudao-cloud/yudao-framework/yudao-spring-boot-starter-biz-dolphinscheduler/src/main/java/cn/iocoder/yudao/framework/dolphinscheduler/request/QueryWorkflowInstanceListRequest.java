package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * queryWorkflowInstanceListPaging 请求参数。
 */
public class QueryWorkflowInstanceListRequest {

    private Long workflowDefinitionCode;

    private String searchVal;

    private String executorName;

    private String stateType;

    private String host;

    private String startDate;

    private String endDate;

    private String otherParamsJson;

    private Integer pageNo;

    private Integer pageSize;

    /**
     * 返回工作流定义编码。
     */
    public Long getWorkflowDefinitionCode() {
        return workflowDefinitionCode;
    }

    /**
     * 设置工作流定义编码。
     */
    public QueryWorkflowInstanceListRequest setWorkflowDefinitionCode(Long workflowDefinitionCode) {
        this.workflowDefinitionCode = workflowDefinitionCode;
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
    public QueryWorkflowInstanceListRequest setSearchVal(String searchVal) {
        this.searchVal = searchVal;
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
    public QueryWorkflowInstanceListRequest setExecutorName(String executorName) {
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
    public QueryWorkflowInstanceListRequest setStateType(String stateType) {
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
    public QueryWorkflowInstanceListRequest setHost(String host) {
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
    public QueryWorkflowInstanceListRequest setStartDate(String startDate) {
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
    public QueryWorkflowInstanceListRequest setEndDate(String endDate) {
        this.endDate = endDate;
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
    public QueryWorkflowInstanceListRequest setOtherParamsJson(String otherParamsJson) {
        this.otherParamsJson = otherParamsJson;
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
    public QueryWorkflowInstanceListRequest setPageNo(Integer pageNo) {
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
    public QueryWorkflowInstanceListRequest setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
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
        if (searchVal != null) {
            params.put("searchVal", searchVal);
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
        if (otherParamsJson != null) {
            params.put("otherParamsJson", otherParamsJson);
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
