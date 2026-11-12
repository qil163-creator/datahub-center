package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.framework.common.util.http.SafeHttpExecutor;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyMaskExemptRespDTO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.QueryTimeoutConnection;
import cn.iocoder.yudao.module.supply.service.app.grant.EffectiveGrantSnapshot;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.sql.Timestamp;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.GRANT_SCOPE_CHANGED;

/**
 * 数据服务-API 执行器：按配置方式取数并返回统一结果，同时记录调用日志
 *
 * TABLE 单表向导式：按请求参数拼装等值 WHERE 条件查询；
 * SQL 脚本式：#{参数名} 占位符替换为预编译参数，支持 &lt;where&gt;/&lt;if&gt; 动态条件标签，
 *            支持 QUERY/INSERT/UPDATE/DELETE/MULTI（多脚本事务）接口类型；
 * FORWARD 第三方转发：经 {@link SafeHttpExecutor} 转发，连接固定到本次已校验的公网地址，透传参数与响应。
 *
 * 附加能力：结果缓存（查询类）、熔断降级（异常数策略）、IP 黑白名单、限流、
 *          返回字段数据模型（隐藏/映射/拼接/替换/截取/分割/时间格式化/脱敏）、失败告警（站内信）。
 *
 * @author Li
 */
@Slf4j
@Component
public class DataApiExecutor {

    @jakarta.annotation.Resource
    private DataApiIndicatorQueryService indicatorQueries;


    private static final Pattern SQL_PARAM_PATTERN = Pattern.compile("#\\{(\\w+)}");

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[\\w.$\"]+$");

    private static final Pattern IF_TAG_PATTERN =
            Pattern.compile("(?is)<if\\s+test\\s*=\\s*\"([^\"]*)\"\\s*>(.*?)</if>");

    private static final Pattern WHERE_TAG_PATTERN = Pattern.compile("(?is)<where>(.*?)</where>");

    private static final int LIST_MAX_ROWS = 1000;

    private static final int FORWARD_TIMEOUT_SECONDS = 10;

    /** 并发租约在最长执行时间之外的余量，覆盖结果处理、日志与网络往返 */
    private static final int CONCURRENCY_LEASE_MARGIN_SECONDS = 30;

    private static final int FORWARD_MAX_BODY_LENGTH = 1024 * 1024;

    @Resource
    private SupplyDatabaseResolver databaseResolver;

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private ApiLogMapper apiLogMapper;

    @Resource
    private NotifyMessageSendApi notifyMessageSendApi;

    @Resource
    private SafetySecurityApi safetySecurityApi;


    @Resource
    private AppApiMapper appApiMapper;

    @Resource
    private SupplyAppMapper supplyAppMapper;

    @Resource
    private SupplyApiRedisStore redisStore;

    @Resource
    private SupplyDataApiProperties dataApiProperties;

    @Resource
    private SupplyGrantReadService grantReadService;

    @Resource
    private DataApiOperationService operationService;

    @Resource
    private cn.iocoder.yudao.module.supply.service.app.SupplyQuotaService quotaService;

    @Resource
    private DataApiEtlBatchService etlBatches;

    @Resource
    private cn.iocoder.yudao.module.supply.service.app.SupplyEtlGrantPreflight etlGrantSubjects;

    /**
     * 执行 API 取数（无调用应用上下文）
     */
    public DataApiExecuteRespVO execute(DataApiDO api, Map<String, Object> params,
            Integer pageNo, Integer pageSize,
            String callerIp, boolean checkGuard, boolean writeLog) {
        return execute(api, params, pageNo, pageSize, callerIp, checkGuard, writeLog, null, false,
                DataApiExecuteMode.ADMIN_EXECUTE);
    }

    /**
     * 执行 API 取数（网关调用：套用授权记录上的申请人权限）
     */
    public DataApiExecuteRespVO execute(DataApiDO api, Map<String, Object> params,
            Integer pageNo, Integer pageSize,
            String callerIp, boolean checkGuard, boolean writeLog,
            Long appId) {
        return execute(api, params, pageNo, pageSize, callerIp, checkGuard, writeLog, appId, false,
                DataApiExecuteMode.APP_GATEWAY);
    }

    /**
     * 执行 API 取数
     *
     * @param api                 API 定义
     * @param params              请求参数值
     * @param pageNo              页码（PAGE 格式）
     * @param pageSize            每页条数（PAGE 格式）
     * @param callerIp            调用者 IP
     * @param checkGuard          是否执行 IP 名单/限流/熔断校验（正式调用 true，向导测试 false）
     * @param writeLog            是否记录调用日志（api.id 为空时不记录）
     * @param appId               调用应用编号（网关调用时记录）
     * @param applyUserPermission 是否使用当前登录人作为执行主体（管理端试调）
     */
    public DataApiExecuteRespVO execute(DataApiDO api, Map<String, Object> params,
            Integer pageNo, Integer pageSize,
            String callerIp, boolean checkGuard, boolean writeLog,
            Long appId, boolean applyUserPermission) {
        return execute(api, params, pageNo, pageSize, callerIp, checkGuard, writeLog, appId,
                applyUserPermission, DataApiExecuteMode.ADMIN_EXECUTE);
    }

