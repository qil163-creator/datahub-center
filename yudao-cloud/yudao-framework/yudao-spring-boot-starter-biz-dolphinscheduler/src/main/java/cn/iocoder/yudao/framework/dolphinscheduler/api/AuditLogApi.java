package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计日志接口，对应 OpenAPI tag：audit log related operation。
 */
public class AuditLogApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code AuditLogApi}。
     */
    public AuditLogApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryAuditOperationTypeList
     * <p>查询审计操作类型列表</p>
     * <p>{@code GET /projects/audit/audit-log-operation-type}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAuditOperationTypeList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/audit/audit-log-operation-type", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAuditModelTypeList
     * <p>查询审计模型类型列表</p>
     * <p>{@code GET /projects/audit/audit-log-model-type}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAuditModelTypeList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/audit/audit-log-model-type", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAuditLogListPaging
     * <p>查询审计日志</p>
     * <p>{@code GET /projects/audit/audit-log-list}</p>
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @param modelTypes 可选，模型类型
     * @param operationTypes 可选，操作类型
     * @param startDate 可选，开始日期
     * @param endDate 可选，结束日期
     * @param userName 可选，用户名
     * @param modelName 可选，模型名称
     * @param objectTypes 可选，对象模型类型
     * @param objectName 可选，对象模型名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAuditLogListPaging(QueryAuditLogListPagingRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/audit/audit-log-list", pathVars);
        return http.get(path, params);
    }

}
