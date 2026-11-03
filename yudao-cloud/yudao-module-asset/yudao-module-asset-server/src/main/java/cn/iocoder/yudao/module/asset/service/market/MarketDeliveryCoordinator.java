package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketAuditEventDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewRow;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_DELIVERY_REDELIVER_DENIED;

/**
 * 市场授权交付的领取与回写协调：领取、远端调用与完成各自是独立短事务，RPC 永远在事务之外。
 *
 * <p>超时或响应丢失时先按稳定 operationId 查询提供方回执，只有权威地确认“没有这次操作”才允许重发；
 * 未知结果保持 UNKNOWN 等待对账，不从当前聚合权限倒推历史操作结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDeliveryCoordinator {

    /** 交付结果的本地收敛状态，与提供方回执分开记录。 */
    public static final String RESOLUTION_CONFIRMED = "CONFIRMED";

    public static final String RESOLUTION_UNKNOWN = "UNKNOWN";

    public static final String RESOLUTION_REVIEW_REQUIRED = "REVIEW_REQUIRED";

    /** 原操作确定被更新来源版本覆盖；已收敛但不能证明当前授权生效。 */
    public static final String RESOLUTION_SUPERSEDED = "SUPERSEDED";

    private static final int MAX_ATTEMPTS = 5;

    private final MarketDeliveryOperationMapper operationMapper;

    private final MarketAuditEventMapper auditEventMapper;

    private final SupplyGrantSourceApi grantSourceApi;

    private final PlatformTransactionManager transactionManager;

    private final MarketDeliveryEvidenceService evidenceService;

    private final MarketDeliveryReviewMapper reviewMapper;

    private final MarketDeliveryEvidenceMapper evidenceMapper;

    /**
     * 领取一条待执行交付：成功时返回带 claimToken 与 rowVersion 的行；已被其他节点持有或未到重试时间返回空。
     */
    public Optional<MarketDeliveryOperationDO> claim(Long operationRowId, String claimToken) {
        if (operationRowId == null || claimToken == null || claimToken.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(shortTransaction().execute(ignored ->
                operationMapper.claim(operationRowId, claimToken)));
    }

    /**
     * 执行一条已领取的交付：解析冻结载荷 → （必要时）查询原回执 → 发送命令 → 回写结果。
     *
     * @return 是否成功回写（false 表示租约已被接管或版本已变化，本次结果必须丢弃）
     */
    public boolean execute(MarketDeliveryOperationDO claimed) {
        Objects.requireNonNull(claimed, "claimed");
        SupplyGrantSourceCommandDTO command;
        try {
            command = MarketDeliveryPayload.fromJson(claimed.getPayloadJson());
        } catch (RuntimeException unusable) {
            // 载荷不可用不是网络故障：保持未知并转人工，不重建命令。
            return complete(claimed, MarketDeliveryOperationDO.STATUS_MANUAL,
                    RESOLUTION_REVIEW_REQUIRED, null, "冻结载荷不可用，需人工复核");
        }
        if (!MarketDeliveryPayload.matchesFrozenOperation(claimed, command)) {
            // 数据库记录与冻结命令任一字段不一致时，绝不能按当前申请重建后投递。
            return complete(claimed, MarketDeliveryOperationDO.STATUS_MANUAL,
                    RESOLUTION_REVIEW_REQUIRED, null, "冻结载荷与交付记录不一致，需人工复核");
        }
        if (command.action() == SupplyGrantSourceCommandDTO.Action.GRANT
                && (command.scope().quota() == null ? operationMapper.unlimitedPolicy(claimed.getApplyId())
                : operationMapper.finitePolicy(claimed.getApplyId(),
                "CALLS".equals(command.scope().quota().metric()) ? 1 : 2, command.scope().quota().limit())) != 1) {
            return complete(claimed, MarketDeliveryOperationDO.STATUS_MANUAL,
                    RESOLUTION_REVIEW_REQUIRED, null, "冻结消费额度与申请批准不一致，禁止降为无限额交付；请复核原申请");
        }
        boolean retry = claimed.getAttemptCount() != null && claimed.getAttemptCount() > 1;
        SupplyGrantSourceResultDTO receipt;
        try {
            receipt = retry ? resend(command) : send(command);
        } catch (RuntimeException transportFailure) {
            log.warn("[execute][来源交付未确认 operationId({}) attempt({}) type({})]", claimed.getOperationId(),
                    claimed.getAttemptCount(), transportFailure.getClass().getSimpleName());
            boolean giveUp = claimed.getAttemptCount() != null && claimed.getAttemptCount() >= MAX_ATTEMPTS;
            return complete(claimed,
                    giveUp ? MarketDeliveryOperationDO.STATUS_MANUAL : MarketDeliveryOperationDO.STATUS_RETRY,
                    RESOLUTION_UNKNOWN, null, "远端结果未确认");
        }
        return converge(claimed, command, receipt);
    }

    /** 首次发送：直接执行命令，提供方按 operationId 保证幂等。 */
    private SupplyGrantSourceResultDTO send(SupplyGrantSourceCommandDTO command) {
        return command == null ? null : grantSourceApi.applySource(command).getCheckedData();
    }

    /**
     * 重试：必须先按原 operationId 查询权威回执。查询失败不允许重发（可能已经生效）；
     * 只有权威返回 NOT_FOUND 才说明提供方确实没有这次操作，可以重发同一条命令。
     */
    private SupplyGrantSourceResultDTO resend(SupplyGrantSourceCommandDTO command) {
        SupplyGrantSourceResultDTO known = grantSourceApi
                .querySourceOperation(command.sourceSystem(), command.operationId()).getCheckedData();
        if (known == null) {
            throw new IllegalStateException("来源操作查询未返回回执");
        }
        // 错配的 NOT_FOUND 不是原 operationId 的缺席证明，不能据此重发。
        if (!Objects.equals(known.operationId(), command.operationId())) {
            return known;
        }
        if (known.outcome() != SupplyGrantSourceResultDTO.Outcome.NOT_FOUND) {
            return known;
        }
        return grantSourceApi.applySource(command).getCheckedData();
    }

    /**
     * 根据交付回执推进市场交付本地状态收敛。
     */
    private boolean converge(MarketDeliveryOperationDO claimed, SupplyGrantSourceCommandDTO command,
            SupplyGrantSourceResultDTO receipt) {
        if (receipt != null && !matchesReceipt(claimed, command, receipt)) {
            return complete(claimed, MarketDeliveryOperationDO.STATUS_MANUAL,
                    RESOLUTION_REVIEW_REQUIRED, null, "提供方回执与交付操作不一致，需人工复核");
        }
        if (receipt == null || receipt.outcome() == null
                || receipt.outcome() == SupplyGrantSourceResultDTO.Outcome.UNKNOWN) {
            boolean giveUp = claimed.getAttemptCount() != null && claimed.getAttemptCount() >= MAX_ATTEMPTS;
            return complete(claimed,
                    giveUp ? MarketDeliveryOperationDO.STATUS_MANUAL : MarketDeliveryOperationDO.STATUS_RETRY,
                    RESOLUTION_UNKNOWN, null, "提供方结果未确认");
        }
        return switch (receipt.outcome()) {
            case APPLIED -> complete(claimed, MarketDeliveryOperationDO.STATUS_SUCCESS,
                    RESOLUTION_CONFIRMED, String.valueOf(receipt.aggregateRevision()), null);
            // STALE 是确定的“被更新版本覆盖”，不能呈现为本次授权已生效。
            case STALE -> complete(claimed, MarketDeliveryOperationDO.STATUS_SUCCESS,
                    RESOLUTION_SUPERSEDED, String.valueOf(receipt.aggregateRevision()), "原操作已被后续来源版本覆盖");
            // 确定拒绝不是网络故障，不进入无限重试。
            case REJECTED -> complete(claimed, MarketDeliveryOperationDO.STATUS_MANUAL,
                    RESOLUTION_CONFIRMED, null, "提供方拒绝：" + receipt.errorCode());
            case NOT_FOUND -> complete(claimed, MarketDeliveryOperationDO.STATUS_RETRY,
                    RESOLUTION_UNKNOWN, null, "提供方未记录该操作");
            case UNKNOWN -> complete(claimed, MarketDeliveryOperationDO.STATUS_RETRY,
                    RESOLUTION_UNKNOWN, null, "提供方结果未确认");
        };
    }

    /**
     * 比对回执。
     */
    private static boolean matchesReceipt(MarketDeliveryOperationDO row, SupplyGrantSourceCommandDTO command,
            SupplyGrantSourceResultDTO receipt) {
        if (!Objects.equals(row.getOperationId(), receipt.operationId())) {
            return false;
        }
        if (receipt.outcome() == SupplyGrantSourceResultDTO.Outcome.NOT_FOUND) {
            return true;
        }
        // 确定结果必须与原命令摘要及来源版本匹配；UNKNOWN 仍是未知，不凭缺失摘要确认成功。
        if (receipt.outcome() == SupplyGrantSourceResultDTO.Outcome.UNKNOWN) {
            return receipt.payloadHash() == null || Objects.equals(row.getPayloadHash(), receipt.payloadHash());
        }
        return Objects.equals(row.getPayloadHash(), receipt.payloadHash())
                && receipt.sourceRevision() == command.sourceRevision();
    }

    /** 回写结果：必须同时命中对象、领取令牌、行版本和未过期租约，否则丢弃本次结果。 */
    public boolean complete(MarketDeliveryOperationDO claimed, String status,
            String resolution, String resultRef, String error) {
        Integer updated = shortTransaction().execute(ignored -> operationMapper.completeClaim(
                claimed.getId(), claimed.getClaimToken(),
                claimed.getRowVersion() == null ? 0 : claimed.getRowVersion(), status, resolution, resultRef, error));
        return Integer.valueOf(1).equals(updated);
    }

    /**
     * 人工对账只查询提供方原操作回执，绝不重发。RPC 在事务外；回写与强制审计在同一短事务内。
     * 重新核对调用方身份与当前对象授权；RPC 后复验，持锁写回前再次核对同一份快照。
     */
    public boolean reconcileManualUnknown(MarketDeliveryOperationDO authorized,
            long actorUserId, Long actorDeptId, String note) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("交付人工核查必须在本地事务外调用");
        }
        if (authorized == null || authorized.getId() == null || authorized.getOperationId() == null || actorUserId <= 0
                || note == null || note.isBlank()) {
            return false;
        }
        evidenceService.assertSameCaller(actorUserId, actorDeptId);
        var before = evidenceService.authorizeCurrent(authorized.getOperationId());
        evidenceService.assertSameCaller(actorUserId, actorDeptId);
        MarketDeliveryOperationDO row = before.operation();
        if (!Objects.equals(row.getId(), authorized.getId())
                || !Objects.equals(row.getApplyId(), authorized.getApplyId())
                || !Objects.equals(row.getOperationId(), authorized.getOperationId())
                || !Objects.equals(row.getRowVersion(), authorized.getRowVersion())
                || !Integer.valueOf(2).equals(row.getProtocolVersion())
                || !MarketDeliveryOperationDO.STATUS_MANUAL.equals(row.getStatus())
                || !RESOLUTION_UNKNOWN.equals(row.getResolutionStatus())) {
            return false;
        }
        String status = MarketDeliveryOperationDO.STATUS_MANUAL;
        String resolution = RESOLUTION_UNKNOWN;
        String resultRef = null;
        String error = null;
        SupplyGrantSourceCommandDTO command = null;
        try {
            command = MarketDeliveryPayload.fromJson(row.getPayloadJson());
        } catch (RuntimeException unusable) {
            // 缺失的冻结命令不能从当前申请或聚合授权反推。
        }
        if (command == null || !MarketDeliveryPayload.matchesFrozenOperation(row, command)) {
            resolution = RESOLUTION_REVIEW_REQUIRED;
            error = "冻结载荷与交付记录不一致，需人工复核";
        } else {
            SupplyGrantSourceResultDTO receipt = null;
            try {
                receipt = grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())
                        .getCheckedData();
            } catch (RuntimeException transportFailure) {
                log.warn("[reconcileManualUnknown][原回执仍未确认 operationId({}) type({})]",
                        row.getOperationId(), transportFailure.getClass().getSimpleName());
            }
            if (receipt != null && !matchesReceipt(row, command, receipt)) {
                resolution = RESOLUTION_REVIEW_REQUIRED;
                error = "提供方回执与交付操作不一致，需人工复核";
            } else if (receipt == null || receipt.outcome() == null) {
                error = "提供方结果仍未确认；未重发原操作";
            } else {
                switch (receipt.outcome()) {
                    case APPLIED -> {
                        status = MarketDeliveryOperationDO.STATUS_SUCCESS;
                        resolution = RESOLUTION_CONFIRMED;
                        resultRef = String.valueOf(receipt.aggregateRevision());
                        error = null;
                    }
                    case STALE -> {
                        status = MarketDeliveryOperationDO.STATUS_SUCCESS;
                        resolution = RESOLUTION_SUPERSEDED;
                        resultRef = String.valueOf(receipt.aggregateRevision());
                        error = "原操作已被后续来源版本覆盖";
                    }
                    case REJECTED -> {
                        resolution = RESOLUTION_CONFIRMED;
                        error = "提供方拒绝：" + receipt.errorCode();
                    }
                    case NOT_FOUND -> error = "提供方未记录原操作；未自动重发";
                    case UNKNOWN -> error = "提供方结果仍未确认；未重发原操作";
                }
            }
        }
        evidenceService.assertSameCaller(actorUserId, actorDeptId);
        var after = evidenceService.authorizeCurrent(row.getOperationId());
        evidenceService.assertSameCaller(actorUserId, actorDeptId);
        if (!before.equals(after)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        String finalStatus = status;
        String finalResolution = resolution;
        String finalResultRef = resultRef;
        String finalError = error;
        TransactionTemplate writeTransaction = shortTransaction();
        writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        Integer updated = writeTransaction.execute(ignored -> {
            evidenceService.assertSnapshotForWrite(before, actorUserId, actorDeptId);
            int changed = operationMapper.reconcileManualUnknown(row, finalStatus,
                    finalResolution, finalResultRef, finalError);
            if (changed != 1) {
                return changed;
            }
            MarketAuditEventDO audit = MarketAuditEventDO.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("DELIVERY_RECHECK")
                    .actorUserId(actorUserId)
                    .deptId(actorDeptId)
                    .objectType("DELIVERY")
                    .objectId(row.getId())
                    .fromStatus(MarketDeliveryOperationDO.STATUS_MANUAL)
                    .toStatus(finalStatus)
                    .reason(note)
                    .result(finalResolution)
                    .occurredAt(LocalDateTime.now())
                    .build();
            if (auditEventMapper.insertDeliveryReview(audit) != 1) {
                throw new IllegalStateException("交付人工核查审计未持久化");
            }
            evidenceService.assertSameCaller(actorUserId, actorDeptId);
            return changed;
        });
        return Integer.valueOf(1).equals(updated);
    }

    /** afterCommit 时原连接仍可能绑定线程；领取/回写必须挂起它并独立提交，RPC 不在任何本地事务中。 */
    private TransactionTemplate shortTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
        return transaction;
    }

    /**
     * 受控重投：只允许冻结命令完整匹配且动作是撤销/暂停的 MANUAL/UNKNOWN 原操作，按原 operationId 重发一次。
     * 必须绑定核查决定（属于同一原操作、未删除）；重投意图与锁定状态同短事务写入唯一请求审计，
     * 远端 RPC 在事务外执行，收敛结果用 CAS 写回，未知结果保持 MANUAL/UNKNOWN。GRANT 一律拒绝。
     */
    public boolean redeliverManualUnknown(MarketDeliveryOperationDO authorized,
            long actorUserId, Long actorDeptId, String note,
            String requestId, String basisReviewRequestId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("交付受控重投必须在本地事务外调用");
        }
        if (authorized == null || authorized.getId() == null || authorized.getOperationId() == null || actorUserId <= 0
                || note == null || note.isBlank()
                || requestId == null || !requestId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || basisReviewRequestId == null
                || !basisReviewRequestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return false;
        }
        String requestHash = DigestUtil.sha256Hex(JsonUtils.toJsonString(
                List.of("delivery-redeliver-request/v1", actorUserId, authorized.getOperationId(), note,
                        basisReviewRequestId)));
        // 同请求重试优先回放首份意图（不重发、不依赖行当前状态）；同键改内容或换操作者拒绝。
        var existingIntent = reviewMapper.selectRedeliverRequest(requestId);
        if (existingIntent != null) {
            if (!Objects.equals(existingIntent.getRequestHash(), requestHash)
                    || !Objects.equals(existingIntent.getActorUserId(), actorUserId)) {
                throw exception(MARKET_DELIVERY_REDELIVER_DENIED);
            }
            return true;
        }
        evidenceService.assertSameCaller(actorUserId, actorDeptId);
        var before = evidenceService.authorizeCurrent(authorized.getOperationId());
        evidenceService.assertSameCaller(actorUserId, actorDeptId);
        MarketDeliveryOperationDO row = before.operation();
        if (!Objects.equals(row.getId(), authorized.getId())
                || !Objects.equals(row.getApplyId(), authorized.getApplyId())
                || !Objects.equals(row.getOperationId(), authorized.getOperationId())
                || !Objects.equals(row.getRowVersion(), authorized.getRowVersion())
                || !Integer.valueOf(2).equals(row.getProtocolVersion())
                || !MarketDeliveryOperationDO.STATUS_MANUAL.equals(row.getStatus())
                || !RESOLUTION_UNKNOWN.equals(row.getResolutionStatus())) {
            return false;
        }
        // 冻结命令必须完整匹配，且只允许收窄授权的撤销/暂停命令；GRANT 一律拒绝。
        SupplyGrantSourceCommandDTO command;
        try {
            command = MarketDeliveryPayload.fromJson(row.getPayloadJson());
        } catch (RuntimeException unusable) {
            return false;
        }
        if (command == null || command.action() == null
                || command.action() == SupplyGrantSourceCommandDTO.Action.GRANT) {
            throw exception(MARKET_DELIVERY_REDELIVER_DENIED);
        }
        if (!MarketDeliveryPayload.matchesFrozenOperation(row, command)) {
            throw exception(MARKET_DELIVERY_REDELIVER_DENIED);
        }
        // 绑定核查决定：属于同一原操作、未被删除。
        var basis = reviewMapper.selectRequest(basisReviewRequestId);
        if (basis == null || basis.getDeleted() == null || basis.getDeleted() != 0
                || !Objects.equals(basis.getOperationId(), row.getOperationId())) {
            throw exception(MARKET_DELIVERY_REDELIVER_DENIED);
        }
        evidenceService.assertSameCaller(actorUserId, actorDeptId);
        var after = evidenceService.authorizeCurrent(row.getOperationId());
        evidenceService.assertSameCaller(actorUserId, actorDeptId);
        if (!before.equals(after)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        var auditRow = new MarketDeliveryReviewRow();
        auditRow.setRequestId(requestId);
        auditRow.setOperationId(row.getOperationId());
        auditRow.setRequestHash(requestHash);
        auditRow.setDecision(command.action().name());
        auditRow.setActorUserId(actorUserId);
        auditRow.setActorDeptId(actorDeptId);
        auditRow.setReason(note);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("action", command.action().name());
        evidence.put("basisReviewRequestId", basisReviewRequestId);
        String evidenceJson = JsonUtils.toJsonString(evidence);
        TransactionTemplate writeTransaction = shortTransaction();
        writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        Boolean intentWritten = writeTransaction.execute(ignored -> {
            // 同请求重试优先回放首份意图（回放不依赖行当前状态），改内容拒绝；不重复重发。
            var existing = reviewMapper.selectRedeliverRequest(requestId);
            if (existing != null) {
                return Objects.equals(existing.getRequestHash(), requestHash);
            }
            var locked = evidenceMapper.lockOperation(row.getOperationId());
            if (locked == null || !Objects.equals(locked.getId(), row.getId())
                    || !Objects.equals(locked.getRowVersion(), row.getRowVersion())
                    || !Objects.equals(locked.getStatus(), MarketDeliveryOperationDO.STATUS_MANUAL)
                    || !Objects.equals(locked.getResolutionStatus(), RESOLUTION_UNKNOWN)) {
                return Boolean.FALSE; // 状态已变化，不重投
            }
            if (reviewMapper.insertRedeliver(auditRow, row.getId(),
                    MarketDeliveryOperationDO.STATUS_MANUAL, MarketDeliveryOperationDO.STATUS_MANUAL,
                    evidenceJson, String.valueOf(actorUserId)) != 1) {
                throw new IllegalStateException("受控重投意图审计未持久化");
            }
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(intentWritten)) {
            return false;
        }
        // 远端重发在事务外；提供方按同一 operationId 幂等，绝不重建或改写命令。
        SupplyGrantSourceResultDTO receipt;
        try {
            receipt = grantSourceApi.applySource(command).getCheckedData();
        } catch (RuntimeException transportFailure) {
            log.warn("[redeliverManualUnknown][重投远端未确认 operationId({}) type({})]", row.getOperationId(),
                    transportFailure.getClass().getSimpleName());
            // 原操作保持 MANUAL/UNKNOWN；已写意图审计，人工可再核对。
            return true;
        }
        String status = MarketDeliveryOperationDO.STATUS_MANUAL;
        String resolution = RESOLUTION_UNKNOWN;
        String resultRef = null;
        String error = null;
        if (receipt != null && !matchesReceipt(row, command, receipt)) {
            resolution = RESOLUTION_REVIEW_REQUIRED;
            error = "重投回执与交付操作不一致，需人工复核";
        } else if (receipt == null || receipt.outcome() == null) {
            error = "提供方结果仍未确认；未再次自动重发";
        } else {
            switch (receipt.outcome()) {
                case APPLIED -> {
                    status = MarketDeliveryOperationDO.STATUS_SUCCESS;
                    resolution = RESOLUTION_CONFIRMED;
                    resultRef = String.valueOf(receipt.aggregateRevision());
                }
                case STALE -> {
                    status = MarketDeliveryOperationDO.STATUS_SUCCESS;
                    resolution = RESOLUTION_SUPERSEDED;
                    resultRef = String.valueOf(receipt.aggregateRevision());
                    error = "原操作已被后续来源版本覆盖";
                }
                case REJECTED -> {
                    resolution = RESOLUTION_CONFIRMED;
                    error = "提供方拒绝：" + receipt.errorCode();
                }
                case NOT_FOUND -> error = "提供方未记录原操作；未再次自动重发";
                case UNKNOWN -> error = "提供方结果仍未确认；未再次自动重发";
            }
        }
        String finalStatus = status;
        String finalResolution = resolution;
        String finalResultRef = resultRef;
        String finalError = error;
        Integer changed = writeTransaction.execute(ignored ->
                operationMapper.redeliverConverge(row, finalStatus, finalResolution, finalResultRef, finalError));
        return true;
    }

    /** 按平台有界扫描；单条故障不阻塞本批其他操作，批末汇总失败，返回值只计成功回写。 */
    public int drainDue(String claimToken) {
        int handled = 0;
        int failed = 0;
        for (MarketDeliveryOperationDO due : operationMapper.selectDueV2()) {
            try {
                Optional<MarketDeliveryOperationDO> claimed = claim(due.getId(), claimToken);
                if (claimed.isPresent() && execute(claimed.get())) {
                    handled++;
                }
            } catch (RuntimeException failure) {
                // 领取/回写异常不能证明远端未执行；保持持久状态，等待原租约与原回执恢复路径。
                failed++;
                log.warn("[drainDue][交付处理未完成 operationRowId({}) type({})]", due.getId(),
                        failure.getClass().getSimpleName());
            }
        }
        if (failed > 0) {
            log.warn("[drainDue][本批处理部分失败 completed({}) failed({})]", handled, failed);
            throw new IllegalStateException("交付重试部分失败：已回写 " + handled + " 条，失败 " + failed + " 条");
        }
        return handled;
    }

}
