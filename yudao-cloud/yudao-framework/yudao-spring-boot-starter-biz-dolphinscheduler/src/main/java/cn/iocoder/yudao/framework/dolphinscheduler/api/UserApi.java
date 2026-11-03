package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户接口，对应 OpenAPI tag：users related operation。
 */
public class UserApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code UserApi}。
     */
    public UserApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * updateUser
     * <p>更新用户</p>
     * <p>{@code POST /users/update}</p>
     * @param id 必填，用户编号
     * @param userName 必填，用户名
     * @param userPassword 必填，用户密码
     * @param queue 可选，队列
     * @param email 必填，邮箱
     * @param tenantId 必填，租户编号
     * @param phone 可选，电话
     * @param state 可选，状态
     * @param timeZone 可选，时区
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateUser(UpdateUserRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/update", pathVars);
        return http.postForm(path, params);
    }

    /**
     * revokeProject
     * <p>撤销用户的项目权限</p>
     * <p>{@code POST /users/revoke-project}</p>
     * @param userId 必填，用户编号
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject revokeProject(Integer userId, Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/revoke-project", pathVars);
        return http.postForm(path, params);
    }

    /**
     * revokeProjectById
     * <p>撤销用户的项目权限</p>
     * <p>{@code POST /users/revoke-project-by-id}</p>
     * @param userId 必填，用户编号
     * @param projectIds 必填，项目编号（字符串形式，多个项目以 "," 分隔）
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject revokeProjectById(Integer userId, String projectIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (projectIds != null) {
            params.put("projectIds", projectIds);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/revoke-project-by-id", pathVars);
        return http.postForm(path, params);
    }

    /**
     * registerUser
     * <p>注册用户</p>
     * <p>{@code POST /users/register}</p>
     * @param userName 必填，用户名
     * @param userPassword 必填，用户密码
     * @param repeatPassword 必填，重复输入的密码
     * @param email 必填，邮箱
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject registerUser(String userName, String userPassword, String repeatPassword, String email) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userName != null) {
            params.put("userName", userName);
        }
        if (userPassword != null) {
            params.put("userPassword", userPassword);
        }
        if (repeatPassword != null) {
            params.put("repeatPassword", repeatPassword);
        }
        if (email != null) {
            params.put("email", email);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/register", pathVars);
        return http.postForm(path, params);
    }

    /**
     * grantProject
     * <p>授予项目权限</p>
     * <p>{@code POST /users/grant-project}</p>
     * @param userId 必填，用户编号
     * @param projectIds 必填，项目编号（字符串形式，多个项目以 "," 分隔）
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject grantProject(Integer userId, String projectIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (projectIds != null) {
            params.put("projectIds", projectIds);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/grant-project", pathVars);
        return http.postForm(path, params);
    }

    /**
     * grantProjectWithReadPerm
     * <p>授予项目只读权限</p>
     * <p>{@code POST /users/grant-project-with-read-perm}</p>
     * @param userId 必填，用户编号
     * @param projectIds 必填，项目编号（字符串形式，多个项目以 "," 分隔）
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject grantProjectWithReadPerm(Integer userId, String projectIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (projectIds != null) {
            params.put("projectIds", projectIds);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/grant-project-with-read-perm", pathVars);
        return http.postForm(path, params);
    }

    /**
     * grantProjectByCode
     * <p>按编码授予项目权限</p>
     * <p>{@code POST /users/grant-project-by-code}</p>
     * @param userId 必填，用户编号
     * @param projectCode 必填，项目编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject grantProjectByCode(Integer userId, Long projectCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (projectCode != null) {
            params.put("projectCode", projectCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/grant-project-by-code", pathVars);
        return http.postForm(path, params);
    }

    /**
     * grantNamespace
     * <p>授予命名空间权限</p>
     * <p>{@code POST /users/grant-namespace}</p>
     * @param userId 必填，用户编号
     * @param namespaceIds 必填，命名空间编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject grantNamespace(Integer userId, String namespaceIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (namespaceIds != null) {
            params.put("namespaceIds", namespaceIds);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/grant-namespace", pathVars);
        return http.postForm(path, params);
    }

    /**
     * grantDataSource
     * <p>授予数据源权限</p>
     * <p>{@code POST /users/grant-datasource}</p>
     * @param userId 必填，用户编号
     * @param datasourceIds 必填，数据源编号（字符串形式，多个数据源以 "," 分隔）
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject grantDataSource(Integer userId, String datasourceIds) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        if (datasourceIds != null) {
            params.put("datasourceIds", datasourceIds);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/grant-datasource", pathVars);
        return http.postForm(path, params);
    }

    /**
     * delUserById
     * <p>按编号删除用户</p>
     * <p>{@code POST /users/delete}</p>
     * @param id 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject delUserById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (id != null) {
            params.put("id", id);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/delete", pathVars);
        return http.postForm(path, params);
    }

    /**
     * createUser
     * <p>创建用户</p>
     * <p>{@code POST /users/create}</p>
     * @param userName 必填，用户名
     * @param userPassword 必填，用户密码
     * @param tenantId 必填，租户编号
     * @param queue 可选，队列
     * @param email 必填，邮箱
     * @param phone 可选，电话
     * @param state 可选，状态
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createUser(CreateUserRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/create", pathVars);
        return http.postForm(path, params);
    }

    /**
     * batchActivateUser
     * <p>批量激活用户</p>
     * <p>{@code POST /users/batch/activate}</p>
     * @param userNames 必填，逗号分隔的用户名，或 JSON 字符串数组；请求以 JSON 数组发送
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject batchActivateUser(String userNames) {
        if (userNames == null || userNames.isBlank()) {
            throw new IllegalArgumentException("User names are required");
        }
        if (userNames.stripLeading().startsWith("[")) {
            JSONArray array;
            try {
                array = JSONUtil.parseArray(userNames);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("User names must be a JSON string array");
            }
            List<String> names = new ArrayList<>(array.size());
            for (Object item : array) {
                if (!(item instanceof String name)) {
                    throw new IllegalArgumentException("User names must be a JSON string array");
                }
                names.add(name);
            }
            return batchActivateUsers(names);
        }
        return batchActivateUsers(java.util.Arrays.stream(userNames.split(",", -1))
                .map(String::strip).toList());
    }

    /**
     * 批量激活用户。DS 3.4.2 Controller 使用 {@code @RequestBody List<String>}，
     * 请求体为 JSON 数组；OpenAPI 中标注的 string 不是实际线协议。
     * @param userNames 必填，非空用户名列表
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject batchActivateUsers(List<String> userNames) {
        if (userNames == null || userNames.isEmpty()
                || userNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("A non-empty list of user names is required");
        }
        return http.postJson("/users/batch/activate", Map.of(), List.copyOf(userNames));
    }

    /**
     * activateUser
     * <p>激活用户</p>
     * <p>{@code POST /users/activate}</p>
     * @param userName 必填，用户名
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject activateUser(String userName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userName != null) {
            params.put("userName", userName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/activate", pathVars);
        return http.postForm(path, params);
    }

    /**
     * verifyUserName
     * <p>校验用户名</p>
     * <p>{@code GET /users/verify-user-name}</p>
     * @param userName 必填，用户名
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyUserName(String userName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userName != null) {
            params.put("userName", userName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/verify-user-name", pathVars);
        return http.get(path, params);
    }

    /**
     * unauthorizedUser
     * <p>取消授权</p>
     * <p>{@code GET /users/unauth-user}</p>
     * @param alertgroupId 必填，告警组编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject unauthorizedUser(String alertgroupId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (alertgroupId != null) {
            params.put("alertgroupId", alertgroupId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/unauth-user", pathVars);
        return http.get(path, params);
    }

    /**
     * listUser
     * <p>列出用户</p>
     * <p>{@code GET /users/list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listUser() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/list", pathVars);
        return http.get(path, params);
    }

    /**
     * queryUserList
     * <p>查询用户列表</p>
     * <p>{@code GET /users/list-paging}</p>
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @param searchVal 可选，搜索值
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryUserList(Integer pageNo, Integer pageSize, String searchVal) {
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
        String path = DolphinSchedulerHttp.fillPath("/users/list-paging", pathVars);
        return http.get(path, params);
    }

    /**
     * listAll
     * <p>{@code GET /users/list-all}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject listAll() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/list-all", pathVars);
        return http.get(path, params);
    }

    /**
     * getUserInfo
     * <p>获取用户信息</p>
     * <p>{@code GET /users/get-user-info}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getUserInfo() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/get-user-info", pathVars);
        return http.get(path, params);
    }

    /**
     * authorizedUser
     * <p>查询已授权的用户</p>
     * <p>{@code GET /users/authed-user}</p>
     * @param alertgroupId 必填，告警组编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject authorizedUser(String alertgroupId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (alertgroupId != null) {
            params.put("alertgroupId", alertgroupId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/users/authed-user", pathVars);
        return http.get(path, params);
    }

}
