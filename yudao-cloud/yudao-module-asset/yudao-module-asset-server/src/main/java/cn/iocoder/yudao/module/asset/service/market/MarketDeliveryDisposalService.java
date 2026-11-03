package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalBatchReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalBatchRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryDisposalReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.ApprovalStatus;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewRow;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.Classification.LOCAL_STATE_CHANGED;
import static cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.Classification.ORIGINAL_APPLIED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_DELIVERY_DISPOSAL_DENIED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_OPERATION_NOT_PERSISTED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;

/**
 * 历史交付的实际处置。只处理仍待核查（REVIEW_REQUIRED 或 MANUAL/UNKNOWN）的原操作：
 * <ul>
 *     <li>REVOKE：沿用普通收回链路真实撤销申请授权并冻结新的收回命令，不创建新申请；</li>
 *     <li>CONFIRM_ORIGINAL：仅当原 GRANT 回执已按冻结命令生效、正式批准凭证匹配且申请仍有效时，
 *     把原操作账本确认为成功；不重发命令、不扩大授权，旧协议或缺批准凭证一律拒绝。</li>
 * </ul>
 * 处置必须绑定此前已记录的核查决定（DELIVERY_REVIEW_DECISION），服务端复验其属于同一原操作、
 * 未被删除，并比对决定中的证据快照与当前证据一致后才执行；不一致必须先重新记录核查决定。
 * 权限与 Supply 查询在锁外完成；本地写在短事务内按 申请/资产 → 原操作 加锁、复验证据未变后，与处置审计一起提交。
 */
@Service
@RequiredArgsConstructor
public class MarketDeliveryDisposalService {

    private static final String EVIDENCE_DOMAIN = "delivery-disposal-evidence/v1:";

    /** 核查决定证据中证明“当时证据与版本”的关键字段；处置前逐项与当前证据比对。 */
    private static final List<String> SNAPSHOT_KEYS = List.of(
            "sourceRevision", "operationRowVersion", "fromStatus", "resolutionStatus", "classification",
            "approvalStatus", "approvalEvidenceId", "approvalEvidenceDigest", "originalAction",
            "frozenFieldCount", "payloadHash", "payloadDigest", "receiptDigest", "contextDigest");

    private final MarketDeliveryEvidenceService evidenceService;

    private final MarketDeliveryEvidenceMapper evidenceMapper;

    private final MarketDeliveryReviewMapper reviewMapper;

    private final MarketDeliveryOperationMapper operationMapper;

    private final MarketApprovalEvidenceMapper approvalMapper;

    private final MarketObjectGuard objectGuard;

    private final MarketApplyService applyService;

    private final PlatformTransactionManager transactionManager;

