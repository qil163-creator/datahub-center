package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI 插件接口，对应 OpenAPI tag：UI plugin related operation。
 */
public class UiPluginApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code UiPluginApi}。
     */
    public UiPluginApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryUiPluginDetailById
     * <p>按编号查询界面插件详情</p>
     * <p>{@code GET /ui-plugins/{id}}</p>
     * @param id 必填，插件编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryUiPluginDetailById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/ui-plugins/{id}", pathVars);
        return http.get(path, params);
    }

    /**
     * queryProductInfo
     * <p>查询产品信息</p>
     * <p>{@code GET /ui-plugins/query-product-info}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProductInfo() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/ui-plugins/query-product-info", pathVars);
        return http.get(path, params);
    }

    /**
     * queryUiPluginsByType
     * <p>按类型查询界面插件</p>
     * <p>{@code GET /ui-plugins/query-by-type}</p>
     * @param pluginType 必填，插件类型，取值：ALERT / REGISTER / TASK
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryUiPluginsByType(String pluginType) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pluginType != null) {
            params.put("pluginType", pluginType);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/ui-plugins/query-by-type", pathVars);
        return http.get(path, params);
    }

}
