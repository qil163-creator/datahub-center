package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SmUtil;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiReleaseDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiReleaseMapper;
import cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import tools.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.supply.enums.dataapi.ErrorCodeConstants.OPERATION_SIGN_REQUIRED;

/**
 * 数据服务对外网关：定位 API、认证应用并执行。
 *
 * <p>签名认证使用国密 SM3。签名串严格为
 * {@code HTTP_METHOD + "_" + API_PATH + "_" + AppKey + "_" + Timestamp + "_" + Nonce}，
 * 请求头中的 {@code X-Sign} 为 {@code SM3(AppSecret + 签名串)}。时间戳允许前后十分钟，
 * Nonce 在 Redis 中保留一分钟，确保多节点网关不能重复使用同一随机数。</p>
 */
@Slf4j
@Service
public class DataApiGatewayService {

    public static final String HEADER_APP_KEY = "X-AppKey";

    public static final String HEADER_TIMESTAMP = "X-Timestamp";

    public static final String HEADER_NONCE = "X-Nonce";

    public static final String HEADER_SIGN = "X-Sign";

    public static final String HEADER_SIGN_VERSION = DataApiSignatureV2.HEADER_SIGN_VERSION;

    public static final String HEADER_KEY_VERSION = DataApiSignatureV2.HEADER_KEY_VERSION;

    static final long SIGN_TIMESTAMP_VALID_MS = 10 * 60 * 1000L;

    static final long CLOCK_SKEW_MARGIN_MS = 2_000L;

    private Clock clock = Clock.systemUTC();

    @Resource
    private DataApiMapper dataApiMapper;

    @Resource
    private SupplyAppMapper appMapper;

    @Resource
    private AppApiMapper appApiMapper;

    @Resource
    private DataApiExecutor dataApiExecutor;

    @Resource
    private SupplyApiRedisStore redisStore;

    @Resource
    private DataApiReleaseMapper dataApiReleaseMapper;

    @Resource
    private ApiLogMapper apiLogMapper;

    /**
     * 按开放 API 路径及版本定位接口，认证应用并执行已发布配置。
     *
     * @param version 版本
     * @param path 路径
     * @param httpMethod HTTP 方法
     * @param params 业务参数映射
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @param callerIp 调用方 IP
     * @param headers 请求头映射
     * @return 数据 API执行响应数据
     */
    public DataApiExecuteRespVO invoke(String version, String path, String httpMethod,
            Map<String, Object> params, Integer pageNo, Integer pageSize,
            String callerIp, Map<String, String> headers) {
        return invoke(version, path, httpMethod, params, pageNo, pageSize, callerIp,
                "", null, headers);
    }

    /**
     * 开放网关入口：保留原始 query/body 内容供执行链路和审计使用。
     */

    public DataApiExecuteRespVO invoke(String version, String path, String httpMethod,
            Map<String, Object> params, Integer pageNo, Integer pageSize,
            String callerIp, String rawQuery, String rawBody,
            Map<String, String> headers) {
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        long started = System.currentTimeMillis();
        DataApiDO api = resolveApi(path, version);
        if (api == null) {
            throw exception(DATA_API_NOT_EXISTS);
        }
        SupplyAppDO app;
        DataApiDO runtime;
        try {
            if (api.getStatus() == null || api.getStatus() != DataApiDO.STATUS_ONLINE) {
                throw exception(DATA_API_NOT_RELEASED);
            }
            if (StrUtil.isNotBlank(api.getMethod()) && !api.getMethod().equalsIgnoreCase(httpMethod)) {
                throw exception(DATA_API_AUTH_FAILED, "请求方式不匹配");
            }
            app = authenticate(api, version, httpMethod, rawQuery, rawBody, headers);
            String apiType = StrUtil.blankToDefault(api.getApiType(), DataApiDO.API_TYPE_QUERY);
            if (app == null && (!DataApiDO.API_TYPE_QUERY.equalsIgnoreCase(apiType)
                    || DataApiDO.CONFIG_TYPE_FORWARD.equalsIgnoreCase(api.getConfigType()))) {
                throw exception(DATA_API_NOT_AUTHORIZED, "写入或转发接口必须使用应用认证");
            }


            runtime = publishedRuntime(api);
            if (DataApiOperationService.requiresOperation(runtime)) {
                if (app == null || !DataApiDO.AUTH_TYPE_SIGN.equalsIgnoreCase(runtime.getAuthType())
                        || !DataApiSignatureV2.VERSION.equalsIgnoreCase(header(safeHeaders, HEADER_SIGN_VERSION))
                        || runtime.getActiveReleaseId() == null) {
                    throw exception(OPERATION_SIGN_REQUIRED);
                }
                DataApiOperationService.key(params);
            }
        } catch (ServiceException ex) {
            logReject(api, header(safeHeaders, HEADER_APP_KEY), callerIp, ex, started);
            throw ex;
        }
        if (app == null) {
            return dataApiExecutor.execute(
                    runtime, params, pageNo, pageSize, callerIp, true, true, null, false,
                    DataApiExecuteMode.APP_GATEWAY);
        }
        Long appId = app.getId();
        return dataApiExecutor.execute(
                runtime, params, pageNo, pageSize, callerIp, true, true, appId, false,
                DataApiExecuteMode.APP_GATEWAY);
    }

