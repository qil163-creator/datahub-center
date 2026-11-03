package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警插件实例接口，对应 OpenAPI tag：alert plugin instance related operation。
 */
public class AlertPluginInstanceApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code AlertPluginInstanceApi}。
     */
    public AlertPluginInstanceApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * getAlertPluginInstance
     * <p>获取告警插件实例</p>
     * <p>{@code GET /alert-plugin-instances/{id}}</p>
     * @param id 必填，
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getAlertPluginInstance(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/alert-plugin-instances/{id}", pathVars);
        return http.get(path, params);
    }

    /**
     * updateAlertPluginInstance
     * <p>更新告警插件实例</p>
     * <p>{@code PUT /alert-plugin-instances/{id}}</p>
     * @param id 必填，
     * @param instanceName 必填，告警插件实例名称
     * @param pluginInstanceParams 必填，告警插件实例参数
     * @param alertPluginInstanceId 必填，告警插件实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateAlertPluginInstanceById(Integer id, String instanceName, String pluginInstanceParams,
            Integer alertPluginInstanceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (instanceName != null) {
            params.put("instanceName", instanceName);
        }
        if (pluginInstanceParams != null) {
            params.put("pluginInstanceParams", pluginInstanceParams);
        }
        if (alertPluginInstanceId != null) {
            params.put("alertPluginInstanceId", alertPluginInstanceId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/alert-plugin-instances/{id}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * deleteAlertPluginInstance
     * <p>删除告警插件实例</p>
     * <p>{@code DELETE /alert-plugin-instances/{id}}</p>
     * @param id 必填，告警插件编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteAlertPluginInstance(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/alert-plugin-instances/{id}", pathVars);
        return http.delete(path, params);
    }

    /**
     * queryAlertPluginInstanceListPaging
     * <p>分页查询告警插件实例</p>
     * <p>{@code GET /alert-plugin-instances}</p>
     * @param searchVal 可选，搜索值
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listPaging_1(String searchVal, Integer pageNo, Integer pageSize) {
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
        String path = DolphinSchedulerHttp.fillPath("/alert-plugin-instances", pathVars);
        return http.get(path, params);
    }

    /**
     * createAlertPluginInstance
     * <p>创建告警插件实例</p>
     * <p>{@code POST /alert-plugin-instances}</p>
     * @param pluginDefineId 必填，告警插件定义编号
     * @param instanceName 必填，告警插件实例名称
     * @param pluginInstanceParams 必填，告警插件实例参数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createAlertPluginInstance(Integer pluginDefineId, String instanceName,
            String pluginInstanceParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pluginDefineId != null) {
            params.put("pluginDefineId", pluginDefineId);
        }
        if (instanceName != null) {
            params.put("instanceName", instanceName);
        }
        if (pluginInstanceParams != null) {
            params.put("pluginInstanceParams", pluginInstanceParams);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/alert-plugin-instances", pathVars);
        return http.postForm(path, params);
    }

    /**
     * testSendAlertPluginInstance
     * <p>测试告警插件实例的发送功能</p>
     * <p>{@code POST /alert-plugin-instances/test-send}</p>
     * @param pluginDefineId 必填，告警插件定义编号
     * @param pluginInstanceParams 必填，告警插件实例参数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject testSendAlertPluginInstance(Integer pluginDefineId, String pluginInstanceParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pluginDefineId != null) {
            params.put("pluginDefineId", pluginDefineId);
        }
        if (pluginInstanceParams != null) {
            params.put("pluginInstanceParams", pluginInstanceParams);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/alert-plugin-instances/test-send", pathVars);
        return http.postForm(path, params);
    }

    /**
     * verifyAlertInstanceName
     * <p>校验告警实例名称</p>
     * <p>{@code GET /alert-plugin-instances/verify-name}</p>
     * @param alertInstanceName 必填，告警实例名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyGroupName(String alertInstanceName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (alertInstanceName != null) {
            params.put("alertInstanceName", alertInstanceName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/alert-plugin-instances/verify-name", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAlertPluginInstanceList
     * <p>查询所有告警插件实例</p>
     * <p>{@code GET /alert-plugin-instances/list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getAlertPluginInstance_1() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/alert-plugin-instances/list", pathVars);
        return http.get(path, params);
    }

}