    /**
     * 按处置结论更新市场交付处置记录并保存审计信息。
     */
    public MarketDeliveryReviewRespVO dispose(MarketDeliveryDisposalReqVO request) {
        validate(request);

        Long actor = objectGuard.requireUserId();
        Long department = objectGuard.currentDeptId();
        String reason = request.reason().strip();
        String basis = request.basis().strip();
        String requestHash = DigestUtil.sha256Hex(JsonUtils.toJsonString(
                List.of("delivery-disposal-request/v1", actor, request.operationId(), request.action().name(), reason,
                        basis,
                        request.basisReviewRequestId())));
        var authorized = evidenceService.authorizeCurrent(request.operationId());
        var previous = reviewMapper.selectDisposalRequest(request.requestId());
        assertSameCaller(actor, department);
        if (previous != null) {
            // 回执丢失后的重试只返回首次结果；授权读取期间归属或原操作变化同样拒绝。
            var current = evidenceService.authorizeCurrent(request.operationId());
            if (!authorized.context().equals(current.context())
                    || !authorized.operation().getId().equals(current.operation().getId())) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            assertSameCaller(actor, department);
            return MarketDeliveryReviewService.replay(previous, requestHash);
        }

        // 绑定核查决定：属于同一原操作、未被删除、证据可解析；快照一致性在取证后比对。
        var basisDecision = reviewMapper.selectRequest(request.basisReviewRequestId());
        String basisEvidence = basisDecision == null ? null
                : reviewMapper.selectDecisionEvidence(request.basisReviewRequestId());
        Map<String, Object> basisSnapshot = parseEvidence(basisEvidence);
        if (basisDecision == null || basisDecision.getDeleted() == null || basisDecision.getDeleted() != 0
                || !Objects.equals(basisDecision.getOperationId(), request.operationId())
                || basisSnapshot == null) {
            throw exception(MARKET_DELIVERY_DISPOSAL_DENIED);
        }

        var observation = evidenceService.observe(request.operationId());
        var snapshot = observation.snapshot();
        var operation = snapshot.operation();
        if (observation.evidence().classification() == LOCAL_STATE_CHANGED || !pendingReview(operation)) {
            throw exception(MARKET_DELIVERY_DISPOSAL_DENIED);
        }
        // 决定记录后证据若已变化，旧决定不再能作为本次处置依据，必须先重新记录核查决定。
        Map<String, Object> currentEvidence = MarketDeliveryReviewService.evidence(observation, basis);
        if (!Objects.equals(snapshotFingerprint(basisSnapshot), snapshotFingerprint(currentEvidence))) {
            throw exception(MARKET_DELIVERY_DISPOSAL_DENIED);
        }
        String resultRef = null;
        if (request.action() == MarketDeliveryDisposalReqVO.Action.CONFIRM_ORIGINAL) {
            resultRef = confirmableReceipt(observation);
        } else if (!Objects.equals(snapshot.context().getStatus(), MarketResourceApplyDO.STATUS_APPROVED)) {
            // 已收回、到期或驳回的申请不再持有可撤销授权；不在此伪造第二次收回。
            throw exception(MARKET_DELIVERY_DISPOSAL_DENIED);
        }
        assertSameCaller(actor, department);

        var row = new MarketDeliveryReviewRow();
        row.setRequestId(request.requestId());
        row.setOperationId(request.operationId());
        row.setRequestHash(requestHash);
        row.setDecision(request.action().name());
        row.setActorUserId(actor);
        row.setActorDeptId(department);
        row.setReason(reason);
        Map<String, Object> evidence = MarketDeliveryReviewService.evidence(observation, basis);
        evidence.put("disposalAction", request.action().name());
        // 处置与核查决定的绑定：决定编号、结论、记录行号及当时证据摘要。
        evidence.put("basisReviewRequestId", request.basisReviewRequestId());
        evidence.put("basisReviewDecision", basisDecision.getDecision());
        evidence.put("basisReviewRecordId", basisDecision.getId());
        evidence.put("basisReviewEvidenceHash", basisSnapshot.get("evidenceHash"));
        String confirmedRef = resultRef;
        String frozenBasisEvidence = basisEvidence;

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        return tx.execute(ignored -> {
            evidenceMapper.boundLockWait();
            // 申请/资产 → 原操作；锁后只做本地复验、状态变更与追加审计，不再调用权限或远端。
            var context = evidenceMapper.lockContext(operation.getApplyId());
            var locked = evidenceMapper.lockOperation(request.operationId());
            var approval = approvalMapper.selectByApply(operation.getApplyId());
            var concurrent = reviewMapper.selectDisposalRequest(request.requestId());
            if (concurrent != null) {
                return MarketDeliveryReviewService.replay(concurrent, requestHash);
            }
            if (!Objects.equals(context, snapshot.context()) || !Objects.equals(locked, operation)
                    || !Objects.equals(approval, snapshot.approval())) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            // 锁内再次复验核查决定未被删除或改写，证据与取证时一致。
            var basisNow = reviewMapper.selectRequest(request.basisReviewRequestId());
            if (basisNow == null || basisNow.getDeleted() == null || basisNow.getDeleted() != 0
                    || !Objects.equals(basisNow.getOperationId(), request.operationId())
                    || !Objects.equals(frozenBasisEvidence,
                    reviewMapper.selectDecisionEvidence(request.basisReviewRequestId()))) {
                throw exception(MARKET_DELIVERY_DISPOSAL_DENIED);
            }
            assertSameCaller(actor, department);
            String toStatus;
            if (request.action() == MarketDeliveryDisposalReqVO.Action.CONFIRM_ORIGINAL) {
                var latest = operationMapper.selectLatestByApply(operation.getApplyId());
                // 只确认当前最新的原操作；已有更新来源版本时它不再代表当前交付。
                if (latest == null || !Objects.equals(latest.getId(), operation.getId())) {
                    throw exception(MARKET_DELIVERY_DISPOSAL_DENIED);
                }
                if (operationMapper.confirmDisposedGrant(locked, confirmedRef,
                        "人工处置：已核对原回执及正式批准") != 1) {
                    throw exception(MARKET_APPLY_STATUS_DENY);
                }
                toStatus = MarketDeliveryOperationDO.STATUS_SUCCESS;
            } else {
                applyService.revokeForDisposal(operation.getApplyId(), reason);
                var latest = operationMapper.selectLatestByApply(operation.getApplyId());
                if (latest != null && !Objects.equals(latest.getId(), operation.getId())
                        && latest.getSourceRevision() != null && operation.getSourceRevision() != null
                        && latest.getSourceRevision() > operation.getSourceRevision()) {
                    evidence.put("resultingOperationId", latest.getOperationId());
                    evidence.put("resultingSourceRevision", latest.getSourceRevision());
                }
                // 原操作本身保持待核查，由更高来源版本的收回命令覆盖。
                toStatus = operation.getStatus();
            }
            String evidenceJson = MarketDeliveryReviewService.seal(evidence, EVIDENCE_DOMAIN);
            if (reviewMapper.insertDisposal(row, operation.getId(), operation.getStatus(), toStatus,
                    evidenceJson, String.valueOf(actor)) != 1) {
                throw exception(MARKET_OPERATION_NOT_PERSISTED);
            }
            var saved = reviewMapper.selectDisposalRequest(request.requestId());
            if (saved == null) {
                throw exception(MARKET_OPERATION_NOT_PERSISTED);
            }
            return MarketDeliveryReviewService.replay(saved, requestHash);
        });
    }