    /**
     * 设置当前组件使用的时钟。
     *
     * @param clock 当前使用的时钟
     */
    void setClock(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 单平台接口路径必须唯一，重复路径不选择任意候选。 */
    private DataApiDO resolveApi(String path, String version) {
        List<DataApiDO> candidates = dataApiMapper.selectListByPathAndVersion(path, version);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() != 1) {
            throw exception(DATA_API_AUTH_FAILED, "接口路径重复，请联系管理员处理");
        }
        return candidates.getFirst();
    }

    /**
     * 校验应用凭据、签名和防重放约束，返回已认证的调用应用。
     *
     * @param api 本次使用的 API 定义
     * @param version 版本
     * @param httpMethod HTTP 方法
     * @param rawQuery 原始查询字符串
     * @param rawBody 原始请求体
     * @param headers 请求头映射
     * @return 供应应用
     */
    private SupplyAppDO authenticate(DataApiDO api, String version, String httpMethod,
            String rawQuery, String rawBody,
            Map<String, String> headers) {
        String authType = StrUtil.blankToDefault(api.getAuthType(), DataApiDO.AUTH_TYPE_NONE);
        if (DataApiDO.AUTH_TYPE_NONE.equalsIgnoreCase(authType)) {
            return null;
        }
        if (!DataApiDO.AUTH_TYPE_APPKEY.equalsIgnoreCase(authType)
                && !DataApiDO.AUTH_TYPE_SIGN.equalsIgnoreCase(authType)) {
            throw exception(DATA_API_AUTH_FAILED, "不支持的认证方式：" + authType);
        }
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        String appKey = header(safeHeaders, HEADER_APP_KEY);
        if (StrUtil.isBlank(appKey)) {
            throw exception(DATA_API_AUTH_FAILED, "缺少请求头 " + HEADER_APP_KEY);
        }
        SupplyAppDO app = appMapper.selectByAppKey(appKey);
        if (app == null) {
            throw exception(DATA_API_AUTH_FAILED, "AppKey 无效");
        }
        validateAuthorization(app, api);
        if (DataApiDO.AUTH_TYPE_SIGN.equalsIgnoreCase(authType)) {
            validateSign(api, app, version, httpMethod, rawQuery, rawBody, safeHeaders);
        }
        return app;
    }

    /**
     * 确认签名协议允许。
     *
     * @param app 调用应用信息
     * @param v2 版本2
     */
    private void assertSignProtocolAllowed(SupplyAppDO app, boolean v2) {
        String protocol = StrUtil.blankToDefault(app.getSignProtocol(), SupplyAppDO.SIGN_PROTOCOL_BOTH);
        if (v2) {
            if (SupplyAppDO.SIGN_PROTOCOL_LEGACY.equalsIgnoreCase(protocol)) {
                throw exception(DATA_API_SIGN_PROTOCOL_NOT_ALLOWED);
            }
            return;
        }
        if (SupplyAppDO.SIGN_PROTOCOL_V2.equalsIgnoreCase(protocol)) {
            throw exception(DATA_API_SIGN_PROTOCOL_NOT_ALLOWED);
        }
    }

