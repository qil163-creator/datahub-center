package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源接口，对应 OpenAPI tag：data source related operation。
 */
public class DataSourceApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code DataSourceApi}。
     */
    public DataSourceApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * queryDataSource
     * <p>按编号查询数据源</p>
     * <p>{@code GET /datasources/{id}}</p>
     * @param id 必填，数据源编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryDataSource(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/datasources/{id}", pathVars);
        return http.get(path, params);
    }

    /**
     * updateDataSource
     * <p>更新数据源</p>
     * <p>{@code PUT /datasources/{id}}</p>
     * @param id 必填，数据源编号
     * @param jsonStr 兼容参数；只有 body 为空时才作为 JSON 请求体使用，不进入 URL
     * @param body 必填，JSON 请求体
     * @return DolphinScheduler 统一 {@code Result}
     * @deprecated 使用 {@link #updateDataSource(Integer, Map)} 或 {@link #updateDataSource(Integer, String)}；
     *             DS 3.4.2 的 jsonStr 是请求体，不是查询参数
     */
    @Deprecated
    public JSONObject updateDataSource(Integer id, Object jsonStr, Object body) {
        Object requestBody = body == null ? jsonStr : body;
        // 原 Object 请求体接口支持 bean；保持同一 JSON 序列化器，同时拒绝数组与标量。
        if (requestBody != null && !(requestBody instanceof Map<?, ?>)
                && !(requestBody instanceof JSONObject) && !(requestBody instanceof String)) {
            requestBody = JSONUtil.toJsonStr(requestBody);
        }
        return updateDataSourceBody(id, requestBody);
    }

    /** 更新数据源；产品插件专属字段保持在 JSON 请求体中，路径 id 由 DS 覆盖写入。 */
    public JSONObject updateDataSource(Integer id, Map<String, ?> body) {
        return updateDataSourceBody(id, body);
    }

    /** 更新数据源；jsonBody 是原始 JSON 对象文本，不会再编码成 JSON 字符串。 */
    public JSONObject updateDataSource(Integer id, String jsonBody) {
        return updateDataSourceBody(id, jsonBody);
    }

    /**
     * 更新数据源端正文。
     */
    private JSONObject updateDataSourceBody(Integer id, Object body) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Data source id must be positive");
        }
        String path = DolphinSchedulerHttp.fillPath("/datasources/{id}", DolphinSchedulerHttp.params("id", id));
        return http.putJson(path, null, requireBody(body));
    }

    /**
     * deleteDataSource
     * <p>删除数据源</p>
     * <p>{@code DELETE /datasources/{id}}</p>
     * @param id 必填，数据源编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteDataSource(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/datasources/{id}", pathVars);
        return http.delete(path, params);
    }

    /**
     * queryDataSourceListPaging
     * <p>分页查询数据源列表</p>
     * <p>{@code GET /datasources}</p>
     * @param searchVal 可选，搜索值
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryDataSourceListPaging(String searchVal, Integer pageNo, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources", pathVars);
        return http.get(path, params);
    }

    /**
     * createDataSource
     * <p>创建数据源</p>
     * <p>{@code POST /datasources}</p>
     * @return DolphinScheduler 统一 {@code Result}
     * @deprecated 数据源参数是必填 JSON 请求体，使用带 body 参数的重载
     */
    @Deprecated
    public JSONObject createDataSource() {
        throw new IllegalArgumentException("Data source JSON request body is required");
    }

    /** 创建数据源，保留产品插件的完整 JSON 参数，接受 DS 返回的 201 Created。 */
    public JSONObject createDataSource(Map<String, ?> body) {
        return http.postJson("/datasources", null, requireBody(body));
    }

    /** 创建数据源；jsonBody 是原始 JSON 对象文本。 */
    public JSONObject createDataSource(String jsonBody) {
        return http.postJson("/datasources", null, requireBody(jsonBody));
    }

    /**
     * connectDataSource
     * <p>连接数据源</p>
     * <p>{@code POST /datasources/connect}</p>
     * @return DolphinScheduler 统一 {@code Result}
     * @deprecated 连接参数是必填 JSON 请求体，使用带 body 参数的重载
     */
    @Deprecated
    public JSONObject connectDataSource() {
        throw new IllegalArgumentException("Data source JSON request body is required");
    }

    /** 测试尚未保存的数据源参数；凭据只发送到 JSON 请求体。 */
    public JSONObject connectDataSource(Map<String, ?> body) {
        return http.postJson("/datasources/connect", null, requireBody(body));
    }

    /** 测试连接；jsonBody 是原始 JSON 对象文本。 */
    public JSONObject connectDataSource(String jsonBody) {
        return http.postJson("/datasources/connect", null, requireBody(jsonBody));
    }

    /**
     * 校验并取得请求体。
     */
    private static Object requireBody(Object body) {
        if (body instanceof Map<?, ?> map && !map.isEmpty()) {
            return body;
        }
        if (body instanceof JSONObject json && !json.isEmpty()) {
            return body;
        }
        if (body instanceof String json && JSONUtil.isTypeJSONObject(json)) {
            return body;
        }
        throw new IllegalArgumentException("Data source JSON object request body is required");
    }

    /**
     * connectionTest
     * <p>测试数据源连接</p>
     * <p>{@code GET /datasources/{id}/connect-test}</p>
     * @param id 必填，数据源编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject connectionTest(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/datasources/{id}/connect-test", pathVars);
        return http.get(path, params);
    }

    /**
     * verifyDataSourceName
     * <p>校验数据源</p>
     * <p>{@code GET /datasources/verify-name}</p>
     * @param name 必填，数据源名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyDataSourceName(String name) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources/verify-name", pathVars);
        return http.get(path, params);
    }

    /**
     * unauthorizedDatasource
     * <p>查询未授权的数据源</p>
     * <p>{@code GET /datasources/unauth-datasource}</p>
     * @param userId 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getUnauthorizedDatasourceList(Integer userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources/unauth-datasource", pathVars);
        return http.get(path, params);
    }

    /**
     * tables
     * <p>获取数据源中的表</p>
     * <p>{@code GET /datasources/tables}</p>
     * @param datasourceId 必填，数据源编号
     * @param database 必填，数据库
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getTables(Integer datasourceId, String database) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (datasourceId != null) {
            params.put("datasourceId", datasourceId);
        }
        if (database != null) {
            params.put("database", database);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources/tables", pathVars);
        return http.get(path, params);
    }

    /**
     * tableColumns
     * <p>获取数据源中的表字段</p>
     * <p>{@code GET /datasources/tableColumns}</p>
     * @param datasourceId 必填，数据源编号
     * @param database 必填，数据库
     * @param tableName 必填，表名
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getTableColumns(Integer datasourceId, String database, String tableName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (datasourceId != null) {
            params.put("datasourceId", datasourceId);
        }
        if (database != null) {
            params.put("database", database);
        }
        if (tableName != null) {
            params.put("tableName", tableName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources/tableColumns", pathVars);
        return http.get(path, params);
    }

    /**
     * queryDataSourceList
     * <p>按数据库类型查询数据源列表</p>
     * <p>{@code GET /datasources/list}</p>
     * @param type 必填，数据库类型，取值：MYSQL / POSTGRESQL / HIVE / SPARK / CLICKHOUSE / ORACLE / SQLSERVER / DB2 / PRESTO / H2 / REDSHIFT / ATHENA / TRINO / STARROCKS / AZURESQL / DAMENG / OCEANBASE / SSH / KYUUBI / DATABEND / SNOWFLAKE / VERTICA / HANA / DORIS / ZEPPELIN / SAGEMAKER / K8S / ALIYUN_SERVERLESS_SPARK / DOLPHINDB
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryDataSourceList(String type) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (type != null) {
            params.put("type", type);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources/list", pathVars);
        return http.get(path, params);
    }

    /**
     * getKerberosStartupState
     * <p>获取 Kerberos 启动状态</p>
     * <p>{@code GET /datasources/kerberos-startup-state}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getKerberosStartupState() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources/kerberos-startup-state", pathVars);
        return http.get(path, params);
    }

    /**
     * databases
     * <p>获取数据源中的数据库</p>
     * <p>{@code GET /datasources/databases}</p>
     * @param datasourceId 必填，数据源编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getDatabases(Integer datasourceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (datasourceId != null) {
            params.put("datasourceId", datasourceId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources/databases", pathVars);
        return http.get(path, params);
    }

    /**
     * authedDatasource
     * <p>查询已授权的数据源</p>
     * <p>{@code GET /datasources/authed-datasource}</p>
     * @param userId 必填，用户编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject getAuthorizedDatasourceList(Integer userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId != null) {
            params.put("userId", userId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/datasources/authed-datasource", pathVars);
        return http.get(path, params);
    }

}