    /**
     * 批量处置：每条独立短事务、独立审计与幂等，部分成功不整体回滚；同批不允许重复原操作。
     * 形状错误整体拒绝；单条业务失败只记入该条目结果。
     */
    public MarketDeliveryDisposalBatchRespVO disposeBatch(MarketDeliveryDisposalBatchReqVO request) {
        if (request == null || request.items() == null || request.items().isEmpty() || request.items().size() > 20) {
            throw new IllegalArgumentException("批量处置每批至少一条、最多二十条");
        }

        objectGuard.requireUserId();
        for (MarketDeliveryDisposalBatchReqVO.Item item : request.items()) {
            validate(new MarketDeliveryDisposalReqVO(item.requestId(), item.operationId(), item.action(),
                    item.reason(), item.basis(), item.basisReviewRequestId()));
        }
        if (request.items().stream().map(MarketDeliveryDisposalBatchReqVO.Item::operationId).distinct().count()
                != request.items().size()) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        List<MarketDeliveryDisposalBatchRespVO.ItemResult> results = new ArrayList<>();
        for (MarketDeliveryDisposalBatchReqVO.Item item : request.items()) {
            try {
                var result = dispose(new MarketDeliveryDisposalReqVO(item.requestId(), item.operationId(),
                        item.action(), item.reason(), item.basis(), item.basisReviewRequestId()));
                results.add(new MarketDeliveryDisposalBatchRespVO.ItemResult(
                        item.requestId(), item.operationId(), true, result, null, null));
            } catch (ServiceException failure) {
                results.add(new MarketDeliveryDisposalBatchRespVO.ItemResult(
                        item.requestId(), item.operationId(), false, null,
                        String.valueOf(failure.getCode()), failure.getMessage()));
            } catch (RuntimeException failure) {
                results.add(new MarketDeliveryDisposalBatchRespVO.ItemResult(
                        item.requestId(), item.operationId(), false, null, "SYSTEM_ERROR",
                        "处置未确认，请稍后核对交付账本与核查历史后重试"));
            }
        }
        return new MarketDeliveryDisposalBatchRespVO(results);
    }

