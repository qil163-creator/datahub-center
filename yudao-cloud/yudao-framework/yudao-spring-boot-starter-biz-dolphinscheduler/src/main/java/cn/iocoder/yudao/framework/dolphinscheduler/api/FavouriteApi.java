package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收藏任务类型接口，对应 OpenAPI tag：favourite related operation。
 */
public class FavouriteApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code FavouriteApi}。
     */
    public FavouriteApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * addTaskType
     * <p>添加任务类型</p>
     * <p>{@code POST /favourite/{taskType}}</p>
     * @param taskType 必填，
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject addFavTask(String taskType) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("taskType", taskType);
        String path = DolphinSchedulerHttp.fillPath("/favourite/{taskType}", pathVars);
        return http.postForm(path, params);
    }

    /**
     * deleteTaskType
     * <p>删除任务类型</p>
     * <p>{@code DELETE /favourite/{taskType}}</p>
     * @param taskType 必填，
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteFavTask(String taskType) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("taskType", taskType);
        String path = DolphinSchedulerHttp.fillPath("/favourite/{taskType}", pathVars);
        return http.delete(path, params);
    }

    /**
     * listTaskType
     * <p>查询任务类型列表</p>
     * <p>{@code GET /favourite/taskTypes}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listTaskType() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/favourite/taskTypes", pathVars);
        return http.get(path, params);
    }

}
