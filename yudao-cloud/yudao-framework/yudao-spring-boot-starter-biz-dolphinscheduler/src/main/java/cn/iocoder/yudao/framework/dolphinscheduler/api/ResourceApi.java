package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.io.File;

/**
 * 资源中心接口，对应 OpenAPI tag：resource center related operation。
 */
public class ResourceApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code ResourceApi}。
     */
    public ResourceApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * pagingResourceItemRequest
     * <p>分页查询资源条目列表</p>
     * <p>{@code GET /resources}</p>
     * @param fullName 必填，资源完整名称
     * @param type 必填，资源文件类型，取值：FILE / ALL
     * @param searchVal 可选，搜索值
     * @param pageNo 必填，页码
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject pagingResourceItemRequest(String fullName, String type, String searchVal, Integer pageNo,
            Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (fullName != null) {
            params.put("fullName", fullName);
        }
        if (type != null) {
            params.put("type", type);
        }
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
        String path = DolphinSchedulerHttp.fillPath("/resources", pathVars);
        return http.get(path, params);
    }

    /**
     * updateResource
     * <p>在线编辑资源文件</p>
     * <p>{@code PUT /resources}</p>
     * @param fullName 必填，资源完整名称
     * @param name 必填，资源名称
     * @param tenantCode 必填，操作系统租户编码
     * @param type 必填，资源文件类型，取值：FILE / ALL
     * @param file 必填，资源文件
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateResource(String fullName, String name, String tenantCode, String type, File file) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (fullName != null) {
            params.put("fullName", fullName);
        }
        if (name != null) {
            params.put("name", name);
        }
        if (tenantCode != null) {
            params.put("tenantCode", tenantCode);
        }
        if (type != null) {
            params.put("type", type);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources", pathVars);
        return http.multipart("PUT", path, params, "file", file);
    }

    /**
     * uploadFile
     * <p>创建文件</p>
     * <p>{@code POST /resources}</p>
     * @param type 必填，资源文件类型，取值：FILE / ALL
     * @param name 必填，资源名称
     * @param currentDir 必填，资源当前目录
     * @param file 必填，资源文件
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createFile(String type, String name, String currentDir, File file) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (type != null) {
            params.put("type", type);
        }
        if (name != null) {
            params.put("name", name);
        }
        if (currentDir != null) {
            params.put("currentDir", currentDir);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources", pathVars);
        return http.multipart("POST", path, params, "file", file);
    }

    /**
     * deleteResource
     * <p>按编号删除资源</p>
     * <p>{@code DELETE /resources}</p>
     * @param fullName 必填，资源完整名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteResource(String fullName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (fullName != null) {
            params.put("fullName", fullName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources", pathVars);
        return http.delete(path, params);
    }

    /**
     * updateFileContent
     * <p>在线编辑资源文件</p>
     * <p>{@code PUT /resources/update-content}</p>
     * @param fullName 必填，完整名称
     * @param content 必填，资源文件内容
     * @param tenantCode 必填，操作系统租户编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateFileContent(String fullName, String content, String tenantCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (fullName != null) {
            params.put("fullName", fullName);
        }
        if (content != null) {
            params.put("content", content);
        }
        if (tenantCode != null) {
            params.put("tenantCode", tenantCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources/update-content", pathVars);
        return http.putForm(path, params);
    }

    /**
     * createFileFromContent
     * <p>在线创建资源</p>
     * <p>{@code POST /resources/online-create}</p>
     * @param type 必填，资源文件类型，取值：FILE / ALL
     * @param fileName 必填，资源名称
     * @param suffix 必填，资源文件后缀
     * @param content 必填，资源文件内容
     * @param currentDir 必填，当前资源所在目录
     * @param description 可选，资源文件描述
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createFileFromContent(String type, String fileName, String suffix, String content,
            String currentDir, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (type != null) {
            params.put("type", type);
        }
        if (fileName != null) {
            params.put("fileName", fileName);
        }
        if (suffix != null) {
            params.put("suffix", suffix);
        }
        if (content != null) {
            params.put("content", content);
        }
        if (currentDir != null) {
            params.put("currentDir", currentDir);
        }
        if (description != null) {
            params.put("description", description);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources/online-create", pathVars);
        return http.postForm(path, params);
    }

    /**
     * createDirectory
     * <p>创建资源</p>
     * <p>{@code POST /resources/directory}</p>
     * @param type 必填，资源文件类型，取值：FILE / ALL
     * @param name 必填，资源名称
     * @param currentDir 必填，资源当前目录
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createDirectory(String type, String name, String currentDir) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (type != null) {
            params.put("type", type);
        }
        if (name != null) {
            params.put("name", name);
        }
        if (currentDir != null) {
            params.put("currentDir", currentDir);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources/directory", pathVars);
        return http.postForm(path, params);
    }

    /**
     * viewResource
     * <p>按编号查看资源</p>
     * <p>{@code GET /resources/view}</p>
     * @param fullName 必填，资源完整名称
     * @param skipLineNum 必填，跳过的行数
     * @param limit 必填，数量上限
     * @param tenantCode 必填，操作系统租户编码
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject viewResource(String fullName, Integer skipLineNum, Integer limit, String tenantCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (fullName != null) {
            params.put("fullName", fullName);
        }
        if (skipLineNum != null) {
            params.put("skipLineNum", skipLineNum);
        }
        if (limit != null) {
            params.put("limit", limit);
        }
        if (tenantCode != null) {
            params.put("tenantCode", tenantCode);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources/view", pathVars);
        return http.get(path, params);
    }

    /**
     * queryResourceFileList
     * <p>查询资源文件列表</p>
     * <p>{@code GET /resources/query-by-type}</p>
     * @param type 必填，资源文件类型，取值：FILE / ALL
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryResourceFileList(String type) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (type != null) {
            params.put("type", type);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources/query-by-type", pathVars);
        return http.get(path, params);
    }

    /**
     * queryResourceList
     * <p>查询资源列表</p>
     * <p>{@code GET /resources/list}</p>
     * @param type 必填，资源文件类型，取值：FILE / ALL
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryResourceList(String type) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (type != null) {
            params.put("type", type);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources/list", pathVars);
        return http.get(path, params);
    }

    /**
     * downloadResource
     * <p>下载资源文件</p>
     * <p>{@code GET /resources/download}</p>
     * @param fullName 必填，资源完整名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public byte[] downloadResource(String fullName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (fullName != null) {
            params.put("fullName", fullName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources/download", pathVars);
        return http.download("GET", path, params);
    }

    /** Bounded resource bytes using the DS 3.4.2 storage fullName contract. */
    public byte[] downloadResource(String fullName, int maxBytes) {
        validateResourceName(fullName);
        return http.download("GET", "/resources/download", DolphinSchedulerHttp.params("fullName", fullName), maxBytes);
    }

    /** Streaming SHA-256 of the actual resource, suitable for immutable artifact pin verification. */
    public String resourceSha256(String fullName, long maxBytes) {
        validateResourceName(fullName);
        return http.downloadSha256("GET", "/resources/download", DolphinSchedulerHttp.params("fullName", fullName),
                maxBytes);
    }

    /**
     * 校验资源名称。
     */
    private static void validateResourceName(String name) {
        new cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters.Resource(name);
    }

    /**
     * queryResourceBaseDir
     * <p>查询资源根目录</p>
     * <p>{@code GET /resources/base-dir}</p>
     * @param type 必填，资源文件类型，取值：FILE / ALL
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryResourceBaseDir(String type) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (type != null) {
            params.put("type", type);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/resources/base-dir", pathVars);
        return http.get(path, params);
    }

}
