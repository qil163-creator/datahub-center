package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Worker 分组接口，对应 OpenAPI tag：worker group related operation。
 */
public class WorkerGroupApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code WorkerGroupApi}。
     */
    public WorkerGroupApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryAllWorkerGroupsPaging
     * <p>分页查询工作组</p>
     * <p>{@code GET /worker-groups}</p>
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @param searchVal 可选，搜索值
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAllWorkerGroupsPaging(Integer pageNo, Integer pageSize, String searchVal) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/worker-groups", pathVars);
        return http.get(path, params);
    }

    /**
     * saveWorkerGroup
     * <p>创建工作组</p>
     * <p>{@code POST /worker-groups}</p>
     * @param id 可选，工作服务器组编号
     * @param name 必填，工作组名称
     * @param addrList 必填，工作节点地址列表
     * @param description 可选，工作节点描述
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject saveWorkerGroup(Integer id, String name, String addrList, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (id != null) {
            params.put("id", id);
        }
        if (name != null) {
            params.put("name", name);
        }
        if (addrList != null) {
            params.put("addrList", addrList);
        }
        if (description != null) {
            params.put("description", description);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/worker-groups", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryWorkerAddressList
     * <p>查询工作节点地址列表</p>
     * <p>{@code GET /worker-groups/worker-address-list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryWorkerAddressList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/worker-groups/worker-address-list", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAllWorkerGroups
     * <p>查询工作组列表</p>
     * <p>{@code GET /worker-groups/all}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAllWorkerGroups() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/worker-groups/all", pathVars);
        return http.get(path, params);
    }

    /**
     * deleteWorkerGroupById
     * <p>按编号删除工作组</p>
     * <p>{@code DELETE /worker-groups/{id}}</p>
     * @param id 必填，工作服务器组编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteWorkerGroupById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/worker-groups/{id}", pathVars);
        return http.delete(path, params);
    }

}