    /**
     * 校验授权。
     *
     * @param app 调用应用信息
     * @param api 本次使用的 API 定义
     */
    private void validateAuthorization(SupplyAppDO app, DataApiDO api) {
        if (app.getAppStatus() != null && app.getAppStatus() != SupplyGrantValidity.APP_ENABLED) {
            throw exception(SUPPLY_APP_DISABLED);
        }
        AppApiDO appApi = appApiMapper.selectByAppIdAndApiId(app.getId(), api.getId());
        if (appApi == null) {
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), SupplyGrantValidity.ZONE);
        if (!SupplyGrantValidity.isEffective(app, appApi, now)) {
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
        if (!SupplyGrantValidity.matchesCurrentContract(appApi, api.getContractHash())) {
            throw exception(DATA_API_CONTRACT_MISMATCH);
        }
    }

    /**
     * 从活动发布记录恢复本次调用使用的冻结 API 配置。
     *
     * @param api 本次使用的 API 定义
     * @return 数据 API
     */
    private DataApiDO publishedRuntime(DataApiDO api) {
        if (api.getActiveReleaseId() == null) {
            return api;
        }
        DataApiReleaseDO release = dataApiReleaseMapper.selectById(api.getActiveReleaseId());
        if (release == null || StrUtil.isBlank(release.getConfigJson())) {
            throw exception(DATA_API_RELEASE_NOT_EXISTS);
        }
        DataApiDO runtime = BeanUtils.toBean(api, DataApiDO.class);
        DataApiContract.applySnapshot(runtime, JsonUtils.parseObject(release.getConfigJson(),
                new TypeReference<>() {
                }));
        runtime.setId(api.getId());

        runtime.setStatus(api.getStatus());
        runtime.setCategoryId(api.getCategoryId());
        runtime.setActiveReleaseId(release.getId());
        runtime.setContractHash(release.getContractHash());
        return runtime;
    }

    /**
     * 记录拒绝。
     *
     * @param api 本次使用的 API 定义
     * @param appKey 应用标识
     * @param callerIp 调用方 IP
     * @param ex 异常信息
     * @param started 已开始
     */
    private void logReject(DataApiDO api, String appKey, String callerIp, ServiceException ex, long started) {
        try {
            Long appId = null;
            if (StrUtil.isNotBlank(appKey)) {
                SupplyAppDO app = appMapper.selectByAppKey(appKey);
                appId = app == null
                        ? null : app.getId();
            }
            String stage = rejectStage(ex.getCode());
            ApiLogDO logEntry = ApiLogDO.builder()
                    .apiId(api.getId())
                    .apiName(api.getName())
                    .categoryId(api.getCategoryId())
                    .appId(appId)
                    .callerIp(callerIp)
                    .callPath(buildGatewayPath(api.getVersion(), api.getPath()))
                    .method(api.getMethod())
                    .callTime(LocalDateTime.now(SupplyGrantValidity.ZONE))
                    .durationMs(System.currentTimeMillis() - started)
                    .dataCount(0L)
                    .status(ApiLogDO.STATUS_FAILED)
                    .errorInfo("请求被拒绝，阶段=" + stage + "，错误码=" + ex.getCode())
                    .callMode(DataApiExecuteMode.APP_GATEWAY.name())
                    .releaseId(api.getActiveReleaseId())
                    .rejectStage(stage)
                    .traceId(currentTraceId())
                    .build();
            apiLogMapper.insert(logEntry);
        } catch (Exception ignored) {
            log.warn("[logReject][API({}) 拒绝日志写入失败]", api.getId());
        }
    }

    /**
     * 拒绝阶段。
     *
     * @param code 编码
     * @return 拒绝阶段文本
     */
    private static String rejectStage(Integer code) {
        if (code == null) {
            return "EXECUTE";
        }
        if (code.equals(DATA_API_AUTH_FAILED.getCode())
                || code.equals(DATA_API_SIGN_PROTOCOL_NOT_ALLOWED.getCode())
                || code.equals(OPERATION_SIGN_REQUIRED.getCode())) {
            return "AUTH";
        }
        if (code.equals(DATA_API_NOT_AUTHORIZED.getCode())
                || code.equals(SUPPLY_APP_DISABLED.getCode())) {
            return "GRANT";
        }
        if (code.equals(DATA_API_CONTRACT_MISMATCH.getCode())) {
            return "CONTRACT";
        }
        return "EXECUTE";
    }

