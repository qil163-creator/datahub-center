package cn.iocoder.yudao.module.supply.service.apitest;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.http.HttpUtils;
import cn.iocoder.yudao.framework.common.util.http.SafeHttpExecutor;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo.ApiCategoryListReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestCatalogRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestDetailRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestKeyValueVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestSendReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestSendRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.service.apicategory.ApiCategoryService;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiService;
import jakarta.annotation.Resource;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_CONFIG_INVALID;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_EXECUTE_FAILED;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_NOT_EXISTS;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_NOT_RELEASED;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_REQUEST_INVALID;

/**
 * 测试目录在当前平台内可见；真实调用仍由公开网关独立验证应用凭据与授权。
 * 请求传输、安全地址绑定与资源限制委托 common HTTP 组件，不继承管理端身份。
 */
@Service
@Validated
public class ApiTestServiceImpl implements ApiTestService {

    private static final Set<String> INTERNAL_HEADERS = Set.of(
            SecurityFrameworkUtils.LOGIN_USER_HEADER,
            WebFrameworkUtils.HEADER_TENANT_ID,
            WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
            DataPermissionRequestInterceptor.ENABLE_HEADER_NAME,
            EnvUtils.HEADER_TAG, "forwarded", "x-real-ip", "wl-proxy-client-ip", "http_client_ip",
            "http_x_forwarded_for");

    @Resource
    private DataApiService dataApiService;

    @Resource
    private ApiCategoryService apiCategoryService;

    @Resource
    private SecurityFrameworkService securityFrameworkService;

    @Resource
    private ApplicationContext applicationContext;

    /**
     * 获得平台内已上线 API 测试目录。
     *
     * @return 目录
     */
    @Override
    public ApiTestCatalogRespVO getCatalog() {
        requirePermission();
        ApiTestCatalogRespVO result = new ApiTestCatalogRespVO();
        result.setPublicBaseUrl(dataApiService.getPublicBaseUrl());
        result.setCategories(apiCategoryService.getApiCategoryList(new ApiCategoryListReqVO()).stream()
                .map(category -> BeanUtils.toBean(category, ApiTestCatalogRespVO.Category.class)).toList());
        result.setApis(dataApiService.getDataApiSimpleList(DataApiDO.STATUS_ONLINE, "test").stream()
                .map(summary -> dataApiService.getPublishedDataApiEntity(summary.getId()))
                .filter(api -> api != null && Objects.equals(api.getStatus(), DataApiDO.STATUS_ONLINE))
                .map(api -> BeanUtils.toBean(api, ApiTestCatalogRespVO.Api.class)).toList());
        return result;
    }

    /**
     * 获得已发布 API 的最小测试定义。
     *
     * @param id 记录编号
     * @return API测试详情响应数据
     */
    @Override
    public ApiTestDetailRespVO getDetail(Long id) {
        requirePermission();
        DataApiDO api = requirePublishedApi(id);
        ApiTestDetailRespVO result = BeanUtils.toBean(api, ApiTestDetailRespVO.class);
        result.setPublicUrl(registeredTarget(api));
        if (StrUtil.isBlank(api.getRequestParamsJson())) {
            result.setRequestParams(List.of());
        } else {
            try {
                List<DataApiSaveReqVO.ParamItem> parameters = JsonUtils.getObjectMapper().readValue(
                        api.getRequestParamsJson(), new TypeReference<>() {
                        });
                result.setRequestParams(parameters == null ? List.of() : parameters.stream()
                        .map(parameter -> BeanUtils.toBean(parameter, ApiTestDetailRespVO.Param.class)).toList());
            } catch (JacksonException ex) {
                throw exception(DATA_API_CONFIG_INVALID, "已发布请求参数定义不合法");
            }
        }
        return result;
    }

