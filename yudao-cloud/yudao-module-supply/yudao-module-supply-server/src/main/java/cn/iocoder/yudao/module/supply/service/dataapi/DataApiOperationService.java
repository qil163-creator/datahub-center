package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiOperationRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiOperationDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiOperationMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.supply.enums.dataapi.ErrorCodeConstants.*;

/**
 * 开放写请求的持久台账。认领和回执使用短独立事务，目标库写入委托既有 DB Switch 执行器。
 * 平台与外部库不构成原子事务：进程崩溃/回执丢失保留 UNKNOWN，任何重试都不会重新写入。
 * 台账不自动清理，人工确认也不释放业务键，不宣称外部库 exactly-once。
 */
@Service
public class DataApiOperationService {

    public static final String KEY_PARAM = "_operationKey";

    public static final String MODE_PARAM = "_operationMode";

    @FunctionalInterface
    public interface WriteAction {

        /**
         * 读取数据 API执行响应数据。
         *
         * @return 数据 API执行响应数据
         */
        DataApiExecuteRespVO get() throws Exception;
    }

    @Resource
    private DataApiOperationMapper operationMapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 判断当前 API 是否必须经过持久写操作台账。
     *
     * @param api 本次使用的 API 定义
     * @return 处理是否成功
     */
    public static boolean requiresOperation(DataApiDO api) {
        String upstreamMethod = cn.hutool.core.util.StrUtil.blankToDefault(api.getForwardMethod(), api.getMethod());
        return !DataApiReadOnlyGuard.isQueryApi(api)
                || DataApiDO.CONFIG_TYPE_FORWARD.equalsIgnoreCase(api.getConfigType())
                && !"GET".equalsIgnoreCase(upstreamMethod);
    }

    /**
     * 判断请求是否只查询已有操作的状态。
     *
     * @param params 业务参数映射
     * @return 判断结果
     */
    public static boolean isStatusQuery(Map<String, Object> params) {
        return params != null && "STATUS".equals(params.get(MODE_PARAM));
    }

    /**
     * 通过持久业务键认领开放写请求，重复调用只读取回执，执行或回执失联时保留未知结果。
     *
     * @param api 本次使用的 API 定义
     * @param appId 应用编号
     * @param params 业务参数映射
     * @param action 待执行的操作
     * @return 数据 API执行响应数据
     */
    public DataApiExecuteRespVO execute(DataApiDO api, Long appId, Map<String, Object> params,
            WriteAction action) {

        if (appId == null || api.getActiveReleaseId() == null || api.getId() == null) {
            throw exception(OPERATION_SIGN_REQUIRED);
        }
        String key = key(params);
        String mode = params.get(MODE_PARAM) == null ? "EXECUTE" : String.valueOf(params.get(MODE_PARAM));
        if (!Set.of("EXECUTE", "STATUS").contains(mode)) {
            throw exception(OPERATION_KEY_REQUIRED);
        }
        if ("STATUS".equals(mode)) {
            DataApiOperationDO existing = operationMapper.find(appId, api.getId(), key);
            if (existing == null) {
                throw exception(OPERATION_NOT_EXISTS);
            }
            return response(existing, true);
        }
        Map<String, Object> businessParams = new LinkedHashMap<>(params);
        businessParams.remove(KEY_PARAM);
        businessParams.remove(MODE_PARAM);
        String hash = requestHash(api, businessParams);
        boolean claimed = Boolean.TRUE.equals(tx().execute(ignored -> operationMapper.claim(appId,
                api.getId(), api.getActiveReleaseId(), key, hash,
                DataApiGatewayService.currentTraceId()) == 1));
        DataApiOperationDO operation = operationMapper.find(appId, api.getId(), key);
        if (operation == null) {
            throw exception(OPERATION_STORE_UNAVAILABLE);
        }
        if (!hash.equals(operation.getRequestHash())) {
            throw exception(OPERATION_CONFLICT);
        }
        if (!claimed) {
            return response(operation, true);
        }

        DataApiExecuteRespVO result;
        try {
            result = action.get();
        } catch (Exception executionFailure) {
            // 不能从异常推断目标库未提交；只记录未知，不保留可能含敏感SQL/参数的异常文本。
            markUnknown(operation.getId());
            operation.setState("UNKNOWN");
            return response(operation, false);
        }
        try {
            boolean forward = DataApiDO.CONFIG_TYPE_FORWARD.equalsIgnoreCase(api.getConfigType());
            String stored = forward ? JsonUtils.toJsonString(new DataApiExecuteRespVO()) : safeResult(result);
            boolean finished = Boolean.TRUE.equals(tx().execute(ignored ->
                    operationMapper.finish(operation.getId(), "SUCCEEDED", stored) == 1));
            if (!finished) {
                operation.setState("UNKNOWN");
                return response(operation, false);
            }
            operation.setState("SUCCEEDED");
            operation.setResultJson(stored);
            if (forward) {
                decorate(result, operation, "SUCCEEDED", false);
                result.setOperationMessage(
                        "上游响应已确认；出于数据最小留存原则，重复请求只返回状态，不重发请求或保存上游响应");
                return result;
            }
            return response(operation, false);
        } catch (Exception receiptFailure) {
            markUnknown(operation.getId());
            operation.setState("UNKNOWN");
            return response(operation, false);
        }
    }

