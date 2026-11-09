package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmissionPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmissionRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmitReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmitRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.*;
import cn.iocoder.yudao.module.dataforge.enums.fill.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-提交服务实现类
 *
 * 冻结语义：compareAndSetStatusWithVersion 同时匹配状态与 serverVersion——
 * 任何在冻结前读取、冻结后才提交的旧编辑事务都会 CAS 失败，不可能写入快照之后的草稿
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class FillSubmissionServiceImpl implements FillSubmissionService {

    private static final int MAX_ISSUES = 500;

    public static final String WS_STATUS_CHANGED = "fill-work-item-status-changed";

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillDraftRowMapper draftRowMapper;

    @Resource
    private FillSubmissionMapper submissionMapper;

    @Resource
    private FillSubmissionRowMapper submissionRowMapper;

    @Resource
    private FillValidationIssueMapper validationIssueMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillEventOutboxMapper outboxMapper;

    @Resource
    private FillWorkItemService workItemService;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private FillNotifyService notifyService;

    @Resource
    private FillCollabService collabService;

    @Resource
    private FillBpmAdapter bpmAdapter;

    @Resource
    private FillCorrectionService correctionService;

    /**
     * 提交：状态与 serverVersion 的工作项 CAS 冻结草稿（旧编辑事务此后必然 CAS 失败），
     *  服务端重读草稿执行强校验；通过则生成不可变快照并经 Outbox 可靠启动 BPM，
     *  失败则返回结构化问题清单并回到填报中/整改中
     *
     * @param workItemId 工作项编号
     * @param reqVO 请求参数
     * @return 填报提交响应数据
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FillSubmitRespVO submit(Long workItemId, FillSubmitReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkEditPermission(item, userId);
        Integer fromStatus = item.getStatus();

        // 1. 冻结 CAS：状态 + serverVersion 同时匹配，并发提交只有一个成功
        int frozen = workItemMapper.compareAndSetStatusWithVersion(workItemId, fromStatus,
                FillWorkItemStatusEnum.SUBMIT_VALIDATING.getStatus(), reqVO.getExpectedVersion());
        if (frozen == 0) {
            FillWorkItemDO latest = workItemMapper.selectById(workItemId);
            if (latest != null && !FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(latest.getStatus())) {
                throw exception(FILL_WORK_ITEM_SUBMITTED_BY_OTHER, "其他填报人");
            }
            throw exception(FILL_WORK_ITEM_VERSION_CONFLICT);
        }

        // 2. 重读服务器草稿并执行强校验（前端"校验通过"永远不作数）
        FillTableVersionDO version = tableVersionService.getTableVersion(item.getTableVersionId());
        List<FillDraftRowDO> rows = draftRowMapper.selectListByWorkItemId(workItemId);
        // 存在草拟中的数据更正时，本次提交强制为"更正"类型
        boolean correcting = correctionService.hasActiveCorrection(workItemId);
        int submissionType = correcting ? FillSubmissionTypeEnum.CORRECTION.getType()
                : (reqVO.getSubmissionType() != null
                ? reqVO.getSubmissionType() : FillSubmissionTypeEnum.NORMAL.getType());
        List<FillSubmitRespVO.Issue> issues = validate(version, rows, submissionType);

        validationIssueMapper.deleteDraftIssuesByWorkItemId(workItemId);
        boolean hasError = issues.stream()
                .anyMatch(issue -> FillValidationSeverityEnum.ERROR.getSeverity().equals(issue.getSeverity()));
        // 错误和警告都落问题清单：警告不阻断提交，但留给审批人参考
        issues.forEach(issue -> validationIssueMapper.insert(FillValidationIssueDO.builder()
                .workItemId(workItemId)
                .ruleCode(issue.getRuleCode())
                .severity(issue.getSeverity())
                .rowId(issue.getRowId())
                .fieldCode(issue.getFieldCode())
                .displayRowNo(issue.getDisplayRowNo())
                .message(issue.getMessage())
                .build()));
        if (hasError) {
            // 3a. 校验失败：回到原状态（首提回填报中，整改提回整改中）
            workItemMapper.compareAndSetStatus(workItemId,
                    FillWorkItemStatusEnum.SUBMIT_VALIDATING.getStatus(), fromStatus);
            FillSubmitRespVO respVO = new FillSubmitRespVO();
            respVO.setSuccess(false);
            respVO.setIssues(issues);
            return respVO;
        }

        // 3b. 校验通过：生成不可变快照
        int attemptNo = item.getAttemptCount() == null ? 1 : item.getAttemptCount() + 1;
        FillSubmissionDO submission = FillSubmissionDO.builder()
                .workItemId(workItemId)
                .taskId(item.getTaskId())
                .attemptNo(attemptNo)
                .submissionType(submissionType)
                .tableVersionId(item.getTableVersionId())
                .rowCount(rows.size())
                .snapshotHash(computeSnapshotHash(rows))
                .emptyReason(FillSubmissionTypeEnum.EMPTY.getType().equals(submissionType)
                        ? reqVO.getEmptyReason() : null)
                .serverVersionAtSubmit(reqVO.getExpectedVersion() + 1)
                .submitterUserId(userId)
                .submitTime(LocalDateTime.now())
                .approvalStatus(FillApprovalStatusEnum.WAIT_START.getStatus())
                .build();
        submissionMapper.insert(submission);
        rows.forEach(row -> submissionRowMapper.insert(FillSubmissionRowDO.builder()
                .submissionId(submission.getId())
                .rowId(row.getRowId())
                .rowOrder(row.getRowOrder())
                .data(row.getData())
                .createUserId(row.getCreateUserId())
                .lastModifyUserId(row.getLastModifyUserId())
                .build()));

        // 4. 实例进入"待启动审批"，其余在线人员立即只读
        workItemMapper.compareAndSetStatus(workItemId,
                FillWorkItemStatusEnum.SUBMIT_VALIDATING.getStatus(),
                FillWorkItemStatusEnum.WAIT_BPM_START.getStatus());
        FillWorkItemDO update = new FillWorkItemDO();
        update.setId(workItemId);
        update.setCurrentSubmissionId(submission.getId());
        update.setAttemptCount(attemptNo);
        update.setSubmissionType(submissionType);
        workItemMapper.updateById(update);

        // 4b. 更正流程：绑定新快照并转审批中
        if (correcting) {
            correctionService.bindSubmissionIfCorrecting(workItemId, submission.getId());
        }

        // 5. 可靠事件：Outbox 启动 BPM（BPM 短暂不可用不丢提交，Job 自动重试）
        insertOutbox(FillEventTypeEnum.FILL_BPM_START_REQUESTED, "SUBMISSION", submission.getId(),
                Map.of("submissionId", submission.getId(), "workItemId", workItemId));

        // 6. 通知其他填报人 + 房间广播状态变化
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        List<Long> otherAssignees = convertList(assigneeMapper.selectValidListByWorkItemId(workItemId),
                FillAssigneeDO::getUserId).stream().filter(id -> !Objects.equals(id, userId)).toList();
        notifyService.sendToUsers(FillNotifyTypeEnum.SUBMITTED_BY_OTHER, task, item, otherAssignees, null);
        registerStatusBroadcast(workItemId, FillWorkItemStatusEnum.WAIT_BPM_START.getStatus());

        FillSubmitRespVO respVO = new FillSubmitRespVO();
        respVO.setSuccess(true);
        respVO.setSubmissionId(submission.getId());
        respVO.setAttemptNo(attemptNo);
        respVO.setRowCount(rows.size());
        // 仅有警告时提交成功，把警告一并返回供前端提示
        respVO.setIssues(issues);
        return respVO;
    }

    /**
     * 撤回审批中的提交（仅实际提交人）：先经 BPM 成功终止流程实例，
     *  保留原快照，实例转入整改中重新开放草稿
     *
     * @param submissionId 提交编号
     * @param reason 操作原因
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long submissionId, String reason) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillSubmissionDO submission = validateSubmissionExists(submissionId);
        // 撤回默认只能由该次实际提交人发起（V1.0 §11.3）
        if (!Objects.equals(submission.getSubmitterUserId(), userId)) {
            throw exception(FILL_SUBMISSION_NOT_SUBMITTER);
        }
        if (!FillApprovalStatusEnum.APPROVING.getStatus().equals(submission.getApprovalStatus())
                || StrUtil.isBlank(submission.getProcessInstanceId())) {
            throw exception(FILL_WORK_ITEM_STATUS_INVALID, "非审批中");
        }
        // 先经 BPM 成功终止流程实例，失败则整体回滚不改本地状态
        bpmAdapter.cancelProcessBySubmitter(userId, submission.getProcessInstanceId(),
                StrUtil.nullToDefault(reason, "提交人撤回"));

        markApprovalFinished(submission.getId(), FillApprovalStatusEnum.WITHDRAWN, reason);
        workItemMapper.compareAndSetStatus(submission.getWorkItemId(),
                FillWorkItemStatusEnum.APPROVING.getStatus(), FillWorkItemStatusEnum.REWORKING.getStatus());
        registerStatusBroadcast(submission.getWorkItemId(), FillWorkItemStatusEnum.REWORKING.getStatus());
    }

    /**
     * 工作项的全部提交记录（按提交次数倒序）
     *
     * @param workItemId 工作项编号
     * @return 工作项的全部提交记录（按提交次数倒序）结果集合
     */
    @Override
    public List<FillSubmissionRespVO> getSubmissionsByWorkItem(Long workItemId) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkViewPermission(item, userId);
        return convertList(submissionMapper.selectListByWorkItemId(workItemId), this::convert);
    }

    /**
     * 提交快照只读预览（审批人从 BPM 业务表单打开；含模板 schema 与全部快照行）
     *
     * @param submissionId 提交编号
     * @return 填报提交预览响应数据
     */
    @Override
    public FillSubmissionPreviewRespVO getSubmissionPreview(Long submissionId) {
        // 接口权限码 dataforge:fill-submission:preview 之外，再按对象收紧：
        // 填报关系（有效填报人 / 任务发起人 / 报送组织负责人）或该提交审批流程的参与者（发起人、审批人、候选人、抄送人）
        FillSubmissionDO submission = validateSubmissionExists(submissionId);
        FillWorkItemDO item = workItemMapper.selectById(submission.getWorkItemId());
        checkPreviewPermission(submission, item, SecurityFrameworkUtils.getLoginUserId());
        FillTaskDO task = taskMapper.selectById(submission.getTaskId());
        FillTableVersionDO version = tableVersionService.getTableVersion(submission.getTableVersionId());

        FillSubmissionPreviewRespVO respVO = new FillSubmissionPreviewRespVO();
        copyTo(submission, respVO);
        if (item != null) {
            respVO.setOrgName(item.getOrgName());
        }
        if (task != null) {
            respVO.setTaskName(task.getName());
            respVO.setPeriodKey(task.getPeriodKey());
        }
        respVO.setFieldSchema(version.getFieldSchema());
        respVO.setRows(convertList(submissionRowMapper.selectListBySubmissionId(submissionId), row -> {
            FillSubmissionPreviewRespVO.Row rowVO = new FillSubmissionPreviewRespVO.Row();
            rowVO.setRowId(row.getRowId());
            rowVO.setRowOrder(row.getRowOrder());
            rowVO.setData(parseRowData(row.getData()));
            return rowVO;
        }));
        // 上一次提交：供审批人对比整改前后的差异（预览权限即可读取，不依赖填报人专用的提交历史接口）
        respVO.setPreviousSubmissionId(submissionMapper.selectListByWorkItemId(submission.getWorkItemId()).stream()
                .filter(other -> other.getAttemptNo() != null && submission.getAttemptNo() != null
                        && other.getAttemptNo() < submission.getAttemptNo())
                .max(java.util.Comparator.comparing(FillSubmissionDO::getAttemptNo))
                .map(FillSubmissionDO::getId).orElse(null));
        // 警告按填报实例保存，只对当前有效提交展示，避免把新提交的警告挂到历史提交上
        if (item != null && Objects.equals(item.getCurrentSubmissionId(), submissionId)) {
            respVO.setWarnings(validationIssueMapper.selectListByWorkItemId(item.getId()).stream()
                    .filter(issue -> FillValidationSeverityEnum.WARNING.getSeverity().equals(issue.getSeverity()))
                    .map(issue -> {
                        FillSubmitRespVO.Issue warning = new FillSubmitRespVO.Issue();
                        warning.setRuleCode(issue.getRuleCode());
                        warning.setSeverity(issue.getSeverity());
                        warning.setRowId(issue.getRowId());
                        warning.setFieldCode(issue.getFieldCode());
                        warning.setDisplayRowNo(issue.getDisplayRowNo());
                        warning.setMessage(issue.getMessage());
                        return warning;
                    }).toList());
        }
        return respVO;
    }

    /**
     * 校验提交快照预览权限：先查本地填报关系，不满足时再向 BPM 查询是否为该提交审批实例的参与者。
     * BPM 不可用时拒绝访问（fail closed），不放宽为仅凭接口权限码访问。
     */
    private void checkPreviewPermission(FillSubmissionDO submission, FillWorkItemDO item, Long userId) {
        if (userId == null) {
            throw exception(FILL_SUBMISSION_PREVIEW_DENIED);
        }
        if (Objects.equals(submission.getSubmitterUserId(), userId) || workItemService.hasViewPermission(item, userId)) {
            return;
        }
        if (StrUtil.isNotBlank(submission.getProcessInstanceId())) {
            boolean participant;
            try {
                participant = bpmAdapter.isProcessInstanceParticipant(submission.getProcessInstanceId(), userId);
            } catch (RuntimeException ex) {
                log.warn("[checkPreviewPermission][提交({}) 查询审批参与者失败，拒绝访问]", submission.getId(), ex);
                participant = false;
            }
            if (participant) {
                return;
            }
        }
        throw exception(FILL_SUBMISSION_PREVIEW_DENIED);
    }

    // ---------------------------------------------------------------- 强校验

    /**
     * 校验填报提交输入及状态。
     *
     * @param version 版本
     * @param rows 待处理的行数据
     * @param submissionType 提交类型
     * @return 校验填报提交输入及状态结果集合
     */
    private List<FillSubmitRespVO.Issue> validate(FillTableVersionDO version,
            List<FillDraftRowDO> rows, int submissionType) {
        List<FillSubmitRespVO.Issue> issues = new ArrayList<>();
        // 零上报：要求无数据行；正常提交：要求至少一行
        if (FillSubmissionTypeEnum.EMPTY.getType().equals(submissionType)) {
            if (!rows.isEmpty()) {
                issues.add(issue("EMPTY_REPORT", null, null, null, "零上报要求没有数据行，请删除数据或改为正常提交"));
            }
            return issues;
        }
        if (rows.isEmpty()) {
            issues.add(issue("NO_DATA", null, null, null, "没有数据行；本期确无数据请使用零上报"));
            return issues;
        }

        List<Map<String, Object>> fields = parseFields(version.getFieldSchema());
        int displayNo = 0;
        for (FillDraftRowDO row : rows) {
            displayNo++;
            if (issues.size() >= MAX_ISSUES) {
                issues.add(
                        issue("TOO_MANY_ISSUES", null, null, null, "问题数量超过 " + MAX_ISSUES + "，请先修正后再提交"));
                break;
            }
            Map<String, Object> data = parseRowData(row.getData());
            for (Map<String, Object> field : fields) {
                String fieldCode = String.valueOf(field.get("fieldCode"));
                String fieldName = String.valueOf(field.getOrDefault("name", fieldCode));
                Object value = data.get(fieldCode);
                String text = value == null ? "" : String.valueOf(value).trim();
                // 必填
                if (Boolean.TRUE.equals(field.get("required")) && StrUtil.isBlank(text)) {
                    issues.add(issue("REQUIRED", row.getRowId(), fieldCode, displayNo,
                            String.format("第 %s 行【%s】不能为空", displayNo, fieldName)));
                    continue;
                }
                if (StrUtil.isBlank(text)) {
                    continue;
                }
                // 字典
                Object dictOptions = field.get("dictOptions");
                if (dictOptions instanceof List<?> options && !options.isEmpty()
                        && options.stream().noneMatch(option -> String.valueOf(option).equals(text))) {
                    issues.add(issue("DICT", row.getRowId(), fieldCode, displayNo,
                            String.format("第 %s 行【%s】的值不在选择范围内", displayNo, fieldName)));
                    continue;
                }
                // 类型（dbswitch 逻辑类型：1小数 5整数 6长整型）
                Object fieldType = field.get("fieldType");
                int type = fieldType instanceof Number number ? number.intValue() : -1;
                if ((type == 5 || type == 6) && !isInteger(text)) {
                    issues.add(issue("TYPE_INTEGER", row.getRowId(), fieldCode, displayNo,
                            String.format("第 %s 行【%s】必须为整数", displayNo, fieldName)));
                    continue;
                }
                if (type == 1 && !isDecimal(text)) {
                    issues.add(issue("TYPE_DECIMAL", row.getRowId(), fieldCode, displayNo,
                            String.format("第 %s 行【%s】必须为数值", displayNo, fieldName)));
                    continue;
                }
                // 长度
                Object length = field.get("length");
                if (type != 1 && type != 5 && type != 6 && length instanceof Number maxLength
                        && maxLength.intValue() > 0 && text.length() > maxLength.intValue()) {
                    issues.add(issue("MAX_LENGTH", row.getRowId(), fieldCode, displayNo,
                            String.format("第 %s 行【%s】长度超过 %s", displayNo, fieldName, maxLength)));
                    continue;
                }
                // 取值范围、小数位与格式规则（级别由字段配置决定，警告不阻断提交）
                for (FillFieldRuleValidator.Violation violation : FillFieldRuleValidator.checkValue(field, text)) {
                    issues.add(issue(violation.ruleCode(), row.getRowId(), fieldCode, displayNo,
                            String.format("第 %s 行【%s】%s", displayNo, fieldName, violation.detail()),
                            violation.severity()));
                }
            }
        }
        validateFieldUnique(fields, rows, issues);
        // UPSERT 策略：业务键组合在草稿内必须唯一，否则合并覆盖行为不确定
        validateBusinessKeyUnique(version, rows, issues);
        return issues;
    }

    /**
     * 校验配置了“唯一”的字段：同一次填报内取值不得重复（空值不参与比较）。
     */
    private void validateFieldUnique(List<Map<String, Object>> fields, List<FillDraftRowDO> rows,
            List<FillSubmitRespVO.Issue> issues) {
        for (Map<String, Object> field : fields) {
            if (!Boolean.TRUE.equals(field.get("unique"))) {
                continue;
            }
            String fieldCode = String.valueOf(field.get("fieldCode"));
            String fieldName = String.valueOf(field.getOrDefault("name", fieldCode));
            int severity = field.get("ruleSeverity") instanceof Number number
                    && number.intValue() == FillFieldRuleValidator.SEVERITY_WARNING
                    ? FillFieldRuleValidator.SEVERITY_WARNING : FillFieldRuleValidator.SEVERITY_ERROR;
            Map<String, Integer> seenRowNo = new LinkedHashMap<>();
            int displayNo = 0;
            for (FillDraftRowDO row : rows) {
                displayNo++;
                if (issues.size() >= MAX_ISSUES) {
                    return;
                }
                Object value = parseRowData(row.getData()).get(fieldCode);
                String text = value == null ? "" : String.valueOf(value).trim();
                if (text.isEmpty()) {
                    continue;
                }
                Integer firstRowNo = seenRowNo.putIfAbsent(text, displayNo);
                if (firstRowNo != null) {
                    issues.add(issue("UNIQUE", row.getRowId(), fieldCode, displayNo,
                            String.format("第 %s 行【%s】与第 %s 行重复，该字段取值必须唯一",
                                    displayNo, fieldName, firstRowNo), severity));
                }
            }
        }
    }

    /**
     * 校验业务键唯一性。
     *
     * @param version 版本
     * @param rows 待处理的行数据
     * @param issues 问题
     */
    private void validateBusinessKeyUnique(FillTableVersionDO version, List<FillDraftRowDO> rows,
            List<FillSubmitRespVO.Issue> issues) {
        if (!FillMergeStrategyEnum.UPSERT.getStrategy().equals(version.getMergeStrategy())
                || StrUtil.isBlank(version.getBusinessKeyFields())) {
            return;
        }
        List<String> keyFields = StrUtil.split(version.getBusinessKeyFields(), ',');
        Map<String, Integer> seenRowNo = new LinkedHashMap<>();
        int displayNo = 0;
        for (FillDraftRowDO row : rows) {
            displayNo++;
            Map<String, Object> data = parseRowData(row.getData());
            StringBuilder key = new StringBuilder();
            boolean hasBlankKey = false;
            for (String keyField : keyFields) {
                Object value = data.get(keyField.trim());
                String text = value == null ? "" : String.valueOf(value).trim();
                if (text.isEmpty()) {
                    // 键字段为空由必填规则负责提示，这里跳过查重
                    hasBlankKey = true;
                    break;
                }
                key.append(text).append('\u0001');
            }
            if (hasBlankKey) {
                continue;
            }
            Integer firstRowNo = seenRowNo.putIfAbsent(key.toString(), displayNo);
            if (firstRowNo != null) {
                issues.add(issue("BUSINESS_KEY_DUPLICATE", row.getRowId(), keyFields.get(0).trim(), displayNo,
                        String.format("第 %s 行与第 %s 行的业务键（%s）重复，UPSERT 合并要求业务键唯一",
                                displayNo, firstRowNo, version.getBusinessKeyFields())));
            }
        }
    }

    /**
     * 获取填报提交的问题。
     *
     * @param ruleCode 规则编码
     * @param rowId 行编号
     * @param fieldCode 字段编码
     * @param displayRowNo 展示行序号
     * @param message 消息
     * @return 问题
     */
    private FillSubmitRespVO.Issue issue(String ruleCode, String rowId, String fieldCode,
            Integer displayRowNo, String message) {
        return issue(ruleCode, rowId, fieldCode, displayRowNo, message,
                FillValidationSeverityEnum.ERROR.getSeverity());
    }

    private FillSubmitRespVO.Issue issue(String ruleCode, String rowId, String fieldCode,
            Integer displayRowNo, String message, int severity) {
        FillSubmitRespVO.Issue issue = new FillSubmitRespVO.Issue();
        issue.setRuleCode(ruleCode);
        issue.setSeverity(severity);
        issue.setRowId(rowId);
        issue.setFieldCode(fieldCode);
        issue.setDisplayRowNo(displayRowNo);
        issue.setMessage(message);
        return issue;
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 校验提交存在性。
     *
     * @param id 记录编号
     * @return 填报提交
     */
    private FillSubmissionDO validateSubmissionExists(Long id) {
        FillSubmissionDO submission = submissionMapper.selectById(id);
        if (submission == null) {
            throw exception(FILL_SUBMISSION_NOT_EXISTS);
        }
        return submission;
    }

    /**
     * 标记审批已完成。
     *
     * @param submissionId 提交编号
     * @param status 目标状态
     * @param reason 操作原因
     */
    private void markApprovalFinished(Long submissionId, FillApprovalStatusEnum status, String reason) {
        FillSubmissionDO update = new FillSubmissionDO();
        update.setId(submissionId);
        update.setApprovalStatus(status.getStatus());
        update.setApprovalEndTime(LocalDateTime.now());
        update.setApprovalReason(reason);
        submissionMapper.updateById(update);
    }

    /**
     * 新增发件箱。
     *
     * @param eventType 事件类型
     * @param bizType 业务类型
     * @param bizId 业务编号
     * @param payload 载荷
     */
    private void insertOutbox(FillEventTypeEnum eventType, String bizType, Long bizId,
            Map<String, Object> payload) {
        outboxMapper.insert(FillEventOutboxDO.builder()
                .eventId(IdUtil.fastSimpleUUID())
                .eventType(eventType.getType())
                .bizType(bizType)
                .bizId(bizId)
                .payload(JsonUtils.toJsonString(payload))
                .status(FillOutboxStatusEnum.PENDING.getStatus())
                .retryCount(0)
                .build());
    }

    /**
     * 注册状态广播。
     *
     * @param workItemId 工作项编号
     * @param status 目标状态
     */
    private void registerStatusBroadcast(Long workItemId, Integer status) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 在本地事务成功提交后触发对应的后续处理。
             */
            @Override
            public void afterCommit() {
                collabService.broadcast(workItemId, WS_STATUS_CHANGED,
                        Map.of("workItemId", workItemId, "status", status));
            }
        });
    }

    /**
     * 计算快照摘要。
     *
     * @param rows 待处理的行数据
     * @return 计算后的摘要或签名字符串
     */
    private String computeSnapshotHash(List<FillDraftRowDO> rows) {
        StringBuilder canonical = new StringBuilder();
        rows.forEach(row -> canonical.append(row.getRowId()).append('|')
                .append(StrUtil.nullToEmpty(row.getData())).append('\n'));
        return DigestUtil.sha256Hex(canonical.toString());
    }

    /**
     * 转换填报提交。
     *
     * @param submission 提交
     * @return 填报提交响应数据
     */
    private FillSubmissionRespVO convert(FillSubmissionDO submission) {
        FillSubmissionRespVO respVO = new FillSubmissionRespVO();
        copyTo(submission, respVO);
        return respVO;
    }

    /**
     * 复制目标。
     *
     * @param submission 提交
     * @param respVO 响应数据
     */
    private void copyTo(FillSubmissionDO submission, FillSubmissionRespVO respVO) {
        respVO.setId(submission.getId());
        respVO.setWorkItemId(submission.getWorkItemId());
        respVO.setTaskId(submission.getTaskId());
        respVO.setAttemptNo(submission.getAttemptNo());
        respVO.setSubmissionType(submission.getSubmissionType());
        respVO.setTableVersionId(submission.getTableVersionId());
        respVO.setRowCount(submission.getRowCount());
        respVO.setSnapshotHash(submission.getSnapshotHash());
        respVO.setSubmitterUserId(submission.getSubmitterUserId());
        respVO.setSubmitTime(submission.getSubmitTime());
        respVO.setProcessInstanceId(submission.getProcessInstanceId());
        respVO.setApprovalStatus(submission.getApprovalStatus());
        respVO.setApprovalEndTime(submission.getApprovalEndTime());
        respVO.setApprovalReason(submission.getApprovalReason());
        respVO.setEmptyReason(submission.getEmptyReason());
    }

    /**
     * 解析行数据。
     *
     * @param dataJson 数据JSON
     * @return 处理后的键值映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRowData(String dataJson) {
        if (StrUtil.isBlank(dataJson)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> data = JsonUtils.parseObject(dataJson, Map.class);
        return data != null ? data : new LinkedHashMap<>();
    }

    /**
     * 解析填报提交的字段。
     *
     * @param fieldSchemaJson 字段结构JSON
     * @return 解析填报提交的字段结果集合
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFields(String fieldSchemaJson) {
        List<Map<String, Object>> fields = JsonUtils.parseObject(fieldSchemaJson, List.class);
        return fields != null ? fields : List.of();
    }

    /**
     * 判断是否满足整数条件。
     *
     * @param text 待处理文本
     * @return 判断结果
     */
    private boolean isInteger(String text) {
        try {
            Long.parseLong(text);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * 判断是否满足小数条件。
     *
     * @param text 待处理文本
     * @return 判断结果
     */
    private boolean isDecimal(String text) {
        try {
            new java.math.BigDecimal(text);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

}
