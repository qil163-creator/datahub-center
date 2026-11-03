package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewPageRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryReviewRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.ApprovalStatus;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.Classification.LOCAL_STATE_CHANGED;

/** 持久核查结论只是待办决策记录，不改变交付、来源、申请或实际权限。 */
@Service
@RequiredArgsConstructor
public class MarketDeliveryReviewService {

    private final MarketDeliveryEvidenceService evidenceService;

    private final MarketDeliveryEvidenceMapper evidenceMapper;

    private final MarketDeliveryReviewMapper reviewMapper;

    private final MarketObjectGuard objectGuard;

    private final PlatformTransactionManager transactionManager;

    private final MarketApprovalEvidenceMapper approvalMapper;

    /**
     * 保存市场交付复核操作记录。
     */
    public MarketDeliveryReviewRespVO record(MarketDeliveryReviewReqVO request) {
        validate(request);

        Long actor = objectGuard.requireUserId();
        Long department = objectGuard.currentDeptId();
        String requestHash = DigestUtil.sha256Hex(JsonUtils.toJsonString(
                List.of("delivery-review-request/v1", actor, request.operationId(), request.decision().name(),
                        request.reason().strip(), request.basis().strip())));
        var authorized = evidenceService.authorizeCurrent(request.operationId());
        var previous = reviewMapper.selectRequest(request.requestId());
        assertSameCaller(actor, department);
        if (previous != null) {
            var current = evidenceService.authorizeCurrent(request.operationId());
            if (!authorized.context().equals(current.context())
                    || !authorized.operation().getId().equals(current.operation().getId())) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            assertSameCaller(actor, department);
            return replay(previous, requestHash);
        }

        var observation = evidenceService.observe(request.operationId());
        var operation = observation.snapshot().operation();
        if (observation.evidence().classification() == LOCAL_STATE_CHANGED
                || !("REVIEW_REQUIRED".equals(operation.getResolutionStatus())
                || "MANUAL".equals(operation.getStatus()) && "UNKNOWN".equals(operation.getResolutionStatus()))
                || !Objects.equals(actor, objectGuard.requireUserId())
                || !Objects.equals(department, objectGuard.currentDeptId())) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        var row = new MarketDeliveryReviewRow();
        row.setRequestId(request.requestId());
        row.setOperationId(request.operationId());
        row.setRequestHash(requestHash);
        row.setDecision(request.decision().name());
        row.setActorUserId(actor);
        row.setActorDeptId(department);
        row.setReason(request.reason().strip());
        String evidenceJson = summarize(observation, request.basis().strip());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
        return tx.execute(ignored -> {
            evidenceMapper.boundLockWait();
            // 先申请/资产，再原操作；只做本地有界复验及追加审计，不在持锁期间查询权限或远端。
            var context = evidenceMapper.lockContext(operation.getApplyId());
            var locked = evidenceMapper.lockOperation(request.operationId());
            var approval = approvalMapper.selectByApply(operation.getApplyId());
            if (!Objects.equals(context, observation.snapshot().context()) || !Objects.equals(locked, operation)
                    || !Objects.equals(approval, observation.snapshot().approval())) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            // 多节点相同请求竞争由唯一约束裁决；后提交者返回同一已保存结果，不覆盖首份证据。
            reviewMapper.insertDecision(row, operation.getId(), operation.getStatus(), evidenceJson);
            var saved = reviewMapper.selectRequest(request.requestId());
            if (saved == null) {
                throw new IllegalStateException("交付核查审计未持久化");
            }
            return replay(saved, requestHash);
        });
    }

