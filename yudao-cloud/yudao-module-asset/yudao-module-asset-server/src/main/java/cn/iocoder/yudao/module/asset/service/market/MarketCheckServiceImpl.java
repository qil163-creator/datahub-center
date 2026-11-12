package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketApprovalWorkOrderDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalWorkOrderMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckTicketRow;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 审批领域读模型与来源意见适配；本地审批始终委托原有申请服务。 */
@Service
public class MarketCheckServiceImpl implements MarketCheckService {

    @Resource
    private MarketApprovalWorkOrderMapper workOrderMapper;

    @Resource
    private MarketApplyService applyService;

    @Resource
    private MarketObjectGuard objectGuard;

    @Resource
    private MarketDisplayHelper displayHelper;

    @Resource
    private DataAssetMapper assetMapper;

    @jakarta.annotation.Resource
    private MarketSnapshotMaskSupport snapshotMasks;

    @Resource
    private MarketAuditRecorder auditRecorder;

    /**
     * 按筛选条件分页查询市场审批工单。
     */
    @Override
    public PageResult<MarketCheckTicketRespVO> getPage(MarketCheckPageReqVO req) {
        long actor = requireChecker();
        List<Long> providerIds = objectGuard.providerAssetIds(actor);
        long total = workOrderMapper.countTickets(req, providerIds);
        if (total == 0) {
            return PageResult.empty();
        }
        long offset = (long) (req.getPageNo() - 1) * req.getPageSize();
        List<MarketCheckTicketRespVO> tickets = workOrderMapper.selectTickets(req, providerIds, offset).stream()
                .map(row -> "QDATA".equals(row.getOrigin()) ? sourceTicket(source(row.getId()))
                        : nativeTicket(applyService.getApply(nativeId(row.getId())), row.getMartName(), actor))
                .toList();
        return new PageResult<>(tickets, total);
    }

    /**
     * 服务审批只读详情；来源快照不代表当前访问授权。
     */
    @Override
    public MarketCheckDetailRespVO get(String id, String origin) {
        long actor = requireChecker();
        if ("QDATA".equals(origin)) {
            MarketApprovalWorkOrderDO order = source(id);
            MarketCheckDetailRespVO result = sourceDetail(order);
            MarketCheckTicketRespVO ticket = sourceTicket(order);
            result.setTicket(ticket);
            result.setCanInvoke(false);
            if (result.getPreview() != null) {
                // 来源快照按当前审批人与当前分类策略脱敏后再展示和写入工单快照视图。
                snapshotMasks.mask(assetMapper.selectByExternalCode("qdata:market:" + order.getMartSourceId()),
                        result.getPreview(), actor);
            }
            result.setCanPreview(Boolean.TRUE.equals(ticket.getCanView()) && result.getPreview() != null);
            if (result.getColumns() == null) {
                result.setColumns(List.of());
            }
            if (result.getResource() == null) {
                result.setResource(new MarketCheckDetailRespVO.ResourceSummary());
            }
            result.getResource().setSourceRecordId(order.getMartSourceId());
            result.getResource().setMarketName(order.getMartName());
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("resource", result.getResource());
            snapshot.put("columns", result.getColumns());
            if (result.getPreview() != null) {
                snapshot.put("preview", result.getPreview());
            }
            if (result.getApiDocument() != null) {
                snapshot.put("apiDocument", result.getApiDocument());
            }
            ticket.setDetailSnapshot(snapshot);
            return result;
        }
        if (!"NATIVE".equals(origin)) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
        MarketApplyRespVO apply = applyService.getApply(nativeId(id));
        var result = new MarketCheckDetailRespVO();
        result.setTicket(nativeTicket(apply, null, actor));
        var summary = new MarketCheckDetailRespVO.ResourceSummary();
        summary.setAssetName(apply.getAssetName());
        summary.setMarketName(apply.getAssetName());
        summary.setAssetType(apply.getAssetType());
        result.setResource(summary);
        // 申请详情只返回原服务已授权的信息；不借审批入口调用维护详情、实时预览或获取应用凭证。
        return result;
    }