    /**
     * 读取当前调用链路的追踪标识。
     *
     * @return 当前调用链路的追踪标识文本
     */
    static String currentTraceId() {
        var request = cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getRequest();
        Object trace = request == null ? null : request.getAttribute("supply.traceId");
        return trace instanceof String value && value.matches("[A-Za-z0-9_-]{1,64}") ? value : null;
    }

    /**
     * 校验签名。
     *
     * @param api 本次使用的 API 定义
     * @param app 调用应用信息
     * @param version 版本
     * @param httpMethod HTTP 方法
     * @param rawQuery 原始查询字符串
     * @param rawBody 原始请求体
     * @param headers 请求头映射
     */
    private void validateSign(DataApiDO api, SupplyAppDO app, String version,
            String httpMethod, String rawQuery, String rawBody,
            Map<String, String> headers) {
        String timestamp = header(headers, HEADER_TIMESTAMP);
        String nonce = header(headers, HEADER_NONCE);
        String sign = header(headers, HEADER_SIGN);
        if (StrUtil.hasBlank(timestamp, nonce, sign) || StrUtil.isBlank(app.getSecret())) {
            throw exception(DATA_API_AUTH_FAILED,
                    "签名认证需携带请求头 " + HEADER_TIMESTAMP + "、" + HEADER_NONCE + "、" + HEADER_SIGN);
        }
        boolean v2 = DataApiSignatureV2.VERSION.equalsIgnoreCase(header(headers, HEADER_SIGN_VERSION));
        assertSignProtocolAllowed(app, v2);
        long now = clock.millis();
        long timestampMillis;
        if (v2) {
            DataApiSignatureV2.validateTimestamp(timestamp);
            timestampMillis = Long.parseLong(timestamp);
            if (timestampMillis < now - SIGN_TIMESTAMP_VALID_MS
                    || timestampMillis >= now + SIGN_TIMESTAMP_VALID_MS) {
                throw exception(DATA_API_AUTH_FAILED, "时间戳已过期（有效期 10 分钟）");
            }
            byte[] body = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
            String expected = DataApiSignatureV2.sign(
                    SupplyAppSecretCipher.decrypt(app.getSecret()),
                    StrUtil.blankToDefault(httpMethod, api.getMethod()),
                    buildGatewayPath(version, api.getPath()),
                    rawQuery, body, app.getAppKey(), timestamp, nonce,
                    header(headers, "Content-Type"),
                    header(headers, HEADER_KEY_VERSION));
            if (!DataApiSignatureV2.matches(expected, sign)) {
                throw exception(DATA_API_AUTH_FAILED, "签名不正确");
            }
        } else {
            long ts;
            try {
                ts = Long.parseLong(timestamp);
            } catch (NumberFormatException ex) {
                throw exception(DATA_API_AUTH_FAILED, "时间戳格式不正确");
            }
            timestampMillis = normalizeTimestampMillis(ts);
            if (timestampMillis < now - SIGN_TIMESTAMP_VALID_MS
                    || timestampMillis > now + SIGN_TIMESTAMP_VALID_MS) {
                throw exception(DATA_API_AUTH_FAILED, "时间戳已过期（有效期 10 分钟）");
            }
            String expected = sign(app.getAppKey(), SupplyAppSecretCipher.decrypt(app.getSecret()),
                    StrUtil.blankToDefault(api.getMethod(), httpMethod),
                    buildGatewayPath(version, api.getPath()), timestamp, nonce);
            if (!constantTimeEquals(expected, sign)) {
                throw exception(DATA_API_AUTH_FAILED, "签名不正确");
            }
        }
        reserveNonce(app, nonce, timestampMillis, now);
    }

    /**
     * 获取数据 API网关的随机数有效期毫秒数。
     *
     * @param timestampMillis 时间戳毫秒数
     * @param now 当前时间
     * @return 随机数有效期毫秒数数值
     */
    static long nonceTtlMillis(long timestampMillis, long now) {
        long remaining = timestampMillis + SIGN_TIMESTAMP_VALID_MS - now;
        if (remaining < 1L) {
            remaining = 1L;
        }
        long ttl = remaining + CLOCK_SKEW_MARGIN_MS;
        long max = SIGN_TIMESTAMP_VALID_MS * 2 + CLOCK_SKEW_MARGIN_MS;
        return Math.min(ttl, max);
    }

