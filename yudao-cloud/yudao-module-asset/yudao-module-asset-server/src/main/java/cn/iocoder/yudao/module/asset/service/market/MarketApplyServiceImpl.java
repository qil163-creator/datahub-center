package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceEndpointDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Slf4j
@Service
@Validated
public class MarketApplyServiceImpl implements MarketApplyService {

    /** 到期作业平台每轮的处理上限；剩余部分留给下一轮，避免长事务与长扫描。 */
    private static final int EXPIRE_BATCH_SIZE = 100;

    @Resource
    private MarketResourceApplyMapper applyMapper;

    @Resource
    private MarketCheckProcessMapper processMapper;

    @Resource
    private MarketDemandMapper demandMapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketResourceEndpointMapper endpointMapper;

    @Resource
    private MarketDisplayHelper displayHelper;

    @Resource
    private MarketAccessService accessService;

    @Resource
    private MarketObjectGuard objectGuard;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private SupplyAppApi supplyAppApi;

    @Resource
    private MarketSupplyApiGuard supplyApiGuard;

    @Resource
    private AssetMonitorService assetMonitorService;

    @Resource
    private MarketNotifyHelper notifyHelper;

    @Resource
    private MarketDeliveryService deliveryService;

    @Resource
    private MarketDeliveryCoordinator deliveryCoordinator;

    @Resource
    private MarketDeliveryEvidenceService deliveryEvidenceService;

    @Resource
    private cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper deliveryOperationMapper;

    @Resource
    private MarketWorkingDayService workingDayService;

    @Resource
    private MarketAuditRecorder auditRecorder;

    @Resource
    private AssetCatalogService catalogService;

    @Resource
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * 提交资源申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createApply(MarketApplySaveReqVO reqVO) {

        Long userId = objectGuard.requireUserId();
        DataAssetDO asset = validatePublishedAsset(reqVO.getAssetId());
        if (applyMapper.selectActiveByUserAndAsset(userId, reqVO.getAssetId()) != null) {
            throw exception(MARKET_APPLY_DUPLICATE);
        }
        validateTime(reqVO.getApplyStartTime(), reqVO.getApplyEndTime());
        validateDelivery(reqVO.getApiId(), reqVO.getAppId(), asset.getId(), userId);
        MarketQuotaPolicy.validate(reqVO.getApplyUseType(), reqVO.getApplyUseTimes());

        MarketResourceApplyDO apply = BeanUtils.toBean(reqVO, MarketResourceApplyDO.class);
        // 创建/编辑共用请求类型；创建主键只能由数据库生成，不能沿用客户端的编辑ID。
        apply.setId(null);
        apply.setApplyNo(nextApplyNo());
        apply.setAssetType(asset.getAssetType());
        apply.setApplyUserId(userId);
        apply.setApplyDeptId(resolveDeptId(reqVO.getApplyDeptId()));
        fillContact(apply);
        boolean submit = reqVO.getSubmit() == null || reqVO.getSubmit();
        apply.setStatus(submit ? MarketResourceApplyDO.STATUS_PENDING : MarketResourceApplyDO.STATUS_DRAFT);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        if (apply.getApplyUseType() == null) {
            apply.setApplyUseType(MarketResourceApplyDO.USE_UNLIMITED);
        }
        apply.setRevision(1);
        apply.setPaused(0);
        apply.setDeliveryStatus(0);
        if (submit) {
            // 先完成元数据/绑定读取，再将申请和提交快照一次写入，避免持有写锁时远程读取及漏回填。
            fillSubmitSnapshot(apply, asset);
        }
        assertStillPublished(asset);
        insertInitialApply(apply);
        if (submit) {
            if (appendProcess(apply.getId(), MarketCheckProcessDO.ACTION_SUBMIT, null,
                    apply.getStatus(), userId, "提交申请") != 1) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            notifyHelper.notifyApplySubmit(apply, asset);
            auditRecorder.record("SUBMIT", "APPLY", apply.getId(), null, apply.getStatus(), apply.getPurpose(), "OK");
        }
        return apply.getId();
    }