    /**
     * 可见工单申请人筛选，最多返回五百项；来源人员不作为本地身份。
     */
    @Override
    public List<MarketCheckApplicantRespVO> applicants() {
        long actor = requireChecker();
        List<MarketCheckTicketRow> rows = workOrderMapper.selectApplicants(objectGuard.providerAssetIds(actor));
        List<Long> localIds = rows.stream().map(MarketCheckTicketRow::getApplicantKey)
                .filter(key -> key != null && key.startsWith("NATIVE:"))
                .map(key -> nativeId(key.substring("NATIVE:".length()))).distinct().toList();
        Map<Long, String> names = displayHelper.userNames(localIds);
        return rows.stream().map(row -> {
            String key = row.getApplicantKey();
            String name = key != null && key.startsWith("NATIVE:")
                    ? displayHelper.userLabel(names, nativeId(key.substring("NATIVE:".length())))
                    : row.getApplicantName();
            return new MarketCheckApplicantRespVO(key, StrUtil.blankToDefault(name, "（未指定）"));
        }).toList();
    }

    /**
     * 保存审批意见；来源工单不授予真实数据权限，本地申请复用原审批链。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void check(MarketCheckDecisionReqVO req) {
        long actor = requireChecker();
        if (!"3".equals(req.getStatus()) && !"4".equals(req.getStatus())) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (req.getExpectedRevision() == null || req.getExpectedRevision() < 1) {
            throw exception(MARKET_APPLY_REVISION_CONFLICT);
        }
        if ("NATIVE".equals(req.getOrigin())) {
            var decision = new MarketCheckReqVO();
            decision.setId(nativeId(req.getId()));
            decision.setStatus("3".equals(req.getStatus()) ? MarketResourceApplyDO.STATUS_APPROVED
                    : MarketResourceApplyDO.STATUS_REJECTED);
            decision.setCheckNote(req.getApprovalReason());
            decision.setExpectedRevision(req.getExpectedRevision());
            applyService.checkApply(decision);
            return;
        }
        if (!"QDATA".equals(req.getOrigin())) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
        MarketApprovalWorkOrderDO order = source(req.getId());
        if (!Objects.equals(order.getRevision(), req.getExpectedRevision())) {
            throw exception(MARKET_APPLY_REVISION_CONFLICT);
        }
        if (!pendingSource(order)) {
            throw exception(MARKET_APPLY_STATUS_DENY);
        }
        if (workOrderMapper.decideSource(order.getId(), req.getExpectedRevision(), req.getStatus(),
                req.getApprovalReason(), actor) != 1) {
            throw exception(MARKET_APPLY_REVISION_CONFLICT);
        }
        // 来源工单只形成独立本地意见；既不生成本地申请，也不补造批准凭证或投递授权。
        auditRecorder.record("SOURCE_WORK_ORDER_REVIEW", "SOURCE_WORK_ORDER", order.getId(),
                Integer.valueOf(order.getSourceStatus()), Integer.valueOf(req.getStatus()),
                req.getApprovalReason(), "SAVED_WITHOUT_GRANT");
    }

    /**
     * 校验市场审批工单的审核人，不满足约束时拒绝当前操作。
     */
    private long requireChecker() {
        long actor = objectGuard.requireUserId();
        if (!objectGuard.hasPermission(MarketObjectGuard.PERM_APPLY_CHECK)) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        return actor;
    }

    /**
     * 读取市场审批工单来源记录。
     */
    private MarketApprovalWorkOrderDO source(String id) {
        MarketApprovalWorkOrderDO result = workOrderMapper.selectSource(id);
        if (result == null) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
        return result;
    }

    /**
     * 将迁入申请组装为审批工单展示信息。
     */
    private MarketCheckTicketRespVO sourceTicket(MarketApprovalWorkOrderDO order) {
        var result = new MarketCheckTicketRespVO();
        result.setId(order.getSourceRecordId());
        result.setOrigin("QDATA");
        result.setDisplayId(order.getSourceRecordId());
        result.setSourceRecordId(order.getSourceRecordId());
        result.setMartName(order.getMartName());
        result.setApplicantKey("QDATA:" + order.getApplicantKey());
        result.setApplicantName(order.getApplicantName());
        result.setApplicantDeptName(order.getApplicantDeptName());
        result.setApplyType(order.getApplyType());
        result.setStartTime(order.getStartTime());
        result.setEndTime(order.getEndTime());
        result.setApplyTime(order.getApplyTime());
        result.setApplyReason(order.getApplyReason());
        result.setApprovalReason(
                order.getLocalStatus() == null ? order.getSourceApprovalReason() : order.getLocalApprovalReason());
        result.setStatus(order.getLocalStatus() == null ? order.getSourceStatus() : order.getLocalStatus());
        result.setStatusLabel(statusLabel(result.getStatus()));
        result.setStatusTone(statusTone(result.getStatus()));
        result.setSourceStatus(order.getSourceStatus());
        result.setSourceStatusLabel(statusLabel(order.getSourceStatus()));
        result.setRevision(order.getRevision());
        result.setCanView(!"5".equals(order.getSourceStatus()) && hasSourceDetail(order));
        result.setCanApprove(pendingSource(order));
        result.setCanReject(pendingSource(order));
        return result;
    }

