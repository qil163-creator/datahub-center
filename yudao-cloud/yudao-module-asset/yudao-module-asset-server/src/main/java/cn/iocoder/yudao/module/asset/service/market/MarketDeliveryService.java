package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetGrantSourceDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_SCOPE_UNCONFIRMED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;

/**
 * 市场授权的持久交付入口：在申请事务内写入不可变交付意图与来源状态，提交后才由协调器执行远端命令。
 *
 * <p>这里不再按当前申请实时组装远端请求，也不再因“还有其他来源”跳过撤销：
 * 逐来源撤销由提供方按来源账本重算，本地只负责可靠投递自己的那条来源命令。
 */
@Slf4j
@Service
public class MarketDeliveryService {

    public static final int DELIVERY_NONE = 0;

    public static final int DELIVERY_PENDING = 1;

    public static final int DELIVERY_RUNNING = 2;

    public static final int DELIVERY_SUCCESS = 3;

    public static final int DELIVERY_RETRY = 4;

    public static final int DELIVERY_MANUAL = 5;

    public static final int DELIVERY_CANCELLED = 6;

    /** 当前 Supply 来源协议只支持个人主体的治理模式；其他模式必须另行审批，不在此隐式扩展。 */
    private static final String POLICY_MODE = "CURRENT_USER_GOVERNANCE";

    private static final String SOURCE_TYPE = "PERSONAL";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private MarketDeliveryOperationMapper operationMapper;

    @Resource
    private MarketResourceApplyMapper applyMapper;

    @Resource
    private AssetGrantSourceMapper grantSourceMapper;

    @Resource
    private MarketDeliveryCoordinator coordinator;

    @Resource
    private MarketApprovalEvidenceMapper approvalEvidenceMapper;

    /**
     * 生成与资产申请来源对应的稳定授权标识。
     */
    public String sourceGrantId(MarketResourceApplyDO apply) {
        int revision = apply.getRevision() == null ? 1 : apply.getRevision();
        return "apply:" + apply.getId() + ":r" + revision;
    }

    /** 来源身份在申请的整个生命周期内保持不变；撤销只是这条来源的新版本，不是新的来源。 */
    public String sourceKey(MarketResourceApplyDO apply) {
        return "asset:apply:" + apply.getId();
    }

    /**
     * 入队授权。
     */
    public void enqueueGrant(MarketResourceApplyDO apply) {
        enqueue(apply, MarketDeliveryOperationDO.TYPE_GRANT, SupplyGrantSourceCommandDTO.Action.GRANT);
    }