    /** 与核查入口相同的待核查范围；已确认/已覆盖的操作不再处置。 */
    private static boolean pendingReview(MarketDeliveryOperationDO operation) {
        return MarketDeliveryCoordinator.RESOLUTION_REVIEW_REQUIRED.equals(operation.getResolutionStatus())
                || MarketDeliveryOperationDO.STATUS_MANUAL.equals(operation.getStatus())
                && MarketDeliveryCoordinator.RESOLUTION_UNKNOWN.equals(operation.getResolutionStatus());
    }

    /** 有条件确认的证据门槛；不满足任一项即拒绝，不降级为部分确认。 */
    private static String confirmableReceipt(MarketDeliveryEvidenceService.Observation observation) {
        var snapshot = observation.snapshot();
        var context = snapshot.context();
        SupplyGrantSourceResultDTO receipt = observation.receipt();
        if (!Integer.valueOf(2).equals(snapshot.operation().getProtocolVersion())
                || !SupplyGrantSourceCommandDTO.Action.GRANT.name().equals(observation.evidence().originalAction())
                || observation.evidence().classification() != ORIGINAL_APPLIED
                || observation.evidence().approvalStatus() != ApprovalStatus.MATCHED
                || receipt == null || receipt.outcome() != SupplyGrantSourceResultDTO.Outcome.APPLIED
                || !Objects.equals(context.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                || !Objects.equals(context.getIfAuth(), MarketResourceApplyDO.AUTH_YES)
                || Objects.equals(context.getPaused(), 1)
                || !Objects.equals(context.getSourceRevision(), snapshot.operation().getSourceRevision())) {
            throw exception(MARKET_DELIVERY_DISPOSAL_DENIED);
        }
        return String.valueOf(receipt.aggregateRevision());
    }

    /**
     * 校验市场交付处置的一致性调用人，不满足约束时拒绝当前操作。
     */
    private void assertSameCaller(Long actor, Long department) {
        if (!Objects.equals(actor, objectGuard.requireUserId())
                || !Objects.equals(department, objectGuard.currentDeptId())) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 校验市场交付处置配置满足当前业务约束。
     */
    private static void validate(MarketDeliveryDisposalReqVO request) {
        if (request == null || request.requestId() == null
                || !request.requestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || request.operationId() == null || request.operationId().isBlank() || request.operationId()
                .length() > 128
                || request.action() == null || request.reason() == null || request.reason().isBlank()
                || request.reason().length() > 500 || request.basis() == null || request.basis().isBlank()
                || request.basis().length() > 500
                || request.basisReviewRequestId() == null
                || !request.basisReviewRequestId()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 解析市场交付处置的证据。
     */
    private static Map<String, Object> parseEvidence(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parseObject(json, Map.class);
        } catch (RuntimeException unusable) {
            return null;
        }
    }

    /**
     * 计算交付处置核验快照的稳定摘要。
     */
    private static String snapshotFingerprint(Map<String, Object> evidence) {
        Map<String, Object> subset = new LinkedHashMap<>();
        for (String key : SNAPSHOT_KEYS) {
            subset.put(key, evidence == null ? null : evidence.get(key));
        }
        return JsonUtils.toJsonString(subset);
    }
}