    /**
     * 读取 API 写操作台账历史并转换为可展示的处置记录。
     *
     * @param apiId API 编号
     * @param state 状态
     * @param afterId 变更后编号
     * @return  API 写操作台账历史并转换为可展示的处置记录结果集合
     */
    public List<DataApiOperationRespVO> history(Long apiId, String state, Long afterId) {
        return history(apiId, state, afterId, null);
    }

    /**
     * 读取 API 写操作台账历史并转换为可展示的处置记录。
     *
     * @param apiId API 编号
     * @param state 状态
     * @param afterId 变更后编号
     * @param operationKey 操作键
     * @return  API 写操作台账历史并转换为可展示的处置记录结果集合
     */
    public List<DataApiOperationRespVO> history(Long apiId, String state, Long afterId, String operationKey) {
        return operationMapper.history(apiId, state, afterId, operationKey)
                .stream().map(DataApiOperationRespVO::from).toList();
    }

    /**
     * 校验人工处置依据并幂等记录未知写操作的确认结论，不释放原业务键。
     *
     * @param id 记录编号
     * @param decision 处置结论
     * @param reason 操作原因
     * @param evidence 处置依据
     * @return 数据 API操作响应数据
     */
    public DataApiOperationRespVO resolve(Long id, String decision, String reason, String evidence) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || !Set.of("CONFIRMED_SUCCEEDED", "CONFIRMED_NOT_APPLIED").contains(decision)
                || reason == null || reason.isBlank() || evidence == null || evidence.isBlank()) {
            throw exception(OPERATION_DISPOSITION_CONFLICT);
        }

        return tx().execute(ignored -> {
            if (operationMapper.resolve(id, decision, actor, reason.strip(), evidence.strip()) != 1) {
                var prior = operationMapper.get(id);
                if (prior == null || !Objects.equals(prior.getState(), decision)
                        || !Objects.equals(prior.getResolvedBy(), actor)
                        || !Objects.equals(prior.getResolution(), reason.strip())
                        || !Objects.equals(prior.getEvidence(), evidence.strip())) {
                    throw exception(OPERATION_DISPOSITION_CONFLICT);
                }
            }
            return DataApiOperationRespVO.from(operationMapper.get(id));
        });
    }

    /**
     * 处理已过期的操作记录，收敛失联执行状态。
     *
     * @return 对账过期数值
     */
    public int reconcileExpired() {
        return tx().execute(ignored -> operationMapper.expire());
    }

    /**
     * 将无法确认外部效果的操作保留为未知状态。
     *
     * @param id 记录编号
     */
    private void markUnknown(long id) {
        try {
            tx().execute(ignored -> operationMapper.finish(id, "UNKNOWN", null));
        } catch (Exception ignored) { /* 认领行仍永久占用业务键，巡检将过期 RUNNING 标记为 UNKNOWN。 */ }
    }

    /**
     * 创建当前业务操作使用的本地事务模板。
     *
     * @return 事务模板
     */
    private TransactionTemplate tx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(5);
        return tx;
    }

    /**
     * 提取并校验开放写操作的持久幂等键。
     *
     * @param params 业务参数映射
     * @return 提取并校验开放写操作的持久幂等键文本
     */
    static String key(Map<String, Object> params) {
        Object value = params == null ? null : params.get(KEY_PARAM);
        if (!(value instanceof String key) || !key.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{7,127}")) {
            throw exception(OPERATION_KEY_REQUIRED);
        }
        return key;
    }

    /**
     * 计算请求内容的稳定摘要，供幂等冲突校验使用。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @return 计算后的摘要或签名字符串
     */
    static String requestHash(DataApiDO api, Map<String, Object> params) {
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of("supply-write/v1", api.getId(),
                api.getActiveReleaseId(), DataApiContract.configHash(api), canonical(params))));
    }

    /**
     * 将输入转为稳定的规范表示。
     *
     * @param value 待处理的值
     * @return 数据 API操作
     */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DataApiOperationService::canonical).toList();
        }
        return value;
    }

    /** 写SQL仅持久化影响行数，绝不将业务行、参数或第三方响应写入台账。 */
    static String safeResult(DataApiExecuteRespVO result) {
        DataApiExecuteRespVO stored = new DataApiExecuteRespVO();
        stored.setRows(result.getRows() == null ? List.of() : result.getRows().stream().limit(1000).map(row -> {
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String key : List.of("statement", "affectedRows")) {
                Object value = row.get(key);
                if (value instanceof Number) {
                    counts.put(key, value.toString());
                }
            }
            return counts;
        }).toList());
        return JsonUtils.toJsonString(stored);
    }

    /**
     * 从持久操作台账恢复回执，并标记本次是否为重复请求。
     *
     * @param operation 操作
     * @param replayed 已重放
     * @return 数据 API执行响应数据
     */
    private static DataApiExecuteRespVO response(DataApiOperationDO operation, boolean replayed) {
        DataApiExecuteRespVO result = "SUCCEEDED".equals(operation.getState()) && operation.getResultJson() != null
                ? JsonUtils.parseObject(operation.getResultJson(), DataApiExecuteRespVO.class)
                : new DataApiExecuteRespVO();
        if (result.getRows() == null) {
            result.setRows(List.of());
        }
        decorate(result, operation, operation.getState(), replayed);
        return result;
    }

    /**
     * 将操作编号、状态及幂等重放标记写入响应。
     *
     * @param result 处理结果
     * @param operation 操作
     * @param state 状态
     * @param replayed 已重放
     */
    private static void decorate(DataApiExecuteRespVO result, DataApiOperationDO operation,
            String state, boolean replayed) {
        result.setOperationId(operation.getId());
        result.setOperationKey(operation.getOperationKey());
        result.setOperationStatus(state);
        result.setReplayed(replayed);
        result.setOperationMessage(switch (state) {
            case "SUCCEEDED" -> "操作已确认；相同编号不会重复执行。HTTP转发只保留状态，不重放上游响应";
            case "RUNNING" -> "请求正在执行，请使用 STATUS 查询同一操作编号";
            case "CONFIRMED_SUCCEEDED" -> "已人工核查为成功，原业务键保持占用";
            case "CONFIRMED_NOT_APPLIED" -> "已人工核查为未写入，原业务键保持占用；新的执行需新的业务决定";
            default -> "外部结果未知，禁止换编号盲目重试；请核查目标库后处置";
        });
    }
}