    /**
     * 发送登记 API 或公开 HTTP 地址的真实测试请求。
     *
     * @param reqVO 请求参数
     * @return API测试发送响应数据
     */
    @Override
    public ApiTestSendRespVO send(ApiTestSendReqVO reqVO) {
        requirePermission();
        try {
            String target;
            SafeHttpExecutor.TargetPolicy policy;
            if ("REGISTERED".equals(reqVO.getMode())) {
                target = registeredTarget(requirePublishedApi(reqVO.getApiId()));
                policy = SafeHttpExecutor.fixedTarget(target);
            } else if ("EXTERNAL".equals(reqVO.getMode())) {
                target = reqVO.getUrl();
                policy = SafeHttpExecutor.publicOnly();
            } else {
                throw exception(DATA_API_REQUEST_INVALID, "请求模式不支持");
            }
            String url = appendQuery(target, reqVO.getQuery());
            List<SafeHttpExecutor.Header> headers = safeHeaders(reqVO.getHeaders());
            byte[] body = reqVO.getBody() == null ? null : reqVO.getBody().getBytes(StandardCharsets.UTF_8);
            if (reqVO.getTimeoutSeconds() == null || reqVO.getFollowRedirects() == null) {
                throw exception(DATA_API_REQUEST_INVALID, "超时或重定向选项缺失");
            }
            SafeHttpExecutor.Result response = SafeHttpExecutor.execute(new SafeHttpExecutor.Request(
                    url, reqVO.getMethod(), headers, body, reqVO.getTimeoutSeconds(),
                    reqVO.getFollowRedirects(), policy));
            return convertResponse(response);
        } catch (SafeHttpExecutor.SafeHttpException ex) {
            switch (ex.reason()) {
                case INVALID_REQUEST -> throw exception(DATA_API_REQUEST_INVALID,
                        "请求方式、地址、请求头或请求体不合法；请求体最多 1 MiB");
                case TARGET_REJECTED -> throw exception(DATA_API_REQUEST_INVALID,
                        "外部地址须指向公网；登记 API 的重定向须保持原 origin 和路径");
                case DNS_FAILED -> throw exception(DATA_API_EXECUTE_FAILED, "目标域名无法解析");
                case TIMEOUT -> throw exception(DATA_API_EXECUTE_FAILED, "HTTP 测试请求超时");
                case CONCURRENCY_LIMIT -> throw exception(DATA_API_EXECUTE_FAILED, "HTTP 测试并发已满，请稍后重试");
                case TOO_MANY_REDIRECTS -> throw exception(DATA_API_EXECUTE_FAILED, "HTTP 重定向超过 5 次");
                default -> throw exception(DATA_API_EXECUTE_FAILED, "无法连接目标 HTTP 服务");
            }
        }
    }

    /**
     * 校验权限。
     */
    private void requirePermission() {
        if (!securityFrameworkService.hasPermission("supply:data-api:invoke")) {
            throw new AccessDeniedException("没有当前平台的 HTTP 测试权限");
        }
    }

    /**
     * 校验已发布API。
     *
     * @param id 记录编号
     * @return 数据 API
     */
    private DataApiDO requirePublishedApi(Long id) {
        DataApiDO api = id == null ? null : dataApiService.getPublishedDataApiEntity(id);
        if (api == null) {
            throw exception(DATA_API_NOT_EXISTS);
        }
        if (!Objects.equals(api.getStatus(), DataApiDO.STATUS_ONLINE)) {
            throw exception(DATA_API_NOT_RELEASED);
        }
        return api;
    }

