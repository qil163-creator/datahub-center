package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.ApprovalStatus;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceRow;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceContext;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_EXISTS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryEvidenceRespVO.Classification.*;

/**
 * 历史冻结命令、正式批准凭证与原始回执的只读关联。它不是复核批准、迁移或当前授权查询。
 * 权限 RPC 和 Supply RPC 均在本地事务外；返回前重读本地状态并重新校验对象权限。
 */
@Service
@RequiredArgsConstructor
public class MarketDeliveryEvidenceService {

    private final MarketDeliveryEvidenceMapper mapper;

    private final MarketObjectGuard objectGuard;

    private final SupplyGrantSourceApi grantSourceApi;

    private final PlatformTransactionManager transactionManager;

    private final MarketApprovalEvidenceMapper approvalMapper;

    /**
     * 读取交付操作当前核验证据。
     */
    public MarketDeliveryEvidenceRespVO inspect(String operationId) {
        return observe(operationId).evidence();
    }

    /** 仅供领域持久核查使用；不作为对外 DTO 或后续授权令牌。 */
    Observation observe(String operationId) {

        if (operationId == null || operationId.isBlank() || operationId.length() > 128) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
        // 不能只挂起调用方事务：挂起不会释放调用方已持有的锁。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("交付证据核对必须在本地事务外调用");
        }
        Caller caller = currentCaller();
        Snapshot before = readAuthorized(operationId, caller);
        Evaluation evaluation = inspectOriginal(before);
        assertSameCaller(caller);
        Snapshot after = readAuthorized(operationId, caller);
        if (!before.equals(after)) {
            // 丢弃旧回执及字段计数；调用者必须重新查询，不能用于后续批准。
            return new Observation(after, result(after, LOCAL_STATE_CHANGED, null, null).evidence(), null, null);
        }
        return new Observation(after, evaluation.evidence(), evaluation.receiptDigest(), evaluation.receipt());
    }

    /** 读取当前对象范围，不调用 Supply；权限 RPC 不能位于调用方事务中。 */
    Snapshot authorizeCurrent(String operationId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("交付核查必须在本地事务外调用");
        }
        return readAuthorized(operationId, currentCaller());
    }

    /** 人工核查写回必须仍属于入口调用者；不进行远端权限查询。 */
    void assertSameCaller(long actorUserId, Long actorDeptId) {
        assertSameCaller(new Caller(actorUserId, actorDeptId));
    }

    /**
     * 在调用方的短写事务中锁定并核对已授权快照；先申请/资产，再原操作，与核查待办锁序一致。
     * 此处只有本地查询，不能把权限或 Supply RPC 移入持锁区。
     */
    void assertSnapshotForWrite(Snapshot expected, long actorUserId, Long actorDeptId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("交付核查写回需要本地写事务");
        }
        assertSameCaller(actorUserId, actorDeptId);
        mapper.boundLockWait();
        var context = mapper.lockContext(expected.operation().getApplyId());
        var operation = mapper.lockOperation(expected.operation().getOperationId());
        var approval = approvalMapper.selectByApply(expected.operation().getApplyId());
        assertSameCaller(actorUserId, actorDeptId);
        if (!expected.equals(new Snapshot(operation, context, approval))) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 读取已授权。
     */
    private Snapshot readAuthorized(String operationId, Caller caller) {
        assertSameCaller(caller);
        Snapshot before = read(operationId);
        authorize(before);
        assertSameCaller(caller);
        // Guard 包含远端权限查询。返回后必须重读，不能把查询前的归属/绑定当作当前状态。
        // 两次读取各自使用短只读事务；权限 RPC 期间不持锁，也不因变化自动重试。
        Snapshot after = read(operationId);
        if (!before.equals(after)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return after;
    }

    /**
     * 读取当前登录用户和部门，建立证据核验调用人上下文。
     */
    private Caller currentCaller() {
        return new Caller(objectGuard.requireUserId(), objectGuard.currentDeptId());
    }

    /**
     * 校验市场交付证据的一致性调用人，不满足约束时拒绝当前操作。
     */
    private void assertSameCaller(Caller caller) {
        if (!caller.equals(currentCaller())) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 读取市场交付证据的业务数据。
     */
    private Snapshot read(String operationId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        tx.setTimeout(5);
        return tx.execute(ignored -> {
            MarketDeliveryOperationDO operation = mapper.selectOperation(operationId);
            if (operation == null || operation.getApplyId() == null) {
                throw exception(MARKET_APPLY_NOT_EXISTS);
            }
            MarketDeliveryEvidenceContext context = mapper.selectContext(operation.getApplyId());
            if (context == null) {
                throw exception(MARKET_APPLY_NOT_EXISTS);
            }
            return new Snapshot(operation, context, approvalMapper.selectByApply(operation.getApplyId()));
        });
    }

    /**
     * 依据交付证据冻结的资产与负责部门，核验当前用户具有申请授权权限。
     */
    private void authorize(Snapshot snapshot) {
        MarketDeliveryEvidenceContext context = snapshot.context();
        objectGuard.assertCanAuthApply(MarketResourceApplyDO.builder().id(context.getApplyId()).build(),
                DataAssetDO.builder().id(context.getAssetId()).ownerDeptId(context.getOwnerDeptId())
                        .techLeaderUserId(context.getTechLeaderUserId())
                        .businessLeaderUserId(context.getBusinessLeaderUserId()).build());
    }

    /**
     * 检查原始。
     */
    private Evaluation inspectOriginal(Snapshot snapshot) {
        MarketDeliveryOperationDO operation = snapshot.operation();
        if (!Integer.valueOf(2).equals(operation.getProtocolVersion())) {
            return result(snapshot, LEGACY_REVIEW_REQUIRED, null, null);
        }
        SupplyGrantSourceCommandDTO command;
        try {
            command = MarketDeliveryPayload.fromJson(operation.getPayloadJson());
        } catch (RuntimeException unusable) {
            return result(snapshot, FROZEN_COMMAND_UNAVAILABLE, null, null);
        }
        if (!MarketDeliveryPayload.matchesFrozenOperation(operation, command)) {
            return result(snapshot, FROZEN_COMMAND_CONFLICT, null, null);
        }
        if (!Objects.equals(snapshot.context().getAppId(), operation.getAppId())
                || !Objects.equals(snapshot.context().getApiId(), operation.getApiId())) {
            return result(snapshot, LOCAL_BINDING_CONFLICT, null, null);
        }
        SupplyGrantSourceResultDTO receipt;
        try {
            receipt = grantSourceApi.querySourceOperation(command.sourceSystem(), command.operationId())
                    .getCheckedData();
        } catch (RuntimeException unavailable) {
            // 不透出异常信息，尤其是连接信息、对端响应或未经匹配的证据。
            return result(snapshot, RECEIPT_UNAVAILABLE, command, null);
        }
        if (receipt == null) {
            return result(snapshot, RECEIPT_UNAVAILABLE, command, null);
        }
        if (!command.operationId().equals(receipt.operationId())) {
            return result(snapshot, RECEIPT_CONFLICT, command, receipt);
        }
        if (receipt.outcome() == null) {
            return result(snapshot, RECEIPT_UNAVAILABLE, command, receipt);
        }
        if (receipt.outcome() == SupplyGrantSourceResultDTO.Outcome.NOT_FOUND) {
            return result(snapshot, RECEIPT_NOT_FOUND, command, receipt);
        }
        if (receipt.outcome() == SupplyGrantSourceResultDTO.Outcome.UNKNOWN && receipt.payloadHash() == null) {
            return result(snapshot, RECEIPT_UNKNOWN, command, receipt);
        }
        if (!Objects.equals(operation.getPayloadHash(), receipt.payloadHash())
                || command.sourceRevision() != receipt.sourceRevision()) {
            return result(snapshot, RECEIPT_CONFLICT, command, receipt);
        }
        var classification = switch (receipt.outcome()) {
            case APPLIED -> ORIGINAL_APPLIED;
            case STALE -> ORIGINAL_STALE;
            case REJECTED -> ORIGINAL_REJECTED;
            case UNKNOWN -> RECEIPT_UNKNOWN;
            case NOT_FOUND -> RECEIPT_NOT_FOUND;
        };
        return result(snapshot, classification, command, receipt);
    }

    /**
     * 组装市场交付证据处理结果。
     */
    private static Evaluation result(Snapshot snapshot,
            MarketDeliveryEvidenceRespVO.Classification classification,
            SupplyGrantSourceCommandDTO command, SupplyGrantSourceResultDTO receipt) {
        var operation = snapshot.operation();
        var evidence = new MarketDeliveryEvidenceRespVO(operation.getApplyId(), operation.getOperationId(),
                operation.getSourceRevision(), classification, command == null ? null : command.action().name(),
                command == null || command.scope() == null ? null : command.scope().approvedColumns().size(),
                receipt == null || receipt.outcome() == null || classification == RECEIPT_CONFLICT
                        ? null : receipt.outcome().name(), approvalStatus(snapshot, command));
        return new Evaluation(evidence, receipt == null ? null : DigestUtil.sha256Hex(JsonUtils.toJsonString(receipt)),
                classification == RECEIPT_CONFLICT ? null : receipt);
    }

    /**
     * 比对授权命令与批准凭证，区分匹配、缺失、冲突和无法核验。
     */
    private static ApprovalStatus approvalStatus(Snapshot snapshot, SupplyGrantSourceCommandDTO command) {
        // 命令不可验证时不能断言凭证缺失，更不能根据 APPLIED 回执推断批准存在。
        if (command == null) {
            return ApprovalStatus.UNVERIFIABLE;
        }
        if (command.action() != SupplyGrantSourceCommandDTO.Action.GRANT) {
            return ApprovalStatus.NOT_APPLICABLE;
        }
        Long assetId = snapshot.context().getAssetId();
        if (assetId == null || assetId <= 0) {
            return ApprovalStatus.UNVERIFIABLE;
        }
        var approval = snapshot.approval();
        if (approval == null) {
            return ApprovalStatus.MISSING;
        }
        return Objects.equals(assetId, approval.getApprovalAssetId())
                && Objects.equals(command.scope().approvalRef(), approval.getApprovalRef())
                && Objects.equals(MarketDeliveryPayload.approvalHash(assetId, command), approval.getApprovalScopeHash())
                ? ApprovalStatus.MATCHED : ApprovalStatus.CONFLICT;
    }

    record Snapshot(MarketDeliveryOperationDO operation, MarketDeliveryEvidenceContext context,
                    MarketApprovalEvidenceRow approval) {

    }

    /** receipt 仅为已与冻结命令匹配的原回执（冲突时为空），只供领域处置使用，不回传浏览器。 */
    record Observation(Snapshot snapshot, MarketDeliveryEvidenceRespVO evidence, String receiptDigest,
                       SupplyGrantSourceResultDTO receipt) {

    }

    private record Caller(Long userId, Long departmentId) {

    }

    private record Evaluation(MarketDeliveryEvidenceRespVO evidence, String receiptDigest,
                              SupplyGrantSourceResultDTO receipt) {

    }
}
