package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * queryAuditLogListPaging 请求参数。
 */
public class QueryAuditLogListPagingRequest {

    private Integer pageNo;

    private Integer pageSize;

    private String modelTypes;

    private String operationTypes;

    private String startDate;

    private String endDate;

    private String userName;

    private String modelName;

    private String objectTypes;

    private String objectName;

    /**
     * 返回页码。
     */
    public Integer getPageNo() {
        return pageNo;
    }

    /**
     * 设置页码。
     */
    public QueryAuditLogListPagingRequest setPageNo(Integer pageNo) {
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
    public QueryAuditLogListPagingRequest setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /**
     * 返回模型类型集合。
     */
    public String getModelTypes() {
        return modelTypes;
    }

    /**
     * 设置模型类型集合。
     */
    public QueryAuditLogListPagingRequest setModelTypes(String modelTypes) {
        this.modelTypes = modelTypes;
        return this;
    }

    /**
     * 返回操作类型集合。
     */
    public String getOperationTypes() {
        return operationTypes;
    }

    /**
     * 设置操作类型集合。
     */
    public QueryAuditLogListPagingRequest setOperationTypes(String operationTypes) {
        this.operationTypes = operationTypes;
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
    public QueryAuditLogListPagingRequest setStartDate(String startDate) {
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
    public QueryAuditLogListPagingRequest setEndDate(String endDate) {
        this.endDate = endDate;
        return this;
    }

    /**
     * 返回用户名。
     */
    public String getUserName() {
        return userName;
    }

    /**
     * 设置用户名。
     */
    public QueryAuditLogListPagingRequest setUserName(String userName) {
        this.userName = userName;
        return this;
    }

    /**
     * 返回模型名称。
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 设置模型名称。
     */
    public QueryAuditLogListPagingRequest setModelName(String modelName) {
        this.modelName = modelName;
        return this;
    }

    /**
     * 返回对象类型集合。
     */
    public String getObjectTypes() {
        return objectTypes;
    }

    /**
     * 设置对象类型集合。
     */
    public QueryAuditLogListPagingRequest setObjectTypes(String objectTypes) {
        this.objectTypes = objectTypes;
        return this;
    }

    /**
     * 返回对象名称。
     */
    public String getObjectName() {
        return objectName;
    }

    /**
     * 设置对象名称。
     */
    public QueryAuditLogListPagingRequest setObjectName(String objectName) {
        this.objectName = objectName;
        return this;
    }

    /**
     * 将请求参数转换为远端接口使用的参数映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        if (modelTypes != null) {
            params.put("modelTypes", modelTypes);
        }
        if (operationTypes != null) {
            params.put("operationTypes", operationTypes);
        }
        if (startDate != null) {
            params.put("startDate", startDate);
        }
        if (endDate != null) {
            params.put("endDate", endDate);
        }
        if (userName != null) {
            params.put("userName", userName);
        }
        if (modelName != null) {
            params.put("modelName", modelName);
        }
        if (objectTypes != null) {
            params.put("objectTypes", objectTypes);
        }
        if (objectName != null) {
            params.put("objectName", objectName);
        }
        return params;
    }

}