    /**
     * 获取API测试的已注册目标。
     *
     * @param api 本次使用的 API 定义
     * @return API测试的已注册目标文本
     */
    String registeredTarget(DataApiDO api) {
        String version = api.getVersion();
        String path = api.getPath();
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || path == null || !path.startsWith("/") || path.startsWith("//") || !safePath(path)) {
            throw exception(DATA_API_CONFIG_INVALID, "已发布 API 路径不合法");
        }
        String base = StrUtil.nullToEmpty(dataApiService.getPublicBaseUrl()).strip();
        if (base.isEmpty()) {
            if (!(applicationContext instanceof WebServerApplicationContext webContext)
                    || webContext.getWebServer() == null || webContext.getWebServer().getPort() <= 0) {
                throw exception(DATA_API_CONFIG_INVALID, "未配置公开基址且本机 HTTP 服务尚未启动");
            }
            boolean ssl = applicationContext.getEnvironment().getProperty("server.ssl.enabled", Boolean.class, false);
            String contextPath = applicationContext.getEnvironment().getProperty("server.servlet.context-path", "");
            base = (ssl ? "https" : "http") + "://127.0.0.1:" + webContext.getWebServer().getPort()
                    + contextPath + "/supply/gateway";
        }
        try {
            URI uri = URI.create(base);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !safePath(uri.getRawPath())) {
                throw exception(DATA_API_CONFIG_INVALID, "公开网关基址不合法");
            }
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_API_CONFIG_INVALID, "公开网关基址不合法");
        }
        return base.replaceAll("/+$", "") + "/services/" + version + path;
    }

    /**
     * 判断安全路径条件是否成立。
     *
     * @param path 路径
     * @return 处理是否成功
     */
    private static boolean safePath(String path) {
        if (path == null || path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        try {
            for (String segment : path.split("/", -1)) {
                String decoded = segment;
                // 重复编码也不能把登记路径变成跳出公开网关的路径。
                for (int i = 0; i < 4 && decoded.contains("%"); i++) {
                    decoded = HttpUtils.decodeUrlPath(decoded);
                }
                if (decoded.contains("%") || decoded.equals(".") || decoded.equals("..")
                        || decoded.contains("/") || decoded.contains("\\") || decoded.contains(";")
                        || decoded.chars().anyMatch(character -> character < 32 || character == 127)) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 追加查询。
     *
     * @param target 目标数据
     * @param query 查询
     * @return 追加查询文本
     */
    static String appendQuery(String target, List<ApiTestKeyValueVO> query) {
        if (target == null || target.isBlank() || target.length() > 8_192) {
            throw exception(DATA_API_REQUEST_INVALID, "目标 URL 缺失或过长");
        }
        try {
            if (URI.create(target).getRawFragment() != null) {
                throw exception(DATA_API_REQUEST_INVALID, "目标 URL 不能包含 fragment");
            }
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_API_REQUEST_INVALID, "目标 URL 不合法");
        }
        StringBuilder result = new StringBuilder(target);
        if (query != null) {
            if (query.size() > 128) {
                throw exception(DATA_API_REQUEST_INVALID, "查询参数超过数量限制");
            }
            boolean hasQuery = target.indexOf('?') >= 0;
            for (ApiTestKeyValueVO pair : query) {
                if (pair == null || StrUtil.isBlank(pair.getName()) || pair.getValue() == null) {
                    throw exception(DATA_API_REQUEST_INVALID, "查询参数名称或值缺失");
                }
                result.append(hasQuery ? '&' : '?');
                hasQuery = true;
                result.append(encodeQueryPart(pair.getName())).append('=').append(encodeQueryPart(pair.getValue()));
            }
        }
        if (result.length() > 8_192) {
            throw exception(DATA_API_REQUEST_INVALID, "目标 URL 过长");
        }
        return result.toString();
    }

    /**
     * 编码查询部分。
     *
     * @param value 待处理的值
     * @return 编码查询部分文本
     */
    private static String encodeQueryPart(String value) {
        return HttpUtils.encodeUtf8(value).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    /**
     * 获取API测试的安全请求头。
     *
     * @param headers 请求头映射
     * @return API测试的安全请求头结果集合
     */
    private static List<SafeHttpExecutor.Header> safeHeaders(List<ApiTestKeyValueVO> headers) {
        if (headers == null) {
            return List.of();
        }
        return headers.stream().map(header -> {
            if (header == null || header.getName() == null || header.getValue() == null) {
                throw exception(DATA_API_REQUEST_INVALID, "请求头名称或值缺失");
            }
            String name = header.getName().toLowerCase(Locale.ROOT);
            if (INTERNAL_HEADERS.contains(name) || name.startsWith("x-forwarded-")) {
                throw exception(DATA_API_REQUEST_INVALID, "不能发送内部身份、路由或客户端地址控制头");
            }
            return new SafeHttpExecutor.Header(header.getName(), header.getValue());
        }).toList();
    }

    /**
     * 转换响应。
     *
     * @param response 响应对象
     * @return API测试发送响应数据
     */
    static ApiTestSendRespVO convertResponse(SafeHttpExecutor.Result response) {
        ApiTestSendRespVO result = new ApiTestSendRespVO();
        result.setStatusCode(response.statusCode());
        result.setStatusText(response.statusText());
        result.setHeaders(response.headers().stream()
                .map(header -> new ApiTestKeyValueVO(header.name(), header.value())).toList());
        result.setBodyBytes(response.body().length);
        result.setDurationMs(response.durationMs());
        result.setTruncated(response.truncated());
        result.setUrl(response.url());
        result.setRedirectChain(response.redirectChain().stream()
                .map(redirect -> new ApiTestSendRespVO.Redirect(redirect.url(), redirect.statusCode())).toList());
        try {
            result.setBody(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body()))
                    .toString());
            result.setBodyEncoding("UTF8");
        } catch (CharacterCodingException ex) {
            result.setBody(Base64.getEncoder().encodeToString(response.body()));
            result.setBodyEncoding("BASE64");
        }
        return result;
    }
}
