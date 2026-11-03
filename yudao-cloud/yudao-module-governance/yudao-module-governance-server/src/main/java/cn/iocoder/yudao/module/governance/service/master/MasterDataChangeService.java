package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 主数据变更工作流。平台事务只登记意图、状态与审计；BPM 和外部数据库在事务外调用。
 * 未知结果保留持久快照与行占用，禁止自动重试；通过显式对账释放。
 */
@Service
@Validated
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MasterDataChangeService {

    @Resource
    private MasterDataChangeMapper changeMapper;

    @Resource
    private MasterChangeLogMapper logMapper;

    @Resource
    private MasterModelMapper modelMapper;

    @Resource
    private MasterColumnMapper columnMapper;

    @Resource
    private MasterModelService modelService;

    @Resource
    private MasterDataService dataService;

    @Resource
    private MasterCodeRuleService codeRuleService;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private BpmProcessInstanceApi bpmApi;

    @Resource
    private AdminUserApi userApi;

    /**
     * 校验并提交主数据变更请求。
     */
    public MasterDataMutationRespVO submit(MasterDataChangeSubmitReqVO req) {
        Long actor = requireActor();
        if (req.getRows() == null || req.getRows().isEmpty() || req.getRows().size() > 200
                || req.getRows().stream().anyMatch(Objects::isNull)
                || !Set.of("CREATE", "UPDATE", "DELETE").contains(req.getOperation())) {
            throw exception(MASTER_DATA_ROW_INVALID, "请选择操作并提交1至200条有效记录");
        }
        String requestId = UUID.fromString(req.getRequestId()).toString();
        String payloadHash = digest(
                actor + ":" + req.getModelId() + ":" + req.getOperation() + ":" + canonical(req.getRows()));
        MasterDataChangeDO duplicate = changeMapper.selectByRequest(requestId);
        if (duplicate != null) {
            return mutation(requireSameRequest(duplicate, payloadHash));
        }
        MasterModelDO snapshotModel = modelService.validateModelReleased(req.getModelId());
        List<Map<String, Object>> suppliedRows = "CREATE".equals(req.getOperation())
                ? codeRuleService.generateForCreate(req.getModelId(), req.getRows()) : req.getRows();
        dataService.validateRows(req.getModelId(), suppliedRows, req.getOperation());
        List<Map<String, Object>> rows = wire(
                dataService.normalizeRows(req.getModelId(), suppliedRows, req.getOperation()));
        List<Map<String, Object>> before = wire(dataService.snapshotRows(req.getModelId(), rows));
        for (Map<String, Object> old : before) {
            if ("CREATE".equals(req.getOperation()) ? old != null : old == null) {
                throw exception(MASTER_DATA_ROW_INVALID,
                        "CREATE".equals(req.getOperation()) ? "主键已存在" : "原数据不存在");
            }
        }
        List<String> keys = rowKeys(req.getModelId(), rows);
        if (new HashSet<>(keys).size() != keys.size()) {
            throw exception(MASTER_DATA_ROW_INVALID, "同批记录主键重复");
        }
        if (JsonUtils.toJsonString(rows).getBytes(StandardCharsets.UTF_8).length > 1_048_576
                || JsonUtils.toJsonString(before).getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw exception(MASTER_DATA_ROW_INVALID, "记录快照超过1MB");
        }
        MasterDataChangeDO change = tx().execute(status -> {
            MasterModelDO model = lockModel(req.getModelId());
            MasterDataChangeDO existing = changeMapper.selectByRequest(requestId);
            if (existing != null) {
                return requireSameRequest(existing, payloadHash);
            }
            if (model.getStatus() != MasterModelDO.STATUS_RELEASED || !"READY".equals(model.getPublishState())) {
                throw exception(MASTER_MODEL_NOT_RELEASED);
            }
            if (!Objects.equals(snapshotModel.getVersion(), model.getVersion())) {
                throw exception(MASTER_DATA_ROW_CHANGED);
            }
            for (MasterDataChangeDO active : changeMapper.selectActive(model.getId())) {
                List<String> activeKeys = JsonUtils.parseArray(active.getRowKeysJson(), String.class);
                if (!Collections.disjoint(keys, activeKeys)) {
                    throw exception(MASTER_DATA_ROW_PENDING);
                }
            }
            MasterDataChangeDO created = new MasterDataChangeDO();
            created.setModelId(model.getId());
            created.setSchemaVersion(model.getVersion());
            created.setRequestId(requestId);
            created.setOperation(req.getOperation());
            created.setPayloadHash(payloadHash);
            created.setRowsJson(JsonUtils.toJsonString(rows));
            created.setBeforeJson(JsonUtils.toJsonString(before));
            created.setRowKeysJson(JsonUtils.toJsonString(keys));
            created.setApplicantId(actor);
            created.setApprovalRequired(Boolean.TRUE.equals(model.getApprovalEnabled()));
            created.setBpmProcessKey(created.getApprovalRequired() ? model.getApprovalProcessKey() : null);
            created.setStatus(!created.getApprovalRequired() ? "APPROVED"
                    : blank(created.getBpmProcessKey()) ? "PENDING" : "WAIT_START");
            changeMapper.insert(created);
            return created;
        });
        if ("WAIT_START".equals(change.getStatus())) {
            startOrFindProcess(change, true);
        }
        if ("APPROVED".equals(change.getStatus())) {
            apply(change.getId());
        }
        return mutation(requireChange(change.getId()));
    }

    /**
     * 按请求标识读取主数据变更详情。
     */
    public MasterApprovalRespVO get(Long id) {
        requireActor();
        MasterDataChangeDO change = requireChange(id);
        return approval(change, users(List.of(change)));
    }

    /**
     * 按筛选条件分页查询主数据变更。
     */
    public PageResult<MasterApprovalRespVO> page(MasterApprovalPageReqVO req) {
        requireActor();
        PageResult<MasterDataChangeDO> page = changeMapper.selectPage(req);
        Map<Long, AdminUserRespDTO> users = users(page.getList());
        return new PageResult<>(page.getList().stream().map(change -> approval(change, users)).toList(),
                page.getTotal());
    }

    /**
     * 审批通过主数据变更并记录结果。
     */
    public MasterApprovalRespVO approve(MasterApprovalReviewReqVO req) {
        review(req, true);
        apply(req.getId());
        return get(req.getId());
    }

    /**
     * 驳回主数据变更并记录原因。
     */
    public MasterApprovalRespVO reject(MasterApprovalReviewReqVO req) {
        review(req, false);
        return get(req.getId());
    }

    /**
     * 校验并记录主数据变更复核结论。
     */
    private void review(MasterApprovalReviewReqVO req, boolean approve) {
        Long actor = requireActor();
        tx().executeWithoutResult(status -> {
            MasterDataChangeDO change = lockChange(req.getId());
            String target = approve ? "APPROVED" : "REJECTED";
            if (target.equals(change.getStatus()) || approve && "APPLIED".equals(change.getStatus())) {
                return;
            }
            if (!blank(change.getBpmProcessKey())) {
                throw exception(MASTER_DATA_BPM_REVIEW_REQUIRED);
            }
            if (!"PENDING".equals(change.getStatus())) {
                throw exception(MASTER_DATA_CHANGE_STATE_INVALID);
            }
            change.setStatus(target);
            change.setReviewerId(actor);
            change.setReviewTime(LocalDateTime.now());
            change.setReviewComment(req.getRemark());
            changeMapper.updateById(change);
        });
    }

    /** 仅从 BPM 提供方的权威状态推进；客户端不提交流程状态或流程编号。 */
    public MasterApprovalRespVO reconcile(Long id) {
        requireActor();
        MasterDataChangeDO change = requireChange(id);
        if ("APPLYING".equals(change.getStatus())) {
            if (change.getApplyStartedAt() != null && change.getApplyStartedAt()
                    .isBefore(LocalDateTime.now().minusMinutes(5))) {
                tx().executeWithoutResult(status -> {
                    MasterDataChangeDO locked = lockChange(id);
                    if ("APPLYING".equals(locked.getStatus())) {
                        locked.setStatus("UNKNOWN");
                        locked.setLastError("执行超时或进程中断，请核查外部数据后确认结果");
                        changeMapper.updateById(locked);
                    }
                });
            }
            return get(id);
        }
        if ("WAIT_START".equals(change.getStatus())) {
            startOrFindProcess(change, false);
        }
        change = requireChange(id);
        if ("PENDING".equals(change.getStatus()) && !blank(change.getProcessInstanceId())) {
            Integer state = bpmApi.getProcessInstanceStatus(change.getProcessInstanceId()).getCheckedData();
            String target = BpmProcessInstanceStatusEnum.APPROVE.getStatus().equals(state) ? "APPROVED"
                    : BpmProcessInstanceStatusEnum.REJECT.getStatus().equals(state) ? "REJECTED"
                    : BpmProcessInstanceStatusEnum.CANCEL.getStatus().equals(state) ? "CANCELLED" : null;
            if (target != null) {
                tx().executeWithoutResult(status -> {
                    MasterDataChangeDO locked = lockChange(id);
                    if ("PENDING".equals(locked.getStatus())) {
                        locked.setStatus(target);
                        locked.setReviewTime(LocalDateTime.now());
                        locked.setReviewComment("按绑定流程的权威状态同步");
                        changeMapper.updateById(locked);
                    }
                });
            }
        }
        if ("APPROVED".equals(requireChange(id).getStatus())) {
            apply(id);
        }
        return get(id);
    }

    /**
     * 比对当前主数据与预期结果，人工确认未知变更为已应用或已取消并保存依据。
     */
    public MasterApprovalRespVO resolve(MasterApprovalResolveReqVO req) {
        Long actor = requireActor();
        MasterDataChangeDO change = requireChange(req.getId());
        if (!"UNKNOWN".equals(change.getStatus())) {
            throw exception(MASTER_DATA_CHANGE_STATE_INVALID);
        }
        List<Map<String, Object>> current = wire(
                dataService.snapshotRows(change.getModelId(), rows(change.getRowsJson())));
        boolean applied = "APPLIED".equals(req.getOutcome());
        List<Map<String, Object>> expected = applied
                ? "DELETE".equals(change.getOperation()) ? new ArrayList<>(Collections.nCopies(current.size(), null))
                : rows(change.getRowsJson()) : rows(change.getBeforeJson());
        if (!canonical(current).equals(canonical(expected))) {
            throw exception(MASTER_DATA_ROW_CHANGED);
        }
        tx().executeWithoutResult(status -> {
            MasterDataChangeDO locked = lockChange(req.getId());
            if (!"UNKNOWN".equals(locked.getStatus())) {
                throw exception(MASTER_DATA_CHANGE_STATE_INVALID);
            }
            locked.setResolutionOutcome(req.getOutcome());
            locked.setResolutionReason(req.getReason());
            locked.setResolutionEvidence(req.getEvidenceRef());
            locked.setResolvedBy(actor);
            locked.setResolvedAt(LocalDateTime.now());
            if (applied) {
                finishApplied(locked);
            } else {
                locked.setStatus("CANCELLED");
                changeMapper.updateById(locked);
            }
        });
        return get(req.getId());
    }

    /**
     * 查询主数据变更的操作日志。
     */
    public PageResult<MasterChangeLogRespVO> logs(MasterChangeLogPageReqVO req) {
        requireActor();
        PageResult<MasterChangeLogDO> page = logMapper.selectPage(req);
        Set<Long> actorIds = new HashSet<>();
        page.getList().forEach(log -> {
            if (log.getChangedBy() != null) {
                actorIds.add(log.getChangedBy());
            }
        });
        Map<Long, AdminUserRespDTO> users = actorIds.isEmpty() ? Map.of() : userApi.getUserMap(actorIds);
        List<MasterChangeLogRespVO> result = page.getList().stream().map(log -> {
            MasterChangeLogRespVO vo = BeanUtils.toBean(log, MasterChangeLogRespVO.class);
            MasterModelDO model = modelMapper.selectById(log.getModelId());
            vo.setModelName(model == null ? "已删除模型" : model.getTableCn());
            AdminUserRespDTO user = log.getChangedBy() == null ? null : users.get(log.getChangedBy());
            vo.setChangedByName(user == null ? null : user.getNickname());
            return vo;
        }).toList();
        return new PageResult<>(result, page.getTotal());
    }

    /**
     * 启动主数据变更审批流程，或按关联信息查找已启动的流程。
     */
    private void startOrFindProcess(MasterDataChangeDO change, boolean allowCreate) {
        try {
            String businessKey = "master-data:" + change.getId();
            String instanceId = bpmApi.getProcessInstanceIdByBusinessKey(change.getBpmProcessKey(), businessKey)
                    .getCheckedData();
            if (instanceId == null && allowCreate) {
                Boolean reserved = tx().execute(status -> {
                    MasterDataChangeDO locked = lockChange(change.getId());
                    if (!"WAIT_START".equals(locked.getStatus()) || !blank(locked.getApplyToken())) {
                        return false;
                    }
                    locked.setApplyToken(UUID.randomUUID().toString());
                    changeMapper.updateById(locked);
                    return true;
                });
                if (Boolean.TRUE.equals(reserved)) {
                    instanceId = bpmApi.createProcessInstance(change.getApplicantId(),
                            new BpmProcessInstanceCreateReqDTO()
                                    .setProcessDefinitionKey(change.getBpmProcessKey()).setBusinessKey(businessKey)
                                    .setVariables(Map.of("masterChangeId", change.getId(), "masterModelId",
                                            change.getModelId(),
                                            "operation", change.getOperation()))).getCheckedData();
                }
            }
            if (instanceId != null) {
                String boundId = instanceId;
                tx().executeWithoutResult(status -> {
                    MasterDataChangeDO locked = lockChange(change.getId());
                    if ("WAIT_START".equals(locked.getStatus())) {
                        locked.setProcessInstanceId(boundId);
                        locked.setStatus("PENDING");
                        locked.setLastError(null);
                        locked.setApplyToken(null);
                        // 显式置空，MyBatis 默认忽略 null 的实体更新策略。
                        changeMapper.update(null,
                                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MasterDataChangeDO.class)
                                        .eq(MasterDataChangeDO::getId, locked.getId())
                                        .eq(MasterDataChangeDO::getStatus, "WAIT_START")
                                        .set(MasterDataChangeDO::getProcessInstanceId, boundId)
                                        .set(MasterDataChangeDO::getStatus, "PENDING")
                                        .set(MasterDataChangeDO::getApplyToken, null)
                                        .set(MasterDataChangeDO::getLastError, null));
                    }
                });
            }
        } catch (RuntimeException ex) {
            tx().executeWithoutResult(status -> {
                MasterDataChangeDO locked = lockChange(change.getId());
                if ("WAIT_START".equals(locked.getStatus())) {
                    locked.setLastError("流程启动结果待核查，请同步流程状态；不会自动重复启动");
                    changeMapper.updateById(locked);
                }
            });
        }
    }

    /**
     * 应用主数据变更配置或已校验的变更。
     */
    private void apply(Long id) {
        Long actor = requireActor();
        MasterDataChangeDO claimed = tx().execute(status -> {
            MasterDataChangeDO change = lockChange(id);
            if (!"APPROVED".equals(change.getStatus())) {
                return null;
            }
            MasterModelDO model = lockModel(change.getModelId());
            if (model.getStatus() != MasterModelDO.STATUS_RELEASED || !"READY".equals(model.getPublishState())
                    || !Objects.equals(model.getVersion(), change.getSchemaVersion())) {
                throw exception(MASTER_MODEL_NOT_RELEASED);
            }
            change.setStatus("APPLYING");
            change.setApplyToken(UUID.randomUUID().toString());
            change.setApplyActorId(actor);
            change.setApplyStartedAt(LocalDateTime.now());
            changeMapper.updateById(change);
            return change;
        });
        if (claimed == null) {
            return;
        }
        boolean invoked = false;
        try {
            List<Map<String, Object>> values = rows(claimed.getRowsJson());
            invoked = true;
            GuardedMutationResult result = dataService.applyGuardedChange(claimed.getModelId(), claimed.getOperation(),
                    rows(claimed.getBeforeJson()), values);
            tx().executeWithoutResult(status -> {
                MasterDataChangeDO locked = lockChange(id);
                if (!ownsApply(locked, claimed)) {
                    return;
                }
                if (result.status() == GuardedMutationResult.Status.APPLIED) {
                    finishApplied(locked);
                } else {
                    locked.setStatus(result.status() == GuardedMutationResult.Status.UNKNOWN ? "UNKNOWN" : "REJECTED");
                    locked.setLastError(result.status() == GuardedMutationResult.Status.UNKNOWN ? "外部写入结果待核查，禁止自动重试"
                            : result.failure() == GuardedMutationResult.Failure.UNSUPPORTED ? "该数据库暂不支持原子条件写，请使用已验证的数据源"
                            : result.failure() == GuardedMutationResult.Failure.CONFLICT ? "原数据已变化，申请未执行，请重新提交"
                            : "整批变更未写入，请核查数据源与字段配置后重新提交");
                    changeMapper.updateById(locked);
                }
            });
        } catch (RuntimeException ex) {
            boolean externalInvoked = invoked;
            tx().executeWithoutResult(status -> {
                MasterDataChangeDO locked = lockChange(id);
                if (ownsApply(locked, claimed)) {
                    locked.setStatus(externalInvoked ? "UNKNOWN" : "APPROVED");
                    locked.setLastError(
                            externalInvoked ? "外部写入结果待核查，禁止自动重试" : "读取原数据失败，请同步后重试");
                    changeMapper.updateById(locked);
                }
            });
        }
    }

    /** 此方法只从真实平台事务调用；日志与完成状态在同一提交内。 */
    private void finishApplied(MasterDataChangeDO change) {
        List<Map<String, Object>> before = rows(change.getBeforeJson());
        List<Map<String, Object>> after = rows(change.getRowsJson());
        List<String> primary = columnMapper.selectListByModelId(change.getModelId()).stream()
                .filter(c -> Integer.valueOf(1).equals(c.getPk())).map(MasterColumnDO::getName).toList();
        for (int i = 0; i < after.size(); i++) {
            Map<String, Object> old = before.get(i);
            Map<String, Object> submitted = after.get(i);
            Map<String, Object> next = "DELETE".equals(change.getOperation()) ? null : submitted;
            Map<String, Object> key = new TreeMap<>();
            primary.forEach(name -> key.put(name, submitted.get(name)));
            String dataId = JsonUtils.toJsonString(key);
            if (dataId.length() > 1000) {
                dataId = "SHA256:" + digest(dataId);
            }
            Set<String> properties = new TreeSet<>();
            if (old != null) {
                properties.addAll(old.keySet());
            }
            if (next != null) {
                properties.addAll(next.keySet());
            }
            for (String property : properties) {
                Object oldValue = old == null ? null : old.get(property);
                Object newValue = next == null ? null : next.get(property);
                if (Objects.equals(oldValue, newValue)) {
                    continue;
                }
                MasterChangeLogDO log = new MasterChangeLogDO();
                log.setChangeId(change.getId());
                log.setModelId(change.getModelId());
                log.setRowOrdinal(i);
                log.setDataId(dataId);
                log.setChangeType(change.getOperation());
                log.setPropertyName(property);
                log.setBeforeValue(
                        oldValue == null ? null : oldValue instanceof String text ? text : JsonUtils.toJsonString(
                                oldValue));
                log.setAfterValue(
                        newValue == null ? null : newValue instanceof String text ? text : JsonUtils.toJsonString(
                                newValue));
                log.setChangedBy(change.getApplyActorId());
                log.setChangedAt(LocalDateTime.now());
                logMapper.insert(log);
            }
        }
        change.setStatus("APPLIED");
        change.setAppliedAt(LocalDateTime.now());
        change.setLastError(null);
        changeMapper.updateById(change);
        changeMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MasterDataChangeDO.class)
                .eq(MasterDataChangeDO::getId, change.getId()).set(MasterDataChangeDO::getLastError, null));
    }

    /**
     * 从变更行提取并规范化主键标识。
     */
    private List<String> rowKeys(Long modelId, List<Map<String, Object>> values) {
        List<String> primary = columnMapper.selectListByModelId(modelId).stream()
                .filter(c -> Integer.valueOf(1).equals(c.getPk())).map(MasterColumnDO::getName).toList();
        return values.stream().map(row -> {
            Map<String, Object> key = new TreeMap<>();
            primary.forEach(name -> key.put(name, row.get(name)));
            return digest(JsonUtils.toJsonString(key));
        }).toList();
    }

    /**
     * 校验主数据变更的变更，不满足约束时拒绝当前操作。
     */
    private MasterDataChangeDO requireChange(Long id) {
        MasterDataChangeDO change = id == null ? null : changeMapper.selectById(id);
        if (change == null) {
            throw exception(MASTER_DATA_CHANGE_NOT_EXISTS);
        }
        return change;
    }

    /**
     * 锁定变更。
     */
    private MasterDataChangeDO lockChange(Long id) {
        MasterDataChangeDO change = changeMapper.selectForUpdate(id);
        if (change == null) {
            throw exception(MASTER_DATA_CHANGE_NOT_EXISTS);
        }
        return change;
    }

    /**
     * 按指定条件读取并锁定主数据变更记录，供事务内一致性校验或修改使用。
     */
    private MasterModelDO lockModel(Long id) {
        MasterModelDO model = modelMapper.selectOne(new LambdaQueryWrapperX<MasterModelDO>()
                .eq(MasterModelDO::getId, id).last("FOR UPDATE"));
        if (model == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
        return model;
    }

    /**
     * 在平台事务边界内完成主数据变更的本地读写。
     */
    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 读取并校验当前操作人身份。
     */
    private Long requireActor() {
        if (SecurityFrameworkUtils.getLoginUserId() == null) {
            throw new IllegalStateException("缺少有效的用户上下文");
        }
        return SecurityFrameworkUtils.getLoginUserId();
    }

    /**
     * 批量读取审批及变更相关用户信息。
     */
    private Map<Long, AdminUserRespDTO> users(List<MasterDataChangeDO> changes) {
        Set<Long> ids = new HashSet<>();
        changes.forEach(c -> {
            ids.add(c.getApplicantId());
            if (c.getReviewerId() != null) {
                ids.add(c.getReviewerId());
            }
        });
        return ids.isEmpty() ? Map.of() : userApi.getUserMap(ids);
    }

    /**
     * 组装主数据变更审批流程上下文。
     */
    private MasterApprovalRespVO approval(MasterDataChangeDO change, Map<Long, AdminUserRespDTO> users) {
        MasterApprovalRespVO vo = BeanUtils.toBean(change, MasterApprovalRespVO.class);
        MasterModelDO model = modelMapper.selectById(change.getModelId());
        vo.setModelName(model == null ? "已删除模型" : model.getTableCn());
        AdminUserRespDTO applicant = users.get(change.getApplicantId());
        AdminUserRespDTO reviewer = change.getReviewerId() == null ? null : users.get(change.getReviewerId());
        vo.setApplicantName(applicant == null ? null : applicant.getNickname());
        vo.setReviewerName(reviewer == null ? null : reviewer.getNickname());
        vo.setRows(rows(change.getRowsJson()));
        vo.setBeforeRows(rows(change.getBeforeJson()));
        if (vo.getRows().size() == 1) {
            Map<String, Object> key = new TreeMap<>();
            columnMapper.selectListByModelId(change.getModelId()).stream()
                    .filter(c -> Integer.valueOf(1).equals(c.getPk()))
                    .forEach(c -> key.put(c.getName(), vo.getRows().getFirst().get(c.getName())));
            vo.setDataId(
                    key.size() == 1 ? String.valueOf(key.values().iterator().next()) : JsonUtils.toJsonString(key));
        } else {
            vo.setDataId(vo.getRows().size() + "条数据");
        }
        return vo;
    }

    /**
     * 组装主数据变更审批通过后需要执行的数据变更。
     */
    private static MasterDataMutationRespVO mutation(MasterDataChangeDO change) {
        MasterDataMutationRespVO vo = BeanUtils.toBean(change, MasterDataMutationRespVO.class);
        vo.setApprovalRequired(change.getApprovalRequired());
        vo.setMessage(change.getLastError());
        return vo;
    }

    /**
     * 校验主数据变更的一致性请求，不满足约束时拒绝当前操作。
     */
    private static MasterDataChangeDO requireSameRequest(MasterDataChangeDO change, String hash) {
        if (!hash.equals(change.getPayloadHash())) {
            throw exception(MASTER_DATA_REQUEST_CONFLICT);
        }
        return change;
    }

    /**
     * 判断指定流程是否属于当前主数据变更申请。
     */
    private static boolean ownsApply(MasterDataChangeDO current, MasterDataChangeDO claimed) {
        return "APPLYING".equals(current.getStatus()) && Objects.equals(current.getApplyToken(),
                claimed.getApplyToken());
    }

    /**
     * 判断输入是否为空或仅含空白。
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 读取主数据变更的数据行。
     */
    static List<Map<String, Object>> rows(String value) {
        return JsonUtils.parseObject(value, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    /**
     * 将主数据值转换为无损的接口传输形式。
     */
    private static List<Map<String, Object>> wire(List<Map<String, Object>> value) {
        return rows(JsonUtils.toJsonString(value));
    }

    /**
     * 生成主数据变更用于比对或摘要的规范表示。
     */
    static String canonical(List<Map<String, Object>> values) {
        return JsonUtils.toJsonString(values.stream().map(row -> row == null ? null : new TreeMap<>(row)).toList());
    }

    /**
     * 计算主数据变更内容摘要。
     */
    static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