    /** 只由审批通过入口调用，批准凭证和首次交付与申请 CAS 同事务，不在恢复入口补造批准。 */
    public void enqueueApprovedGrant(MarketResourceApplyDO apply, Long actor, Long department, String note) {
        if (actor == null || actor <= 0 || !Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                || !Objects.equals(apply.getIfAuth(), MarketResourceApplyDO.AUTH_YES)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        enqueue(apply, MarketDeliveryOperationDO.TYPE_GRANT, SupplyGrantSourceCommandDTO.Action.GRANT,
                new ApprovalActor(actor, department, note));
    }

    /**
     * 入队撤回。
     */
    public void enqueueRevoke(MarketResourceApplyDO apply) {
        enqueue(apply, MarketDeliveryOperationDO.TYPE_REVOKE, SupplyGrantSourceCommandDTO.Action.REVOKE);
    }

    /**
     * 可恢复停用：保留批准范围与来源身份，只是暂停生效。
     * 不能用撤销代替——撤销是终态墓碑，恢复时无法复活同一条来源。
     */
    public void enqueueSuspend(MarketResourceApplyDO apply) {
        enqueue(apply, MarketDeliveryOperationDO.TYPE_REVOKE, SupplyGrantSourceCommandDTO.Action.SUSPEND);
    }

    /** 按平台执行到期的交付操作；由调度作业触发。 */
    public int retryPending() {
        return coordinator.drainDue(nodeToken());
    }

    /** 列表展示使用已确认的交付账本，不把申请上仅表示投递意图的 delivery_status 当作回执。 */
    public boolean isConfirmedApply(MarketResourceApplyDO apply) {
        if (apply == null || apply.getSourceWorkOrderId() != null || apply.getId() == null || apply.getId() <= 0) {
            return false;
        }

        MarketDeliveryOperationDO latest = operationMapper.selectLatestByApply(apply.getId());
        String resolution = currentDeliveryResolution(apply, latest);
        return resolution == null || "APPLIED".equals(resolution);
    }

    /**
     * 已确认回执必须属于当前申请的同一批准与来源版本。只用已有编码器比较语义，
     * 不重新发送命令、不补造批准，也不把历史成功等同于远端当前可访问。
     * 调用方一次读取最新账本，并用同一行展示 operationId、技术状态与本结果。
     */
    String currentDeliveryResolution(MarketResourceApplyDO apply, MarketDeliveryOperationDO latest) {
        if (apply == null || apply.getSourceWorkOrderId() != null || apply.getId() == null || apply.getId() <= 0) {
            return "UNKNOWN";
        }
        // 查询失败必须向上传播；不可被下面的坏载荷容错转换成无凭证的历史兼容。
        var approval = approvalEvidenceMapper.selectByApply(apply.getId());
        return currentDeliveryResolution(apply, latest, approval);
    }

    /** 列表以同一事务内批量取得的本地证据复用确认规则，不逐条执行额外查询。 */
    String currentDeliveryResolution(MarketResourceApplyDO apply, MarketDeliveryOperationDO latest,
            cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceRow approval) {
        if (apply == null || apply.getSourceWorkOrderId() != null || apply.getId() == null || apply.getId() <= 0) {
            return "UNKNOWN";
        }
        if (apply.getApiId() == null) {
            return apply.getAppId() == null && latest == null && approval == null ? null : "UNKNOWN";
        }
        if (apply.getAppId() == null) {
            return "UNKNOWN";
        }
        if (latest == null) {
            // 0042 给历史行补的是 0；正版本、有正式批准或非法负版本都不能借旧成功标志放行。
            if (approval != null || (apply.getSourceRevision() != null && apply.getSourceRevision() != 0)) {
                return "UNKNOWN";
            }
            return legacyDeliveryResolution(apply.getDeliveryStatus());
        }
        String resolution = deliveryResolution(latest);
        if (!"APPLIED".equals(resolution)) {
            return resolution;
        }
        int revision = apply.getRevision() == null ? 1 : apply.getRevision();
        if (revision <= 0 || apply.getSourceRevision() == null || apply.getSourceRevision() <= 0
                || !Objects.equals(latest.getProtocolVersion(), 2)
                || !Objects.equals(latest.getApplyId(), apply.getId())
                || !Objects.equals(latest.getExpectedRevision(), revision)
                || !Objects.equals(latest.getSourceRevision(), apply.getSourceRevision())
                || !Objects.equals(latest.getAppId(), apply.getAppId())
                || !Objects.equals(latest.getApiId(), apply.getApiId())
                || !Objects.equals(latest.getSourceGrantId(), sourceGrantId(apply))
                || !Objects.equals(apply.getSourceGrantId(), sourceGrantId(apply))
                || !Objects.equals(latest.getOperationId(),
                "asset:" + sourceKey(apply) + ":r" + apply.getSourceRevision() + ":GRANT")) {
            return "UNKNOWN";
        }
        try {
            var frozen = MarketDeliveryPayload.fromJson(latest.getPayloadJson());
            if (!MarketDeliveryPayload.matchesFrozenOperation(latest, frozen)
                    || apply.getAssetId() == null || apply.getAssetId() <= 0
                    || !Objects.equals(longValue(readSnapshot(apply.getSnapshot()).get("assetId")),
                    apply.getAssetId())) {
                return "UNKNOWN";
            }
            var current = buildCommand(apply, SupplyGrantSourceCommandDTO.Action.GRANT,
                    latest.getOperationId(), apply.getSourceRevision());
            if (!Objects.equals(latest.getPayloadHash(), MarketDeliveryPayload.hash(current))) {
                return "UNKNOWN";
            }
            if (approval != null && (!Objects.equals(approval.getApprovalRef(), sourceGrantId(apply))
                    || !Objects.equals(approval.getApprovalAssetId(), apply.getAssetId())
                    || !Objects.equals(approval.getApprovalScopeHash(),
                    MarketDeliveryPayload.approvalHash(apply.getAssetId(), current)))) {
                return "UNKNOWN";
            }
            return "APPLIED";
        } catch (RuntimeException invalidEvidence) {
            // 不泄露快照内容；损坏/缺失的冻结证据不能转换成批准成功。
            return "UNKNOWN";
        }
    }

    /**
     * 申请、资源与需求共用的交付呈现规则。执行终态与回执确认是两件事；
     * 取消仅表示本地停止交付，不能证明远端未生效，更不能当作无需交付。
     * 该规则不读取远端当前聚合，也不是实际访问授权凭证。
     */
    static String deliveryResolution(MarketDeliveryOperationDO latest) {
        if (latest == null) {
            return "UNKNOWN";
        }
        String resolution = latest.getResolutionStatus();
        if (MarketDeliveryCoordinator.RESOLUTION_SUPERSEDED.equals(resolution)) {
            return "SUPERSEDED";
        }
        if (!MarketDeliveryCoordinator.RESOLUTION_CONFIRMED.equals(resolution)
                && !"UNCONFIRMED".equals(resolution)) {
            return "UNKNOWN";
        }
        if (!MarketDeliveryOperationDO.TYPE_GRANT.equals(latest.getOpType())
                && !MarketDeliveryOperationDO.TYPE_REVOKE.equals(latest.getOpType())) {
            return "UNKNOWN";
        }
        return switch (StrUtil.nullToEmpty(latest.getStatus())) {
            case MarketDeliveryOperationDO.STATUS_SUCCESS ->
                    MarketDeliveryCoordinator.RESOLUTION_CONFIRMED.equals(resolution)
                            ? (MarketDeliveryOperationDO.TYPE_GRANT.equals(latest.getOpType())
                            ? "APPLIED" : switch (MarketDeliveryPayload.operationAction(latest)) {
                        case "SUSPEND" -> "SUSPENDED";
                        case "REVOKE" -> "REVOKED";
                        default -> "UNKNOWN";
                    }) : "UNKNOWN";
            case MarketDeliveryOperationDO.STATUS_MANUAL ->
                    MarketDeliveryCoordinator.RESOLUTION_CONFIRMED.equals(resolution)
                            ? "REJECTED" : "UNKNOWN";
            case MarketDeliveryOperationDO.STATUS_PENDING, MarketDeliveryOperationDO.STATUS_RUNNING,
                 MarketDeliveryOperationDO.STATUS_RETRY -> "UNCONFIRMED".equals(resolution)
                    ? "PENDING" : "UNKNOWN";
            default -> "UNKNOWN";
        };
    }

    /** 没有账本的历史兼容：保留明确的 NONE/SUCCESS，不把取消或未知代码推断成成功。 */
    static String legacyDeliveryResolution(Integer status) {
        if (Objects.equals(status, DELIVERY_NONE)) {
            return null;
        }
        if (Objects.equals(status, DELIVERY_SUCCESS)) {
            return "APPLIED";
        }
        if (Objects.equals(status, DELIVERY_PENDING) || Objects.equals(status, DELIVERY_RUNNING)
                || Objects.equals(status, DELIVERY_RETRY)) {
            return "PENDING";
        }
        return "UNKNOWN";
    }

    /** 与呈现结果使用同一条账本记录；申请的 deliveryStatus 继续保留原始投递意图。 */
    static int technicalDeliveryStatus(MarketDeliveryOperationDO latest) {
        return switch (StrUtil.nullToEmpty(latest.getStatus())) {
            case MarketDeliveryOperationDO.STATUS_PENDING -> DELIVERY_PENDING;
            case MarketDeliveryOperationDO.STATUS_RUNNING -> DELIVERY_RUNNING;
            case MarketDeliveryOperationDO.STATUS_SUCCESS -> DELIVERY_SUCCESS;
            case MarketDeliveryOperationDO.STATUS_RETRY -> DELIVERY_RETRY;
            case MarketDeliveryOperationDO.STATUS_CANCELLED -> DELIVERY_CANCELLED;
            default -> DELIVERY_MANUAL;
        };
    }

    /**
     * 保存待执行的市场交付请求。
     */
    private void enqueue(MarketResourceApplyDO apply, String type, SupplyGrantSourceCommandDTO.Action action) {
        enqueue(apply, type, action, null);
    }

    /**
     * 保存待执行的市场交付请求。
     */
    private void enqueue(MarketResourceApplyDO apply, String type, SupplyGrantSourceCommandDTO.Action action,
            ApprovalActor approval) {
        if (apply != null && apply.getSourceWorkOrderId() != null) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            // 来源状态与冻结操作必须与申请状态同事务提交；无事务时禁止部分落库或直接 RPC。
            throw new IllegalStateException("Market delivery enqueue requires an active transaction");
        }

        boolean revoke = action != SupplyGrantSourceCommandDTO.Action.GRANT;
        if (!revoke) {
            MarketQuotaPolicy.validate(apply.getApplyUseType(), apply.getApplyUseTimes());
        }
        long sourceRevision = persistGrantSource(apply, action, revoke);
        if (apply.getApiId() == null || apply.getAppId() == null) {
            if (!revoke && approvalEvidenceMapper.selectByApply(apply.getId()) != null) {
                // 有正式 API 批准后，不能通过清空 API/应用身份绕过原范围核对。
                throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
            }
            // 无 API 的个人预览申请只更新本地来源，不伪造远端成功回执。
            if (applyMapper.update(null, new LambdaUpdateWrapper<MarketResourceApplyDO>()
                    .eq(MarketResourceApplyDO::getId, apply.getId())
                    .set(MarketResourceApplyDO::getDeliveryStatus, DELIVERY_NONE)
                    .set(MarketResourceApplyDO::getSourceGrantId, sourceGrantId(apply))
                    .set(MarketResourceApplyDO::getSourceRevision, sourceRevision)) != 1) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            return;
        }
        String operationId = "asset:" + sourceKey(apply) + ":r" + sourceRevision + ":" + action.name();
        SupplyGrantSourceCommandDTO command = buildCommand(apply, action, operationId, sourceRevision);
        if (action == SupplyGrantSourceCommandDTO.Action.GRANT) {
            String scopeHash = MarketDeliveryPayload.approvalHash(apply.getAssetId(), command);
            if (approval != null) {
                // 失败必须抛出并回滚批准/来源/交付，不能套用普通尽力审计的吞异常行为。
                if (approvalEvidenceMapper.insertApproval(apply.getId(), apply.getAssetId(),
                        sourceGrantId(apply), scopeHash, approval.actor(), approval.department(),
                        approval.note()) != 1) {
                    throw exception(MARKET_APPLY_STATUS_DENY);
                }
            } else {
                var evidence = approvalEvidenceMapper.selectByApply(apply.getId());
                if (evidence != null && (!Objects.equals(evidence.getApprovalRef(), sourceGrantId(apply))
                        || !Objects.equals(evidence.getApprovalAssetId(), apply.getAssetId())
                        || !Objects.equals(evidence.getApprovalScopeHash(), scopeHash))) {
                    throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
                }
                // 没有新凭证的历史申请保留原有限行为；不能因此标记其历史批准已被证明。
            }
        }
        MarketDeliveryOperationDO operation = MarketDeliveryOperationDO.builder()
                .operationId(operationId)
                .applyId(apply.getId())
                .sourceGrantId(sourceGrantId(apply))
                .opType(type)
                .appId(apply.getAppId())
                .apiId(apply.getApiId())
                .expectedRevision(apply.getRevision() == null ? 1 : apply.getRevision())
                .sourceRevision(sourceRevision)
                .payloadJson(MarketDeliveryPayload.toJson(command))
                .payloadHash(MarketDeliveryPayload.hash(command))
                .build();
        // 只有完整保存且具备可领取主键的意图，才能回填申请并注册提交后执行。
        if (operationMapper.insertFrozen(operation) != 1
                || operation.getId() == null || operation.getId() <= 0) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (applyMapper.update(null, new LambdaUpdateWrapper<MarketResourceApplyDO>()
                .eq(MarketResourceApplyDO::getId, apply.getId())
                .set(MarketResourceApplyDO::getDeliveryStatus, DELIVERY_PENDING)
                .set(MarketResourceApplyDO::getSourceGrantId, operation.getSourceGrantId())
                .set(MarketResourceApplyDO::getSourceRevision, sourceRevision)) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        // 提交后只尝试领取；未领取到说明其他节点或作业已持有，不在此直接执行。
        Long operationRowId = operation.getId();
        runAfterCommit(() -> coordinator.claim(operationRowId, nodeToken())
                .ifPresent(claimed -> coordinator.execute(claimed)));
    }

    private record ApprovalActor(long actor, Long department, String note) {

    }

    /**
     * 组装并冻结来源命令。批准列、物理绑定与发布证据全部来自提交时冻结的快照，
     * 缺少任一证据时拒绝开通，不用当前元数据补齐历史批准。
     */
    SupplyGrantSourceCommandDTO buildCommand(MarketResourceApplyDO apply, SupplyGrantSourceCommandDTO.Action action,
            String operationId, long sourceRevision) {
        if (action != SupplyGrantSourceCommandDTO.Action.GRANT) {
            // 撤销只需来源身份：不要求当前元数据，API 下线或合同变更后仍可撤权。
            return new SupplyGrantSourceCommandDTO(2, operationId, MarketDeliveryPayload.SOURCE_SYSTEM,
                    sourceKey(apply), sourceRevision, apply.getAppId(), apply.getApiId(), action, null,
                    "数据集市来源 " + sourceGrantId(apply));
        }
        MarketQuotaPolicy.validate(apply.getApplyUseType(), apply.getApplyUseTimes());
        MarketQuotaPolicy.requireFrozen(apply);
        Map<String, Object> snapshot = readSnapshot(apply.getSnapshot());
        List<String> approvedColumns = List.copyOf(MarketFieldScope.requireFrozenColumns(apply.getSnapshot()));
        Long databaseId = longValue(snapshot.get("databaseId"));
        String schemaName = text(snapshot.get("schemaName"));
        String tableName = text(snapshot.get("tableName"));
        String contractHash = text(snapshot.get("approvedContractHash"));
        String apiVersion = text(snapshot.get("apiVersion"));
        if (databaseId == null || StrUtil.isBlank(schemaName) || StrUtil.isBlank(tableName)
                || StrUtil.isBlank(contractHash) || StrUtil.isBlank(apiVersion)) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        boolean forever = apply.getApplyStartTime() == null && apply.getApplyEndTime() == null;
        SupplyGrantSourceCommandDTO.Scope scope = new SupplyGrantSourceCommandDTO.Scope(
                sourceGrantId(apply), SOURCE_TYPE, apply.getApplyUserId(),
                new SupplyGrantSourceCommandDTO.Binding(databaseId, schemaName, tableName),
                contractHash, apiVersion, StrUtil.blankToDefault(apply.getPurpose(), "数据集市申请"),
                StrUtil.blankToDefault(apply.getDutyMatter(), "未填写"), POLICY_MODE,
                approvedColumns, forever, millis(apply.getApplyStartTime()),
                forever ? null : millis(apply.getApplyEndTime()),
                MarketQuotaPolicy.quota(apply.getApplyUseType(), apply.getApplyUseTimes()));
        return new SupplyGrantSourceCommandDTO(2, operationId, MarketDeliveryPayload.SOURCE_SYSTEM,
                sourceKey(apply), sourceRevision, apply.getAppId(), apply.getApiId(), action, scope,
                "数据集市来源 " + sourceGrantId(apply));
    }

    /** 写入本地来源状态并返回单调递增的来源版本；撤销也推进版本，保证乱序回执不能复活旧授权。 */
    private long persistGrantSource(MarketResourceApplyDO apply,
            SupplyGrantSourceCommandDTO.Action action, boolean revoke) {
        String status = switch (action) {
            case GRANT -> "ACTIVE";
            case SUSPEND -> "SUSPENDED";
            case REVOKE -> "REVOKED";
        };
        String sourceKey = sourceKey(apply);
        AssetGrantSourceDO existing = grantSourceMapper.selectSourceForUpdate(sourceKey);
        if (existing == null) {
            existing = grantSourceMapper.selectByApplyId(apply.getId());
        }
        if (existing != null && "REVOKED".equals(existing.getSourceStatus())
                && action != SupplyGrantSourceCommandDTO.Action.REVOKE) {
            // 历史不一致的申请状态不能覆盖已持久化的终态来源；须重新申请，不复活同一身份。
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        long previous = existing == null || existing.getRevision() == null ? 0L : existing.getRevision();
        long applicationRevision = apply.getSourceRevision() == null ? 0L : apply.getSourceRevision();
        long approvalRevision = apply.getRevision() == null ? 1L : apply.getRevision().longValue();
        // 来源行缺失/待修复时仍不得回退申请已经发布过的来源序号；溢出时失败并回滚。
        long revision = Math.addExact(Math.max(Math.max(previous, applicationRevision), approvalRevision), 1L);
        if (existing == null) {
            if (grantSourceMapper.insert(AssetGrantSourceDO.builder()
                    .applyId(apply.getId())
                    .sourceType(AssetGrantSourceDO.TYPE_APPLY)
                    .sourceKey(sourceKey)
                    .sourceStatus(status)
                    .appId(apply.getAppId())
                    .apiId(apply.getApiId())
                    .assetId(apply.getAssetId())
                    .scopeJson(apply.getSnapshot())
                    .startTime(apply.getApplyStartTime())
                    .endTime(apply.getApplyEndTime())
                    .revoked(revoke ? 1 : 0)
                    .revision(revision)
                    .build()) != 1) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            return revision;
        }
        existing.setSourceKey(sourceKey);
        existing.setSourceStatus(status);
        existing.setAppId(apply.getAppId());
        existing.setApiId(apply.getApiId());
        existing.setAssetId(apply.getAssetId());
        existing.setScopeJson(apply.getSnapshot());
        existing.setStartTime(apply.getApplyStartTime());
        existing.setEndTime(apply.getApplyEndTime());
        existing.setRevoked(revoke ? 1 : 0);
        existing.setRevision(revision);
        if (grantSourceMapper.updateById(existing) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return revision;
    }

    /**
     * 生成当前交付处理节点使用的标识令牌。
     */
    private static String nodeToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * 读取市场交付的快照。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSnapshot(String snapshotJson) {
        if (StrUtil.isBlank(snapshotJson)) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        Map<String, Object> body = JsonUtils.parseObjectQuietly(snapshotJson, Map.class);
        return body == null ? new LinkedHashMap<>() : body;
    }

    /**
     * 读取并转换市场交付中的字符串值。
     */
    private static String text(Object value) {
        return value == null ? null : StrUtil.trim(String.valueOf(value));
    }

    /**
     * 解析市场交付中的长整数值。
     */
    private static Long longValue(Object value) {
        String text = text(value);
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * 解析市场交付中的毫秒时间值。
     */
    private static Long millis(java.time.LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZONE).toInstant().toEpochMilli();
    }

    /**
     * 在事务成功提交后触发市场交付后续处理。
     */
    private void runAfterCommit(Runnable task) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 在当前事务成功提交后触发市场交付后续处理。
             */
            @Override
            public void afterCommit() {
                try {
                    task.run();
                } catch (RuntimeException failure) {
                    // 本地提交已经成功，不能把即时交付失败误报成申请事务失败；定时作业按持久操作重试。
                    log.warn("[afterCommit][集市交付即时尝试失败，等待定时补偿 type({})]",
                            failure.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * 判断授权操作是否因申请状态或修订变化而失效。
     */
    boolean shouldSkipStaleGrant(MarketResourceApplyDO apply, AssetGrantSourceDO source,
            MarketDeliveryOperationDO operation) {
        if (source != null && Objects.equals(source.getRevoked(), 1)
                && source.getRevision() != null && operation.getExpectedRevision() != null
                && source.getRevision() > operation.getExpectedRevision()) {
            return true;
        }
        if (apply == null) {
            return true;
        }
        return !Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                || !Objects.equals(apply.getIfAuth(), MarketResourceApplyDO.AUTH_YES)
                || Objects.equals(apply.getPaused(), 1);
    }
}
