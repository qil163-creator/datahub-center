package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目接口，对应 OpenAPI tag：project related operation。
 */
public class ProjectApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code ProjectApi}。
     */
    public ProjectApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryProjectByCode
     * <p>按项目编号查询项目信息</p>
     * <p>{@code GET /projects/{code}}</p>
     * @param code 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProjectByCode(Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{code}", pathVars);
        return http.get(path, params);
    }

    /**
     * update
     * <p>更新项目</p>
     * <p>{@code PUT /projects/{code}}</p>
     * @param code 必填，项目编码
     * @param projectName 必填，项目名称
     * @param description 可选，项目描述
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateProject(Long code, String projectName, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectName != null) {
            params.put("projectName", projectName);
        }
        if (description != null) {
            params.put("description", description);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{code}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * delete
     * <p>按编号删除项目</p>
     * <p>{@code DELETE /projects/{code}}</p>
     * @param code 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteProject(Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{code}", pathVars);
        return http.delete(path, params);
    }

    /**
     * queryProjectListPaging
     * <p>分页查询项目列表</p>
     * <p>{@code GET /projects}</p>
     * @param searchVal 可选，搜索值
     * @param pageSize 必填，每页条数
     * @param pageNo 必填，页码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProjectListPaging(String searchVal, Integer pageSize, Integer pageNo) {
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
        String path = DolphinSchedulerHttp.fillPath("/projects", pathVars);
        return http.get(path, params);
    }

    /**
     * create
     * <p>创建项目</p>
     * <p>{@code POST /projects}</p>
     * @param projectName 必填，项目名称
     * @param description 可选，项目描述
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createProject(String projectName, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectName != null) {
            params.put("projectName", projectName);
        }
        if (description != null) {
            params.put("description", description);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryUnauthorizedProject
     * <p>查询未授权的项目</p>
     * <p>{@code GET /projects/unauth-project}</p>
     * @param userId 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryUnauthorizedProject(Integer userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/unauth-project", pathVars);
        return http.get(path, params);
    }

    /**
     * queryProjectWithAuthorizedLevel
     * <p>查询项目及其授权级别</p>
     * <p>{@code GET /projects/project-with-authorized-level}</p>
     * @param userId 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProjectWithAuthorizedLevel(Integer userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/project-with-authorized-level", pathVars);
        return http.get(path, params);
    }

    /**
     * queryProjectWithAuthorizedLevelListPaging
     * <p>分页查询项目及其授权级别</p>
     * <p>{@code GET /projects/project-with-authorized-level-list-paging}</p>
     * @param userId 必填，用户编号
     * @param searchVal 可选，搜索值
     * @param pageSize 必填，每页条数
     * @param pageNo 必填，页码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProjectWithAuthorizedLevelListPaging(Integer userId, String searchVal, Integer pageSize,
            Integer pageNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
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
        String path = DolphinSchedulerHttp.fillPath("/projects/project-with-authorized-level-list-paging", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAllProjectList
     * <p>查询所有项目</p>
     * <p>{@code GET /projects/list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAllProjectList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/list", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAllProjectListForDependent
     * <p>查询可用于依赖配置的所有项目</p>
     * <p>{@code GET /projects/list-dependent}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAllProjectListForDependent() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/list-dependent", pathVars);
        return http.get(path, params);
    }

    /**
     * queryProjectCreatedAndAuthorizedByUser
     * <p>查询已授权的项目及用户创建的项目</p>
     * <p>{@code GET /projects/created-and-authed}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProjectCreatedAndAuthorizedByUser() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/created-and-authed", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAuthorizedUser
     * <p>查询已授权的用户</p>
     * <p>{@code GET /projects/authed-user}</p>
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAuthorizedUser(Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/authed-user", pathVars);
        return http.get(path, params);
    }

    /**
     * queryAuthorizedProject
     * <p>查询已授权的项目</p>
     * <p>{@code GET /projects/authed-project}</p>
     * @param userId 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryAuthorizedProject(Integer userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/projects/authed-project", pathVars);
        return http.get(path, params);
    }

}
