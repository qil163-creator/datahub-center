package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.*;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlDataSourceUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 仅负责操作权限、批次完整性和连接绑定；所有物理数据库操作均使用 DB Switch。 */
@Component
public class EtlTargetDatabaseAdapter {

    @Resource
    private DbSwitchRuntime runtime;

    @Resource
    private EtlConnectionResolverAdapter connections;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private EtlEnvironmentService environments;

    @Resource
    private cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi qualityApi;

    /**
     * 准备ETL目标数据源适配器的配置。
     *
     * @param input 输入数据
     * @param actor 操作人编号
     * @return JSON对象
     */
    public JSONObject prepareConfig(JSONObject input, Long actor) {
        JSONObject config = JSONUtil.parseObj(input.toString());
        Long databaseId = config.getLong("databaseId");
        access.requireUserPermission(actor, ExecutionAccessService.PERMISSION_ETL_RUN);
        access.requireDatabase(databaseId);
        var connection = connections.resolve(databaseId);
        String referenceText = config.containsKey("bindingHash") ? connection.getProductType().quoteSchemaTableName(
                config.getStr("schemaName", connection.getSchema()), config.getStr("tableName")) : config.getStr(
                "tableName");
        var reference = RestrictedSelectGuard.parseTableReference(connection.getProductType(),
                referenceText, config.getStr("schemaName", connection.getSchema()));
        config.set("schemaName", reference.getSchemaName());
        config.set("tableName", reference.getTableName());
        config.set("batchColumn", config.getStr("batchColumn", "_etl_batch_key"));
        config.set("rowColumn", config.getStr("rowColumn", "_etl_row_no"));
        config.set("statusColumn", config.getStr("statusColumn", "_quality_status"));
        config.set("disposition", config.getStr("disposition", "STRICT"));
        config.set("allowWarnings", config.getBool("allowWarnings", true));


        var keys = config.getJSONArray("businessKeys");
        var fields = config.getJSONArray("fields");
        if (keys == null || keys.isEmpty() || keys.size() > 16 || fields == null || fields.size() > 254
                || !fields.containsAll(keys) || keys.contains(config.getStr("statusColumn"))
                || new HashSet<>(keys.toList(String.class)).size() != keys.size()) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "需配置输出业务字段和 1 至 16 个稳定业务键");
        }
        config.set("bindingHash", bindingHash(connection));
        var target = target(config);
        try (var source = EtlDataSourceUtils.create(connection, runtime)) {
            runtime.products().atomicBatchWriter(source, connection.getProductType()).preflight(target, fields(config));
        }
        return config;
    }

    /**
     * 请求ETL目标数据源适配器。
     *
     * @param config 处理配置
     * @param batchKey 批次键
     * @param rows 待处理的行数据
     * @param filterRejected 筛选已拒绝
     * @return 原子批次请求
     */
    public AtomicBatchRequest request(JSONObject config, String batchKey,
            List<Map<String, Object>> rows, boolean filterRejected) {
        List<String> fields = fields(config);
        String statusField = config.getStr("statusColumn");
        boolean strict = filterRejected && "STRICT".equals(config.getStr("disposition"));
        boolean allowWarnings = config.getBool("allowWarnings", true);
        List<Object[]> values = new ArrayList<>();
        long ordinal = 0;
        for (var row : rows) {
            ordinal++;
            String status = Objects.toString(row.get(statusField), "");
            if (!Set.of("PASS", "WARN", "REJECT").contains(status)) {
                throw exception(ETL_TARGET_CONFIG_INVALID, "输入质量状态缺失或无效");
            }
            if (strict && !("PASS".equals(status) || allowWarnings && "WARN".equals(status))) {
                continue;
            }
            Object[] line = new Object[fields.size()];
            for (int i = 0; i < fields.size() - 2; i++) {
                if (!row.containsKey(fields.get(i))) {
                    throw exception(ETL_TARGET_CONFIG_INVALID, "输入缺少输出字段");
                }
                line[i] = row.get(fields.get(i));
            }
            line[fields.size() - 2] = batchKey;
            line[fields.size() - 1] = ordinal;
            values.add(line);
        }
        return AtomicBatchRequest.create(target(config), new AtomicBatchKey("dataforge-etl", batchKey), fields, values);
    }

    /**
     * 提交ETL目标数据源适配器。
     *
     * @param batch 目标批次信息
     * @return 原子批次回执
     */
    public AtomicBatchReceipt commit(EtlTargetBatchDO batch) {
        JSONObject config = JSONUtil.parseObj(batch.getConfigJson());
        environments.validateTargetBinding(config, batch.getExecutionUserId());
        access.requireUserPermission(batch.getExecutionUserId(), ExecutionAccessService.PERMISSION_ETL_RUN);
        access.requireDatabase(config.getLong("databaseId"));
        var connection = connections.resolve(config.getLong("databaseId"));
        if (!Objects.equals(config.getStr("bindingHash"), bindingHash(connection))) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        AtomicBatchRequest request = AtomicBatchCodec.decode(batch.getPayloadJson());
        if (!request.payloadHash().equals(batch.getPayloadHash()) || !request.target().equals(target(config))
                || !request.key().equals(new AtomicBatchKey("dataforge-etl", physicalKey(batch)))
                || request.rowCount() != batch.getOutputCount()) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }

        try (var source = EtlDataSourceUtils.create(connection, runtime)) {
            var writer = runtime.products().atomicBatchWriter(source, connection.getProductType());
            writer.preflight(request.target(), request.fields());
            writer.prepareLedger(request.target().schema());
            AtomicBatchReceipt receipt;
            if (batch.getReplayOfBatchId() == null) {
                receipt = writer.commit(request);
            } else {

                var completed = writer.findOperationReceipt(request.target(), request.key(), batch.getOperationKey());
                if (completed.isPresent()) {
                    receipt = completed.get();
                } else {
                    var authorization = new cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeReqDTO();
                    authorization.setWorkOrderId(batch.getWorkOrderId());
                    authorization.setSubmissionId(batch.getWorkOrderSubmissionId());
                    authorization.setExecutionUserId(batch.getExecutionUserId());
                    var scope = qualityApi.describeEtlWorkOrderReplay(authorization).getCheckedData();
                    if (scope == null || !Objects.equals(scope.getWorkOrderId(), batch.getWorkOrderId())
                            || !Objects.equals(scope.getSubmissionId(),
                            batch.getWorkOrderSubmissionId()) || scope.getOriginalTargets() == null
                            || scope.getOriginalTargets().stream()
                            .noneMatch(t -> Objects.equals(t.getTargetBatchId(), batch.getReplayOfBatchId())
                                    && Objects.equals(t.getRecordId(), batch.getReplayOfRecordId()))) {
                        throw exception(ETL_TARGET_STATE_CHANGED);
                    }
                    receipt = writer.replace(new AtomicBatchReplacement(request.target(), request.key(),
                            batch.getExpectedPayloadHash(), batch.getOperationKey(), request));
                }
            }
            if (!Objects.equals(receipt.target(), request.target()) || !Objects.equals(receipt.key(), request.key())
                    || !Objects.equals(receipt.fields(), request.fields()) || !Objects.equals(receipt.payloadHash(),
                    request.payloadHash())
                    || receipt.rowCount() != request.rowCount()) {
                throw exception(ETL_TARGET_INTEGRITY_FAILED);
            }
            return receipt;
        }
    }

    /**
     * 查找已提交。
     *
     * @param batch 目标批次信息
     * @return 原子批次回执
     */
    public Optional<AtomicBatchReceipt> findCommitted(EtlTargetBatchDO batch) {
        JSONObject config = JSONUtil.parseObj(batch.getConfigJson());
        environments.validateTargetBinding(config, batch.getExecutionUserId());
        access.requireUserPermission(batch.getExecutionUserId(), ExecutionAccessService.PERMISSION_ETL_RUN);
        access.requireDatabase(config.getLong("databaseId"));
        var connection = connections.resolve(config.getLong("databaseId"));
        if (!Objects.equals(config.getStr("bindingHash"), bindingHash(connection))) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }

        try (var source = EtlDataSourceUtils.create(connection, runtime)) {
            var writer = runtime.products().atomicBatchWriter(source, connection.getProductType());
            var key = new AtomicBatchKey("dataforge-etl", physicalKey(batch));
            var receipt = batch.getReplayOfBatchId() == null ? writer.findCurrentReceipt(target(config), key)
                    : writer.findOperationReceipt(target(config), key, batch.getOperationKey());
            receipt.ifPresent(r -> verifyReceipt(batch, r));
            return receipt;
        }
    }

    /**
     * 读取已准入。
     *
     * @param batch 目标批次信息
     * @param actor 操作人编号
     * @param afterRow 变更后行
     * @param limit 读取或处理上限
     * @return 已准入结果集合
     */
    public List<Map<String, Object>> readAdmitted(EtlTargetBatchDO batch, Long actor, long afterRow, int limit) {
        if (actor == null || afterRow < 0 || limit < 1 || limit > 201) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "分页参数无效");
        }
        JSONObject config = JSONUtil.parseObj(batch.getConfigJson());
        environments.validateTargetBinding(config, actor);
        access.requireDatabase(config.getLong("databaseId"));
        var connection = connections.resolve(config.getLong("databaseId"));
        if (!Objects.equals(config.getStr("bindingHash"), bindingHash(connection))) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        try (var source = EtlDataSourceUtils.create(connection, runtime)) {
            var writer = runtime.products().atomicBatchWriter(source, connection.getProductType());
            Set<String> statuses = config.getBool("allowWarnings", true) ? Set.of("PASS", "WARN") : Set.of("PASS");
            var plan = writer.planReadBatch(target(config), new AtomicBatchKey("dataforge-etl", physicalKey(batch)),
                    fields(config), afterRow, limit, statuses);
            verifyReceipt(batch, plan.receipt());
            // DB Switch 使用同一本地快照完成批次物化校验和参数化读取。
            return writer.readAuthorizedBatch(plan, plan.authorizationSql(), batch.getPayloadHash());
        }
    }

    /**
     * 授权描述。
     *
     * @param batch 目标批次信息
     * @param actor 操作人编号
     */
    public void authorizeDescription(EtlTargetBatchDO batch, Long actor) {
        JSONObject config = JSONUtil.parseObj(batch.getConfigJson());
        environments.validateTargetBinding(config, actor);
        access.requireDatabase(config.getLong("databaseId"));
        var connection = connections.resolve(config.getLong("databaseId"));
        if (!Objects.equals(config.getStr("bindingHash"), bindingHash(connection))) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }

    }

    /**
     * 核对回执。
     *
     * @param batch 目标批次信息
     * @param receipt 回执
     */
    private void verifyReceipt(EtlTargetBatchDO batch, AtomicBatchReceipt receipt) {
        var config = JSONUtil.parseObj(batch.getConfigJson());
        if (!receipt.target().equals(target(config)) || !receipt.key()
                .equals(new AtomicBatchKey("dataforge-etl", physicalKey(batch)))
                || !receipt.payloadHash().equals(batch.getPayloadHash()) || receipt.rowCount() != batch.getOutputCount()
                || !receipt.fields().equals(fields(config))) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
    }

    /**
     * 获取ETL目标数据源适配器的目标。
     *
     * @param config 处理配置
     * @return 原子批次目标
     */
    public static AtomicBatchTarget target(JSONObject config) {
        return new AtomicBatchTarget(config.getStr("schemaName"), config.getStr("tableName"),
                config.getStr("batchColumn"), config.getStr("rowColumn"), config.getStr("statusColumn"));
    }

    /**
     * 获取ETL目标数据源适配器的物理键。
     *
     * @param batch 目标批次信息
     * @return ETL目标数据源适配器的物理键文本
     */
    public static String physicalKey(EtlTargetBatchDO batch) {
        return Objects.requireNonNullElse(batch.getPhysicalBatchKey(), batch.getBatchKey());
    }

    /**
     * 读取ETL目标数据源适配器字段集合。
     *
     * @param config 处理配置
     * @return ETL目标数据源适配器字段集合结果集合
     */
    public static List<String> fields(JSONObject config) {
        var result = new ArrayList<>(config.getJSONArray("fields").toList(String.class));
        if (result.contains(config.getStr("batchColumn")) || result.contains(config.getStr("rowColumn"))
                || !result.contains(config.getStr("statusColumn"))) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "技术字段配置冲突");
        }
        result.add(config.getStr("batchColumn"));
        result.add(config.getStr("rowColumn"));
        return AtomicBatchRequest.checkedFields(result);
    }

    /**
     * 获取ETL目标数据源适配器的绑定摘要。
     *
     * @param connection 数据库连接
     * @return 计算后的摘要或签名字符串
     */
    private static String bindingHash(cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo connection) {
        // 不持久化连接串或凭据；目标标识变化时阻止自动重试。
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(JsonUtils.toJsonString(Arrays.asList(
                connection.getProductType().name(), connection.getJdbcUrl(), connection.getUsername(),
                connection.getSchema())));
    }
}