    /**
     * 将本地申请组装为审批工单展示信息。
     */
    private MarketCheckTicketRespVO nativeTicket(MarketApplyRespVO apply, String martName, long actor) {
        var result = new MarketCheckTicketRespVO();
        result.setId(String.valueOf(apply.getId()));
        result.setOrigin("NATIVE");
        result.setDisplayId(StrUtil.blankToDefault(apply.getApplyNo(), result.getId()));
        result.setMartName(StrUtil.blankToDefault(martName, apply.getAssetName()));
        result.setApplicantKey("NATIVE:" + apply.getApplyUserId());
        result.setApplicantName(apply.getApplyUserName());
        result.setApplicantDeptName(apply.getApplyDeptName());
        result.setApplyType(apply.getApplyStartTime() == null && apply.getApplyEndTime() == null ? 1 : 2);
        result.setStartTime(apply.getApplyStartTime());
        result.setEndTime(apply.getApplyEndTime());
        result.setApplyTime(apply.getCreateTime());
        result.setApplyReason(StrUtil.blankToDefault(apply.getApplyNote(), apply.getPurpose()));
        result.setApprovalReason(apply.getCheckNote());
        result.setStatus(nativeStatus(apply.getStatus()));
        result.setStatusLabel(switch (apply.getStatus() == null ? -1 : apply.getStatus()) {
            case 0 -> "草稿";
            case 4 -> "已撤销";
            case 6 -> "已收回";
            case 8 -> "待补正";
            default -> statusLabel(result.getStatus());
        });
        result.setStatusTone(statusTone(result.getStatus()));
        result.setRevision(apply.getRevision() == null ? 1 : apply.getRevision());
        boolean provider = apply.getAssetId() != null && objectGuard.isProvider(
                assetMapper.selectById(apply.getAssetId()), actor);
        boolean pending = Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_PENDING) && provider;
        result.setCanApprove(pending);
        result.setCanReject(pending);
        result.setCanView(true);
        result.setNativeApply(apply);
        return result;
    }

    /**
     * 解析并校验迁入审批工单的详情快照。
     */
    private MarketCheckDetailRespVO sourceDetail(MarketApprovalWorkOrderDO order) {
        if (StrUtil.isBlank(order.getDetailSnapshot())) {
            return new MarketCheckDetailRespVO();
        }
        try {
            MarketCheckDetailRespVO detail = JsonUtils.getObjectMapper()
                    .readValue(order.getDetailSnapshot(), MarketCheckDetailRespVO.class);
            return detail == null ? new MarketCheckDetailRespVO() : detail;
        } catch (JacksonException invalid) {
            // 不把原始快照写入异常或日志，避免来源数据随解析错误外泄。
            throw exception(MARKET_CHECK_SNAPSHOT_INVALID);
        }
    }

    /**
     * 判断市场审批工单是否具有来源详情。
     */
    private boolean hasSourceDetail(MarketApprovalWorkOrderDO order) {
        return sourceDetail(order).getResource() != null;
    }

    /**
     * 判断迁入审批工单是否仍处于待处理状态。
     */
    private static boolean pendingSource(MarketApprovalWorkOrderDO order) {
        return order.getLocalStatus() == null && ("1".equals(order.getSourceStatus()) || "2".equals(
                order.getSourceStatus()));
    }

    /**
     * 将审批工单标识解析为本地申请编号。
     */
    private static long nativeId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            throw exception(MARKET_APPLY_NOT_EXISTS);
        }
    }

    /**
     * 将本地申请状态转换为审批工单状态。
     */
    static String nativeStatus(Integer status) {
        return switch (status == null ? -1 : status) {
            case 1, 8 -> "2";
            case 2 -> "3";
            case 3 -> "4";
            case 4, 6 -> "5";
            case 5 -> "6";
            default -> "1";
        };
    }

    /**
     * 将审批工单状态转换为界面文案。
     */
    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "1" -> "未授权";
            case "2" -> "待授权";
            case "3" -> "已通过";
            case "4" -> "已驳回";
            case "5" -> "已下架";
            case "6" -> "已过期";
            default -> "未知状态";
        };
    }

    /**
     * 将审批工单状态转换为界面显示色调。
     */
    private static String statusTone(String status) {
        return switch (status == null ? "" : status) {
            case "3" -> "success";
            case "4" -> "error";
            case "1", "2" -> "warning";
            default -> "default";
        };
    }
}
