package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集群接口，对应 OpenAPI tag：cluster related operation。
 */
public class ClusterApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code ClusterApi}。
     */
    public ClusterApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * verifyCluster
     * <p>校验集群</p>
     * <p>{@code POST /cluster/verify-cluster}</p>
     * @param clusterName 必填，集群名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyCluster(String clusterName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (clusterName != null) {
            params.put("clusterName", clusterName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cluster/verify-cluster", pathVars);
        return http.postForm(path, params);
    }

    /**
     * updateCluster
     * <p>更新集群</p>
     * <p>{@code POST /cluster/update}</p>
     * @param code 必填，集群编码
     * @param name 必填，集群名称
     * @param config 必填，集群配置
     * @param description 可选，集群描述
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateCluster(Long code, String name, String config, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (code != null) {
            params.put("code", code);
        }
        if (name != null) {
            params.put("name", name);
        }
        if (config != null) {
            params.put("config", config);
        }
        if (description != null) {
            params.put("description", description);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cluster/update", pathVars);
        return http.postForm(path, params);
    }

    /**
     * deleteClusterByCode
     * <p>按编码删除集群</p>
     * <p>{@code POST /cluster/delete}</p>
     * @param clusterCode 必填，集群编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteCluster(Long clusterCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (clusterCode != null) {
            params.put("clusterCode", clusterCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cluster/delete", pathVars);
        return http.postForm(path, params);
    }

    /**
     * createCluster
     * <p>创建集群</p>
     * <p>{@code POST /cluster/create}</p>
     * @param name 必填，集群名称
     * @param config 必填，集群配置
     * @param description 可选，集群描述
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createCluster(String name, String config, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        if (config != null) {
            params.put("config", config);
        }
        if (description != null) {
            params.put("description", description);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cluster/create", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryAllClusterList
     * <p>查询所有集群</p>
     * <p>{@code GET /cluster/query-cluster-list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAllClusterList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cluster/query-cluster-list", pathVars);
        return http.get(path, params);
    }

    /**
     * queryClusterByCode
     * <p>按编码查询集群</p>
     * <p>{@code GET /cluster/query-by-code}</p>
     * @param clusterCode 必填，集群编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryClusterByCode(Long clusterCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (clusterCode != null) {
            params.put("clusterCode", clusterCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cluster/query-by-code", pathVars);
        return http.get(path, params);
    }

    /**
     * queryClusterListPaging
     * <p>分页查询集群列表</p>
     * <p>{@code GET /cluster/list-paging}</p>
     * @param searchVal 可选，搜索值
     * @param pageSize 必填，每页条数
     * @param pageNo 必填，页码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryClusterListPaging(String searchVal, Integer pageSize, Integer pageNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/cluster/list-paging", pathVars);
        return http.get(path, params);
    }

}
