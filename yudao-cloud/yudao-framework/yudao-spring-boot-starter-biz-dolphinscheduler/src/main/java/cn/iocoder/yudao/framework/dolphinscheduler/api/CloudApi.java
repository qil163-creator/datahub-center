package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云资源接口，对应 OpenAPI tag：CLOUD_TAG。
 */
public class CloudApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code CloudApi}。
     */
    public CloudApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * listResourceGroup
     * <p>查询资源组列表</p>
     * <p>{@code GET /cloud/azure/datafactory/resourceGroups}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listResourceGroup() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cloud/azure/datafactory/resourceGroups", pathVars);
        return http.get(path, params);
    }

    /**
     * listPipeline
     * <p>查询流水线列表</p>
     * <p>{@code GET /cloud/azure/datafactory/pipelines}</p>
     * @param factoryName 必填，工厂名称
     * @param resourceGroupName 必填，资源组名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listPipeline(String factoryName, String resourceGroupName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (factoryName != null) {
            params.put("factoryName", factoryName);
        }
        if (resourceGroupName != null) {
            params.put("resourceGroupName", resourceGroupName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cloud/azure/datafactory/pipelines", pathVars);
        return http.get(path, params);
    }

    /**
     * listDataFactory
     * <p>查询数据工厂列表</p>
     * <p>{@code GET /cloud/azure/datafactory/factories}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listDataFactory() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cloud/azure/datafactory/factories", pathVars);
        return http.get(path, params);
    }

}