    /**
     * 执行 API 取数（带明确执行场景）
     */
    public DataApiExecuteRespVO execute(DataApiDO api, Map<String, Object> params,
            Integer pageNo, Integer pageSize,
            String callerIp, boolean checkGuard, boolean writeLog,
            Long appId, boolean applyUserPermission,
            DataApiExecuteMode mode) {
        DataApiExecuteMode executeMode = mode == null ? DataApiExecuteMode.ADMIN_EXECUTE : mode;
        String testRunNo = executeMode == DataApiExecuteMode.DRAFT_READONLY_PREVIEW
                ? IdUtil.getSnowflakeNextIdStr() : null;
        String configHash = configHash(api);
        long start = System.currentTimeMillis();
        String rejectStage = "EXECUTE";
        cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Receipt quotaReceipt = null;
        List<SupplyApiRedisStore.ConcurrencyLease> leases = new ArrayList<>(2);
        try {
            if (executeMode.readOnly() || DataApiReadOnlyGuard.isQueryApi(api)) {
                DataApiReadOnlyGuard.assertConfigurationAllowed(api, executeMode);
            }
            if (checkGuard) {
                rejectStage = "IP";
                checkIpRule(api, callerIp);
                boolean writeStatusQuery = executeMode == DataApiExecuteMode.APP_GATEWAY
                        && DataApiOperationService.requiresOperation(api)
                        && DataApiOperationService.isStatusQuery(params);
                if (!writeStatusQuery) {
                    rejectStage = "RATE";
                    checkRateLimit(api, appId);
                    rejectStage = "BREAKER";
                    checkBreaker(api, appId);
                    rejectStage = "CONCURRENCY";
                    acquireConcurrency(api, appId, leases);
                }
            }
            rejectStage = "GRANT";
            // 数据安全：脱敏字段清单与白名单豁免（治理模块，已审批+启用+有效期内）
            List<DataApiSaveReqVO.FieldItem> fieldDefines = parseFieldDefines(api.getResponseFieldsJson());
            List<String> maskColumns = collectMaskColumns(fieldDefines);
            Long permissionUserId = resolvePermissionUserId(appId, api.getId(), applyUserPermission);
            SafetyMaskExemptRespDTO maskExempt = permissionUserId != null
                    ? checkMaskExempt(permissionUserId)
                    : (isQueryApi(api) && !maskColumns.isEmpty() ? checkMaskExempt() : null);
            boolean skipMask = maskExempt != null && Boolean.TRUE.equals(maskExempt.getExempt());
            ExecuteContext permissionCtx = permissionUserId != null
                    ? new ExecuteContext(permissionUserId, skipMask) : null;
            Integer grantRevision = null;
            EffectiveGrantSnapshot grantSnapshot = null;
            if (executeMode == DataApiExecuteMode.APP_GATEWAY && appId != null && api.getId() != null) {
                // v2 来源聚合：整次查询使用同一份权威快照，主体与批准列都来自它，不回退应用创建人。
                grantSnapshot = readGrantSnapshot(api, appId);
                if (grantSnapshot != null) {
                    permissionUserId = grantSnapshot.permissionUserId();
                    maskExempt = checkMaskExempt(permissionUserId);
                    skipMask = maskExempt != null && Boolean.TRUE.equals(maskExempt.getExempt());
                    permissionCtx = new ExecuteContext(permissionUserId, skipMask);
                    grantRevision = (int) Math.floorMod(grantSnapshot.aggregateRevision(), Integer.MAX_VALUE);
                } else {
                    grantRevision = assertCurrentGrant(api, appId);
                }
            }
            // 结果缓存按调用主体隔离；应用批准列受限或带额度来源时仍禁止缓存。
            boolean cacheable = canUseResultCache(api, executeMode, grantSnapshot);
            String cacheKey = cacheable
                    ? buildCacheKey(api, params, pageNo, pageSize, appId, permissionUserId, skipMask, grantRevision)
                    : null;
            if (cacheKey != null) {
                DataApiExecuteRespVO cached = readCachedResult(cacheKey);
                if (cached != null) {
                    DataApiExecuteRespVO result = copyResult(cached);
                    result.setDurationMs(System.currentTimeMillis() - start);
                    decorateResult(result, executeMode, testRunNo, configHash);
                    saveLog(api, params, callerIp, result.getDurationMs(),
                            result.getRows() == null ? 0 : result.getRows().size(), null, writeLog, appId,
                            executeMode, testRunNo, configHash);
                    recordAccessAudit(api, maskColumns, false, null, callerIp, appId, permissionUserId);
                    // 缓存只复用数据结果，命中后仍须验证当前应用授权。
                    if (grantSnapshot != null) {
                        grantReadService.assertStillCurrent(grantSnapshot);
                    } else if (executeMode == DataApiExecuteMode.APP_GATEWAY && appId != null && api.getId() != null
                            && !Objects.equals(grantRevision, assertCurrentGrant(api, appId))) {
                        throw exception(DATA_API_NOT_AUTHORIZED);
                    }
                    return result;
                }
            }
            if (grantSnapshot != null && grantSnapshot.quotaSources().stream()
                    .anyMatch(source -> source.quota() != null)) {
                rejectStage = "QUOTA";
                String consumer = "API:" + appId + ":" + api.getId();
                String key = params == null ? null : (String) params.get("_consumeKey");
                if (params != null && "STATUS".equals(params.get("_consumeMode"))) {
                    var receipt = quotaService.receipt(consumer, key, permissionUserId);
                    DataApiExecuteRespVO status = new DataApiExecuteRespVO();
                    status.setConsumptionKey(key);
                    status.setConsumptionState(receipt.state());
                    status.setRows(List.of());
                    status.setConsumptionRows(receipt.actualRows());
                    return status;
                }
                Map<String, Object> consumptionParams = new LinkedHashMap<>(params == null ? Map.of() : params);
                consumptionParams.remove("_consumeKey");
                consumptionParams.remove("_consumeMode");
                // 消费摘要绑定发布版本、授权修订与业务参数，同一消费键不能被复用于另一份查询或授权范围。
                String hash = DigestUtil.sha256Hex(
                        JsonUtils.toJsonString(Arrays.asList(api.getId(), api.getActiveReleaseId(),
                                grantSnapshot.aggregateRevision(), new TreeMap<>(consumptionParams), pageNo,
                                pageSize)));
                quotaReceipt = quotaService.reserve(
                        new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Reserve(
                                consumer, key, permissionUserId, hash, resolvePageBound(api, pageNo, pageSize)[1],
                                grantSnapshot.approvedColumns(), grantSnapshot.quotaSources()));
                permissionCtx = new ExecuteContext(permissionUserId, skipMask, quotaReceipt.rowLimit());
                params = consumptionParams;
            }
            rejectStage = "EXECUTE";
            DataApiExecuteRespVO result;
            if (executeMode == DataApiExecuteMode.APP_GATEWAY && DataApiOperationService.requiresOperation(api)) {
                Map<String, Object> businessParams = new LinkedHashMap<>(params == null ? Map.of() : params);
                businessParams.remove(DataApiOperationService.KEY_PARAM);
                businessParams.remove(DataApiOperationService.MODE_PARAM);
                requireNoFieldScope(grantSnapshot);
                DataApiOperationService.WriteAction action;
                if (DataApiOperationService.isStatusQuery(params)) {
                    action = () -> {
                        throw new IllegalStateException("状态查询不得发起执行");
                    };
                } else {
                    // 确定性的参数、计划和授权拒绝发生在认领前，不污染未知结果队列。
                    Map<String, Object> effective = resolveParams(api, businessParams);
                    if (DataApiDO.CONFIG_TYPE_SQL.equals(api.getConfigType())) {
                        boolean multi = DataApiDO.API_TYPE_MULTI.equalsIgnoreCase(api.getApiType());
                        PreparedWrite prepared = prepareWrite(api, processDynamicTags(api.getSqlText(), effective),
                                effective, permissionCtx, multi);
                        action = () -> executeWrite(prepared);
                    } else if (DataApiDO.CONFIG_TYPE_FORWARD.equals(api.getConfigType())) {
                        validateForwardUrl(api.getForwardUrl(), dataApiProperties);
                        action = () -> executeForward(api, effective);
                    } else {
                        throw exception(DATA_API_TYPE_NOT_SUPPORTED, api.getApiType());
                    }
                }
                result = operationService.execute(api, appId, params, action);
            } else {
                result = doExecute(api, params, pageNo, pageSize, permissionCtx, grantSnapshot);
            }
            if (isQueryApi(api)) {
                result.setRows(applyFieldModels(result.getRows(), fieldDefines, skipMask));
            }
            if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType())) {
                etlGrantSubjects.requireActiveSubject(permissionUserId);
            }
            if (grantSnapshot != null) {
                // 返回前的最后一次权威校验：查询过程中撤权、到期、停用或合同变化都必须丢弃本次结果。
                grantReadService.assertStillCurrent(grantSnapshot);
            } else if (executeMode == DataApiExecuteMode.APP_GATEWAY && appId != null
                    && DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType())
                    && !Objects.equals(grantRevision, assertCurrentGrant(api, appId))) {
                throw exception(DATA_API_NOT_AUTHORIZED);
            }
            result.setDurationMs(System.currentTimeMillis() - start);
            decorateResult(result, executeMode, testRunNo, configHash);
            saveLog(api, params, callerIp, result.getDurationMs(),
                    result.getRows() == null ? 0 : result.getRows().size(),
                    "UNKNOWN".equals(result.getOperationStatus()) ? "外部写入结果未知，请核查操作台账" : null,
                    writeLog, appId, executeMode, testRunNo, configHash);
            recordAccessAudit(api, maskColumns, skipMask, maskExempt, callerIp, appId, permissionUserId);
            if (cacheKey != null) {
                putCache(cacheKey, api, result);
            }
            if (quotaReceipt != null) {
                // 以权限处理后的实际返回行数结算；认领令牌把结算绑定到本次预留，避免并发请求串账。
                var receipt = quotaService.settle(new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Settle(
                        quotaReceipt.consumerKey(), quotaReceipt.requestKey(), quotaReceipt.token(),
                        result.getRows() == null ? 0 : result.getRows().size(), false));
                result.setConsumptionKey(receipt.requestKey());
                result.setConsumptionState(receipt.state());
                result.setConsumptionRows(receipt.actualRows());
            }
            return result;
        } catch (Exception ex) {
            if (quotaReceipt != null) {
                try {
                    quotaService.settle(new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Settle(
                            quotaReceipt.consumerKey(), quotaReceipt.requestKey(), quotaReceipt.token(), null, true));
                } catch (Exception ignored) { /* 已确认不回退；未知回执由查询和租约巡检收敛。 */ }
            }
            long duration = System.currentTimeMillis() - start;
            boolean timedOut = "EXECUTE".equals(rejectStage) && QueryTimeoutConnection.isQueryTimeout(ex);
            String errorInfo = timedOut ? "查询超时（超过 " + queryTimeoutSeconds(api) + " 秒），已取消执行"
                    : safeExecutionError(ex, rejectStage);
            saveLog(api, params, callerIp, duration, 0, errorInfo, writeLog, appId,
                    executeMode, testRunNo, configHash, rejectStage);
            if (checkGuard && "EXECUTE".equals(rejectStage)) {
                try {
                    recordBreakerError(api, appId);
                } catch (Exception ignored) { /* 不覆盖原失败。 */ }
                sendAlarm(api, errorInfo);
            }
            if (timedOut) {
                throw exception(DATA_API_QUERY_TIMEOUT, queryTimeoutSeconds(api));
            }
            if (ex instanceof cn.iocoder.yudao.framework.common.exception.ServiceException serviceException) {
                throw serviceException;
            }
            throw exception(DATA_API_EXECUTE_FAILED, "执行未完成，请通过调用记录核查");
        } finally {
            releaseConcurrency(leases);
        }
    }

    // ==================== 并发与超时 ====================

    /**
     * 生效的查询超时：接口配置优先，否则平台默认；均不超过平台上限。
     *
     * @param api 本次使用的 API 定义
     * @return 超时秒数
     */
    int queryTimeoutSeconds(DataApiDO api) {
        int max = Math.max(1, dataApiProperties.getMaxQueryTimeoutSeconds());
        Integer configured = api.getTimeoutSeconds();
        int value = configured != null && configured > 0 ? configured : dataApiProperties.getDefaultQueryTimeoutSeconds();
        return Math.min(Math.max(1, value), max);
    }

    /** 转发超时：接口配置了超时则沿用（不超过执行器上限 120 秒），否则保持 10 秒默认值 */
    private int forwardTimeoutSeconds(DataApiDO api) {
        return api.getTimeoutSeconds() != null && api.getTimeoutSeconds() > 0
                ? Math.min(queryTimeoutSeconds(api), 120) : FORWARD_TIMEOUT_SECONDS;
    }

    /**
     * 申请接口级与应用级并发租约；任一达到上限即拒绝，已获得的租约由调用方 finally 释放。
     * 租约时长覆盖最长执行时间并留余量，进程崩溃时占位会自动过期回收。
     */
    private void acquireConcurrency(DataApiDO api, Long appId, List<SupplyApiRedisStore.ConcurrencyLease> leases) {
        Integer apiLimit = api.getMaxConcurrency();
        int appLimit = dataApiProperties.getAppMaxConcurrency();
        boolean limitApi = api.getId() != null && apiLimit != null && apiLimit > 0;
        boolean limitApp = appId != null && appLimit > 0;
        if (!limitApi && !limitApp) {
            return;
        }
        long leaseMillis = (Math.max(queryTimeoutSeconds(api), forwardTimeoutSeconds(api)) + CONCURRENCY_LEASE_MARGIN_SECONDS)
                * 1000L;
        try {
            if (limitApi) {
                var lease = redisStore.acquireConcurrency("api:" + api.getId(), apiLimit, leaseMillis);
                if (lease == null) {
                    throw exception(DATA_API_CONCURRENCY_LIMITED, "接口上限 " + apiLimit);
                }
                leases.add(lease);
            }
            if (limitApp) {
                var lease = redisStore.acquireConcurrency("app:" + appKey(appId), appLimit, leaseMillis);
                if (lease == null) {
                    throw exception(DATA_API_CONCURRENCY_LIMITED, "应用上限 " + appLimit);
                }
                leases.add(lease);
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException rejected) {
            throw rejected;
        } catch (RuntimeException storeFailure) {
            // 并发控制存储不可用时拒绝执行，不能在失去保护的情况下放行
            log.warn("[acquireConcurrency][API({}) 并发控制存储不可用]", api.getId());
            throw exception(DATA_API_EXECUTE_FAILED, "并发控制暂不可用，请稍后重试");
        }
    }

    /** 释放本次请求持有的全部并发租约；释放失败时租约到期自动回收 */
    private void releaseConcurrency(List<SupplyApiRedisStore.ConcurrencyLease> leases) {
        for (var lease : leases) {
            try {
                redisStore.releaseConcurrency(lease);
            } catch (RuntimeException ex) {
                log.warn("[releaseConcurrency][并发租约释放失败，将于到期后自动回收：{}]", lease.key());
            }
        }
    }

    /**
     * 依据 API 配置类型分派单表、SQL、转发或 ETL 批次执行。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @param permissionCtx 权限上下文
     * @param grantSnapshot 授权快照
     * @return 数据 API执行响应数据
     */
    private DataApiExecuteRespVO doExecute(DataApiDO api, Map<String, Object> params,
            Integer pageNo, Integer pageSize,
            ExecuteContext permissionCtx,
            EffectiveGrantSnapshot grantSnapshot) throws Exception {
        Map<String, Object> effectiveParams = resolveParams(api, params);
        // 非查询类接口仅 SQL 脚本模式支持
        if (!isQueryApi(api) && !DataApiDO.CONFIG_TYPE_SQL.equals(api.getConfigType())) {
            throw exception(DATA_API_TYPE_NOT_SUPPORTED, api.getApiType());
        }
        return switch (StrUtil.nullToEmpty(api.getConfigType())) {
            case DataApiDO.CONFIG_TYPE_ETL_BATCH -> {
                requireNoFieldScope(grantSnapshot);
                if (permissionCtx == null) {
                    throw exception(DATA_API_NOT_AUTHORIZED);
                }
                etlGrantSubjects.requireActiveSubject(permissionCtx.userId());
                // 保留原输入以拒绝未知条件，同时应用声明的游标默认值与精确整数转换。
                Map<String, Object> cursorParams = new LinkedHashMap<>(params == null ? Map.of() : params);
                cursorParams.putAll(effectiveParams);
                yield etlBatches.read(api, cursorParams, pageSize, permissionCtx.userId());
            }
            case DataApiDO.CONFIG_TYPE_TABLE ->
                    executeTable(api, effectiveParams, pageNo, pageSize, permissionCtx, grantSnapshot);
            case DataApiDO.CONFIG_TYPE_SQL -> {
                requireNoFieldScope(grantSnapshot);
                yield executeSql(api, effectiveParams, pageNo, pageSize, permissionCtx);
            }
            case DataApiDO.CONFIG_TYPE_FORWARD -> {
                requireNoFieldScope(grantSnapshot);
                yield executeForward(api, effectiveParams);
            }
            case DataApiDO.CONFIG_TYPE_INDICATOR -> {
                requireNoFieldScope(grantSnapshot);
                if (permissionCtx == null) {
                    throw exception(DATA_API_NOT_AUTHORIZED);
                }
                yield indicatorQueries.execute(api, effectiveParams, pageSize, permissionCtx.userId());
            }
            default -> throw exception(DATA_API_CONFIG_INVALID, "未知配置方式 " + api.getConfigType());
        };
    }

    /**
     * 判断 API 定义是否属于查询类接口。
     *
     * @param api 本次使用的 API 定义
     * @return 判断结果
     */
    private boolean isQueryApi(DataApiDO api) {
        return StrUtil.isBlank(api.getApiType())
                || DataApiDO.API_TYPE_QUERY.equalsIgnoreCase(api.getApiType());
    }

    // ==================== 参数处理 ====================

    /**
     * 解析有效参数：传入值优先，缺省取默认值；必填缺失报错；按参数类型转换
     */
    private Map<String, Object> resolveParams(DataApiDO api, Map<String, Object> params) {
        List<DataApiSaveReqVO.ParamItem> defines = parseParamDefines(api.getRequestParamsJson());
        Map<String, Object> input = params == null ? Map.of() : params;
        Map<String, Object> effective = new LinkedHashMap<>();
        for (DataApiSaveReqVO.ParamItem define : defines) {
            if (StrUtil.isBlank(define.getName())) {
                continue;
            }
            Object value = input.get(define.getName());
            if (value == null || StrUtil.isBlank(String.valueOf(value))) {
                value = StrUtil.isBlank(define.getDefaultValue()) ? null : define.getDefaultValue();
            }
            if (value == null) {
                // nullable：0-否（必填）
                if (define.getNullable() == null || define.getNullable() == 0) {
                    throw exception(DATA_API_PARAM_REQUIRED, define.getName());
                }
                continue;
            }
            effective.put(define.getName(), convertParam(value, define.getParamType()));
        }
        return effective;
    }

    /**
     * 解析参数定义。
     *
     * @param json JSON 内容
     * @return 解析参数定义结果集合
     */
    private List<DataApiSaveReqVO.ParamItem> parseParamDefines(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        return JsonUtils.parseObject(json, new TypeReference<>() {
        });
    }

    /**
     * 解析字段定义。
     *
     * @param json JSON 内容
     * @return 解析字段定义结果集合
     */
    private List<DataApiSaveReqVO.FieldItem> parseFieldDefines(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        return JsonUtils.parseObject(json, new TypeReference<>() {
        });
    }

    /**
     * 转换参数。
     *
     * @param value 待处理的值
     * @param paramType 参数类型
     * @return 数据 API
     */
    private Object convertParam(Object value, String paramType) {
        String text = String.valueOf(value).trim();
        return switch (StrUtil.nullToEmpty(paramType).toUpperCase()) {
            case "INTEGER" -> Long.valueOf(text);
            case "DOUBLE" -> Double.valueOf(text);
            case "DECIMAL", "NUMBER", "NUMERIC" -> text;
            case "DATE" -> Timestamp.valueOf(DateUtil.parse(text).toLocalDateTime());
            default -> text;
        };
    }

    // ==================== 单表向导式 ====================

    /**
     * 执行表。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @param permissionCtx 权限上下文
     * @param grantSnapshot 授权快照
     * @return 数据 API执行响应数据
     */
    private DataApiExecuteRespVO executeTable(DataApiDO api, Map<String, Object> params,
            Integer pageNo, Integer pageSize,
            ExecuteContext permissionCtx,
            EffectiveGrantSnapshot grantSnapshot) throws Exception {
        PreparedSql prepared = prepareTableQuery(api, params);
        String sql = restrictToApprovedColumns(api, prepared.sql(), grantSnapshot);
        return queryDatabase(api, sql, prepared.binds(), pageNo, pageSize, permissionCtx);
    }

    /**
     * 按单表向导配置生成带参数绑定的查询计划。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @return 已准备SQL
     */
    private PreparedSql prepareTableQuery(DataApiDO api, Map<String, Object> params) {
        if (api.getDatabaseId() == null || StrUtil.isBlank(api.getTableName())) {
            throw exception(DATA_API_CONFIG_INVALID, "请配置数据源与数据表");
        }
        validateIdentifier(api.getTableName());
        List<DataApiSaveReqVO.FieldItem> fields = parseFieldDefines(api.getResponseFieldsJson());
        String columns = "*";
        if (!fields.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (DataApiSaveReqVO.FieldItem field : fields) {
                if (StrUtil.isNotBlank(field.getName())) {
                    validateIdentifier(field.getName());
                    names.add(field.getName());
                }
            }
            if (!names.isEmpty()) {
                columns = String.join(", ", names);
            }
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(columns)
                .append(" FROM ").append(api.getTableName());
        List<Object> binds = new ArrayList<>();
        if (!params.isEmpty()) {
            sql.append(" WHERE ");
            List<String> conditions = new ArrayList<>();
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                validateIdentifier(entry.getKey());
                conditions.add(entry.getKey() + " = ?");
                binds.add(entry.getValue());
            }
            sql.append(String.join(" AND ", conditions));
        }
        return new PreparedSql(sql.toString(), binds);
    }

    /**
     * 字段受限来源：在数据库执行前把 SELECT 与过滤限制到批准列，通配符按批准列展开。
     * 技术实现复用 DB Switch 的受限投影，业务侧只提供绑定与批准集合。
     */
    private String restrictToApprovedColumns(DataApiDO api, String sql, EffectiveGrantSnapshot snapshot) {
        if (snapshot == null) {
            return sql;
        }
        var binding = snapshot.binding();
        if (binding == null || !java.util.Objects.equals(api.getDatabaseId(), binding.databaseId())
                || snapshot.approvedColumns().isEmpty()) {
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
        try {
            return RestrictedSelectGuard.restrictProjection(
                    databaseResolver.resolve(api.getDatabaseId()).getProductType(), sql,
                    binding.schemaName(), binding.tableName(),
                    snapshot.approvedColumns(), Set.copyOf(snapshot.approvedColumns())).sql();
        } catch (IllegalArgumentException | UnsupportedOperationException rejected) {
            // 不回传数据库标识符细节，只说明超出批准范围。
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
    }

    /** 受限字段授权只在已验证的单表查询上成立；其他配置方式一律拒绝，不做“先查后删列”。 */
    private void requireNoFieldScope(EffectiveGrantSnapshot snapshot) {
        if (snapshot != null && snapshot.fieldScoped()) {
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
    }

    /** 只有已确认的 v2 来源聚合使用快照；旧协议聚合继续走既有校验，不在此隐式升级。 */
    private EffectiveGrantSnapshot readGrantSnapshot(DataApiDO api, Long appId) {
        AppApiDO aggregate = appApiMapper.selectByAppIdAndApiId(appId, api.getId());
        if (aggregate == null || !Integer.valueOf(2).equals(aggregate.getProtocolVersion())) {
            return null;
        }
        EffectiveGrantSnapshot snapshot = grantReadService.snapshot(appId, api.getId());
        // 网关先装配发布配置，随后才读取授权。两次读取之间可能已发布/回滚并重批来源，
        // 此时“当前授权仍有效”不能证明传入的旧配置可执行（尤其是过滤和脱敏规则）。
        // 在任何取数/结果缓存之前绑定本次实际配置；不自动替换或重试旧请求。
        if (snapshot == null || !snapshot.fieldScoped()
                || !Objects.equals(appId, snapshot.appId()) || !Objects.equals(api.getId(), snapshot.apiId())
                || !Integer.valueOf(DataApiDO.STATUS_ONLINE).equals(api.getStatus())
                || !DataApiDO.CONFIG_TYPE_TABLE.equals(api.getConfigType())
                || !DataApiDO.API_TYPE_QUERY.equals(api.getApiType())
                || api.getActiveReleaseId() == null || api.getActiveReleaseId() <= 0
                || api.getRowVersion() == null || api.getRowVersion() < 0
                || !Objects.equals(api.getRowVersion(), snapshot.apiControlVersion())
                || StrUtil.isBlank(api.getContractHash())
                || !Objects.equals(api.getContractHash(), snapshot.contractHash())
                || !Objects.equals(DataApiContract.contractHash(api), snapshot.contractHash())
                || StrUtil.isBlank(api.getVersion()) || !Objects.equals(api.getVersion(), snapshot.apiVersion())
                || snapshot.binding() == null
                || !Objects.equals(api.getDatabaseId(), snapshot.binding().databaseId())
                || !Objects.equals(api.getTableName(), snapshot.binding().tableName())) {
            throw exception(GRANT_SCOPE_CHANGED);
        }
        return snapshot;
    }

    // ==================== SQL 脚本式 ====================

    /**
     * 执行SQL。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @param permissionCtx 权限上下文
     * @return 数据 API执行响应数据
     */
    private DataApiExecuteRespVO executeSql(DataApiDO api, Map<String, Object> params,
            Integer pageNo, Integer pageSize,
            ExecuteContext permissionCtx) throws Exception {
        if (api.getDatabaseId() == null || StrUtil.isBlank(api.getSqlText())) {
            throw exception(DATA_API_CONFIG_INVALID, "请配置数据源与 SQL 脚本");
        }
        String script = processDynamicTags(api.getSqlText(), params);
        if (DataApiDO.API_TYPE_MULTI.equalsIgnoreCase(api.getApiType())) {
            return executeMultiSql(api, script, params, permissionCtx);
        }
        PreparedSql prepared = prepareSql(script, params);
        if (isQueryApi(api)) {
            return queryDatabase(api, prepared.sql, prepared.binds, pageNo, pageSize, permissionCtx);
        }
        return executeWrite(prepareWrite(api, script, params, permissionCtx, false));
    }

    /**
     * 多脚本：整份计划先校验，再在同一事务内执行；提交或回滚异常保留 UNKNOWN。
     */
    private DataApiExecuteRespVO executeMultiSql(DataApiDO api, String script,
            Map<String, Object> params,
            ExecuteContext permissionCtx) throws Exception {
        return executeWrite(prepareWrite(api, script, params, permissionCtx, true));
    }

    /**
     * 解析写入计划并校验受限语法，固定后续执行使用的 SQL、参数及目标。
     *
     * @param api 本次使用的 API 定义
     * @param script 脚本
     * @param params 业务参数映射
     * @param permissionCtx 权限上下文
     * @param multi 多条
     * @return 已准备写入
     */
    private PreparedWrite prepareWrite(DataApiDO api, String script, Map<String, Object> params,
            ExecuteContext permissionCtx, boolean multi) {
        if (api.getDatabaseId() == null || StrUtil.isBlank(script)) {
            throw exception(DATA_API_CONFIG_INVALID, "请配置数据源与 SQL 脚本");
        }
        SupplyDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(api.getDatabaseId());
        PreparedSql prepared = prepareSql(script, params);
        var plan = cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(conn.getProductType(),
                prepared.sql);
        if (plan.steps().isEmpty() || plan.steps().size() > 1000 || (!multi && plan.steps().size() != 1)
                || plan.steps().stream().anyMatch(step -> step.kind()
                != cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier.Kind.DML)
                || plan.steps().stream()
                .mapToInt(cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.Step::parameterCount).sum()
                != prepared.binds.size()) {
            throw exception(DATA_API_CONFIG_INVALID, "写入只支持参数数量匹配的DML；单条模式仅1条，事务多脚本至多1000条");
        }
        // 在认领和连接目标前解析全部语句，绑定配置中的精确命名空间。
        if (StrUtil.isBlank(conn.getSchema()) || StrUtil.isBlank(conn.getConfigurationRevision())) {
            throw exception(DATA_API_CONFIG_INVALID, "写入必须配置明确的数据库模式和连接修订");
        }
        for (Object value : prepared.binds) {
            if (value instanceof Float || value instanceof Double) {
                throw exception(DATA_API_CONFIG_INVALID, "精确写入参数不能使用浮点近似值");
            }
        }
        List<String> statements = new ArrayList<>();
        List<cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlTarget> targets = new ArrayList<>();
        for (var step : plan.steps()) {
            var shape = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlAuthorization.inspect(
                    conn.getProductType(), step.sql(), conn.getSchema());
            if (!Objects.equals(conn.getSchema(), shape.schemaName())) {
                throw exception(DATA_API_CONFIG_INVALID, "写入对象必须位于数据源配置的数据库模式内");
            }
            var bound = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlGuard.bindAuthorizedTable(
                    dbSwitchRuntime.products().metadata(conn.getProductType()), step.sql(), shape.schemaName(),
                    shape.tableName());
            statements.add(bound.getNormalizedSql());
            targets.add(new cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlTarget(
                    step.ordinal(), shape.schemaName(), shape.tableName()));
        }
        var boundPlan = cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(conn.getProductType(),
                String.join(";\n", statements));
        cn.iocoder.yudao.framework.dbswitch.core.service.PostgresDmlTargetGuard.validatePlan(boundPlan, targets);
        return new PreparedWrite(conn, boundPlan, prepared.binds, multi, List.copyOf(targets),
                queryTimeoutSeconds(api));
    }

    /**
     * 执行已绑定精确目标的写入计划，并保留未知事务结果的错误语义。
     *
     * @param prepared 已准备的执行配置或记录
     * @return 数据 API执行响应数据
     */
    private DataApiExecuteRespVO executeWrite(PreparedWrite prepared) throws Exception {
        var conn = prepared.connection();
        if (!Objects.equals(conn.getConfigurationRevision(),
                databaseResolver.resolve(conn.getDatabaseId()).getConfigurationRevision())) {
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
        JdbcSelectResult execution = dbSwitchRuntime.jdbcMetadata(conn.getProductType()).executeAuthorizedDmlPlan(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), prepared.plan(), prepared.binds(),
                prepared.targets(), ignored -> {
                }, prepared.queryTimeoutSeconds());
        if (!Boolean.TRUE.equals(execution.getSuccess())) {
            throw exception(DATA_API_CONFIG_INVALID, execution.getTransactionOutcome()
                    == JdbcSelectResult.TransactionOutcome.UNKNOWN
                    ? "事务结果未知，核查数据库后才能重试" : "多脚本执行失败");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 1;
        for (JdbcSelectResult statement : execution.getResults()) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (prepared.multi()) {
                row.put("statement", index++);
            }
            row.put("affectedRows", statement.getCount());
            rows.add(row);
            if (!prepared.multi()) {
                break;
            }
        }
        DataApiExecuteRespVO result = new DataApiExecuteRespVO();
        result.setRows(rows);
        return result;
    }

    /**
     * #{参数名} → ? 预编译占位
     */
    private PreparedSql prepareSql(String script, Map<String, Object> params) {
        List<Object> binds = new ArrayList<>();
        Matcher matcher = SQL_PARAM_PATTERN.matcher(script);
        StringBuilder sql = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!params.containsKey(name)) {
                throw exception(DATA_API_PARAM_REQUIRED, name);
            }
            binds.add(params.get(name));
            matcher.appendReplacement(sql, "?");
        }
        matcher.appendTail(sql);
        String trimmed = StrUtil.removeSuffix(sql.toString().trim(), ";");
        return new PreparedSql(trimmed, binds);
    }

    private record PreparedSql(String sql, List<Object> binds) {

    }

    private record PreparedWrite(SupplyDatabaseResolver.ConnectionInfo connection,
                                 cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan plan,
                                 List<Object> binds, boolean multi,
                                 List<cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlTarget> targets,
                                 int queryTimeoutSeconds) {

    }

    // ==================== 动态条件标签 ====================

    /**
     * 处理 MyBatis 风格动态标签：
     * &lt;if test="param != null and param != ''"&gt;片段&lt;/if&gt;：参数满足条件时保留片段；
     * &lt;where&gt;...&lt;/where&gt;：内部有内容时输出 WHERE 并去除前导 AND/OR，否则整体移除
     */
    private String processDynamicTags(String script, Map<String, Object> params) {
        // 先渲染 <if>
        Matcher ifMatcher = IF_TAG_PATTERN.matcher(script);
        StringBuilder buffer = new StringBuilder();
        while (ifMatcher.find()) {
            String replacement = evalTest(ifMatcher.group(1), params) ? ifMatcher.group(2) : "";
            ifMatcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        ifMatcher.appendTail(buffer);
        // 再渲染 <where>
        Matcher whereMatcher = WHERE_TAG_PATTERN.matcher(buffer.toString());
        StringBuilder result = new StringBuilder();
        while (whereMatcher.find()) {
            String inner = whereMatcher.group(1).trim();
            String replacement;
            if (StrUtil.isBlank(inner)) {
                replacement = "";
            } else {
                inner = inner.replaceFirst("(?i)^(AND|OR)\\s+", "");
                replacement = " WHERE " + inner;
            }
            whereMatcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        whereMatcher.appendTail(result);
        return result.toString();
    }

    /**
     * 判断 test 表达式：支持 and 连接的空值判断（param != null / param != '' / param == null）
     */
    private boolean evalTest(String test, Map<String, Object> params) {
        for (String condition : test.split("(?i)\\s+and\\s+")) {
            condition = condition.trim();
            if (condition.isEmpty()) {
                continue;
            }
            String name;
            boolean expected;
            if (condition.matches("(?i)^\\w+\\s*!=\\s*null$")) {
                name = condition.split("!=")[0].trim();
                expected = params.get(name) != null;
            } else if (condition.matches("^\\w+\\s*!=\\s*''$")) {
                name = condition.split("!=")[0].trim();
                Object value = params.get(name);
                expected = value != null && StrUtil.isNotBlank(String.valueOf(value));
            } else if (condition.matches("(?i)^\\w+\\s*==\\s*null$")) {
                name = condition.split("==")[0].trim();
                expected = params.get(name) == null;
            } else {
                throw exception(DATA_API_CONFIG_INVALID, "不支持的动态条件：" + condition);
            }
            if (!expected) {
                return false;
            }
        }
        return true;
    }

    // ==================== 第三方转发 ====================

    /**
     * 执行转发。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @return 数据 API执行响应数据
     */
    private DataApiExecuteRespVO executeForward(DataApiDO api, Map<String, Object> params) throws Exception {
        if (StrUtil.isBlank(api.getForwardUrl())) {
            throw exception(DATA_API_CONFIG_INVALID, "请配置转发地址");
        }
        validateForwardUrl(api.getForwardUrl(), dataApiProperties);
        String method = StrUtil.blankToDefault(api.getForwardMethod(), api.getMethod());
        boolean get = "GET".equalsIgnoreCase(method);
        String url = get ? appendQuery(api.getForwardUrl(), params) : api.getForwardUrl();
        List<SafeHttpExecutor.Header> headers = get ? List.of()
                : List.of(new SafeHttpExecutor.Header("Content-Type", "application/json;charset=UTF-8"));
        byte[] payload = get ? null : JSONUtil.toJsonStr(params).getBytes(StandardCharsets.UTF_8);
        SafeHttpExecutor.Result response;
        try {
            // 每次执行都重新解析并校验全部地址，并把连接绑定到这些地址（DNS 重绑定无法在校验后换目标）；
            // 不跟随重定向、不走代理、不携带平台身份与 Cookie。
            response = SafeHttpExecutor.execute(new SafeHttpExecutor.Request(url, get ? "GET" : "POST",
                    headers, payload, forwardTimeoutSeconds(api), false, SafeHttpExecutor.publicOnly()));
        } catch (SafeHttpExecutor.SafeHttpException ex) {
            throw exception(DATA_API_EXECUTE_FAILED, "转发失败：" + ex.getMessage());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw exception(DATA_API_EXECUTE_FAILED, "转发响应异常 HTTP " + response.statusCode());
        }
        byte[] responseBytes = response.body();
        if (response.truncated() || responseBytes.length > FORWARD_MAX_BODY_LENGTH) {
            throw exception(DATA_API_EXECUTE_FAILED, "转发响应超过允许大小");
        }
        String body = new String(responseBytes, StandardCharsets.UTF_8);
        DataApiExecuteRespVO result = new DataApiExecuteRespVO();
        // 尽力解析为行数据，无法解析时原样返回
        if (JSONUtil.isTypeJSONArray(body)) {
            result.setRows(toRows(JSONUtil.parseArray(body)));
        } else if (JSONUtil.isTypeJSONObject(body)) {
            JSONObject json = JSONUtil.parseObj(body);
            Object data = json.get("data");
            if (data instanceof cn.hutool.json.JSONArray array) {
                result.setRows(toRows(array));
            } else {
                result.setRows(List.of(new LinkedHashMap<>(json)));
            }
        } else {
            result.setRaw(body);
            result.setRows(List.of());
        }
        return result;
    }

    /**
     * 校验转发 URL。
     *
     * @param forwardUrl 转发 URL
     * @param properties 组件配置
     */
    public static void validateForwardUrl(String forwardUrl, SupplyDataApiProperties properties) {
        try {
            URI uri = URI.create(forwardUrl);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw exception(DATA_API_CONFIG_INVALID, "转发地址仅允许 HTTP/HTTPS");
            }
            if (StrUtil.isNotBlank(uri.getUserInfo()) || StrUtil.isBlank(uri.getHost())) {
                throw exception(DATA_API_CONFIG_INVALID, "转发地址格式不合法");
            }
            String host = normalizeForwardHost(uri.getHost());
            Set<String> allowHosts = properties == null ? Set.of() : properties.getForwardAllowHosts();
            if (allowHosts == null || allowHosts.stream().noneMatch(item -> host.equals(normalizeForwardHost(item)))) {
                throw exception(DATA_API_CONFIG_INVALID, "转发地址未配置在允许访问的主机白名单中");
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isForbiddenForwardAddress(address)) {
                    throw exception(DATA_API_CONFIG_INVALID, "转发地址不能指向内网或本机地址");
                }
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(DATA_API_CONFIG_INVALID, "转发地址格式不合法");
        }
    }

    /**
     * 规范化转发主机。
     *
     * @param host 主机
     * @return 规范化转发主机文本
     */
    private static String normalizeForwardHost(String host) {
        String normalized = StrUtil.nullToEmpty(host).trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 判断是否满足禁止转发地址条件。
     *
     * @param address 地址
     * @return 判断结果
     */
    private static boolean isForbiddenForwardAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isIpv6UniqueLocalAddress(address);
    }

    /**
     * 判断是否满足IP 版本6唯一性本地地址条件。
     *
     * @param address 地址
     * @return 判断结果
     */
    private static boolean isIpv6UniqueLocalAddress(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte first = address.getAddress()[0];
        return (first & 0xfe) == 0xfc;
    }

    /**
     * 把业务参数按 application/x-www-form-urlencoded 规则追加到 GET 转发地址的查询串。
     *
     * @param forwardUrl 转发地址
     * @param params 业务参数
     * @return 带查询串的地址
     */
    static String appendQuery(String forwardUrl, Map<String, Object> params) {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(forwardUrl);
        if (base == null) {
            throw exception(DATA_API_CONFIG_INVALID, "转发地址格式不合法");
        }
        okhttp3.HttpUrl.Builder builder = base.newBuilder();
        if (params != null) {
            params.forEach((name, value) -> {
                if (value instanceof Iterable<?> values) {
                    values.forEach(item -> builder.addQueryParameter(name, item == null ? "" : String.valueOf(item)));
                } else if (value != null) {
                    builder.addQueryParameter(name, String.valueOf(value));
                }
            });
        }
        return builder.build().toString();
    }

    /**
     * 转换为行。
     *
     * @param array 数组
     * @return 转换为行结果集合
     */
    private List<Map<String, Object>> toRows(JSONArray array) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : array) {
            if (item instanceof JSONObject json) {
                rows.add(new LinkedHashMap<>(json));
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("value", item);
                rows.add(row);
            }
        }
        return rows;
    }

    // ==================== 数据库查询 ====================

    // 包内可见：便于在不接真实数据库的情况下验证执行前投影，生产路径未改变。

    /**
     * 获取数据 API的查询数据源。
     *
     * @param api 本次使用的 API 定义
     * @param sql SQL
     * @param binds 绑定值
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @param permissionCtx 权限上下文
     * @return 数据 API执行响应数据
     */
    DataApiExecuteRespVO queryDatabase(DataApiDO api, String sql, List<Object> binds,
            Integer pageNo, Integer pageSize,
            ExecuteContext permissionCtx) throws Exception {
        SupplyDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(api.getDatabaseId());
        DataApiReadOnlyGuard.assertSqlAllowed(conn.getProductType(), sql);
        int[] bound = resolvePageBound(api, pageNo, pageSize);
        int page = bound[0];
        int limit = permissionCtx != null && permissionCtx.quotaLimit() != null ? Math.min(bound[1],
                permissionCtx.quotaLimit()) : bound[1];
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        JdbcSelectResult jdbc = metaDataService.pageDataBySql(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                sql, binds, page, limit, null, queryTimeoutSeconds(api));
        if (jdbc == null || Boolean.FALSE.equals(jdbc.getSuccess())) {
            throw exception(DATA_API_EXECUTE_FAILED,
                    jdbc == null ? "分页查询失败" : StrUtil.blankToDefault(jdbc.getErrorMsg(), "分页查询失败"));
        }
        DataApiExecuteRespVO result = new DataApiExecuteRespVO();
        List<Map<String, Object>> rows = jdbc.getList() != null ? jdbc.getList() : jdbc.getRowData();
        result.setRows(canonicalizeRowValues(rows));
        result.setTotal(jdbc.getTotal());
        return result;
    }

    /**
     * 解析分页边界。
     *
     * @param api 本次使用的 API 定义
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @return 数据 API
     */
    static int[] resolvePageBound(DataApiDO api, Integer pageNo, Integer pageSize) {
        if (DataApiDO.RESULT_FORMAT_PAGE.equalsIgnoreCase(api.getResultFormat())) {
            int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
            int limit = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, LIST_MAX_ROWS);
            return new int[]{page, limit};
        }
        if (DataApiDO.RESULT_FORMAT_DETAIL.equalsIgnoreCase(api.getResultFormat())) {
            return new int[]{1, 1};
        }
        return new int[]{1, LIST_MAX_ROWS};
    }

    /**
     * 将行字段值转换为一致的返回表示。
     *
     * @param rows 待处理的行数据
     * @return 将行字段值转换为一致的返回表示结果集合
     */
    static List<Map<String, Object>> canonicalizeRowValues(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null) {
                result.add(null);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((key, value) -> copy.put(key, canonicalizeCell(value)));
            result.add(copy);
        }
        return result;
    }

    /**
     * 将数据库单元格值转为稳定的返回类型。
     *
     * @param value 待处理的值
     * @return 数据 API
     */
    private static Object canonicalizeCell(Object value) {
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value;
    }

    /**
     * 校验标识符。
     *
     * @param identifier 标识符
     */
    private void validateIdentifier(String identifier) {
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw exception(DATA_API_CONFIG_INVALID,
                    String.format("标识符【%s】包含非法字符", identifier));
        }
    }

    // ==================== 返回字段数据模型 ====================

    /**
     * 应用返回字段数据模型：字段隐藏、参数名映射、内容拼接/替换/截取/分割、时间格式化、脱敏
     *
     * @param skipMask true 表示当前调用命中脱敏白名单，跳过脱敏（其余数据模型照常生效）
     */
    private List<Map<String, Object>> applyFieldModels(List<Map<String, Object>> rows,
            List<DataApiSaveReqVO.FieldItem> defines,
            boolean skipMask) {
        if (rows == null || rows.isEmpty() || defines == null || defines.isEmpty()) {
            return rows;
        }
        assertUniqueOutputNames(defines);
        List<Map<String, Object>> processed = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (DataApiSaveReqVO.FieldItem define : defines) {
                if (StrUtil.isBlank(define.getName()) || "HIDE".equalsIgnoreCase(define.getModel())) {
                    continue;
                }
                Object value = row == null ? null : row.get(define.getName());
                value = applyModel(define, value);
                if (!skipMask && define.getDesensitize() != null && define.getDesensitize() == 1
                        && value != null) {
                    value = maskValue(String.valueOf(value));
                }
                String outputKey = StrUtil.blankToDefault(define.getOutputName(), define.getName());
                out.put(outputKey, value);
            }
            processed.add(out);
        }
        return processed;
    }

    /**
     * 确认唯一性输出名称。
     *
     * @param defines 定义
     */
    static void assertUniqueOutputNames(List<DataApiSaveReqVO.FieldItem> defines) {
        if (defines == null || defines.isEmpty()) {
            return;
        }
        Set<String> outputs = new LinkedHashSet<>();
        for (DataApiSaveReqVO.FieldItem define : defines) {
            if (StrUtil.isBlank(define.getName()) || "HIDE".equalsIgnoreCase(define.getModel())) {
                continue;
            }
            String outputKey = StrUtil.blankToDefault(define.getOutputName(), define.getName());
            if (!outputs.add(outputKey)) {
                throw exception(DATA_API_CONFIG_INVALID, "返回字段输出名重复：" + outputKey);
            }
        }
    }

    /**
     * 应用模型。
     *
     * @param define 定义
     * @param value 待处理的值
     * @return 数据 API
     */
    private Object applyModel(DataApiSaveReqVO.FieldItem define, Object value) {
        if (value == null || StrUtil.isBlank(define.getModel())) {
            return value;
        }
        String text = String.valueOf(value);
        String rule = StrUtil.nullToEmpty(define.getModelRule());
        return switch (define.getModel().toUpperCase()) {
            case "CONCAT" -> rule.contains("${value}") ? rule.replace("${value}", text) : text + rule;
            case "REPLACE" -> {
                String[] parts = rule.split(",", 2);
                yield parts.length == 2 ? text.replace(parts[0], parts[1]) : text;
            }
            case "SUBSTRING" -> {
                String[] parts = rule.split(",");
                try {
                    int begin = Math.max(0, Integer.parseInt(parts[0].trim()));
                    int end = parts.length > 1
                            ? Math.min(text.length(), Integer.parseInt(parts[1].trim()))
                            : text.length();
                    yield begin >= end ? "" : text.substring(begin, end);
                } catch (NumberFormatException ex) {
                    yield text;
                }
            }
            case "SPLIT" -> StrUtil.isBlank(rule) ? value : StrUtil.split(text, rule);
            case "DATE_FORMAT" -> {
                try {
                    yield DateUtil.format(DateUtil.parse(text),
                            StrUtil.blankToDefault(rule, "yyyy-MM-dd HH:mm:ss"));
                } catch (Exception ex) {
                    yield value;
                }
            }
            default -> value;
        };
    }

    // ==================== 数据安全（治理模块联动） ====================

    /**
     * 收集配置了脱敏的返回字段名
     */
    private List<String> collectMaskColumns(List<DataApiSaveReqVO.FieldItem> defines) {
        List<String> columns = new ArrayList<>();
        for (DataApiSaveReqVO.FieldItem define : defines) {
            if (define.getDesensitize() != null && define.getDesensitize() == 1
                    && StrUtil.isNotBlank(define.getName())) {
                columns.add(define.getName());
            }
        }
        return columns;
    }

    /**
     * 确定当前执行使用的权限主体，缺失授权绑定时拒绝请求。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @param applyUserPermission 应用用户权限
     * @return 解析权限主体用户编号数值
     */
    private Long resolvePermissionUserId(Long appId, Long apiId, boolean applyUserPermission) {
        if (applyUserPermission) {
            return SecurityFrameworkUtils.getLoginUserId();
        }
        if (appId == null || apiId == null) {
            return null;
        }
        AppApiDO appApi = appApiMapper.selectByAppIdAndApiId(appId, apiId);
        if (appApi == null || appApi.getPermissionUserId() == null) {
            return null;
        }
        SupplyAppDO app = supplyAppMapper.selectById(appId);
        if (app == null) {
            return null;
        }
        return appApi.getPermissionUserId();
    }

    /**
     * 解析用户编号。
     *
     * @param creator 创建人
     * @return 解析用户编号数值
     */
    private Long parseUserId(String creator) {
        if (StrUtil.isBlank(creator)) {
            return null;
        }
        try {
            return Long.valueOf(creator.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    record ExecuteContext(Long userId, boolean skipMask, Integer quotaLimit) {

        /**
         * 创建执行上下文实例。
         *
         * @param userId 用户编号
         * @param skipMask 跳过脱敏
         */
        ExecuteContext(Long userId, boolean skipMask) {
            this(userId, skipMask, null);
        }
    }

    /**
     * 查询当前登录用户是否命中脱敏白名单（管理端调用才有登录用户；网关应用调用不豁免）。
     * 治理服务不可用时按未豁免处理，保证脱敏兜底。
     */
    private SafetyMaskExemptRespDTO checkMaskExempt() {
        return checkMaskExempt(SecurityFrameworkUtils.getLoginUserId());
    }

    /**
     * 查询当前登录用户是否命中脱敏白名单（管理端调用才有登录用户；网关应用调用不豁免）。
     *  治理服务不可用时按未豁免处理，保证脱敏兜底。
     *
     * @param userId 用户编号
     * @return 安全脱敏豁免响应数据
     */
    private SafetyMaskExemptRespDTO checkMaskExempt(Long userId) {
        try {
            if (userId == null) {
                return null;
            }
            return safetySecurityApi.getMaskExempt(userId, "QUERY").getCheckedData();
        } catch (Exception ex) {
            log.warn("[checkMaskExempt][白名单豁免查询失败，按未豁免处理：{}]", ex.getMessage());
            return null;
        }
    }

    /**
     * 上报敏感访问审计（仅含脱敏字段的查询接口）；失败不影响主流程
     */
    private void recordAccessAudit(DataApiDO api, List<String> maskColumns, boolean skipMask,
            SafetyMaskExemptRespDTO maskExempt, String callerIp, Long appId,
            Long permissionUserId) {
        if (maskColumns.isEmpty() || !isQueryApi(api)) {
            return;
        }
        try {
            SafetyAccessLogCreateReqDTO reqDTO = new SafetyAccessLogCreateReqDTO();
            reqDTO.setUserId(permissionUserId != null
                    ? permissionUserId : SecurityFrameworkUtils.getLoginUserId());
            reqDTO.setAppId(appId);
            reqDTO.setAccessType(SafetyAccessLogCreateReqDTO.ACCESS_TYPE_API);
            reqDTO.setResourceName(StrUtil.blankToDefault(api.getName(), "未命名 API"));
            reqDTO.setDatabaseId(api.getDatabaseId());
            reqDTO.setTableName(api.getTableName());
            reqDTO.setSensitiveColumns(StrUtil.maxLength(String.join(",", maskColumns), 1000));
            reqDTO.setColumnCount(maskColumns.size());
            reqDTO.setMaskedFlag(skipMask ? 0 : 1);
            reqDTO.setWhitelistId(skipMask && maskExempt != null ? maskExempt.getWhitelistId() : null);
            reqDTO.setCallerIp(callerIp);
            safetySecurityApi.recordAccessLog(reqDTO);
        } catch (Exception ex) {
            log.warn("[recordAccessAudit][API({}) 审计上报失败：{}]", api.getId(), ex.getMessage());
        }
    }

    /**
     * 脱敏：保留首尾各 1/4（至少 1 位），中间以 * 填充
     */
    private String maskValue(String text) {
        int length = text.length();
        if (length <= 2) {
            return StrUtil.repeat('*', length);
        }
        int keep = Math.max(1, length / 4);
        if (length - keep * 2 < 1) {
            keep = 1;
        }
        return text.substring(0, keep) + StrUtil.repeat('*', length - keep * 2)
                + text.substring(length - keep);
    }

    // ==================== 结果缓存 ====================

    /**
     * 判断当前执行模式及授权来源是否允许复用结果缓存。
     *
     * @param api 本次使用的 API 定义
     * @param mode 处理模式
     * @param snapshot 快照
     * @return 判断结果
     */
    private boolean canUseResultCache(DataApiDO api, DataApiExecuteMode mode, EffectiveGrantSnapshot snapshot) {
        if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType())) {
            return false;
        }
        if (DataApiOperationService.requiresOperation(api)) {
            return false;
        }
        if (!dataApiProperties.isResultCacheEnabled() || mode == DataApiExecuteMode.DRAFT_READONLY_PREVIEW) {
            return false;
        }
        if (snapshot != null && snapshot.fieldScoped()) {
            // 字段受限来源的结果不得进入任何缓存路径，避免撤权或收窄后仍能读到旧字段。
            return false;
        }
        if (snapshot != null && snapshot.quotaSources().stream().anyMatch(source -> source.quota() != null)) {
            // 缓存命中会跳过额度预留；带额度来源必须完整经过消费台账，不能复用结果。
            return false;
        }
        return api.getId() != null && api.getActiveReleaseId() != null && isQueryApi(api)
                && api.getCacheEnabled() != null && api.getCacheEnabled() == 1;
    }

    /**
     * 读取并校验当前应用对 API 的有效授权，返回授权修订号。
     *
     * @param api 本次使用的 API 定义
     * @param appId 应用编号
     * @return 确认当前授权数值
     */
    private Integer assertCurrentGrant(DataApiDO api, Long appId) {
        SupplyAppDO app = supplyAppMapper.selectById(appId);
        AppApiDO grant = appApiMapper.selectByAppIdAndApiId(appId, api.getId());
        if (!SupplyGrantValidity.isEffective(app, grant, LocalDateTime.now(SupplyGrantValidity.ZONE))) {
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
        String currentContract = StrUtil.blankToDefault(api.getContractHash(), "");
        if (StrUtil.isNotBlank(currentContract)
                && !currentContract.equals(grant.getApprovedContractHash())) {
            throw exception(DATA_API_CONTRACT_MISMATCH);
        }
        return grant.getGrantRevision();
    }

    /**
     * 按 API 发布版本、查询参数、分页、调用主体及应用授权修订构造隔离缓存键。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @param pageNo 页码
     * @param pageSize 每页行数
     * @param appId 应用编号
     * @param permissionUserId 权限主体用户编号
     * @param skipMask 跳过脱敏
     * @param grantRevision 授权修订号
     * @return 隔离的结果缓存键文本
     */
    private String buildCacheKey(DataApiDO api, Map<String, Object> params,
            Integer pageNo, Integer pageSize,
            Long appId, Long permissionUserId, boolean skipMask,
            Integer grantRevision) {
        if (api.getId() == null || !isQueryApi(api)
                || api.getCacheEnabled() == null || api.getCacheEnabled() != 1) {
            return null;
        }
        Map<String, Object> orderedParams = params == null ? Map.of() : new TreeMap<>(params);
        String material = "read-cache/v3|" + api.getId() + "|" + api.getActiveReleaseId() + "|"
                + StrUtil.nullToEmpty(api.getContractHash()) + "|"
                + grantRevision + "|" + JSONUtil.toJsonStr(orderedParams)
                + "|" + pageNo + "|" + pageSize + "|" + permissionUserId + "|" + skipMask;
        return SupplyApiRedisStore.resultKey(appKey(appId), DigestUtil.sha256Hex(material));
    }

    /**
     * 写入缓存。
     *
     * @param cacheKey 缓存键
     * @param api 本次使用的 API 定义
     * @param result 处理结果
     */
    private void putCache(String cacheKey, DataApiDO api, DataApiExecuteRespVO result) {
        long seconds = Math.max(api.getCacheSeconds() == null ? 60 : api.getCacheSeconds(), 1);
        redisStore.putResult(cacheKey, result, seconds);
    }

    /**
     * 读取缓存的结果。
     *
     * @param cacheKey 缓存键
     * @return 数据 API执行响应数据
     */
    private DataApiExecuteRespVO readCachedResult(String cacheKey) {
        Object value = redisStore.getResult(cacheKey);
        if (value == null) {
            return null;
        }
        if (value instanceof DataApiExecuteRespVO result) {
            return result;
        }
        try {
            return JsonUtils.parseObject(JsonUtils.toJsonString(value), DataApiExecuteRespVO.class);
        } catch (Exception ex) {
            redisStore.deleteResult(cacheKey);
            log.warn("[readCachedResult][缓存结果反序列化失败，已删除 key={}]", cacheKey);
            return null;
        }
    }

    /**
     * 复制结果。
     *
     * @param source 来源数据
     * @return 数据 API执行响应数据
     */
    private DataApiExecuteRespVO copyResult(DataApiExecuteRespVO source) {
        DataApiExecuteRespVO copy = new DataApiExecuteRespVO();
        copy.setTotal(source.getTotal());
        if (source.getRows() == null) {
            copy.setRows(null);
        } else {
            List<Map<String, Object>> rows = new ArrayList<>(source.getRows().size());
            for (Map<String, Object> row : source.getRows()) {
                rows.add(row == null ? null : new LinkedHashMap<>(row));
            }
            copy.setRows(rows);
        }
        copy.setRaw(source.getRaw());
        return copy;
    }

    // ==================== 防护 ====================

    /**
     * 检查IP规则。
     *
     * @param api 本次使用的 API 定义
     * @param callerIp 调用方 IP
     */
    private void checkIpRule(DataApiDO api, String callerIp) {
        if (StrUtil.isBlank(api.getIpWhitelist()) || StrUtil.isBlank(callerIp)) {
            return;
        }
        List<String> ipList = StrUtil.split(api.getIpWhitelist(), ',', true, true);
        boolean black = "BLACK".equalsIgnoreCase(api.getIpRuleType());
        boolean hit = ipList.contains(callerIp);
        if (black ? hit : !hit) {
            throw exception(DATA_API_IP_NOT_ALLOWED);
        }
    }

    /**
     * 检查频率上限。
     *
     * @param api 本次使用的 API 定义
     * @param appId 应用编号
     */
    private void checkRateLimit(DataApiDO api, Long appId) {
        if (api.getId() == null || api.getLimitEnabled() == null || api.getLimitEnabled() != 1) {
            return;
        }
        long windowMs = Math.max(api.getLimitSeconds() == null ? 60 : api.getLimitSeconds(), 1) * 1000L;
        long limit = Math.max(api.getLimitCount() == null ? 5 : api.getLimitCount(), 1);
        if (!redisStore.tryAcquireRate(appKey(appId), api.getId(), windowMs, limit)) {
            throw exception(DATA_API_RATE_LIMITED);
        }
    }

    // ==================== 熔断降级 ====================

    /**
     * 检查熔断。
     *
     * @param api 本次使用的 API 定义
     * @param appId 应用编号
     */
    private void checkBreaker(DataApiDO api, Long appId) {
        if (api.getId() == null || api.getBreakerEnabled() == null || api.getBreakerEnabled() != 1) {
            return;
        }
        if (redisStore.isCircuitOpen(appKey(appId), api.getId())) {
            throw exception(DATA_API_CIRCUIT_OPEN);
        }
    }

    /**
     * 记录熔断错误。
     *
     * @param api 本次使用的 API 定义
     * @param appId 应用编号
     */
    private void recordBreakerError(DataApiDO api, Long appId) {
        if (api.getId() == null || api.getBreakerEnabled() == null || api.getBreakerEnabled() != 1) {
            return;
        }
        long windowMs = Math.max(api.getBreakerWindowSeconds() == null
                ? 60 : api.getBreakerWindowSeconds(), 1) * 1000L;
        int threshold = Math.max(api.getBreakerErrorCount() == null
                ? 10 : api.getBreakerErrorCount(), 1);
        long openMs = Math.max(api.getBreakerOpenSeconds() == null
                ? 60 : api.getBreakerOpenSeconds(), 1) * 1000L;
        long count = redisStore.recordBreakerError(appKey(appId), api.getId(),
                windowMs, threshold, openMs);
        if (count >= threshold) {
            log.warn("[recordBreakerError][API({}) 异常次数达到阈值 {}，熔断 {} 秒]",
                    api.getId(), threshold, openMs / 1000);
        }
    }


    /**
     * 获取数据 API的应用标识。
     *
     * @param appId 应用编号
     * @return 数据 API的应用标识文本
     */
    private String appKey(Long appId) {
        return appId == null ? "anonymous" : String.valueOf(appId);
    }

    // ==================== 告警通知 ====================

    /**
     * 调用失败时站内信通知 API 创建人（需存在编码 supply_api_alarm 的通知模板）
     */
    private void sendAlarm(DataApiDO api, String errorInfo) {
        if (api.getAlarmEnabled() == null || api.getAlarmEnabled() != 1
                || StrUtil.isBlank(api.getCreator())) {
            return;
        }
        try {
            NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
            reqDTO.setUserId(Long.parseLong(api.getCreator()));
            reqDTO.setTemplateCode("supply_api_alarm");
            Map<String, Object> templateParams = new LinkedHashMap<>();
            templateParams.put("apiName", StrUtil.nullToEmpty(api.getName()));
            templateParams.put("errorInfo", StrUtil.maxLength(StrUtil.nullToEmpty(errorInfo), 200));
            reqDTO.setTemplateParams(templateParams);
            notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
        } catch (Exception ex) {
            log.warn("[sendAlarm][API({}) 告警通知发送失败：{}]", api.getId(), ex.getMessage());
        }
    }

    // ==================== 日志 ====================

    /**
     * 按日志开关记录 API 调用结果、执行模式及拒绝阶段。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @param callerIp 调用方 IP
     * @param durationMs 耗时毫秒
     * @param dataCount 数据数量
     * @param errorInfo 错误信息
     * @param writeLog 写入日志
     * @param appId 应用编号
     * @param mode 处理模式
     * @param testRunNo 测试运行序号
     * @param configHash 配置摘要
     */
    private void saveLog(DataApiDO api, Map<String, Object> params, String callerIp,
            long durationMs, long dataCount, String errorInfo, boolean writeLog,
            Long appId, DataApiExecuteMode mode, String testRunNo, String configHash) {
        saveLog(api, params, callerIp, durationMs, dataCount, errorInfo, writeLog, appId,
                mode, testRunNo, configHash, errorInfo == null ? null : "EXECUTE");
    }

    /**
     * 按日志开关记录 API 调用结果、执行模式及拒绝阶段。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @param callerIp 调用方 IP
     * @param durationMs 耗时毫秒
     * @param dataCount 数据数量
     * @param errorInfo 错误信息
     * @param writeLog 写入日志
     * @param appId 应用编号
     * @param mode 处理模式
     * @param testRunNo 测试运行序号
     * @param configHash 配置摘要
     * @param stage 阶段
     */
    private void saveLog(DataApiDO api, Map<String, Object> params, String callerIp,
            long durationMs, long dataCount, String errorInfo, boolean writeLog,
            Long appId, DataApiExecuteMode mode, String testRunNo, String configHash, String stage) {
        if (!writeLog) {
            return;
        }
        try {
            Map<String, Object> logParams = new LinkedHashMap<>();
            logParams.put("mode", mode == null ? "" : mode.name());
            logParams.put("configHash", StrUtil.nullToEmpty(configHash));
            logParams.put("paramNames", params == null ? List.of() : List.copyOf(params.keySet()));
            ApiLogDO logEntry = ApiLogDO.builder()
                    .apiId(api.getId())
                    .apiName(api.getName())
                    .categoryId(api.getCategoryId())
                    .appId(appId)
                    .callerIp(callerIp)
                    .callPath("/services/" + StrUtil.nullToEmpty(api.getVersion())
                            + StrUtil.nullToEmpty(api.getPath()))
                    .method(api.getMethod())
                    .paramsJson(JSONUtil.toJsonStr(logParams))
                    .callTime(LocalDateTime.now())
                    .durationMs(durationMs)
                    .dataCount(dataCount)
                    .status(errorInfo == null ? ApiLogDO.STATUS_SUCCESS : ApiLogDO.STATUS_FAILED)
                    .errorInfo(errorInfo)
                    .callMode(mode == null ? null : mode.name())
                    .testRunNo(testRunNo)
                    .releaseId(api.getActiveReleaseId())
                    .rejectStage(errorInfo == null ? null : stage)
                    .traceId(DataApiGatewayService.currentTraceId())
                    .build();
            apiLogMapper.insert(logEntry);
        } catch (Exception ex) {
            log.warn("[saveLog][API({}) 调用日志写入失败：{}]", api.getId(), ex.getMessage());
        }
    }

    /**
     * 补充结果。
     *
     * @param result 处理结果
     * @param mode 处理模式
     * @param testRunNo 测试运行序号
     * @param configHash 配置摘要
     */
    private void decorateResult(DataApiExecuteRespVO result, DataApiExecuteMode mode,
            String testRunNo, String configHash) {
        result.setExecuteMode(mode == null ? null : mode.name());
        result.setTestRunNo(testRunNo);
        result.setConfigHash(configHash);
    }

    /**
     * 将执行异常转为可对外返回的脱敏错误说明。
     *
     * @param ex 异常信息
     * @param stage 阶段
     * @return 将执行异常转为可对外返回的脱敏错误说明文本
     */
    private String safeExecutionError(Exception ex, String stage) {
        if (ex instanceof cn.iocoder.yudao.framework.common.exception.ServiceException business) {
            return "请求未完成，阶段=" + stage + "，错误码=" + business.getCode();
        }
        return "请求未完成，阶段=" + stage + "；请使用追踪编号核查";
    }

    /**
     * 计算配置摘要，供版本及内容一致性校验使用。
     *
     * @param api 本次使用的 API 定义
     * @return 计算后的摘要或签名字符串
     */
    private String configHash(DataApiDO api) {
        String material = StrUtil.nullToEmpty(api.getConfigType()) + '|'
                + StrUtil.nullToEmpty(api.getApiType()) + '|'
                + StrUtil.nullToEmpty(api.getTableName()) + '|'
                + StrUtil.nullToEmpty(api.getSqlText()) + '|'
                + StrUtil.nullToEmpty(api.getRequestParamsJson()) + '|'
                + StrUtil.nullToEmpty(api.getResponseFieldsJson()) + '|'
                + api.getDatabaseId();
        return DigestUtil.sha256Hex(material);
    }

}