    /**
     * 预留随机数。
     *
     * @param app 调用应用信息
     * @param nonce 随机数
     * @param timestampMillis 时间戳毫秒数
     * @param now 当前时间
     */
    private void reserveNonce(SupplyAppDO app, String nonce, long timestampMillis, long now) {
        try {
            if (!redisStore.reserveNonce(app.getAppKey(),
                    nonce, nonceTtlMillis(timestampMillis, now))) {
                throw exception(DATA_API_AUTH_FAILED, "Nonce 已被使用，请重新生成");
            }
        } catch (IllegalStateException ex) {
            throw exception(DATA_API_AUTH_FAILED, "认证存储不可用");
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_API_AUTH_FAILED, "Nonce 不合法");
        }
    }

    /**
     * 签名数据 API网关。
     *
     * @param appKey 应用标识
     * @param appSecret 应用密钥
     * @param method HTTP 方法
     * @param apiPath API路径
     * @param timestamp 时间戳
     * @param nonce 随机数
     * @return 计算后的摘要或签名字符串
     */
    public static String sign(String appKey, String appSecret, String method,
            String apiPath, String timestamp, String nonce) {
        String signature = signatureString(method, apiPath, appKey, timestamp, nonce);
        return SmUtil.sm3(canonicalValue(appSecret) + signature);
    }

    /**
     * 签名数据 API网关。
     *
     * @param appKey 应用标识
     * @param appSecret 应用密钥
     * @param method HTTP 方法
     * @param apiPath API路径
     * @param queryString 查询字符串
     * @param body 请求体
     * @param timestamp 时间戳
     * @param nonce 随机数
     * @return 计算后的摘要或签名字符串
     */
    public static String sign(String appKey, String appSecret, String method,
            String apiPath, String queryString, String body,
            String timestamp, String nonce) {
        // 查询串和请求体不属于约定签名串，保留参数只是兼容管理端旧的调试请求结构。
        return sign(appKey, appSecret, method, apiPath, timestamp, nonce);
    }

    /** 返回协议约定的签名串，便于客户端联调和单元测试复用。 */
    public static String signatureString(String method, String apiPath, String appKey,
            String timestamp, String nonce) {
        return canonicalValue(method).toUpperCase(Locale.ROOT) + "_"
                + normalizePath(apiPath) + "_"
                + canonicalValue(appKey) + "_"
                + canonicalValue(timestamp) + "_"
                + canonicalValue(nonce);
    }

    /** 保留旧调试工具方法名，但返回当前协议的精确签名串。 */
    public static String canonicalRequest(String version, String method, String apiPath,
            String queryString, String body, String appKey,
            String timestamp, String nonce) {
        return signatureString(method, apiPath, appKey, timestamp, nonce);
    }

    /**
     * 构造网关路径。
     *
     * @param version 版本
     * @param path 路径
     * @return 构造网关路径文本
     */
    public static String buildGatewayPath(String version, String path) {
        return "/services/" + StrUtil.nullToEmpty(version) + StrUtil.nullToEmpty(path);
    }

    /**
     * 规范化路径。
     *
     * @param path 路径
     * @return 规范化路径文本
     */
    private static String normalizePath(String path) {
        String value = StrUtil.blankToDefault(path, "/");
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    /**
     * 将空字符串值统一为规范表示。
     *
     * @param value 待处理的值
     * @return 将空字符串值统一为规范表示文本
     */
    private static String canonicalValue(String value) {
        return value == null ? "" : value;
    }

    /**
     * 兼容秒级和毫秒级 Unix 时间戳，但签名串始终使用请求头中的原始文本。
     */
    private static long normalizeTimestampMillis(long timestamp) {
        if (Math.abs(timestamp) < 100_000_000_000L) {
            try {
                return Math.multiplyExact(timestamp, 1000L);
            } catch (ArithmeticException ex) {
                return timestamp;
            }
        }
        return timestamp;
    }

    /**
     * 读取当前请求的指定请求头。
     *
     * @param headers 请求头映射
     * @param name 名称
     * @return 当前请求的指定请求头文本
     */
    private static String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String exact = headers.get(name);
        if (exact != null) {
            return exact;
        }
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 以固定时间比较摘要，避免逐字符短路比较泄露匹配前缀。
     *
     * @param expected 预期值
     * @param actual 实际值
     * @return 处理是否成功
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

}
