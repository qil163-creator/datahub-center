package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * K8s 命名空间接口，对应 OpenAPI tag：k8s namespace related operation。
 */
public class K8sNamespaceApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code K8sNamespaceApi}。
     */
    public K8sNamespaceApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryNamespaceListPaging
     * <p>分页查询 Kubernetes 命名空间列表</p>
     * <p>{@code GET /k8s-namespace}</p>
     * @param searchVal 可选，搜索值
     * @param pageSize 必填，每页条数
     * @param pageNo 必填，页码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryNamespaceListPaging(String searchVal, Integer pageSize, Integer pageNo) {
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
        String path = DolphinSchedulerHttp.fillPath("/k8s-namespace", pathVars);
        return http.get(path, params);
    }

    /**
     * createK8sNamespace
     * <p>创建 Kubernetes 命名空间</p>
     * <p>{@code POST /k8s-namespace}</p>
     * @param namespace 必填，Kubernetes 命名空间
     * @param clusterCode 必填，集群编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createNamespace(String namespace, Long clusterCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (namespace != null) {
            params.put("namespace", namespace);
        }
        if (clusterCode != null) {
            params.put("clusterCode", clusterCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/k8s-namespace", pathVars);
        return http.postForm(path, params);
    }

    /**
     * verifyNamespaceK8s
     * <p>校验 Kubernetes 命名空间</p>
     * <p>{@code POST /k8s-namespace/verify}</p>
     * @param namespace 必填，Kubernetes 命名空间
     * @param clusterCode 必填，集群编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyNamespace(String namespace, Long clusterCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (namespace != null) {
            params.put("namespace", namespace);
        }
        if (clusterCode != null) {
            params.put("clusterCode", clusterCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/k8s-namespace/verify", pathVars);
        return http.postForm(path, params);
    }

    /**
     * delNamespaceById
     * <p>按编号删除 Kubernetes 命名空间</p>
     * <p>{@code POST /k8s-namespace/delete}</p>
     * @param id 必填，Kubernetes 命名空间编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject delNamespaceById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (id != null) {
            params.put("id", id);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/k8s-namespace/delete", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryUnauthorizedNamespace
     * <p>查询未授权的 Kubernetes 命名空间</p>
     * <p>{@code GET /k8s-namespace/unauth-namespace}</p>
     * @param userId 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryUnauthorizedNamespace(Integer userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/k8s-namespace/unauth-namespace", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAvailableNamespaceList
     * <p>查询可用的 Kubernetes 命名空间</p>
     * <p>{@code GET /k8s-namespace/available-list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAvailableNamespaceList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/k8s-namespace/available-list", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAuthorizedNamespace
     * <p>查询已授权的 Kubernetes 命名空间</p>
     * <p>{@code GET /k8s-namespace/authed-namespace}</p>
     * @param userId 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAuthorizedNamespace(Integer userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/k8s-namespace/authed-namespace", pathVars);
        return http.get(path, params);
    }

}
