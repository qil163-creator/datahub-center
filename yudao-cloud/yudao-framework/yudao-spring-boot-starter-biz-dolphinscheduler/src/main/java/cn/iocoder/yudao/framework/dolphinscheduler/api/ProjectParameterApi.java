package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目参数接口，对应 OpenAPI tag：project parameter related operation。
 */
public class ProjectParameterApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code ProjectParameterApi}。
     */
    public ProjectParameterApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryProjectParameterByCode
     * <p>查询项目参数</p>
     * <p>{@code GET /projects/{projectCode}/project-parameter/{code}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，项目参数编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProjectParameterByCode(Long projectCode, Long code) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-parameter/{code}", pathVars);
        return http.get(path, params);
    }

    /**
     * updateProjectParameter
     * <p>更新项目参数</p>
     * <p>{@code PUT /projects/{projectCode}/project-parameter/{code}}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，项目参数编码
     * @param projectParameterName 必填，项目参数名称
     * @param projectParameterValue 必填，项目参数值
     * @param projectParameterDataType 必填，项目参数数据类型
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateProjectParameter(Long projectCode, Long code, String projectParameterName,
            String projectParameterValue, String projectParameterDataType) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectParameterName != null) {
            params.put("projectParameterName", projectParameterName);
        }
        if (projectParameterValue != null) {
            params.put("projectParameterValue", projectParameterValue);
        }
        if (projectParameterDataType != null) {
            params.put("projectParameterDataType", projectParameterDataType);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-parameter/{code}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * queryProjectParameterListPaging
     * <p>分页查询项目参数列表</p>
     * <p>{@code GET /projects/{projectCode}/project-parameter}</p>
     * @param projectCode 必填，项目编码
     * @param searchVal 可选，搜索值
     * @param projectParameterDataType 可选，项目参数数据类型
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryProjectParameterListPaging(Long projectCode, String searchVal,
            String projectParameterDataType, Integer pageNo, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (projectParameterDataType != null) {
            params.put("projectParameterDataType", projectParameterDataType);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-parameter", pathVars);
        return http.get(path, params);
    }

    /**
     * createProjectParameter
     * <p>创建项目参数</p>
     * <p>{@code POST /projects/{projectCode}/project-parameter}</p>
     * @param projectCode 必填，项目编码
     * @param projectParameterName 必填，项目参数名称
     * @param projectParameterValue 必填，项目参数值
     * @param projectParameterDataType 可选，项目参数数据类型
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createProjectParameter(Long projectCode, String projectParameterName,
            String projectParameterValue, String projectParameterDataType) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectParameterName != null) {
            params.put("projectParameterName", projectParameterName);
        }
        if (projectParameterValue != null) {
            params.put("projectParameterValue", projectParameterValue);
        }
        if (projectParameterDataType != null) {
            params.put("projectParameterDataType", projectParameterDataType);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-parameter", pathVars);
        return http.postForm(path, params);
    }

    /**
     * deleteProjectParametersByCode
     * <p>删除项目参数</p>
     * <p>{@code POST /projects/{projectCode}/project-parameter/delete}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，项目参数编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteProjectParametersByCode(Long projectCode, String code) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (code != null) {
            params.put("code", code);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-parameter/delete", pathVars);
        return http.postForm(path, params);
    }

    /**
     * batchDeleteProjectParametersByCodes
     * <p>删除项目参数</p>
     * <p>{@code POST /projects/{projectCode}/project-parameter/batch-delete}</p>
     * @param projectCode 必填，项目编码
     * @param codes 必填，项目参数编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject batchDeleteProjectParametersByCodes(Long projectCode, String codes) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (codes != null) {
            params.put("codes", codes);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/project-parameter/batch-delete", pathVars);
        return http.postForm(path, params);
    }

}
