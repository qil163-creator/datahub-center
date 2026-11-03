package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态任务类型接口，对应 OpenAPI tag：DYNAMIC_TASK_TYPE。
 */
public class DynamicTaskTypeApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code DynamicTaskTypeApi}。
     */
    public DynamicTaskTypeApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * listDynamicTaskTypes
     * <p>查询动态任务类型列表</p>
     * <p>{@code GET /dynamic/{taskCategory}/taskTypes}</p>
     * @param taskCategory 必填，
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listDynamicTaskTypes(String taskCategory) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("taskCategory", taskCategory);
        String path = DolphinSchedulerHttp.fillPath("/dynamic/{taskCategory}/taskTypes", pathVars);
        return http.get(path, params);
    }

    /**
     * listTaskCategories
     * <p>查询任务类型分类列表</p>
     * <p>{@code GET /dynamic/taskCategories}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listDynamicTaskCategories() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/dynamic/taskCategories", pathVars);
        return http.get(path, params);
    }

}