    /**
     * 读取市场交付复核操作及复核历史。
     */
    public MarketDeliveryReviewPageRespVO history(String operationId, long afterId) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 128 || afterId < 0) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }

        Long actor = objectGuard.requireUserId();
        Long department = objectGuard.currentDeptId();
        var before = evidenceService.authorizeCurrent(operationId);
        var rows = reviewMapper.selectHistory(operationId, afterId);
        assertSameCaller(actor, department);
        var after = evidenceService.authorizeCurrent(operationId);
        if (!before.context().equals(after.context()) || !before.operation().getId()
                .equals(after.operation().getId())) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        assertSameCaller(actor, department);
        boolean hasMore = rows.size() > 50;
        var items = rows.stream().limit(50).map(MarketDeliveryReviewService::toResponse).toList();
        return new MarketDeliveryReviewPageRespVO(items, hasMore ? items.getLast().id() : null, hasMore);
    }

    /**
     * 校验市场交付复核的一致性调用人，不满足约束时拒绝当前操作。
     */
    private void assertSameCaller(Long actor, Long department) {
        if (!Objects.equals(actor, objectGuard.requireUserId())
                || !Objects.equals(department, objectGuard.currentDeptId())) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 按稳定请求标识读取市场交付复核已有复核结果。
     */
    static MarketDeliveryReviewRespVO replay(MarketDeliveryReviewRow row, String hash) {
        if (!Objects.equals(hash, row.getRequestHash()) || row.getDeleted() == null || row.getDeleted() != 0) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return toResponse(row);
    }

    /**
     * 将市场交付复核业务记录转换为接口响应。
     */
    private static MarketDeliveryReviewRespVO toResponse(MarketDeliveryReviewRow row) {
        return new MarketDeliveryReviewRespVO(row.getId(), row.getRequestId(), row.getOperationId(), row.getApplyId(),
                row.getSourceRevision(), row.getDecision(), row.getClassification(), row.getActorUserId(),
                row.getActorDeptId(), row.getReason(), row.getBasis(), row.getOccurredAt(),
                ApprovalStatus.fromStored(row.getApprovalStatus()),
                "DELIVERY_DISPOSAL".equals(row.getEventType())
                        ? MarketDeliveryReviewRespVO.KIND_DISPOSAL : MarketDeliveryReviewRespVO.KIND_DECISION,
                row.getResultingOperationId());
    }

    /**
     * 汇总交付观察结果与复核依据，并生成证据摘要。
     */
    private static String summarize(MarketDeliveryEvidenceService.Observation observation, String basis) {
        return seal(evidence(observation, basis), "delivery-review-evidence/v1:");
    }

    /** 核查与处置共用的有界证据摘要；不含冻结载荷、字段名、字段值或密钥。 */
    static Map<String, Object> evidence(MarketDeliveryEvidenceService.Observation observation, String basis) {
        var operation = observation.snapshot().operation();
        var result = observation.evidence();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("operationId", operation.getOperationId());
        evidence.put("applyId", operation.getApplyId());
        evidence.put("appId", operation.getAppId());
        evidence.put("apiId", operation.getApiId());
        evidence.put("sourceRevision", operation.getSourceRevision());
        evidence.put("sourceGrantId", operation.getSourceGrantId());
        evidence.put("protocolVersion", operation.getProtocolVersion());
        evidence.put("operationRowVersion", operation.getRowVersion());
        evidence.put("fromStatus", operation.getStatus());
        evidence.put("toStatus", operation.getStatus());
        evidence.put("resolutionStatus", operation.getResolutionStatus());
        evidence.put("classification", result.classification().name());
        // v1 证据的加法式扩展：历史记录不回填、不按今天的批准凭证重算。
        evidence.put("approvalStatus", result.approvalStatus().name());
        var approval = observation.snapshot().approval();
        evidence.put("approvalEvidenceId", approval == null ? null : approval.getId());
        evidence.put("approvalEvidenceDigest",
                approval == null ? null : DigestUtil.sha256Hex(JsonUtils.toJsonString(approval)));
        evidence.put("originalAction", result.originalAction());
        evidence.put("frozenFieldCount", result.frozenFieldCount());
        evidence.put("payloadHash", operation.getPayloadHash());
        evidence.put("payloadDigest",
                operation.getPayloadJson() == null ? null : DigestUtil.sha256Hex(operation.getPayloadJson()));
        evidence.put("receiptDigest", observation.receiptDigest());
        evidence.put("contextDigest", DigestUtil.sha256Hex(JsonUtils.toJsonString(observation.snapshot().context())));
        // 操作者提交的依据仅为待办说明，未经本接口验证为正式审批，不能凭此恢复授权。
        evidence.put("basis", basis);
        return evidence;
    }

    /**
     * 为规范化复核证据附加摘要并序列化保存。
     */
    static String seal(Map<String, Object> evidence, String domain) {
        evidence.put("evidenceHash", DigestUtil.sha256Hex(domain + JsonUtils.toJsonString(evidence)));
        return JsonUtils.toJsonString(evidence);
    }

    /**
     * 校验市场交付复核配置满足当前业务约束。
     */
    private static void validate(MarketDeliveryReviewReqVO request) {
        if (request == null || request.requestId() == null
                || !request.requestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || request.operationId() == null || request.operationId().isBlank() || request.operationId()
                .length() > 128
                || request.decision() == null || request.reason() == null || request.reason()
                .isBlank() || request.reason().length() > 500
                || request.basis() == null || request.basis().isBlank() || request.basis().length() > 500) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }
}
