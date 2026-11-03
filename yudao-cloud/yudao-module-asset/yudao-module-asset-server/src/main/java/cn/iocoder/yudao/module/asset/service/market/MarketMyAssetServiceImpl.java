package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckProcessRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyApplicationRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetApiDocumentRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_AUTHORIZED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_SCOPE_UNCONFIRMED;

/** 我的资产是本人批准来源的只读视图，历史记录可见性与实时消费授权分别处理。 */
@Service
@Validated
public class MarketMyAssetServiceImpl implements MarketMyAssetService {

    @Resource
    private MarketMyAssetMapper myAssetMapper;

    @Resource
    private MarketApplyService applyService;

    @Override
    public PageResult<MarketMyApplicationRespVO> getApplications(MarketApplyPageReqVO req) {
        return applyService.getMyApplications(req);
    }

    @Override
    public MarketApplyRespVO getApplication(Long id) {
        return applyService.getMyApplication(id);
    }

    @Resource
    private MarketResourceApplyMapper applyMapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketCategoryService categoryService;

    @Resource
    private MarketDeliveryOperationMapper deliveryMapper;

    @Resource
    private MarketApprovalEvidenceMapper approvalMapper;

    @Resource
    private MarketCheckProcessMapper processMapper;

    @Resource
    private MarketDeliveryService deliveryService;

    @Resource
    private MarketAccessService accessService;

    @Resource
    private SupplyDataApiApi supplyDataApiApi;

    /**
     * 按筛选条件分页查询本人获批资产。
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public PageResult<MarketMyAssetRespVO> getPage(MarketMyAssetPageReqVO req) {
        long userId = requireUserId();
        var categoryIds = req.getMarketCategoryId() == null ? null : categoryService.descendants(
                req.getMarketCategoryId());
        LocalDateTime now = requireNow();
        long count = myAssetMapper.countOwned(userId, req, categoryIds);
        if (count == 0) {
            return PageResult.empty();
        }
        long offset = (long) (req.getPageNo() - 1) * req.getPageSize();
        List<MarketMyAssetRow> rows = myAssetMapper.selectOwnedPage(userId, req, categoryIds, offset);
        return new PageResult<>(present(rows, now), count);
    }

    /**
     * 本人的批准详情；读取历史不恢复消费授权。
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MarketMyAssetDetailRespVO get(Long applyId) {
        MarketMyAssetRow row = owned(applyId);
        MarketMyAssetRespVO summary = present(List.of(row), requireNow()).getFirst();
        var result = BeanUtils.toBean(summary, MarketMyAssetDetailRespVO.class);
        result.setDescription(row.getDescription());
        result.setAssetVersion(row.getAssetVersion());
        result.setPurpose(row.getPurpose());
        result.setDutyMatter(row.getDutyMatter());
        result.setLegalBasis(row.getLegalBasis());
        try {
            result.setApprovedColumns(List.copyOf(MarketFieldScope.requireFrozenColumns(row.getSnapshot())));
        } catch (ServiceException invalidHistory) {
            result.setApprovedColumns(List.of());
        }
        result.setProcesses(processMapper.selectListByApplyId(row.getId()).stream()
                .map(item -> BeanUtils.toBean(item, MarketCheckProcessRespVO.class)).toList());
        return result;
    }

    /**
     * 按本人指定批准实时核验并预览库表。
     */
    @Override
    public DatabasePreviewRespDTO preview(Long applyId, Integer limit) {
        requireUserId();
        return accessService.previewAuthorizedApply(applyId, limit);
    }