    /**
     * 修改草稿申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateApply(MarketApplySaveReqVO reqVO) {
        MarketResourceApplyDO apply = validateOwnerEditable(reqVO.getId());
        assertSeenRevision(apply, reqVO.getExpectedRevision());
        MarketResourceApplyDO update = BeanUtils.toBean(reqVO, MarketResourceApplyDO.class);
        if (apply.getRenewalOfApplyId() != null) {
            preserveRenewalContent(apply, update);
        }
        validateTime(reqVO.getApplyStartTime(), reqVO.getApplyEndTime());
        DataAssetDO asset = validatePublishedAsset(update.getAssetId());
        validateDelivery(update.getApiId(), update.getAppId(), asset.getId(), apply.getApplyUserId());
        MarketQuotaPolicy.validate(update.getApplyUseType(), update.getApplyUseTimes());
        update.setId(apply.getId());
        update.setAssetType(asset.getAssetType());
        update.setApplyNo(apply.getApplyNo());
        update.setApplyUserId(apply.getApplyUserId());
        update.setApplyDeptId(resolveDeptId(reqVO.getApplyDeptId()));
        update.setRevision(nextContentRevision(apply));
        assertStillPublished(asset);
        // 校验期间可能已提交或被另一编辑覆盖；只更新仍处于所读版本的可编辑申请。
        if (applyMapper.update(update, submissionCondition(apply, apply.getStatus())) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 提交申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitApply(Long id) {
        submit(id, null);
    }

    /**
     * expectedRevision 为客户端所见内容版本；为空时与 {@link #submitApply(Long)} 相同。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitApply(Long id, Integer expectedRevision) {
        submit(id, expectedRevision);
    }

    /**
     * 校验并提交市场资产申请请求。
     */
    private void submit(Long id, Integer expectedRevision) {

        MarketResourceApplyDO apply = validateOwnerDraft(id);
        MarketQuotaPolicy.validate(apply.getApplyUseType(), apply.getApplyUseTimes());
        assertSeenRevision(apply, expectedRevision);
        DataAssetDO asset = validatePublishedAsset(apply.getAssetId());
        validateDelivery(apply.getApiId(), apply.getAppId(), asset.getId(), apply.getApplyUserId());
        fillSubmitSnapshot(apply, asset);
        assertStillPublished(asset);
        casStatus(apply, MarketResourceApplyDO.STATUS_DRAFT, MarketResourceApplyDO.STATUS_PENDING, wrapper -> wrapper
                .setSql("snapshot = CAST({0} AS jsonb)", apply.getSnapshot())
                .set(MarketResourceApplyDO::getCatalogVersionId, apply.getCatalogVersionId())
                .set(MarketResourceApplyDO::getReceivedTime, apply.getReceivedTime())
                .set(MarketResourceApplyDO::getReplyDeadline, apply.getReplyDeadline())
                .set(MarketResourceApplyDO::getDeliveryDeadline, apply.getDeliveryDeadline())
                .set(MarketResourceApplyDO::getRevision, apply.getRevision() == null ? 1 : apply.getRevision()));
        if (appendProcess(id, MarketCheckProcessDO.ACTION_SUBMIT, MarketResourceApplyDO.STATUS_DRAFT,
                MarketResourceApplyDO.STATUS_PENDING, objectGuard.requireUserId(), "提交申请") != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        auditRecorder.record("SUBMIT", "APPLY", id, MarketResourceApplyDO.STATUS_DRAFT,
                MarketResourceApplyDO.STATUS_PENDING, null, "OK");
        notifyHelper.notifyApplySubmit(apply, asset);
    }

    /**
     * 删除草稿/驳回/已撤销申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteApply(Long id) {
        MarketResourceApplyDO apply = validateExists(id);
        assertOwner(apply);
        if (apply.getStatus() != MarketResourceApplyDO.STATUS_DRAFT
                && apply.getStatus() != MarketResourceApplyDO.STATUS_REJECTED
                && apply.getStatus() != MarketResourceApplyDO.STATUS_CANCELLED) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (applyMapper.delete(submissionCondition(apply, apply.getStatus())) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 取消申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelApply(Long id) {
        cancel(id, null);
    }

    /**
     * 撤销自己的申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelApply(Long id, Integer expectedRevision) {
        cancel(id, expectedRevision);
    }

    /**
     * 取消市场资产申请当前申请或运行。
     */
    private void cancel(Long id, Integer expectedRevision) {

        MarketResourceApplyDO apply = validateExists(id);
        assertOwner(apply);
        assertSeenRevision(apply, expectedRevision);
        Integer from = apply.getStatus();
        if (!Objects.equals(from, MarketResourceApplyDO.STATUS_DRAFT)
                && !Objects.equals(from, MarketResourceApplyDO.STATUS_PENDING)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        casStatus(apply, from, MarketResourceApplyDO.STATUS_CANCELLED, null);
        if (appendProcess(id, MarketCheckProcessDO.ACTION_CANCEL, from,
                MarketResourceApplyDO.STATUS_CANCELLED, objectGuard.requireUserId(), "申请人撤销") != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        // 普通审计与状态变更同一事务；失败整体回滚，不发出任何成功提示。
        auditRecorder.record("APPLY_CANCEL", "APPLY", id, from,
                MarketResourceApplyDO.STATUS_CANCELLED, "申请人撤销", "OK");
    }

    /**
     * 审批申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkApply(MarketCheckReqVO reqVO) {

        MarketResourceApplyDO apply = validateExists(reqVO.getId());
        DataAssetDO asset = apply.getAssetId() == null ? null : assetMapper.selectById(apply.getAssetId());
        objectGuard.assertCanCheckApply(apply, asset);
        // 审批人所见内容版本：补正重提会递增版本，旧页面不能批准未看过的新内容。
        assertSeenRevision(apply, reqVO.getExpectedRevision());
        if (!Objects.equals(reqVO.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                && !Objects.equals(reqVO.getStatus(), MarketResourceApplyDO.STATUS_REJECTED)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        Long userId = objectGuard.requireUserId();
        boolean approved = Objects.equals(reqVO.getStatus(), MarketResourceApplyDO.STATUS_APPROVED);
        if (approved) {
            MarketQuotaPolicy.validate(apply.getApplyUseType(), apply.getApplyUseTimes());
            MarketFieldScope.requireMatchesAsset(apply, asset);
            MarketQuotaPolicy.requireFrozen(apply);
        }
        LocalDateTime checkTime = LocalDateTime.now();
        casStatus(apply, MarketResourceApplyDO.STATUS_PENDING, reqVO.getStatus(), wrapper -> wrapper
                .set(MarketResourceApplyDO::getCheckUserId, userId)
                .set(MarketResourceApplyDO::getCheckTime, checkTime)
                .set(MarketResourceApplyDO::getCheckNote, reqVO.getCheckNote())
                .set(MarketResourceApplyDO::getIfAuth, approved
                        ? MarketResourceApplyDO.AUTH_YES : MarketResourceApplyDO.AUTH_NO));
        // 申请 -> 资产：权限 RPC 期间资产可能换责任人、删除或换绑，锁内复验后再写过程与交付。
        objectGuard.lockProviderAsset(apply, approved);
        String action = approved ? MarketCheckProcessDO.ACTION_APPROVE : MarketCheckProcessDO.ACTION_REJECT;
        if (appendProcess(apply.getId(), action, MarketResourceApplyDO.STATUS_PENDING, reqVO.getStatus(),
                userId, reqVO.getCheckNote()) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        // 后续交付与通知使用本次已写入的审核结果，不能携带上一轮的审核意见。
        apply.setStatus(reqVO.getStatus());
        apply.setIfAuth(approved ? MarketResourceApplyDO.AUTH_YES : MarketResourceApplyDO.AUTH_NO);
        apply.setCheckUserId(userId);
        apply.setCheckTime(checkTime);
        apply.setCheckNote(reqVO.getCheckNote());
        if (approved) {
            deliveryService.enqueueApprovedGrant(apply, userId, objectGuard.currentDeptId(), reqVO.getCheckNote());
            recordApplyUsage(apply.getAssetId());
            notifyHelper.notifyApplyChecked(apply, asset, "通过");
            auditRecorder.record("APPROVE", "APPLY", apply.getId(),
                    MarketResourceApplyDO.STATUS_PENDING, apply.getStatus(), reqVO.getCheckNote(), "OK");
        } else {
            notifyHelper.notifyApplyChecked(apply, asset, "驳回");
            auditRecorder.record("REJECT", "APPLY", apply.getId(),
                    MarketResourceApplyDO.STATUS_PENDING, apply.getStatus(), reqVO.getCheckNote(), "OK");
        }
    }

    /**
     * 切换已通过市场申请的开通或取消授权状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void authApply(Long id, Integer auth) {
        authorize(id, auth, null);
    }

    /**
     * expectedSourceRevision 为客户端所见交付来源版本；为空时与 {@link #authApply(Long, Integer)} 相同。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void authApply(Long id, Integer auth, Long expectedSourceRevision) {
        authorize(id, auth, expectedSourceRevision);
    }

    /**
     * 核验已通过申请的来源修订后切换授权状态，保存流程、审计及交付意图。
     */
    private void authorize(Long id, Integer auth, Long expectedSourceRevision) {

        MarketResourceApplyDO apply = validateExists(id);
        DataAssetDO asset = apply.getAssetId() == null ? null : assetMapper.selectById(apply.getAssetId());
        objectGuard.assertCanAuthApply(apply, asset);
        // 先于同目标幂等返回：旧页面所见来源版本已变化时不能按“无变化”静默成功。
        assertSeenSourceRevision(apply, expectedSourceRevision);
        if (!Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (Objects.equals(auth, 1)) {
            MarketFieldScope.requireMatchesAsset(apply, asset);
        }
        int nextAuth = Objects.equals(auth, 1) ? MarketResourceApplyDO.AUTH_YES : MarketResourceApplyDO.AUTH_NO;
        if (Objects.equals(apply.getIfAuth(), nextAuth)) {
            return; // 同一目标状态重试，不重复推进来源版本、流程或交付意图。
        }
        casLifecycle(apply, nextAuth == MarketResourceApplyDO.AUTH_YES,
                wrapper -> wrapper.set(MarketResourceApplyDO::getIfAuth, nextAuth));
        boolean enabled = nextAuth == MarketResourceApplyDO.AUTH_YES;
        objectGuard.lockProviderAsset(apply, enabled);
        if (appendProcess(id, enabled ? MarketCheckProcessDO.ACTION_AUTH : MarketCheckProcessDO.ACTION_UNAUTH,
                apply.getStatus(), apply.getStatus(), objectGuard.requireUserId(),
                enabled ? "开通授权" : "取消授权") != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        apply.setIfAuth(nextAuth);
        // 普通审计与状态变更同一事务；失败整体回滚，不继续来源/交付/通知。
        auditRecorder.record("APPLY_AUTH", "APPLY", id, apply.getStatus(), apply.getStatus(),
                enabled ? "开通授权" : "取消授权", "OK");
        enqueueCurrentAuthorization(apply);
        notifyHelper.notifyApplyChecked(apply, asset, enabled ? "开通授权" : "取消授权");
    }

    /**
     * 撤回申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void revokeApply(Long id, String note) {
        revoke(id, note, null);
    }

    /**
     * 收回已通过的授权。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void revokeApply(Long id, String note, Long expectedSourceRevision) {
        revoke(id, note, expectedSourceRevision);
    }

    /**
     * 撤回市场资产申请已生效的授权。
     */
    private void revoke(Long id, String note, Long expectedSourceRevision) {

        MarketResourceApplyDO apply = validateExists(id);
        DataAssetDO asset = apply.getAssetId() == null ? null : assetMapper.selectById(apply.getAssetId());
        objectGuard.assertCanAuthApply(apply, asset);
        assertSeenSourceRevision(apply, expectedSourceRevision);
        revokeChecked(apply, asset, note);
    }

    /**
     * 历史交付处置的收回：只能加入调用方已锁定并复验证据的事务，不再做权限 RPC；
     *  复用与普通收回相同的状态 CAS、资产复验、过程、冻结收回命令及提交后通知。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void revokeForDisposal(Long id, String note) {

        MarketResourceApplyDO apply = validateExists(id);
        // 权限 RPC 已由处置入口在锁外完成；此处仍在锁内复验操作人是当前提供方。
        revokeChecked(apply, apply.getAssetId() == null ? null : assetMapper.selectById(apply.getAssetId()), note);
    }

    /**
     * 撤回已核验。
     */
    private void revokeChecked(MarketResourceApplyDO apply, DataAssetDO asset, String note) {
        Long id = apply.getId();
        casLifecycle(apply, false, wrapper -> wrapper
                .set(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_REVOKED)
                .set(MarketResourceApplyDO::getIfAuth, MarketResourceApplyDO.AUTH_NO)
                .set(MarketResourceApplyDO::getCheckNote, note));
        objectGuard.lockProviderAsset(apply, false);
        if (appendProcess(id, MarketCheckProcessDO.ACTION_REVOKE, MarketResourceApplyDO.STATUS_APPROVED,
                MarketResourceApplyDO.STATUS_REVOKED, objectGuard.requireUserId(), note) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        apply.setStatus(MarketResourceApplyDO.STATUS_REVOKED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        apply.setCheckNote(note);
        // 普通审计与状态变更同一事务；处置收回复用同一链路，同样留下 APPLY_REVOKE 审计。
        auditRecorder.record("APPLY_REVOKE", "APPLY", id, MarketResourceApplyDO.STATUS_APPROVED,
                MarketResourceApplyDO.STATUS_REVOKED, note, "OK");
        deliveryService.enqueueRevoke(apply);
        notifyHelper.notifyApplyRevoked(apply, asset);
    }

    /**
     * 仅由已持久化停供意图驱动的内部逐申请撤权。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void revokeForAssetStop(Long applyId) {

        MarketResourceApplyDO apply = validateExists(applyId);
        if (!Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)) {
            return;
        }
        // 先申请 CAS、再资产锁，与审批/撤权的既有锁顺序一致。
        casLifecycle(apply, false,
                update -> update.set(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_REVOKED)
                        .set(MarketResourceApplyDO::getIfAuth, MarketResourceApplyDO.AUTH_NO));
        DataAssetDO stopped = assetMapper.selectForMatching(apply.getAssetId());
        if (stopped == null || !Objects.equals(stopped.getStatus(), DataAssetDO.STATUS_OFFLINE)
                || stopped.getStopSupplyUserId() == null) {
            throw exception(DATA_ASSET_RETIRE_PENDING);
        }
        if (appendProcess(applyId, MarketCheckProcessDO.ACTION_REVOKE, MarketResourceApplyDO.STATUS_APPROVED,
                MarketResourceApplyDO.STATUS_REVOKED, stopped.getStopSupplyUserId(),
                stopped.getStopSupplyReason()) != 1) {
            throw exception(MARKET_OPERATION_NOT_PERSISTED);
        }
        apply.setStatus(MarketResourceApplyDO.STATUS_REVOKED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        deliveryService.enqueueRevoke(apply);
        auditRecorder.recordAs("ASSET_STOP_REVOKE", "APPLY", applyId, MarketResourceApplyDO.STATUS_APPROVED,
                MarketResourceApplyDO.STATUS_REVOKED, stopped.getStopSupplyReason(), "REQUESTED",
                stopped.getStopSupplyUserId(), stopped.getStopSupplyDeptId());
    }

    /**
     * 到期失效：按数据库时间有界扫描，逐申请独立短事务。
     * 单条失败不影响其余申请，也不会把整批已处理结果回滚；批末汇总失败供调度器报警。
     */
    @Override
    public int expireDueApplies() {

        int count = 0;
        int failed = 0;
        for (MarketResourceApplyDO due : applyMapper.selectExpiredDue(EXPIRE_BATCH_SIZE)) {
            try {
                if (Boolean.TRUE.equals(shortTransaction().execute(status -> expireOne(due)))) {
                    count++;
                }
            } catch (RuntimeException failure) {
                // 事务已退出；不根据异常推断提交结果，更不能补造撤销命令或跳过后续申请。
                failed++;
                log.warn("[expireDueApplies][到期处理未完成 applyId({}) type({})]", due.getId(),
                        failure.getClass().getSimpleName());
            }
        }
        if (failed > 0) {
            // 原始 JDBC/远端异常可能带冻结载荷，不传给调度器的根因/堆栈输出。
            log.warn("[expireDueApplies][本批处理部分失败 completed({}) failed({})]", count, failed);
            throw new IllegalStateException("到期处理部分失败：完成 " + count + " 条，失败 " + failed + " 条");
        }
        return count;
    }

    /**
     * 使其到期单个。
     */
    private boolean expireOne(MarketResourceApplyDO apply) {
        // 复制的来源申请只承载部门业务查看，不由本地到期作业补造过程、操作者或撤权命令。
        if (apply.getSourceWorkOrderId() != null) {
            return false;
        }
        int updated = applyMapper.update(null, lifecycleCondition(apply)
                .apply("apply_end_time <= clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")
                .set(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_EXPIRED)
                .set(MarketResourceApplyDO::getIfAuth, MarketResourceApplyDO.AUTH_NO));
        if (updated == 0) {
            return false; // 已被其他节点或人工处理
        }
        if (updated != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (appendProcess(apply.getId(), MarketCheckProcessDO.ACTION_EXPIRE, MarketResourceApplyDO.STATUS_APPROVED,
                MarketResourceApplyDO.STATUS_EXPIRED, apply.getApplyUserId(), "使用期限到期自动失效") != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        apply.setStatus(MarketResourceApplyDO.STATUS_EXPIRED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        // 到期作业无登录上下文；审计与过程记录一致使用申请人作为操作者，部门可空。
        auditRecorder.recordAs("APPLY_EXPIRE", "APPLY", apply.getId(), MarketResourceApplyDO.STATUS_APPROVED,
                MarketResourceApplyDO.STATUS_EXPIRED, "使用期限到期自动失效", "OK", apply.getApplyUserId(), null);
        deliveryService.enqueueRevoke(apply);
        notifyHelper.notifyApplyExpired(apply, apply.getAssetId() == null ? null
                : assetMapper.selectById(apply.getAssetId()));
        return true;
    }

    /**
     * 在独立短事务内完成市场资产申请本地状态修改。
     */
    private TransactionTemplate shortTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(10);
        return template;
    }

    /**
     * 申请详情（含审批轨迹）。
     */
    @Override
    public MarketApplyRespVO getApply(Long id) {
        MarketResourceApplyDO apply = validateExists(id);
        return toApplyDetail(apply);
    }

    private MarketApplyRespVO toApplyDetail(MarketResourceApplyDO apply) {
        DataAssetDO asset = apply.getAssetId() == null ? null : assetMapper.selectById(apply.getAssetId());
        objectGuard.assertCanViewApply(apply, asset);
        List<MarketCheckProcessDO> processes = processMapper.selectListByApplyId(apply.getId());
        Map<Long, String> names = displayHelper.userNames(displayHelper.collect(processes,
                MarketCheckProcessDO::getOperatorUserId));
        MarketApplyRespVO vo = toResp(apply);
        vo.setProcesses(processes.stream().map(item -> {
            MarketCheckProcessRespVO process = BeanUtils.toBean(item, MarketCheckProcessRespVO.class);
            process.setOperatorName(displayHelper.userLabel(names, item.getOperatorUserId()));
            return process;
        }).toList());
        return vo;
    }

    /**
     * 申请分页。
     */
    @Override
    public PageResult<MarketApplyRespVO> getApplyPage(MarketApplyPageReqVO reqVO) {
        MarketObjectGuard.ApplyPageScope scope = objectGuard.resolveApplyPage(reqVO);
        if (scope.emptyProviderFilter()) {
            return PageResult.empty();
        }
        PageResult<MarketResourceApplyDO> page = applyMapper.selectPage(reqVO, scope.userId(),
                scope.ownOnly(), scope.providerOnly(), scope.providerAssetIds());
        return new PageResult<>(page.getList().stream().map(this::toResp).toList(), page.getTotal());
    }

    @Override
    public PageResult<MarketMyApplicationRespVO> getMyApplications(MarketApplyPageReqVO reqVO) {
        Long userId = objectGuard.requireUserId();
        PageResult<MarketResourceApplyDO> page = applyMapper.selectPage(reqVO, userId, true, false, List.of());
        if (page.getList().isEmpty()) {
            return new PageResult<>(List.of(), page.getTotal());
        }
        Map<Long, DataAssetDO> assets = displayHelper.assets(page.getList().stream()
                .map(MarketResourceApplyDO::getAssetId).toList());
        return new PageResult<>(page.getList().stream().map(item -> {
            MarketMyApplicationRespVO result = BeanUtils.toBean(item, MarketMyApplicationRespVO.class);
            DataAssetDO asset = item.getAssetId() == null ? null : assets.get(item.getAssetId());
            if (asset != null) {
                result.setAssetName(asset.getAssetName());
            }
            return result;
        }).toList(), page.getTotal());
    }

    @Override
    public MarketApplyRespVO getMyApplication(Long id) {
        MarketResourceApplyDO apply = validateExists(id);
        if (!Objects.equals(apply.getApplyUserId(), objectGuard.requireUserId())) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        return toApplyDetail(apply);
    }

    /**
     * 查询可用应用。
     */
    @Override
    public List<SupplyAppRespDTO> listAvailableApps() {
        Long userId = objectGuard.requireUserId();
        List<SupplyAppRespDTO> apps = supplyAppApi.listAppsByCreator(userId).getCheckedData();
        return apps == null ? List.of() : apps;
    }

    /**
     * 查询可用API。
     */
    @Override
    public List<MarketEndpointRespVO> listAvailableApis(Long assetId) {
        objectGuard.requireUserId();
        DataAssetDO asset = validatePublishedAsset(assetId);
        return endpointMapper.selectListByAssetId(assetId).stream()
                .map(item -> toVerifiedEndpoint(asset, item))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 当前资产可证明关联的已绑定 API。
     */
    @Override
    public List<MarketEligibleApiRespVO> listEligibleApis(Long assetId) {
        return listAvailableApis(assetId).stream().map(item -> {
            MarketEligibleApiRespVO vo = new MarketEligibleApiRespVO();
            vo.setId(item.getApiId());
            vo.setName(item.getName());
            vo.setApiType("QUERY");
            vo.setConfigType("TABLE");
            return vo;
        }).toList();
    }

    /**
     * 请求补正。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestSupplement(Long id, String note) {
        supplement(id, note, null);
    }

    /**
     * 请求补正。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestSupplement(Long id, String note, Integer expectedRevision) {
        supplement(id, note, expectedRevision);
    }

    /**
     * 将市场资产申请转入待补正状态并记录处理意见。
     */
    private void supplement(Long id, String note, Integer expectedRevision) {

        MarketResourceApplyDO apply = validateExists(id);
        DataAssetDO asset = apply.getAssetId() == null ? null : assetMapper.selectById(apply.getAssetId());
        objectGuard.assertCanCheckApply(apply, asset);
        assertSeenRevision(apply, expectedRevision);
        casStatus(apply, MarketResourceApplyDO.STATUS_PENDING, MarketResourceApplyDO.STATUS_SUPPLEMENT,
                wrapper -> wrapper
                        .set(MarketResourceApplyDO::getSupplementNote, note));
        objectGuard.lockProviderAsset(apply, false);
        if (appendProcess(id, MarketCheckProcessDO.ACTION_SUPPLEMENT, MarketResourceApplyDO.STATUS_PENDING,
                MarketResourceApplyDO.STATUS_SUPPLEMENT, objectGuard.requireUserId(), note) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        auditRecorder.record("SUPPLEMENT", "APPLY", id, MarketResourceApplyDO.STATUS_PENDING,
                MarketResourceApplyDO.STATUS_SUPPLEMENT, note, "OK");
    }

    /**
     * 重新提交申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resubmitApply(Long id) {
        resubmit(id, null);
    }

    /**
     * 补正后重新提交，生成新修订快照。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resubmitApply(Long id, Integer expectedRevision) {
        resubmit(id, expectedRevision);
    }

    /**
     * 重新提交经过补正的市场资产申请。
     */
    private void resubmit(Long id, Integer expectedRevision) {

        MarketResourceApplyDO apply = validateExists(id);
        assertOwner(apply);
        assertSeenRevision(apply, expectedRevision);
        if (!Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_SUPPLEMENT)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        MarketQuotaPolicy.validate(apply.getApplyUseType(), apply.getApplyUseTimes());
        if (apply.getRenewalOfApplyId() != null) {
            MarketQuotaPolicy.requireFrozen(apply);
        }
        DataAssetDO asset = validatePublishedAsset(apply.getAssetId());
        // 保留 CAS 所需的旧版本；新提交的快照必须写入即将持久化的版本，不能先冻旧版再加一。
        MarketResourceApplyDO submitted = BeanUtils.toBean(apply, MarketResourceApplyDO.class);
        submitted.setRevision(nextContentRevision(apply));
        if (apply.getRenewalOfApplyId() == null) {
            fillSubmitSnapshot(submitted, asset);
        } else {
            prepareRenewalResubmission(submitted, asset);
        }
        assertStillPublished(asset);
        casStatus(apply, MarketResourceApplyDO.STATUS_SUPPLEMENT, MarketResourceApplyDO.STATUS_PENDING,
                wrapper -> wrapper
                        .setSql("snapshot = CAST({0} AS jsonb)", submitted.getSnapshot())
                        .set(MarketResourceApplyDO::getCatalogVersionId, submitted.getCatalogVersionId())
                        .set(MarketResourceApplyDO::getRevision, submitted.getRevision())
                        .set(MarketResourceApplyDO::getReceivedTime, submitted.getReceivedTime())
                        .set(MarketResourceApplyDO::getReplyDeadline, submitted.getReplyDeadline())
                        .set(MarketResourceApplyDO::getDeliveryDeadline, submitted.getDeliveryDeadline()));
        if (appendProcess(id, MarketCheckProcessDO.ACTION_RESUBMIT, MarketResourceApplyDO.STATUS_SUPPLEMENT,
                MarketResourceApplyDO.STATUS_PENDING, objectGuard.requireUserId(), "补正后重新提交") != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        auditRecorder.record("RESUBMIT", "APPLY", id, MarketResourceApplyDO.STATUS_SUPPLEMENT,
                MarketResourceApplyDO.STATUS_PENDING, null, "OK");
    }

    /**
     * 旧的原地续期入口：新协议下不再延长原批准，调用会返回明确错误，请改用 {@link #createRenewal}。
     */
    @Override
    public void renewApply(Long id, LocalDateTime applyEndTime) {
        // 原地延长会让已批准范围在没有新审批的情况下继续有效；续期必须是新的申请。
        throw exception(MARKET_APPLY_RENEWAL_REQUIRED);
    }

    /**
     * 创建续期申请：关联原申请的新申请，复制原批准证据并重新走审批。
     *  同一 operationId 的重复提交返回同一条续期申请；原申请的期限与来源保持不变。
     *  独立 READ_COMMITTED 事务提交，不参与调用方外层事务；必须通过 Spring 代理调用。
     *  幂等比较首次请求截止时间（微秒精度），缺少历史证据或复用身份时明确拒绝。
     *
     *  @return 新申请编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Long createRenewal(MarketRenewalReqVO reqVO) {
        if (reqVO.getId() == null || reqVO.getOperationId() == null || reqVO.getOperationId().isBlank()
                || reqVO.getOperationId().length() > 128) {
            throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
        }
        if (reqVO.getApplyEndTime() == null) {
            throw exception(MARKET_APPLY_TIME_INVALID);
        }

        long userId = objectGuard.requireUserId();
        String operationId = reqVO.getOperationId().strip();
        // PostgreSQL timestamp(6) 的精度；在首次写入和每次比较之前使用同一规范化规则。
        LocalDateTime requestedEnd = reqVO.getApplyEndTime().truncatedTo(ChronoUnit.MICROS);
        MarketResourceApplyDO existing = applyMapper.selectRenewalByOperation(operationId);
        if (existing != null) {
            return sameRenewalOrConflict(existing, reqVO.getId(), userId, requestedEnd);
        }
        MarketResourceApplyDO original = applyMapper.selectOwnedForRenewal(reqVO.getId(), userId);
        if (original == null) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        if (original.getSourceWorkOrderId() != null) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        // 等待原申请锁期间可能已有同操作提交；重放只返回结果，不重新授予权限。
        existing = applyMapper.selectRenewalByOperation(operationId);
        if (existing != null) {
            return sameRenewalOrConflict(existing, reqVO.getId(), userId, requestedEnd);
        }
        LocalDateTime now = applyMapper.databaseNow();
        if (!Objects.equals(original.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                || !Objects.equals(original.getIfAuth(), MarketResourceApplyDO.AUTH_YES)
                || (original.getPaused() != null && original.getPaused() != 0)
                || (original.getApplyStartTime() != null && original.getApplyStartTime().isAfter(now))
                || (original.getApplyEndTime() != null && !original.getApplyEndTime().isAfter(now))) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (original.getApplyEndTime() == null || !requestedEnd.isAfter(original.getApplyEndTime())) {
            throw exception(MARKET_APPLY_TIME_INVALID);
        }
        if (applyMapper.hasActiveRenewalByParent(original.getId())) {
            throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
        }
        if (applyMapper.hasActivePendingApply(userId, original.getAssetId())) {
            throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
        }
        validateTime(original.getApplyStartTime(), requestedEnd);
        // 仅读取本次SLA所需的共享属性；不额外改变“父申请有效即可发起续期”的既有资格政策。
        DataAssetDO asset = assetMapper.selectById(original.getAssetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        MarketResourceApplyDO renewal = BeanUtils.toBean(original, MarketResourceApplyDO.class);
        renewal.setId(null);
        renewal.clean();
        renewal.setDeleted(null);
        renewal.setApplyNo(nextApplyNo());
        renewal.setRenewalOfApplyId(original.getId());
        renewal.setRenewalOperationId(operationId);
        renewal.setRenewalRequestedEndTime(requestedEnd);
        renewal.setApplyEndTime(requestedEnd);
        renewal.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        renewal.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        renewal.setPaused(0);
        renewal.setRevision(1);
        renewal.setSourceRevision(null);
        renewal.setSourceGrantId(null);
        renewal.setDeliveryStatus(MarketDeliveryService.DELIVERY_NONE);
        renewal.setCheckUserId(null);
        renewal.setCheckTime(null);
        renewal.setCheckNote(null);
        renewal.setFirstUsedTime(null);
        renewal.setBpmInstanceId(null);
        renewal.setSupplementNote(null);
        // 续期是新的审批轮次，不继承父申请的受理时间、答复和交付截止。
        fillSubmissionDeadlines(renewal, asset, now);
        // 复制原批准当时冻结的证据；不按当前元数据重新冻结，避免批准后新增字段被带入。
        renewal.setSnapshot(original.getSnapshot());
        // 使用现有事务管理器保存点隔离唯一键失败，否则 PostgreSQL 会拒绝后续结果查询。
        TransactionTemplate insertion = new TransactionTemplate(transactionManager);
        insertion.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        try {
            insertion.executeWithoutResult(status -> {
                if (applyMapper.insertRenewal(renewal) != 1 || renewal.getId() == null || renewal.getId() <= 0) {
                    throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
                }
            });
        } catch (DuplicateKeyException concurrent) {
            MarketResourceApplyDO winner = applyMapper.selectRenewalByOperation(operationId);
            if (winner != null) {
                return sameRenewalOrConflict(winner, reqVO.getId(), userId, requestedEnd);
            }
            if (applyMapper.hasActiveRenewalByParent(original.getId())) {
                throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
            }
            if (applyMapper.hasActivePendingApply(userId, original.getAssetId())) {
                throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
            }
            throw concurrent;
        }
        if (appendProcess(renewal.getId(), MarketCheckProcessDO.ACTION_RENEW, null,
                MarketResourceApplyDO.STATUS_PENDING, userId,
                "续期申请（原申请 " + original.getId() + "）") != 1) {
            throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
        }
        auditRecorder.record("RENEW", "APPLY", renewal.getId(), null,
                MarketResourceApplyDO.STATUS_PENDING, "续期申请", "OK");
        return renewal.getId();
    }

    /** 同一 operationId 必须对应同一份续期数据；载荷不同说明是另一个请求，明确拒绝。 */
    private Long sameRenewalOrConflict(MarketResourceApplyDO existing, long originalId, long userId,
            LocalDateTime requestedEnd) {
        if (Boolean.TRUE.equals(existing.getDeleted())
                || !Objects.equals(existing.getApplyUserId(), userId)
                || !Objects.equals(existing.getRenewalOfApplyId(), originalId)
                || !Objects.equals(existing.getRenewalRequestedEndTime(), requestedEnd)) {
            throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
        }
        return existing.getId();
    }

    /**
     * 暂停申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void pauseApply(Long id, String note) {
        pause(id, note, null);
    }

    /**
     * 暂停申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void pauseApply(Long id, String note, Long expectedSourceRevision) {
        pause(id, note, expectedSourceRevision);
    }

    /**
     * 暂停市场资产申请当前申请的生效状态。
     */
    private void pause(Long id, String note, Long expectedSourceRevision) {

        MarketResourceApplyDO apply = validateExists(id);
        DataAssetDO asset = apply.getAssetId() == null ? null : assetMapper.selectById(apply.getAssetId());
        objectGuard.assertCanAuthApply(apply, asset);
        assertSeenSourceRevision(apply, expectedSourceRevision);
        if (!Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                || Objects.equals(apply.getPaused(), 1)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        casLifecycle(apply, false, wrapper -> wrapper.set(MarketResourceApplyDO::getPaused, 1));
        objectGuard.lockProviderAsset(apply, false);
        if (appendProcess(id, MarketCheckProcessDO.ACTION_PAUSE, apply.getStatus(), apply.getStatus(),
                objectGuard.requireUserId(), note) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        apply.setPaused(1);
        // 暂停是可恢复停用；用撤销会产生终态墓碑，恢复时无法复活同一条来源。
        deliveryService.enqueueSuspend(apply);
        auditRecorder.record("PAUSE", "APPLY", id, apply.getStatus(), apply.getStatus(), note, "OK");
    }

    /**
     * 恢复申请。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void resumeApply(Long id) {
        resume(id, null);
    }

    /**
     * 恢复已暂停授权。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void resumeApply(Long id, Long expectedSourceRevision) {
        resume(id, expectedSourceRevision);
    }

    /**
     * 恢复已暂停的市场资产申请。
     */
    private void resume(Long id, Long expectedSourceRevision) {

        MarketResourceApplyDO apply = validateExists(id);
        DataAssetDO asset = apply.getAssetId() == null ? null : assetMapper.selectById(apply.getAssetId());
        objectGuard.assertCanAuthApply(apply, asset);
        // 暂停再恢复后 paused 值不变，只有来源版本能识别旧页面，状态 CAS 无法识别。
        assertSeenSourceRevision(apply, expectedSourceRevision);
        if (!Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                || !Objects.equals(apply.getPaused(), 1)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        MarketFieldScope.requireMatchesAsset(apply, asset);
        casLifecycle(apply, true, wrapper -> wrapper.set(MarketResourceApplyDO::getPaused, 0));
        objectGuard.lockProviderAsset(apply, true);
        if (appendProcess(id, MarketCheckProcessDO.ACTION_RESUME, apply.getStatus(), apply.getStatus(),
                objectGuard.requireUserId(), "恢复") != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        apply.setPaused(0);
        enqueueCurrentAuthorization(apply);
        auditRecorder.record("RESUME", "APPLY", id, apply.getStatus(), apply.getStatus(), null, "OK");
    }

    /** 两个独立开关同时允许才可发放；任何一个关闭均为可恢复停用，不产生撤销墓碑。 */
    private void enqueueCurrentAuthorization(MarketResourceApplyDO apply) {
        if (Objects.equals(apply.getIfAuth(), MarketResourceApplyDO.AUTH_YES)
                && !Objects.equals(apply.getPaused(), 1)) {
            deliveryService.enqueueGrant(apply);
        } else {
            deliveryService.enqueueSuspend(apply);
        }
    }

    /**
     * 只修改本次状态字段，不回写旧 DO。Guard/冻结证据检查先于 CAS；获胜后在同一事务中
     * 按申请行 → 来源行的固定顺序写流程、递增来源版本及冻结交付，失败全部回滚。
     */
    private void casLifecycle(MarketResourceApplyDO apply, boolean activating,
            Consumer<LambdaUpdateWrapper<MarketResourceApplyDO>> changes) {
        LambdaUpdateWrapper<MarketResourceApplyDO> wrapper = lifecycleCondition(apply);
        if (activating) {
            // 到期扫描可能尚未执行；不得在已过期但仍标为 APPROVED 的申请上重新开通。
            wrapper.apply(
                    "(apply_end_time IS NULL OR apply_end_time > clock_timestamp() AT TIME ZONE 'Asia/Shanghai')");
        }
        changes.accept(wrapper);
        if (applyMapper.update(null, wrapper) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 构造市场资产申请生命周期更新所需的状态和版本条件。
     */
    private LambdaUpdateWrapper<MarketResourceApplyDO> lifecycleCondition(MarketResourceApplyDO apply) {
        return new LambdaUpdateWrapper<MarketResourceApplyDO>()

                .eq(MarketResourceApplyDO::getId, apply.getId())
                .isNull(MarketResourceApplyDO::getSourceWorkOrderId)
                .eq(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_APPROVED)
                .eq(apply.getSourceRevision() != null, MarketResourceApplyDO::getSourceRevision,
                        apply.getSourceRevision())
                .isNull(apply.getSourceRevision() == null, MarketResourceApplyDO::getSourceRevision)
                .eq(apply.getIfAuth() != null, MarketResourceApplyDO::getIfAuth, apply.getIfAuth())
                .isNull(apply.getIfAuth() == null, MarketResourceApplyDO::getIfAuth)
                .eq(apply.getPaused() != null, MarketResourceApplyDO::getPaused, apply.getPaused())
                .isNull(apply.getPaused() == null, MarketResourceApplyDO::getPaused);
    }

    /**
     * 重试待执行或失败的交付操作。
     */
    @Override
    public int retryDeliveries() {
        return deliveryService.retryPending();
    }

    /**
     * 查看原交付操作状态（仅授权提供方）。
     */
    @Override
    public MarketDeliveryOperationRespVO getDeliveryOperation(String operationId) {
        return toDeliveryResp(authorizedDeliveryOperation(operationId));
    }

    /**
     * 人工按原操作身份核查未知结果；不重发交付命令。
     */
    @Override
    public MarketDeliveryOperationRespVO recheckDelivery(MarketDeliveryRecheckReqVO reqVO) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("交付人工核查必须在本地事务外调用");
        }

        long actorUserId = objectGuard.requireUserId();
        Long actorDeptId = objectGuard.currentDeptId();
        MarketDeliveryOperationDO operation = authorizedDeliveryOperation(reqVO.operationId());
        assertDeliveryRecheckCaller(actorUserId, actorDeptId);
        if (!MarketDeliveryOperationDO.STATUS_MANUAL.equals(operation.getStatus())
                || !MarketDeliveryCoordinator.RESOLUTION_UNKNOWN.equals(operation.getResolutionStatus())
                || !Objects.equals(operation.getProtocolVersion(), 2)
                || reqVO.note() == null || reqVO.note().isBlank()) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        boolean updated = deliveryCoordinator.reconcileManualUnknown(
                operation, actorUserId, actorDeptId, reqVO.note().strip());
        assertDeliveryRecheckCaller(actorUserId, actorDeptId);
        if (!updated) {
            // 其他节点或人工已完成核查；不得返回本次 RPC 的结果冒充当前账本状态。
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        MarketDeliveryOperationDO current = authorizedDeliveryOperation(reqVO.operationId());
        assertDeliveryRecheckCaller(actorUserId, actorDeptId);
        return toDeliveryResp(current);
    }

    /**
     * 受控重投：仅限冻结命令完整匹配且动作是撤销/暂停的 MANUAL/UNKNOWN 原操作，按原 operationId 重发一次；
     *  必须绑定核查决定，GRANT 一律拒绝。
     */
    @Override
    public MarketDeliveryOperationRespVO redeliverDelivery(MarketDeliveryRedeliverReqVO reqVO) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("交付受控重投必须在本地事务外调用");
        }

        long actorUserId = objectGuard.requireUserId();
        Long actorDeptId = objectGuard.currentDeptId();
        MarketDeliveryOperationDO operation = authorizedDeliveryOperation(reqVO.operationId());
        assertDeliveryRecheckCaller(actorUserId, actorDeptId);
        if (!Objects.equals(operation.getProtocolVersion(), 2)) {
            throw exception(MARKET_DELIVERY_REDELIVER_DENIED);
        }
        boolean updated = deliveryCoordinator.redeliverManualUnknown(operation, actorUserId, actorDeptId,
                reqVO.note().strip(), reqVO.requestId(), reqVO.basisReviewRequestId());
        assertDeliveryRecheckCaller(actorUserId, actorDeptId);
        if (!updated) {
            // 其他节点或人工已处理；不得返回本次 RPC 的结果冒充当前账本状态。
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        MarketDeliveryOperationDO current = authorizedDeliveryOperation(reqVO.operationId());
        assertDeliveryRecheckCaller(actorUserId, actorDeptId);
        return toDeliveryResp(current);
    }

    /** 本地身份连续性检查，不执行权限 RPC，也不把失效身份替换为新的调用者。 */
    private void assertDeliveryRecheckCaller(long actorUserId, Long actorDeptId) {

        Long currentUserId;
        try {
            currentUserId = objectGuard.requireUserId();
        } catch (ServiceException ex) {
            if (Objects.equals(ex.getCode(), MARKET_APPLY_FORBIDDEN.getCode())) {
                // 入口已经取得用户；此时用户失效属于调用身份漂移。
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            throw ex;
        }
        if (!Objects.equals(currentUserId, actorUserId)
                || !Objects.equals(objectGuard.currentDeptId(), actorDeptId)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 读取交付操作并核验当前调用人的查询权限。
     */
    private MarketDeliveryOperationDO authorizedDeliveryOperation(String operationId) {
        // 详情与人工对账回显共用权限 RPC 前后的独立短快照，不能返回旧归属下授权的数据。
        return deliveryEvidenceService.authorizeCurrent(operationId).operation();
    }

    /**
     * 转换交付响应。
     */
    private static MarketDeliveryOperationRespVO toDeliveryResp(MarketDeliveryOperationDO operation) {
        return new MarketDeliveryOperationRespVO(operation.getId(), operation.getOperationId(),
                operation.getApplyId(), operation.getOpType(), operation.getSourceRevision(),
                operation.getStatus(), operation.getResolutionStatus(), operation.getAttemptCount(),
                operation.getResultRef(), operation.getLastError(), operation.getUpdateTime(),
                operation.getProtocolVersion(),
                MarketDeliveryPayload.operationAction(operation));
    }

    /**
     * 转换已核验端点。
     */
    private MarketEndpointRespVO toVerifiedEndpoint(DataAssetDO asset, MarketResourceEndpointDO item) {
        try {
            SupplyApiBindingRespDTO binding = supplyApiGuard.requireQueryableTableApi(item.getApiId());
            supplyApiGuard.requireMatchesAsset(asset, binding);
            MarketEndpointRespVO vo = new MarketEndpointRespVO();
            vo.setAssetId(item.getAssetId());
            vo.setApiId(item.getApiId());
            vo.setName(StrUtil.blankToDefault(binding.getName(), "API #" + item.getApiId()));
            return vo;
        } catch (RuntimeException ex) {
            log.warn("[listAvailableApis] 跳过无法核验的 API assetId={} apiId={} msg={}",
                    item.getAssetId(), item.getApiId(), ex.getMessage());
            return null;
        }
    }

    /**
     * 内部需求确认编排：复用时返回所属申请锁内的当前行，锁随调用方事务提交/回滚释放。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public MarketResourceApplyDO createApplyFromDemand(Long demandId, Long assetId, Long applyUserId,
            Long applyDeptId, String purpose) {

        DataAssetDO asset = validatePublishedAsset(assetId);
        MarketResourceApplyDO candidate = applyMapper.selectActiveByUserAndAsset(applyUserId, assetId);
        if (candidate != null) {
            if (candidate.getId() == null) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            // 匹配 -> 申请 -> 资产 -> 需求；复用所属申请行锁，持有到外层确认事务结束。
            // 首次查询仅定位候选，不能再次普通查询后无锁返回旧行、null 或另一条申请。
            MarketResourceApplyDO current = applyMapper.selectOwnedForRenewal(candidate.getId(), applyUserId);
            if (current == null || Boolean.TRUE.equals(current.getDeleted())
                    || !Objects.equals(current.getId(), candidate.getId())
                    || !Objects.equals(current.getApplyUserId(), applyUserId)
                    || !Objects.equals(current.getAssetId(), assetId)
                    || !(Objects.equals(current.getStatus(), MarketResourceApplyDO.STATUS_DRAFT)
                    || Objects.equals(current.getStatus(), MarketResourceApplyDO.STATUS_PENDING)
                    || Objects.equals(current.getStatus(), MarketResourceApplyDO.STATUS_SUPPLEMENT))) {
                throw exception(MARKET_APPLY_STATUS_DENY);
            }
            // 锁内仍符合原复用规则即可；返回当前行，不改写跨需求的原申请，也不把它当作已授权。
            return current;
        }
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setApplyNo(nextApplyNo());
        apply.setTitle("需求对接申请：" + asset.getAssetName());
        apply.setAssetId(assetId);
        apply.setAssetType(asset.getAssetType());
        apply.setDemandId(demandId);
        apply.setApplyDeptId(applyDeptId);
        apply.setApplyUserId(applyUserId);
        apply.setPurpose(purpose);
        apply.setStatus(MarketResourceApplyDO.STATUS_DRAFT);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        apply.setApplyUseType(MarketResourceApplyDO.USE_UNLIMITED);
        apply.setRevision(1);
        apply.setPaused(0);
        apply.setDeliveryStatus(0);
        MarketDemandDO demand = demandMapper.selectById(demandId);
        if (demand != null) {
            apply.setFieldScope(demand.getExpectedFields());
            apply.setDutyMatter(StrUtil.blankToDefault(demand.getTitle(), purpose));
            apply.setLegalBasis(demand.getPurpose());
        }
        insertInitialApply(apply);
        if (appendProcess(apply.getId(), MarketCheckProcessDO.ACTION_SUBMIT, null,
                apply.getStatus(), applyUserId, "需求匹配确认后生成申请草稿") != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return apply;
    }

    /** 两种首次创建共用的领域写入门禁；调用方的真实事务负责申请与过程/需求的整体回滚。 */
    private void insertInitialApply(MarketResourceApplyDO apply) {
        int inserted = applyMapper.insertInitialApplyIfAbsent(apply);
        if (inserted == 0) {
            // 定向冲突不使PG事务失效；复读区分在途竞争者与触发器等导致的零行插入。
            // 不在失败后改选其他申请或隐式重试。
            if (applyMapper.hasActivePendingApply(apply.getApplyUserId(), apply.getAssetId())) {
                throw exception(MARKET_APPLY_DUPLICATE);
            }
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (inserted != 1 || apply.getId() == null || apply.getId() <= 0) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 将市场资产申请业务记录转换为接口响应。
     */
    private MarketApplyRespVO toResp(MarketResourceApplyDO apply) {
        MarketApplyRespVO vo = BeanUtils.toBean(apply, MarketApplyRespVO.class);
        Map<Long, DataAssetDO> assets = displayHelper.assets(List.of(apply.getAssetId()));
        DataAssetDO asset = assets.get(apply.getAssetId());
        if (asset != null) {
            vo.setAssetName(asset.getAssetName());
            vo.setAssetCode(asset.getAssetCode());
            vo.setDatasourceId(asset.getDatasourceId());
            vo.setTableName(asset.getTableName());
            vo.setDefaultCheckerUserId(notifyHelper.primaryReviewer(asset));
            vo.setOwnerReviewer(notifyHelper.defaultReviewers(asset)
                    .contains(SecurityFrameworkUtils.getLoginUserId()));
        }
        if (apply.getDemandId() != null) {
            MarketDemandDO demand = demandMapper.selectById(apply.getDemandId());
            if (demand != null) {
                vo.setDemandNo(demand.getDemandNo());
            }
        }
        Map<Long, String> users = displayHelper.userNames(
                apply.getApplyUserId(), apply.getCheckUserId(), vo.getDefaultCheckerUserId());
        vo.setApplyUserName(displayHelper.userLabel(users, apply.getApplyUserId()));
        vo.setCheckUserName(displayHelper.userLabel(users, apply.getCheckUserId()));
        vo.setDefaultCheckerName(displayHelper.userLabel(users, vo.getDefaultCheckerUserId()));
        vo.setApplyDeptName(displayHelper.deptLabel(
                displayHelper.deptNames(apply.getApplyDeptId()), apply.getApplyDeptId()));
        vo.setApprovalResult(apply.getStatus());
        vo.setTechProvisionStatus(apply.getDeliveryStatus());
        String resolution = deliveryResolution(apply, vo);
        vo.setDeliveryResolution(resolution);
        // 交付查询后按实际访问的权威时钟复核；到期点立即失效，不依赖节点时间。
        vo.setEffective(accessService.isEffective(apply));
        vo.setAuthStatus(vo.getEffective() ? 1 : 0);
        // 审批通过不等于交付已生效：只有账本确认 APPLIED（或本就无需交付）才算当前可用。
        vo.setUsable(Boolean.TRUE.equals(vo.getEffective())
                && (resolution == null || RESOLUTION_APPLIED.equals(resolution)));
        if (Boolean.TRUE.equals(vo.getUsable())) {
            try {
                MarketFieldScope.requireMatchesAsset(apply, asset);
            } catch (cn.iocoder.yudao.framework.common.exception.ServiceException unconfirmed) {
                // 审批历史仍按原记录展示，但缺少冻结证据不能展示成当前可用。
                vo.setUsable(false);
            }
        }
        return vo;
    }

    private static final String RESOLUTION_UNKNOWN = "UNKNOWN";

    private static final String RESOLUTION_APPLIED = "APPLIED";

    /**
     * 使用同一条账本展示历史技术状态，并由交付服务核对当前批准身份与冻结范围。
     * 无 API 也必须检查既有账本与正式批准证据，不能清空绑定后绕过交付确认。
     */
    private String deliveryResolution(MarketResourceApplyDO apply, MarketApplyRespVO vo) {
        if (apply.getId() == null || apply.getId() <= 0) {
            return RESOLUTION_UNKNOWN;
        }

        MarketDeliveryOperationDO latest = deliveryOperationMapper.selectLatestByApply(apply.getId());
        if (latest != null) {
            // 历史回执状态保留，但成功回执只有匹配当前申请时才表示当前交付已确认。
            vo.setDeliveryOperationId(latest.getOperationId());
            vo.setTechProvisionStatus(MarketDeliveryService.technicalDeliveryStatus(latest));
        }
        return deliveryService.currentDeliveryResolution(apply, latest);
    }

    /**
     * 记录市场资产申请的申请使用记录。
     */
    private void recordApplyUsage(Long assetId) {
        DataAssetDO asset = assetId == null ? null : assetMapper.selectById(assetId);
        if (asset != null) {
            assetMonitorService.recordUsageLog(asset, AssetUsageLogDO.ACTION_APPLY);
        }
    }

    /**
     * 追加流程。
     */
    private int appendProcess(Long applyId, String action, Integer from, Integer to,
            Long operatorId, String note) {
        return processMapper.insert(MarketCheckProcessDO.builder()
                .resourceApplyId(applyId)
                .action(action)
                .fromStatus(from)
                .toStatus(to)
                .operatorUserId(operatorId)
                .note(note)
                .build());
    }

    /**
     * 校验市场资产申请的仍然已发布，不满足约束时拒绝当前操作。
     */
    private void assertStillPublished(DataAssetDO frozen) {
        DataAssetDO current = assetMapper.selectForMatching(frozen.getId());
        if (current == null || !Objects.equals(current.getStatus(), DataAssetDO.STATUS_PUBLISHED)
                || !Objects.equals(current.getPublishedCatalogVersionId(), frozen.getPublishedCatalogVersionId())) {
            throw exception(MARKET_APPLY_ASSET_NOT_PUBLISHED);
        }
    }

    /**
     * 校验市场资产申请的已发布资产，不满足约束时拒绝当前操作。
     */
    private DataAssetDO validatePublishedAsset(Long assetId) {
        DataAssetDO asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (!Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
            throw exception(MARKET_APPLY_ASSET_NOT_PUBLISHED);
        }
        return catalogService.requireCompletePublishedAsset(asset);
    }

    /**
     * 读取并校验市场资产申请记录存在，不存在时抛出业务异常。
     */
    private MarketResourceApplyDO validateExists(Long id) {
        MarketResourceApplyDO apply = applyMapper.selectById(id);
        if (apply == null) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
        // 来源申请通过部门资产读模型查看；未知本地申请人不能进入本地申请生命周期。
        if (apply.getSourceWorkOrderId() != null) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return apply;
    }

    /**
     * 校验市场资产申请的所属人草稿，不满足约束时拒绝当前操作。
     */
    private MarketResourceApplyDO validateOwnerDraft(Long id) {
        MarketResourceApplyDO apply = validateExists(id);
        assertOwner(apply);
        if (apply.getStatus() != MarketResourceApplyDO.STATUS_DRAFT) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return apply;
    }

    /**
     * 校验市场资产申请的所属人可编辑，不满足约束时拒绝当前操作。
     */
    private MarketResourceApplyDO validateOwnerEditable(Long id) {
        MarketResourceApplyDO apply = validateExists(id);
        assertOwner(apply);
        if (apply.getStatus() != MarketResourceApplyDO.STATUS_DRAFT
                && apply.getStatus() != MarketResourceApplyDO.STATUS_SUPPLEMENT) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return apply;
    }

    /**
     * 补充市场资产申请的提交快照。
     */
    private void fillSubmitSnapshot(MarketResourceApplyDO apply, DataAssetDO asset) {
        fillSubmissionDeadlines(apply, asset, LocalDateTime.now());
        if (asset != null) {
            apply.setCatalogVersionId(asset.getPublishedCatalogVersionId());
        }
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("assetId", asset == null ? "" : String.valueOf(asset.getId()));
        snapshot.put("assetVersion", asset == null ? "" : StrUtil.blankToDefault(asset.getPublishedVersion(), ""));
        snapshot.put("catalogVersionId", apply.getCatalogVersionId() == null ? ""
                : String.valueOf(apply.getCatalogVersionId()));
        snapshot.put("fieldScope", StrUtil.blankToDefault(apply.getFieldScope(), ""));
        snapshot.put("appId", apply.getAppId() == null ? "" : String.valueOf(apply.getAppId()));
        snapshot.put("apiId", apply.getApiId() == null ? "" : String.valueOf(apply.getApiId()));
        snapshot.put("dutyMatter", StrUtil.blankToDefault(apply.getDutyMatter(), ""));
        snapshot.put("applyUseType", String.valueOf(apply.getApplyUseType() == null ? 0 : apply.getApplyUseType()));
        snapshot.put("applyUseTimes", String.valueOf(apply.getApplyUseTimes() == null ? 0 : apply.getApplyUseTimes()));
        snapshot.put("revision", apply.getRevision() == null ? "1" : String.valueOf(apply.getRevision()));
        // 冻结失败即拒绝提交：空集合或元数据故障都不能变成“全部字段”的隐式授权。
        java.util.List<String> frozen = freezeApprovedColumns(asset, apply.getFieldScope());
        snapshot.put("frozenColumns", frozen);
        snapshot.put("publishedSnapshotId", asset.getPublishedSnapshotId() == null ? ""
                : String.valueOf(asset.getPublishedSnapshotId()));
        if (Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
            if (asset.getDatasourceId() == null || StrUtil.isBlank(asset.getSchemaName())
                    || StrUtil.isBlank(asset.getTableName())) {
                throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
            }
            snapshot.put("databaseId", String.valueOf(asset.getDatasourceId()));
            snapshot.put("schemaName", asset.getSchemaName());
            snapshot.put("tableName", asset.getTableName());
        }
        if (apply.getApiId() != null) {
            freezeApiBinding(snapshot, apply, asset, frozen);
        }
        apply.setSnapshot(JsonUtils.toJsonString(snapshot));
    }

    /**
     * 补充市场资产申请的提交记录截止时间。
     */
    private void fillSubmissionDeadlines(MarketResourceApplyDO apply, DataAssetDO asset, LocalDateTime received) {
        apply.setReceivedTime(received);
        int replyDays = DataAssetDO.resolveShareAttr(asset) == DataAssetDO.SHARE_UNCONDITIONAL ? 1 : 10;
        apply.setReplyDeadline(workingDayService.plusWorkingDays(received, replyDays));
        apply.setDeliveryDeadline(workingDayService.plusWorkingDays(received, replyDays + 20));
    }

    /**
     * 续期补正只能补材料；用途、义务、字段范围和资产/API/应用绑定来自子申请自身的继承内容。
     * 不再查询父申请：父暂停、撤销或到期不改变已创建子申请的审批与来源。
     * 旧调用方省略可空字段时保留原值，明确传入不同值则拒绝，不能移除绑定或扩大范围。
     */
    private void preserveRenewalContent(MarketResourceApplyDO apply, MarketResourceApplyDO update) {
        requireUnchangedRenewalValue(update.getAssetId(), apply.getAssetId());
        requireUnchangedRenewalValue(update.getApiId(), apply.getApiId());
        requireUnchangedRenewalValue(update.getAppId(), apply.getAppId());
        requireUnchangedRenewalValue(update.getPurpose(), apply.getPurpose());
        requireUnchangedRenewalValue(update.getDutyMatter(), apply.getDutyMatter());
        requireUnchangedRenewalValue(update.getLegalBasis(), apply.getLegalBasis());
        requireUnchangedRenewalValue(update.getFieldScope(), apply.getFieldScope());
        requireUnchangedRenewalValue(update.getApplyUseType(), apply.getApplyUseType());
        requireUnchangedRenewalValue(update.getApplyUseTimes(), apply.getApplyUseTimes());
        update.setAssetId(apply.getAssetId());
        update.setApiId(apply.getApiId());
        update.setAppId(apply.getAppId());
        update.setPurpose(apply.getPurpose());
        update.setDutyMatter(apply.getDutyMatter());
        update.setLegalBasis(apply.getLegalBasis());
        update.setFieldScope(apply.getFieldScope());
        update.setApplyUseType(apply.getApplyUseType());
        update.setApplyUseTimes(apply.getApplyUseTimes());
    }

    /**
     * 校验市场资产申请的未变化续期值，不满足约束时拒绝当前操作。
     */
    private static void requireUnchangedRenewalValue(Object provided, Object inherited) {
        if (provided != null && !Objects.equals(provided, inherited)) {
            throw exception(MARKET_APPLY_RENEWAL_CONFLICT);
        }
    }

    /** 保留原发布/物理范围证据，只更新提交轮次和SLA；实际绑定已失效时拒绝，不能重新冻结掩盖。 */
    private void prepareRenewalResubmission(MarketResourceApplyDO submitted, DataAssetDO asset) {
        MarketFieldScope scope = MarketFieldScope.requireMatchesAsset(submitted, asset);
        Map<String, Object> snapshot = JsonUtils.parseObject(submitted.getSnapshot(), Map.class);
        requireRenewalSnapshotValue(snapshot, "catalogVersionId", submitted.getCatalogVersionId());
        requireRenewalSnapshotValue(snapshot, "apiId", submitted.getApiId());
        requireRenewalSnapshotValue(snapshot, "appId", submitted.getAppId());
        requireRenewalSnapshotValue(snapshot, "fieldScope", submitted.getFieldScope());
        requireRenewalSnapshotValue(snapshot, "dutyMatter", submitted.getDutyMatter());
        java.util.Set<String> available = new java.util.LinkedHashSet<>();
        for (MetadataColumnRespDTO column : readPublishedColumns(asset)) {
            available.add(column.getColumnName());
        }
        if (!available.containsAll(scope.getColumns())) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        validateDelivery(submitted.getApiId(), submitted.getAppId(), asset.getId(), submitted.getApplyUserId());
        if (submitted.getApiId() != null) {
            Map<String, Object> currentBinding = new java.util.LinkedHashMap<>();
            freezeApiBinding(currentBinding, submitted, asset, java.util.List.copyOf(scope.getColumns()));
            for (Map.Entry<String, Object> entry : currentBinding.entrySet()) {
                if (!snapshot.containsKey(entry.getKey()) || !Objects.equals(snapshot.get(entry.getKey()),
                        entry.getValue())) {
                    throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
                }
            }
        }
        snapshot.put("revision", String.valueOf(submitted.getRevision()));
        submitted.setSnapshot(JsonUtils.toJsonString(snapshot));
        fillSubmissionDeadlines(submitted, asset, LocalDateTime.now());
    }

    /**
     * 校验市场资产申请的续期快照值，不满足约束时拒绝当前操作。
     */
    private static void requireRenewalSnapshotValue(Map<String, Object> snapshot, String key, Object expected) {
        String value = expected == null ? "" : String.valueOf(expected);
        if (!snapshot.containsKey(key) || !Objects.equals(snapshot.get(key), value)) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
    }

    /**
     * 冻结开通所需的物理绑定与发布证据：交付时只重发这份快照，不按后来变化的 API 重新推导。
     * 输出字段映射必须覆盖全部冻结列，否则说明批准列无法落到真实物理字段。
     */
    private void freezeApiBinding(java.util.Map<String, Object> snapshot, MarketResourceApplyDO apply,
            DataAssetDO asset, java.util.List<String> frozenColumns) {
        SupplyApiBindingRespDTO binding = supplyApiGuard.requireQueryableTableApi(apply.getApiId());
        supplyApiGuard.requireMatchesAsset(asset, binding);
        if (!binding.isFieldMappingVerified() || binding.getActiveReleaseId() == null
                || StrUtil.isBlank(binding.getContractHash()) || StrUtil.isBlank(binding.getSchemaName())
                || StrUtil.isBlank(binding.getTableName()) || binding.getDatabaseId() == null
                || binding.getOutputFieldMapping() == null
                || !binding.getOutputFieldMapping().values().containsAll(frozenColumns)) {
            throw exception(MARKET_APPLY_API_TYPE_UNSUPPORTED);
        }
        snapshot.put("databaseId", String.valueOf(binding.getDatabaseId()));
        snapshot.put("schemaName", binding.getSchemaName());
        snapshot.put("tableName", binding.getTableName());
        snapshot.put("approvedContractHash", binding.getContractHash());
        snapshot.put("apiVersion", StrUtil.blankToDefault(binding.getVersion(), ""));
        snapshot.put("activeReleaseId", String.valueOf(binding.getActiveReleaseId()));
    }

    /**
     * 冻结申请提交时刻的真实物理列。读取元数据失败、资源无列、所选范围匹配不到任何列时，
     * 抛出业务异常阻止提交，绝不返回空集合让后续按“全部字段”处理。
     */
    private java.util.List<String> freezeApprovedColumns(DataAssetDO asset, String fieldScope) {
        java.util.List<MetadataColumnRespDTO> columns = readPublishedColumns(asset);
        java.util.Set<String> physicalNames = new java.util.LinkedHashSet<>();
        for (MetadataColumnRespDTO column : columns) {
            physicalNames.add(column.getColumnName());
        }
        if (StrUtil.isBlank(fieldScope)) {
            return java.util.List.copyOf(physicalNames);
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (String token : fieldScope.split("[,;，、\\s\\n]+")) {
            if (StrUtil.isBlank(token)) {
                continue;
            }
            // 每个选择必须唯一映射；不能丢弃未知项，也不能由重复显示名扩大到多列。
            java.util.Set<String> matched = new java.util.LinkedHashSet<>();
            for (MetadataColumnRespDTO column : columns) {
                if (token.equals(column.getColumnName()) || token.equals(column.getBusinessName())) {
                    matched.add(column.getColumnName());
                }
            }
            if (matched.size() != 1) {
                throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
            }
            names.add(matched.iterator().next());
        }
        if (names.isEmpty()) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        return java.util.List.copyOf(names);
    }

    /** 冻结与续期复核共享同一元数据入口；复核只检查旧列仍存在，不把新增列纳入批准范围。 */
    private java.util.List<MetadataColumnRespDTO> readPublishedColumns(DataAssetDO asset) {
        if (asset == null) {
            throw exception(MARKET_APPLY_ASSET_NOT_PUBLISHED);
        }
        java.util.List<MetadataColumnRespDTO> columns;
        try {
            columns = catalogService.listColumns(asset.getId(), asset.getPublishedSnapshotId());
        } catch (RuntimeException ex) {
            // 不记录元数据原文，只保留可定位的资产编号与错误类型。
            log.warn("[freezeApprovedColumns][读取资源字段失败 assetId({}) type({})]",
                    asset.getId(), ex.getClass().getSimpleName());
            throw exception(MARKET_APPLY_METADATA_UNAVAILABLE);
        }
        if (columns == null || columns.isEmpty()) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        java.util.Set<String> physicalNames = new java.util.LinkedHashSet<>();
        for (MetadataColumnRespDTO column : columns) {
            if (column == null || StrUtil.isBlank(column.getColumnName())
                    || "*".equals(column.getColumnName()) || !physicalNames.add(column.getColumnName())) {
                throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
            }
        }
        return columns;
    }

    /**
     * 校验市场资产申请的所属人，不满足约束时拒绝当前操作。
     */
    private void assertOwner(MarketResourceApplyDO apply) {
        if (!Objects.equals(apply.getApplyUserId(), objectGuard.requireUserId())) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
    }

    /**
     * 校验市场资产申请的时间，不满足约束时拒绝当前操作。
     */
    private void validateTime(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && !end.isAfter(start)) {
            throw exception(MARKET_APPLY_TIME_INVALID);
        }
    }

    /**
     * 校验市场资产申请的交付，不满足约束时拒绝当前操作。
     */
    private void validateDelivery(Long apiId, Long appId, Long assetId, Long userId) {
        if ((apiId == null) != (appId == null)) {
            throw exception(MARKET_APPLY_API_APP_REQUIRED);
        }
        if (apiId == null) {
            return;
        }
        if (endpointMapper.selectByAssetAndApi(assetId, apiId) == null) {
            throw exception(MARKET_APPLY_API_NOT_BOUND);
        }
        DataAssetDO asset = assetMapper.selectById(assetId);
        SupplyApiBindingRespDTO binding = supplyApiGuard.requireQueryableTableApi(apiId);
        supplyApiGuard.requireMatchesAsset(asset, binding);
        List<SupplyAppRespDTO> apps = supplyAppApi.listAppsByCreator(userId).getCheckedData();
        boolean owned = apps != null && apps.stream().anyMatch(app -> Objects.equals(app.getId(), appId));
        if (!owned) {
            throw exception(MARKET_APPLY_APP_FORBIDDEN);
        }
    }

    /**
     * 解析市场资产申请的部门编号。
     */
    private Long resolveDeptId(Long requestedDeptId) {
        Long deptId = SecurityFrameworkUtils.getLoginUserDeptId();
        if (requestedDeptId != null && deptId != null && !Objects.equals(requestedDeptId, deptId)) {
            throw exception(MARKET_APPLY_DEPT_INVALID);
        }
        return deptId != null ? deptId : requestedDeptId;
    }

    /**
     * 补充市场资产申请的联系方式。
     */
    private void fillContact(MarketResourceApplyDO apply) {
        try {
            AdminUserRespDTO user = adminUserApi.getUser(apply.getApplyUserId()).getCheckedData();
            if (user == null) {
                return;
            }
            if (apply.getApplyDeptId() == null) {
                apply.setApplyDeptId(user.getDeptId());
            }
            if (StrUtil.isBlank(apply.getPhone())) {
                apply.setPhone(user.getMobile());
            }
            if (StrUtil.isBlank(apply.getEmail())) {
                apply.setEmail(user.getEmail());
            }
        } catch (RuntimeException ex) {
            log.warn("[fillContact] 用户资料不可用：{}", ex.getMessage());
        }
    }

    /**
     * 按预期状态及修订号更新市场资产申请，拒绝并发冲突。
     */
    private void casStatus(MarketResourceApplyDO observed, int fromStatus, int toStatus,
            Consumer<LambdaUpdateWrapper<MarketResourceApplyDO>> extra) {
        LambdaUpdateWrapper<MarketResourceApplyDO> wrapper = submissionCondition(observed, fromStatus)
                .set(MarketResourceApplyDO::getStatus, toStatus);
        if (extra != null) {
            extra.accept(wrapper);
        }
        if (applyMapper.update(null, wrapper) != 1) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
    }

    /**
     * 申请内容/提交轮次保护，不代替已批准来源的 sourceRevision 协议。
     * 元数据及权限读取不持申请行锁；最后条件写入时同时检查对象、状态和所读内容版本。
     * revision 为空的旧草稿只匹配 NULL，首次编辑/提交补正时初始化，不把 NULL 当任意版本。
     */
    private LambdaUpdateWrapper<MarketResourceApplyDO> submissionCondition(MarketResourceApplyDO observed, int status) {
        if (!Objects.equals(observed.getStatus(), status)
                || (observed.getRevision() != null && observed.getRevision() < 1)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return new LambdaUpdateWrapper<MarketResourceApplyDO>()

                .eq(MarketResourceApplyDO::getId, observed.getId())
                .eq(MarketResourceApplyDO::getStatus, status)
                .eq(MarketResourceApplyDO::getApplyUserId, observed.getApplyUserId())
                .eq(MarketResourceApplyDO::getAssetId, observed.getAssetId())
                .eq(observed.getRevision() != null, MarketResourceApplyDO::getRevision, observed.getRevision())
                .isNull(observed.getRevision() == null, MarketResourceApplyDO::getRevision);
    }

    /**
     * 客户端所见版本（可选）：提供时必须等于入口读取的当前值，否则说明页面已过期；
     * 缺省时保持原服务端读取后的 CAS 语义，兼容未升级的调用方。最终写入仍由 CAS 条件裁决。
     */
    private static void assertSeenRevision(MarketResourceApplyDO apply, Integer expectedRevision) {
        if (expectedRevision != null && !Objects.equals(expectedRevision, apply.getRevision())) {
            throw exception(MARKET_APPLY_REVISION_CONFLICT);
        }
    }

    /**
     * 校验市场资产申请的已见到来源修订号，不满足约束时拒绝当前操作。
     */
    private static void assertSeenSourceRevision(MarketResourceApplyDO apply, Long expectedSourceRevision) {
        if (expectedSourceRevision != null && !Objects.equals(expectedSourceRevision, apply.getSourceRevision())) {
            throw exception(MARKET_APPLY_REVISION_CONFLICT);
        }
    }

    /**
     * 生成下一个内容修订号。
     */
    private int nextContentRevision(MarketResourceApplyDO apply) {
        Integer revision = apply.getRevision();
        if (revision == null) {
            return 1;
        }
        if (revision < 1 || revision == Integer.MAX_VALUE) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return revision + 1;
    }

    /**
     * 生成下一个申请编号。
     */
    private String nextApplyNo() {
        Long sequence = applyMapper.nextApplyNoSeq();
        if (sequence == null || sequence <= 0) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        return displayHelper.nextBizNo("APP", sequence);
    }

}
