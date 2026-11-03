package cn.iocoder.yudao.module.supply.service.app.grant;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantCommandCodec;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantSourceDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantOpDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.*;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiService;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyApiBindingService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

import static cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.GRANT;
import static cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO.Outcome.*;

/** 来源命令的领域事务协调；尚未接入 HTTP 或旧授权入口。 */
@Service
@RequiredArgsConstructor
public class SupplyGrantCoordinator {

    private final SupplyAppMapper appMapper;

    private final AppApiMapper aggregateMapper;

    private final SupplyGrantSourceMapper sourceMapper;

    private final SupplyGrantOpMapper operationMapper;

    private final DataApiMapper apiMapper;

    private final AdminUserApi adminUserApi;

    private final DataApiService dataApiService;

    private final SupplyApiBindingService bindingService;

    private final PlatformTransactionManager transactionManager;

    /**
     * 挂起调用方事务，完成远程授权预检后以独立持久回执边界应用来源授权命令。
     *
     * @param command 待处理命令
     * @return 供应授权来源结果
     */
    public SupplyGrantSourceResultDTO apply(SupplyGrantSourceCommandDTO command) {
        Objects.requireNonNull(command, "command");
        // 显式挂起调用方事务：远端预检不占用业务锁；此命令以自己的持久回执为提交边界。
        return boundary(TransactionDefinition.PROPAGATION_NOT_SUPPORTED).execute(ignored -> {
            String hash = SupplyGrantCommandCodec.hash(command);
            var existing = operationMapper.selectSourceOperation(command.sourceSystem(), command.operationId());
            if (existing != null) {
                return replay(existing, command, hash);
            }
            Proof proof = command.action() == GRANT ? preflight(command) : new Proof(null, null);
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return boundary(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                            .execute(status -> applyInTransaction(command, hash, proof));
                } catch (DuplicateKeyException conflict) {
                    // 必须在整个失败事务回滚后查原回执，不能在 PostgreSQL 已中止的事务内继续查询。
                    existing = operationMapper.selectSourceOperation(command.sourceSystem(), command.operationId());
                    if (existing != null) {
                        return replay(existing, command, hash);
                    }
                    // 不同聚合竞争同一来源身份：重读赢家的不可迁移身份；最多重试一次。
                    if (attempt == 1) {
                        throw conflict;
                    }
                }
            }
            throw new IllegalStateException("Unreachable source command retry");
        });
    }

    /**
     * 在调用方事务外按来源系统及操作编号读取来源命令回执。
     *
     * @param sourceSystem 来源系统
     * @param operationId 操作编号
     * @return 供应授权来源结果
     */
    public SupplyGrantSourceResultDTO query(String sourceSystem, String operationId) {
        if (sourceSystem == null || sourceSystem.isBlank() || operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("sourceSystem and operationId are required");
        }
        return boundary(TransactionDefinition.PROPAGATION_NOT_SUPPORTED).execute(ignored -> {
            var receipt = operationMapper.selectSourceOperation(sourceSystem, operationId);
            return receipt == null ? new SupplyGrantSourceResultDTO(operationId, NOT_FOUND, null, 0, 0, null)
                    : resultFromReceipt(receipt);
        });
    }

    /**
     * 锁定应用及授权聚合，复核来源修订并原子保存状态变更和命令回执。
     *
     * @param command 待处理命令
     * @param hash 摘要
     * @param proof 证明
     * @return 供应授权来源结果
     */
    private SupplyGrantSourceResultDTO applyInTransaction(SupplyGrantSourceCommandDTO command,
            String hash, Proof proof) {
        var existing = operationMapper.selectSourceOperation(command.sourceSystem(), command.operationId());
        if (existing != null) {
            return replay(existing, command, hash);
        }
        var app = appMapper.selectGrantParentForUpdate(command.appId());
        var aggregate = aggregateMapper.selectAggregateForUpdate(command.appId(), command.apiId());
        if (app == null) {
            return reject(command, hash, aggregate, "APP_NOT_FOUND");
        }
        if (aggregate != null && !Integer.valueOf(2).equals(aggregate.getProtocolVersion())) {
            return reject(command, hash, aggregate, "REVIEW_REQUIRED");
        }
        var previousRow = sourceMapper.selectForUpdate(command.sourceSystem(), command.sourceKey());
        GrantSourceState previous = previousRow == null ? null : state(previousRow);
        GrantSourceState next;
        try {
            next = SupplyGrantPolicy.transition(previous, command);
        } catch (SupplyGrantPolicy.Rejected rejected) {
            return reject(command, hash, aggregate, rejected.reason().name());
        }
        // 同来源同版本相同语义、不同 operationId 也只是重放，不再次推进聚合。
        if (next.equals(previous)) {
            return persist(command, hash, aggregate, APPLIED, null);
        }
        if (command.action() == GRANT) {
            if (Boolean.TRUE.equals(app.getDeleted()) || !Integer.valueOf(1).equals(app.getAppStatus())) {
                return reject(command, hash, aggregate, "APP_DISABLED");
            }
            if (proof.error() != null) {
                return reject(command, hash, aggregate, proof.error());
            }
            var api = apiMapper.selectGrantContractForShare(command.apiId());
            var binding = proof.binding();
            if (api == null || !Integer.valueOf(1).equals(api.getStatus())
                    || !Objects.equals(api.getContractHash(), binding.getContractHash())
                    || !Objects.equals(api.getVersion(), binding.getVersion())
                    || !Objects.equals(api.getActiveReleaseId(), binding.getActiveReleaseId())
                    || !Objects.equals(api.getDatabaseId(), binding.getDatabaseId())
                    || !Objects.equals(api.getTableName(), binding.getTableName())
                    || !"TABLE".equals(api.getConfigType()) || !"QUERY".equals(api.getApiType())) {
                return reject(command, hash, aggregate, "CONTRACT_MISMATCH");
            }
            // 复用纯策略检查全部未关闭来源（包括未来、过期、暂停），而不要求当下已有生效字段。
            var candidates = new ArrayList<GrantSourceState>();
            for (var row : sourceMapper.selectAggregateSources(command.appId(), command.apiId())) {
                if (!row.getSourceSystem().equals(command.sourceSystem()) || !row.getSourceKey()
                        .equals(command.sourceKey())) {
                    candidates.add(state(row));
                }
            }
            candidates.add(next);
            try {
                SupplyGrantPolicy.evaluate(command.appId(), command.apiId(), revision(aggregate), false,
                        candidates, Instant.ofEpochMilli(aggregateMapper.databaseTimeMillis()));
            } catch (SupplyGrantPolicy.Rejected rejected) {
                if (rejected.reason() != SupplyGrantPolicy.Reason.NO_EFFECTIVE_SCOPE) {
                    return reject(command, hash, aggregate, rejected.reason().name());
                }
            }
        }
        if (aggregate == null) {
            aggregate = new AppApiDO();

            aggregate.setAppId(command.appId());
            aggregate.setApiId(command.apiId());
            aggregate.setAggregateRevision(0L);
            aggregateMapper.insertSourceAggregate(aggregate);
        }
        var source = new SupplyGrantSourceDO();

        source.setSourceSystem(next.sourceSystem());
        source.setSourceKey(next.sourceKey());
        source.setAppId(next.appId());
        source.setApiId(next.apiId());
        source.setSourceRevision(next.sourceRevision());
        source.setSourceStatus(next.status().name());
        source.setPayloadHash(next.payloadHash());
        source.setScopeJson(next.scope() == null ? null : JsonUtils.toJsonString(next.scope()));
        source.setCompatibilityKey(next.scope() == null ? null : SupplyGrantPolicy.compatibilityKey(next.scope()));
        int written = previous == null ? sourceMapper.insertSource(source) : sourceMapper.updateSource(source);
        if (written != 1 || aggregateMapper.advanceSourceRevision(aggregate.getId(), revision(aggregate)) != 1) {
            throw new IllegalStateException("Source aggregate changed outside its transaction");
        }
        aggregate.setAggregateRevision(Math.addExact(revision(aggregate), 1));
        return persist(command, hash, aggregate, APPLIED, null);
    }

    /**
     * 在获取业务锁前核验权限用户、活动发布契约及字段映射证明。
     *
     * @param command 待处理命令
     * @return 证明
     */
    private Proof preflight(SupplyGrantSourceCommandDTO command) {
        var scope = command.scope();
        var user = adminUserApi.getUser(scope.permissionUserId()).getCheckedData();
        if (user == null || !Objects.equals(user.getId(), scope.permissionUserId())
                || !Integer.valueOf(0).equals(user.getStatus())) {
            return new Proof(null, "PERMISSION_USER_INVALID");
        }
        var published = dataApiService.getPublishedDataApiEntity(command.apiId());
        if (published == null) {
            return new Proof(null, "CONTRACT_MISMATCH");
        }
        var binding = bindingService.resolve(published);
        if (binding == null || !binding.isFieldMappingVerified() || !Objects.equals(binding.getId(), command.apiId())
                || !Integer.valueOf(1).equals(binding.getStatus()) || !"TABLE".equals(binding.getConfigType())
                || !"QUERY".equals(binding.getApiType()) || binding.getActiveReleaseId() == null
                || !Objects.equals(binding.getContractHash(), scope.approvedContractHash())
                || !Objects.equals(binding.getVersion(), scope.apiVersion())
                || !Objects.equals(binding.getDatabaseId(), scope.binding().databaseId())
                || !Objects.equals(binding.getSchemaName(), scope.binding().schemaName())
                || !Objects.equals(binding.getTableName(), scope.binding().tableName())
                || binding.getOutputFieldMapping() == null
                || !binding.getOutputFieldMapping().values().containsAll(scope.approvedColumns())) {
            return new Proof(null, "BINDING_UNVERIFIED");
        }
        return new Proof(binding, null);
    }

    /**
     * 按拒绝原因构造并持久化来源命令的拒绝或过期回执。
     *
     * @param command 待处理命令
     * @param hash 摘要
     * @param aggregate 聚合
     * @param reason 操作原因
     * @return 供应授权来源结果
     */
    private SupplyGrantSourceResultDTO reject(SupplyGrantSourceCommandDTO command, String hash,
            AppApiDO aggregate, String reason) {
        return persist(command, hash, aggregate,
                "STALE_REVISION".equals(reason) ? STALE : REJECTED, reason);
    }

    /**
     * 保存来源命令的内容摘要、目标状态及可重复读取的结果回执。
     *
     * @param command 待处理命令
     * @param hash 摘要
     * @param aggregate 聚合
     * @param outcome 结果
     * @param reason 操作原因
     * @return 供应授权来源结果
     */
    private SupplyGrantSourceResultDTO persist(SupplyGrantSourceCommandDTO command, String hash,
            AppApiDO aggregate, SupplyGrantSourceResultDTO.Outcome outcome, String reason) {
        var result = new SupplyGrantSourceResultDTO(command.operationId(), outcome, reason,
                command.sourceRevision(), revision(aggregate), hash);
        operationMapper.insertSourceReceipt(SupplyGrantOpDO.builder()
                .sourceSystem(command.sourceSystem()).operationId(command.operationId())
                .payloadHash(hash).aggregateKey(command.appId() + ":" + command.apiId())
                .targetStatus(switch (command.action()) {
                    case GRANT -> 1;
                    case SUSPEND -> 2;
                    case REVOKE -> 3;
                })
                .resultStatus(outcome.name()).grantId(aggregate == null ? null : aggregate.getId())
                .errorCode(reason).protocolVersion(2).sourceRevision(command.sourceRevision())
                .aggregateRevision(revision(aggregate)).commandType(command.action().name())
                .responseJson(JsonUtils.toJsonString(result)).build());
        return result;
    }

    /**
     * 核对原命令内容摘要，一致时返回持久回执，否则拒绝操作编号复用。
     *
     * @param receipt 回执
     * @param command 待处理命令
     * @param hash 摘要
     * @return 供应授权来源结果
     */
    private static SupplyGrantSourceResultDTO replay(SupplyGrantOpDO receipt,
            SupplyGrantSourceCommandDTO command, String hash) {
        if (!Objects.equals(receipt.getPayloadHash(), hash)) {
            return new SupplyGrantSourceResultDTO(command.operationId(), REJECTED, "OPERATION_CONFLICT",
                    command.sourceRevision(),
                    receipt.getAggregateRevision() == null ? 0 : receipt.getAggregateRevision(), hash);
        }
        return resultFromReceipt(receipt);
    }

    /**
     * 从受支持协议的持久响应恢复结果，旧协议或缺失响应明确返回未知状态。
     *
     * @param receipt 回执
     * @return 供应授权来源结果
     */
    private static SupplyGrantSourceResultDTO resultFromReceipt(SupplyGrantOpDO receipt) {
        if (!Integer.valueOf(2).equals(receipt.getProtocolVersion()) || receipt.getResponseJson() == null) {
            return new SupplyGrantSourceResultDTO(receipt.getOperationId(), UNKNOWN, "RECEIPT_UNCONFIRMED",
                    receipt.getSourceRevision() == null ? 0 : receipt.getSourceRevision(),
                    receipt.getAggregateRevision() == null ? 0 : receipt.getAggregateRevision(),
                    receipt.getPayloadHash());
        }
        return Objects.requireNonNull(
                JsonUtils.parseObject(receipt.getResponseJson(), SupplyGrantSourceResultDTO.class),
                "Invalid persisted source receipt");
    }

    /**
     * 从来源记录恢复领域状态，批准范围损坏时拒绝继续评估。
     *
     * @param source 来源数据
     * @return 授权来源状态
     */
    static GrantSourceState state(SupplyGrantSourceDO source) {
        // 批准范围包含主体和用途；复用静默解析，损坏时明确拒绝，不记录 JSON 原文。
        var scope = source.getScopeJson() == null ? null
                : JsonUtils.parseObjectQuietly(source.getScopeJson(), SupplyGrantSourceCommandDTO.Scope.class);
        if (source.getScopeJson() != null && scope == null) {
            throw new IllegalStateException("Invalid persisted grant scope");
        }
        return new GrantSourceState(source.getSourceSystem(), source.getSourceKey(), source.getSourceRevision(),
                source.getAppId(), source.getApiId(), GrantSourceState.Status.valueOf(source.getSourceStatus()),
                scope, source.getPayloadHash());
    }

    /**
     * 读取授权聚合修订号，不存在时返回零。
     *
     * @param aggregate 聚合
     * @return 修订号数值
     */
    private static long revision(AppApiDO aggregate) {
        return aggregate == null || aggregate.getAggregateRevision() == null ? 0 : aggregate.getAggregateRevision();
    }


    /**
     * 按指定事务传播行为构造带十秒超时的本地边界。
     *
     * @param propagation 传播
     * @return 事务模板
     */
    private TransactionTemplate boundary(int propagation) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        template.setTimeout(10);
        return template;
    }

    private record Proof(SupplyApiBindingRespDTO binding, String error) {

    }
}