    /**
     * 实时核对本人批准及固定 API 合同后查看文档。
     */
    @Override
    public MarketMyAssetApiDocumentRespVO apiDocument(Long applyId) {
        MarketMyAssetRow row = owned(applyId);
        if (row.getApiId() == null || row.getAppId() == null || !deliveryService.isConfirmedApply(row)) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        // 远端文档读取前后分别复验本地批准；不在持锁事务内调用 Supply。
        MarketQuerySnapshot authorization = accessService.requireQuerySnapshot(requireUserId(), applyId);
        MarketQuerySnapshot.Grant entry = authorization.grants().stream().filter(grant -> grant.applyId() == applyId)
                .findFirst().orElseThrow(() -> exception(MARKET_APPLY_NOT_AUTHORIZED));
        var document = supplyDataApiApi.getPublishedApiDocument(row.getApiId()).getCheckedData();
        var policy = entry.policy();
        if (document == null || !document.isFieldMappingVerified()
                || !Objects.equals(document.getId(), row.getApiId())
                || !Objects.equals(policy.apiId(), row.getApiId())
                || !Objects.equals(policy.appId(), row.getAppId())
                || StrUtil.isBlank(policy.contractHash()) || !Objects.equals(document.getContractHash(),
                policy.contractHash())
                || StrUtil.isBlank(policy.apiVersion()) || !Objects.equals(document.getVersion(), policy.apiVersion())
                || document.getActiveReleaseId() == null
                || !Objects.equals(String.valueOf(document.getActiveReleaseId()), policy.releaseId())
                || !Objects.equals(document.getDatabaseId(), authorization.databaseId())
                || !Objects.equals(document.getSchemaName(), authorization.schemaName())
                || !Objects.equals(document.getTableName(), authorization.tableName())
                || document.getOutputFieldMapping() == null || document.getResponseFields() == null
                || document.getRequestParams() == null) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        var approvedColumns = MarketFieldScope.requireFrozenColumns(entry.frozenEvidence());
        var result = new MarketMyAssetApiDocumentRespVO();
        result.setApplyId(applyId);
        result.setApiId(document.getId());
        result.setName(document.getName());
        result.setPath(document.getPath());
        result.setMethod(document.getMethod());
        result.setVersion(document.getVersion());
        result.setRequestParams(document.getRequestParams().stream()
                .filter(item -> item != null && approvedColumns.contains(item.getName()))
                .map(item -> BeanUtils.toBean(item, MarketMyAssetApiDocumentRespVO.RequestParam.class)).toList());
        result.setResponseFields(document.getResponseFields().stream()
                .filter(item -> item != null && approvedColumns.contains(
                        document.getOutputFieldMapping().get(item.getName())))
                .map(item -> BeanUtils.toBean(item, MarketMyAssetApiDocumentRespVO.ResponseField.class)).toList());
        if (result.getResponseFields().isEmpty()) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        accessService.assertStillCurrent(authorization);
        return result;
    }

    /**
     * 构造仅当前执行令牌及有效租约可更新的本人获批资产条件。
     */
    private MarketMyAssetRow owned(Long applyId) {
        long userId = requireUserId();
        if (applyId == null || applyId <= 0) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        MarketMyAssetRow row = myAssetMapper.selectOwned(userId, applyId);
        if (row == null || !Objects.equals(row.getApplyUserId(), userId)) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        return row;
    }

    /**
     * 校验本人获批资产的当前时点，不满足约束时拒绝当前操作。
     */
    private LocalDateTime requireNow() {
        LocalDateTime now = applyMapper.databaseNow();
        if (now == null) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        return now;
    }

