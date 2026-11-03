package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警组接口，对应 OpenAPI tag：alert group related operation。
 */
public class AlertGroupApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code AlertGroupApi}。
     */
    public AlertGroupApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * updateAlertGroup
     * <p>更新告警组</p>
     * <p>{@code PUT /alert-groups/{id}}</p>
     * @param id 必填，告警组编号
     * @param groupName 必填，组名称
     * @param description 可选，描述
     * @param alertInstanceIds 必填，告警实例编号（字符串形式，多个实例以 "," 分隔）
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateAlertGroupById(Integer id, String groupName, String description, String alertInstanceIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (groupName != null) {
            params.put("groupName", groupName);
        }
        if (description != null) {
            params.put("description", description);
        }
        if (alertInstanceIds != null) {
            params.put("alertInstanceIds", alertInstanceIds);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/alert-groups/{id}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * delAlertGroupById
     * <p>按编号删除告警组</p>
     * <p>{@code DELETE /alert-groups/{id}}</p>
     * @param id 必填，告警组编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteAlertGroupById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/alert-groups/{id}", pathVars);
        return http.delete(path, params);
    }

    /**
     * queryAlertGroupListPaging
     * <p>分页查询告警组列表</p>
     * <p>{@code GET /alert-groups}</p>
     * @param searchVal 可选，搜索值
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listPaging_2(String searchVal, Integer pageNo, Integer pageSize) {
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
        String path = DolphinSchedulerHttp.fillPath("/alert-groups", pathVars);
        return http.get(path, params);
    }

    /**
     * createAlertGroup
     * <p>创建告警组</p>
     * <p>{@code POST /alert-groups}</p>
     * @param groupName 必填，组名称
     * @param description 可选，描述
     * @param alertInstanceIds 必填，告警实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createAlertGroup(String groupName, String description, String alertInstanceIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (groupName != null) {
            params.put("groupName", groupName);
        }
        if (description != null) {
            params.put("description", description);
        }
        if (alertInstanceIds != null) {
            params.put("alertInstanceIds", alertInstanceIds);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/alert-groups", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryAlertGroupById
     * <p>按编号查询告警组</p>
     * <p>{@code POST /alert-groups/query}</p>
     * @param id 必填，告警组编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAlertGroupById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (id != null) {
            params.put("id", id);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/alert-groups/query", pathVars);
        return http.postForm(path, params);
    }

    /**
     * verifyGroupName
     * <p>校验告警组名称，检查告警组是否存在</p>
     * <p>{@code GET /alert-groups/verify-name}</p>
     * @param groupName 必填，组名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyGroupName_1(String groupName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (groupName != null) {
            params.put("groupName", groupName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/alert-groups/verify-name", pathVars);
        return http.get(path, params);
    }

    /**
     * listAlertGroupById
     * <p>查询告警组列表</p>
     * <p>{@code GET /alert-groups/list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject list() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/alert-groups/list", pathVars);
        return http.get(path, params);
    }

}
