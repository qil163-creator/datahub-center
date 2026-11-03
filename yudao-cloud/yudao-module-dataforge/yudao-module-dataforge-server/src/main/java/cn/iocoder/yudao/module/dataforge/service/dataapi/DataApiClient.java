package cn.iocoder.yudao.module.dataforge.service.dataapi;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.RequestParamItem;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_API_PARSE_FAILED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_API_REQUEST_FAILED;

/**
 * 外部 API 请求客户端
 *
 * 参考 srt-cloud 的 DataApiServiceImpl.executeApi/fetchData，改进点：
 * Query 参数 URL 编码、HTTP 非 2xx 状态视为失败、正常 TLS 校验
 *
 * @author Li
 */
@Component
public class DataApiClient {

    private static final int TIMEOUT_MS = 30_000;

    /**
     * 执行 API 请求，返回原始响应文本
     *
     * @param api      API 配置
     * @param page     页码（分页 API 时注入，可空）
     * @param pageSize 每页条数（可空）
     */
    public String executeApi(DataApiSaveReqVO api, Integer page, Integer pageSize) {
        boolean paged = Integer.valueOf(1).equals(api.getIfPage()) && page != null
                && StrUtil.isNotBlank(api.getPageKey()) && StrUtil.isNotBlank(api.getPageSizeKey());
        Map<String, Object> queryParams = toParamMap(api.getRequestParam());
        Map<String, Object> formData = toParamMap(api.getBodyFormData());
        Map<String, Object> urlencoded = toParamMap(api.getBodyUrlencoded());
        String bodyRaw = api.getBodyRaw();
        if (paged) {
            queryParams.put(api.getPageKey(), page);
            queryParams.put(api.getPageSizeKey(), pageSize);
            formData.put(api.getPageKey(), page);
            formData.put(api.getPageSizeKey(), pageSize);
            urlencoded.put(api.getPageKey(), page);
            urlencoded.put(api.getPageSizeKey(), pageSize);
            if ("raw".equals(api.getBodyType())) {
                try {
                    JSONObject bodyMap = JSONUtil.parseObj(StrUtil.blankToDefault(bodyRaw, "{}"));
                    bodyMap.set(api.getPageKey(), page);
                    bodyMap.set(api.getPageSizeKey(), pageSize);
                    bodyRaw = bodyMap.toString();
                } catch (Exception ignored) {
                    // raw 不是 JSON 对象时保持原文
                }
            }
        }

        String url = api.getRequestUrl().trim();
        HttpRequest request;
        if ("POST".equalsIgnoreCase(api.getRequestMethod())) {
            // POST 时 Query 参数拼接到 URL（自动编码）
            if (!queryParams.isEmpty()) {
                url = HttpUtil.urlWithForm(url, queryParams, StandardCharsets.UTF_8, true);
            }
            request = HttpRequest.post(url);
            String bodyType = StrUtil.blankToDefault(api.getBodyType(), "none");
            switch (bodyType) {
                case "form-data" -> {
                    // 加入一个空标记促使 hutool 走 multipart；无参数时退化为普通表单
                    formData.forEach(request::form);
                    request.contentType("multipart/form-data");
                }
                case "urlencoded" -> {
                    urlencoded.forEach(request::form);
                    request.contentType("application/x-www-form-urlencoded");
                }
                case "raw" -> request.body(StrUtil.blankToDefault(bodyRaw, ""), "application/json");
                default -> {
                    // none：无请求体
                }
            }
        } else {
            request = HttpRequest.get(HttpUtil.urlWithForm(url, queryParams, StandardCharsets.UTF_8, true));
        }
        if (CollUtil.isNotEmpty(api.getRequestHeader())) {
            for (RequestParamItem header : api.getRequestHeader()) {
                if (StrUtil.isNotBlank(header.getName())) {
                    request.header(header.getName(), header.getValue());
                }
            }
        }
        try (HttpResponse response = request.timeout(TIMEOUT_MS).execute()) {
            String body = response.body();
            if (!response.isOk()) {
                throw new IllegalStateException("HTTP " + response.getStatus() + " "
                        + StrUtil.maxLength(body, 500));
            }
            return body;
        } catch (Exception ex) {
            throw exception(DATA_API_REQUEST_FAILED, ex.getMessage());
        }
    }

    /**
     * 执行 API 并按 dataKey / dataType 解析为行数据
     */
    public List<Map<String, Object>> fetchRows(DataApiSaveReqVO api, Integer page, Integer pageSize) {
        String responseText = executeApi(api, page, pageSize);
        try {
            Object node = resolveDataNode(responseText, api.getDataKey());
            List<Map<String, Object>> rows = new ArrayList<>();
            if (node == null) {
                return rows;
            }
            if (Integer.valueOf(1).equals(api.getDataType())) {
                if (node instanceof JSONObject obj) {
                    rows.add(new LinkedHashMap<>(obj));
                }
            } else {
                if (node instanceof JSONArray array) {
                    for (Object item : array) {
                        if (item instanceof JSONObject obj) {
                            rows.add(new LinkedHashMap<>(obj));
                        }
                    }
                } else if (node instanceof JSONObject obj) {
                    rows.add(new LinkedHashMap<>(obj));
                }
            }
            return rows;
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(DATA_API_PARSE_FAILED, ex.getMessage());
        }
    }

    /**
     * 解析数据节点：空 key 取整个响应；含点为逐级路径；单名则递归查找第一个同名节点
     */
    private Object resolveDataNode(String responseText, String dataKey) {
        JSON root = JSONUtil.parse(responseText);
        if (StrUtil.isBlank(dataKey)) {
            return root;
        }
        if (dataKey.contains(".")) {
            Object current = root;
            for (String segment : dataKey.split("\\.")) {
                if (!(current instanceof JSONObject obj)) {
                    return null;
                }
                current = obj.get(segment);
            }
            return current;
        }
        return searchNode(root, dataKey.trim());
    }

    /**
     * 获取数据 API的搜索节点。
     *
     * @param node 节点
     * @param key 标识键
     * @return 数据 API
     */
    private Object searchNode(Object node, String key) {
        if (node instanceof JSONObject obj) {
            if (obj.containsKey(key)) {
                return obj.get(key);
            }
            for (Object value : new HashMap<>(obj).values()) {
                Object found = searchNode(value, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JSONArray array) {
            for (Object item : array) {
                Object found = searchNode(item, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 将已解析的查询参数转为执行参数映射。
     *
     * @param items 项
     * @return 处理后的键值映射
     */
    private Map<String, Object> toParamMap(List<RequestParamItem> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(items)) {
            for (RequestParamItem item : items) {
                if (StrUtil.isNotBlank(item.getName())) {
                    map.put(item.getName(), item.getValue());
                }
            }
        }
        return map;
    }

}