    /**
     * 读取并校验当前登录用户编号。
     */
    private static long requireUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || userId <= 0) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        return userId;
    }

    /**
     * 将本人资产申请与资源信息组装为展示响应。
     */
    private List<MarketMyAssetRespVO> present(List<MarketMyAssetRow> rows, LocalDateTime now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var assetIds = rows.stream().map(MarketMyAssetRow::getAssetId).distinct().toList();
        Map<Long, DataAssetDO> assets = assetMapper.selectByIds(assetIds).stream()
                .collect(Collectors.toMap(DataAssetDO::getId, Function.identity()));
        var applyIds = rows.stream().map(MarketMyAssetRow::getId).toList();
        Map<Long, MarketDeliveryOperationDO> operations = deliveryMapper.selectLatestByApplies(applyIds).stream()
                .collect(Collectors.toMap(MarketDeliveryOperationDO::getApplyId, Function.identity()));
        Map<Long, MarketApprovalEvidenceRow> approvals = approvalMapper.selectByApplies(applyIds).stream()
                .collect(Collectors.toMap(MarketApprovalEvidenceRow::getApplyId, Function.identity()));
        return rows.stream().map(row -> {
            var result = BeanUtils.toBean(row, MarketMyAssetRespVO.class);
            result.setApplyId(row.getId());
            result.setEvaluatedAt(now);
            String resolution = deliveryService.currentDeliveryResolution(row, operations.get(row.getId()),
                    approvals.get(row.getId()));
            result.setDeliveryResolution(resolution);
            result.setEffective(effectiveAt(row, now));
            String reason = unavailableReason(row, assets.get(row.getAssetId()));
            String state;
            if (Objects.equals(row.getStatus(), MarketResourceApplyDO.STATUS_REVOKED)) {
                state = "REVOKED";
                reason = "授权已收回，仅可查看申请历史";
            } else if (Objects.equals(row.getStatus(), MarketResourceApplyDO.STATUS_EXPIRED)
                    || (row.getApplyEndTime() != null && !now.isBefore(row.getApplyEndTime()))) {
                state = "EXPIRED";
                reason = "授权已到期，仅可查看申请历史";
            } else if (Objects.equals(row.getPaused(), 1)) {
                state = "PAUSED";
                reason = "授权已暂停";
            } else if (row.getApplyStartTime() != null && now.isBefore(row.getApplyStartTime())) {
                state = "NOT_STARTED";
                reason = "尚未到授权生效时间";
            } else if (!Boolean.TRUE.equals(result.getEffective()) || reason != null) {
                state = "UNAVAILABLE";
                if (reason == null) {
                    reason = "授权尚未开通或已停用";
                }
            } else if ("PENDING".equals(resolution)) {
                state = "DELIVERY_PENDING";
                reason = "交付尚未完成，暂不可使用";
            } else if (resolution != null && !"APPLIED".equals(resolution)) {
                state = "DELIVERY_UNKNOWN";
                reason = "交付结果未能确认，请联系提供方核查";
            } else {
                state = "AVAILABLE";
            }
            result.setAccessStatus(state);
            boolean usable = "AVAILABLE".equals(state);
            result.setUsable(usable);
            result.setCanPreview(usable && Objects.equals(row.getDeliveryType(), 1));
            result.setCanViewApiDocument(usable && Objects.equals(row.getDeliveryType(), 2));
            result.setCanDownload(false);
            result.setDisabledReason(reason);
            return result;
        }).toList();
    }

    /**
     * 判断获批申请在指定时点是否仍然生效。
     */
    private static boolean effectiveAt(MarketResourceApplyDO row, LocalDateTime now) {
        return Objects.equals(row.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                && Objects.equals(row.getIfAuth(), MarketResourceApplyDO.AUTH_YES)
                && !Objects.equals(row.getPaused(), 1)
                && (row.getApplyStartTime() == null || !now.isBefore(row.getApplyStartTime()))
                && (row.getApplyEndTime() == null || now.isBefore(row.getApplyEndTime()));
    }

    /**
     * 根据申请及资产当前状态生成不可消费原因。
     */
    private static String unavailableReason(MarketMyAssetRow row, DataAssetDO asset) {
        if (Objects.equals(row.getDeliveryType(), 3)) {
            return "当前尚未提供文件交付，无法获取文件";
        }
        if (!Objects.equals(row.getDeliveryType(), 1) && !Objects.equals(row.getDeliveryType(), 2)) {
            return "交付形态无法核验";
        }
        if (!Boolean.TRUE.equals(row.getFrozenCatalogValid())) {
            return "批准目录快照不完整，请联系提供方核查";
        }
        if (!MarketFieldScope.supportsExistingGrant(asset)) {
            return "资产已停止供数、退役或删除";
        }
        if (row.getSourceRevision() == null || row.getSourceRevision() <= 0 || row.getRevision() == null || row.getRevision() <= 0) {
            return "授权版本证据不完整，请联系提供方核查";
        }
        if (Objects.equals(row.getDeliveryType(), 2) && (row.getApiId() == null || row.getAppId() == null)) {
            return "API 授权绑定不完整，请联系提供方核查";
        }
        try {
            MarketFieldScope.requireMatchesAsset(row, asset);
        } catch (ServiceException invalidEvidence) {
            return "批准内容与当前发布版本不一致，请重新申请";
        }
        return null;
    }
}
