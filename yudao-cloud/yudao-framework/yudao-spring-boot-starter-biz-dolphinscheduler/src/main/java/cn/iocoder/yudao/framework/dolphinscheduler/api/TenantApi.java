package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租户接口，对应 OpenAPI tag：tenant related operation。
 */
public class TenantApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code TenantApi}。
     */
    public TenantApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * updateTenant
     * <p>更新租户</p>
     * <p>{@code PUT /tenants/{id}}</p>
     * @param id 必填，租户编号
     * @param tenantCode 必填，操作系统租户编码
     * @param queueId 必填，队列编号
     * @param description 可选，租户描述
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateTenant(Integer id, String tenantCode, Integer queueId, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (tenantCode != null) {
            params.put("tenantCode", tenantCode);
        }
        if (queueId != null) {
            params.put("queueId", queueId);
        }
        if (description != null) {
            params.put("description", description);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/tenants/{id}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * deleteTenantById
     * <p>删除租户</p>
     * <p>{@code DELETE /tenants/{id}}</p>
     * @param id 必填，租户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteTenantById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/tenants/{id}", pathVars);
        return http.delete(path, params);
    }

    /**
     * queryTenantListPaging
     * <p>分页查询租户列表</p>
     * <p>{@code GET /tenants}</p>
     * @param searchVal 可选，搜索值
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTenantListPaging(String searchVal, Integer pageNo, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/tenants", pathVars);
        return http.get(path, params);
    }

    /**
     * createTenant
     * <p>创建租户</p>
     * <p>{@code POST /tenants}</p>
     * @param tenantCode 必填，操作系统租户编码
     * @param queueId 必填，队列编号
     * @param description 可选，租户描述
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createTenant(String tenantCode, Integer queueId, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (tenantCode != null) {
            params.put("tenantCode", tenantCode);
        }
        if (queueId != null) {
            params.put("queueId", queueId);
        }
        if (description != null) {
            params.put("description", description);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/tenants", pathVars);
        return http.postForm(path, params);
    }

    /**
     * verifyTenantCode
     * <p>校验租户编码</p>
     * <p>{@code GET /tenants/verify-code}</p>
     * @param tenantCode 必填，操作系统租户编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyTenantCode(String tenantCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (tenantCode != null) {
            params.put("tenantCode", tenantCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/tenants/verify-code", pathVars);
        return http.get(path, params);
    }

    /**
     * queryTenantList
     * <p>查询租户列表</p>
     * <p>{@code GET /tenants/list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryTenantList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/tenants/list", pathVars);
        return http.get(path, params);
    }

}
