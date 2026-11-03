package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.governance.api.etl.GovernanceEtlApi;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityResultReqDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 有界隔离留存和可靠上报；不把内联报告伪装成目标数据库已验收。 */
@Service
@Slf4j
public class EtlQualityEvidenceService {

    @Resource
    private EtlArchiveService archives;

    @Resource
    private EtlQualityBatchMapper batchMapper;

    @Resource
    private EtlQualityIssueMapper issueMapper;

    @Resource
    private EtlRecordMapper recordMapper;

    @Resource
    private EtlRunCoordinator coordinator;

    @Resource
    private GovernanceEtlApi governanceApi;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private EtlSourceEvidenceService sourceEvidence;

    /**
     * 开始ETL质量证据。
     *
     * @param record 运行记录
     * @param nodeNo 节点序号
     * @param planHash 计划摘要
     * @return 记录编号
     */
    public Long begin(EtlRecordDO record, String nodeNo, String planHash) {
        EtlQualityBatchDO existing = batchMapper.selectOne(new LambdaQueryWrapperX<EtlQualityBatchDO>()
                .eq(EtlQualityBatchDO::getRecordId, record.getId()).eq(EtlQualityBatchDO::getNodeNo, nodeNo));
        if (existing != null) {
            if (!Objects.equals(existing.getPlanHash(), planHash) || !"COLLECTING".equals(
                    existing.getRegistrationStatus())
                    || issueMapper.selectCount(
                    new LambdaQueryWrapperX<EtlQualityIssueDO>().eq(EtlQualityIssueDO::getBatchId,
                            existing.getId())) > 0) {
                throw new IllegalStateException("本次运行已有质量证据，禁止隐式覆盖或重放");
            }
            return existing.getId();
        }
        EtlQualityBatchDO batch = new EtlQualityBatchDO();
        batch.setRecordId(record.getId());
        batch.setNodeNo(nodeNo);
        batch.setPlanHash(planHash);
        batch.setExecutionUserId(record.getExecutionUserId());
        batch.setRegistrationStatus("COLLECTING");
        batch.setAttemptCount(0);
        owned(record, () -> {
            if (batchMapper.insert(batch) != 1 || batch.getId() == null) {
                throw new IllegalStateException("质量证据批次未成功持久化");
            }
        });
        return batch.getId();
    }

    /**
     * 记录问题。
     *
     * @param record 运行记录
     * @param batchId 批次编号
     * @param sequence 序列
     * @param rowJson 行JSON
     * @param violations 违规
     */
    public void recordIssue(EtlRecordDO record, Long batchId, long sequence, String rowJson,
            List<EtlRowPolicyPort.Violation> violations) {
        recordIssue(record, batchId, sequence, rowJson, violations, null);
    }

    /**
     * 获取ETL质量证据的来源证明。
     *
     * @param record 运行记录
     * @param batchId 批次编号
     * @param origin 原始
     * @param row 当前行数据
     * @return ETL质量证据的来源证明文本
     */
    public String sourceProof(EtlRecordDO record, Long batchId,
            cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin origin,
            Map<String, Object> row) {
        return sourceEvidence.freeze(record, requireBatch(batchId), origin, row);
    }

    /**
     * 记录问题。
     *
     * @param record 运行记录
     * @param batchId 批次编号
     * @param sequence 序列
     * @param rowJson 行JSON
     * @param violations 违规
     * @param provenanceJson 来源JSON
     */
    public void recordIssue(EtlRecordDO record, Long batchId, long sequence, String rowJson,
            List<EtlRowPolicyPort.Violation> violations, String provenanceJson) {
        EtlQualityIssueDO issue = new EtlQualityIssueDO();
        issue.setBatchId(batchId);
        issue.setRowNumber(sequence);
        issue.setRowJson(rowJson);
        issue.setQualityStatus(violations.stream().anyMatch(v -> "ERROR".equals(v.severity())) ? "REJECT" : "WARN");
        issue.setViolationsJson(JsonUtils.toJsonString(violations));
        issue.setProvenanceJson(provenanceJson);
        owned(record, () -> {
            if (issueMapper.insert(issue) != 1) {
                throw new IllegalStateException("质量问题证据未成功持久化");
            }
        });
    }

    /**
     * 完成ETL质量证据处理。
     *
     * @param record 运行记录
     * @param batchId 批次编号
     * @param counts 数量
     */
    public void complete(EtlRecordDO record, Long batchId, EtlRowPolicyPort.Counts counts) {
        complete(record, batchId, counts, null, null);
    }

    /**
     * 完成ETL质量证据处理。
     *
     * @param record 运行记录
     * @param batchId 批次编号
     * @param counts 数量
     * @param identityFields 身份字段
     * @param identityHash 身份摘要
     */
    public void complete(EtlRecordDO record, Long batchId, EtlRowPolicyPort.Counts counts, List<String> identityFields,
            String identityHash) {
        if ((identityFields == null) != (identityHash == null) || identityHash != null && !identityHash.matches(
                "[a-f0-9]{64}")) {
            throw new IllegalArgumentException("完整检测范围身份无效");
        }
        if (counts.input() < 0 || counts.passed() < 0 || counts.warned() < 0 || counts.rejected() < 0
                || counts.violations() < 0 || counts.passed() > counts.input() || counts.warned() > counts.input() - counts.passed()
                || counts.rejected() != counts.input() - counts.passed() - counts.warned()) {
            throw new IllegalArgumentException("质量行计数不守恒");
        }
        owned(record, () -> {
            int changed = batchMapper.update(null, Wrappers.lambdaUpdate(EtlQualityBatchDO.class)
                    .eq(EtlQualityBatchDO::getId, batchId).eq(EtlQualityBatchDO::getRegistrationStatus, "COLLECTING")
                    .set(EtlQualityBatchDO::getInputCount, counts.input())
                    .set(EtlQualityBatchDO::getPassCount, counts.passed())
                    .set(EtlQualityBatchDO::getWarnCount, counts.warned())
                    .set(EtlQualityBatchDO::getRejectCount, counts.rejected())
                    .set(EtlQualityBatchDO::getIdentityFieldsJson,
                            identityFields == null ? null : JsonUtils.toJsonString(identityFields))
                    .set(EtlQualityBatchDO::getIdentityHash, identityHash)
                    .set(EtlQualityBatchDO::getViolationCount, counts.violations())
                    .set(EtlQualityBatchDO::getRegistrationStatus, "PENDING"));
            if (changed != 1) {
                throw new IllegalStateException("质量批次已变化");
            }
        });
    }

    /**
     * 构造绑定当前执行权的状态筛选或更新条件。
     *
     * @param record 运行记录
     * @param write 写入
     */
    private void owned(EtlRecordDO record, Runnable write) {
        if (!coordinator.writeOwned(record.getId(), record.getExecutionToken(), () -> {
            if (coordinator.shouldStop(record.getId(), record.getExecutionToken())) {
                throw new java.util.concurrent.CancellationException("ETL 运行已停止或所有权失效");
            }
            write.run();
        })) {
            throw new java.util.concurrent.CancellationException("ETL 运行所有权失效");
        }
    }

    /** 复用既有 ETL 恢复扫描入口；每条先校验记录，RPC 不处于数据库锁事务内。 */
    public void relayPending() {
        for (var candidate : batchMapper.selectDeliveryCandidates(LocalDateTime.now())) {
            try {
                deliver(candidate);
            } catch (RuntimeException failure) {
                log.warn("[relayPending][质量登记暂未完成 batchId={} errorType={}]", candidate.getId(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    /** 仅重新登记已冻结检测事实，绝不重新执行节点或改变原执行身份。 */
    public void retryRegistration(Long batchId) {
        var batch = requireBatch(batchId);
        var record = requireRecord(batch.getRecordId());
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        access.requireUserPermission(actor, ExecutionAccessService.PERMISSION_ETL_RUN);
        access.requireUserPermission(actor, "dataforge:etl:query");
        access.requireUserPermission(record.getExecutionUserId(), "governance:quality-rule:query");
        access.requireUserPermission(record.getExecutionUserId(), "governance:quality-rule-set:query");
        coordinator.retryQualityRegistration(batchId, record.getId(), actor);
    }

    /**
     * 投递ETL质量证据。
     *
     * @param candidate 候选
     */
    private void deliver(EtlQualityBatchDO candidate) {
        LocalDateTime now = LocalDateTime.now();
        if (candidate.getAttemptCount() >= 10) {
            batchMapper.update(null, Wrappers.lambdaUpdate(EtlQualityBatchDO.class)
                    .eq(EtlQualityBatchDO::getId, candidate.getId())
                    .eq(EtlQualityBatchDO::getRegistrationStatus, "REGISTERING")
                    .le(EtlQualityBatchDO::getLeaseUntil, now).set(EtlQualityBatchDO::getRegistrationStatus, "FAILED")
                    .set(EtlQualityBatchDO::getLeaseToken, null).set(EtlQualityBatchDO::getLeaseUntil, null));
            return;
        }
        String token = UUID.randomUUID().toString();
        var claim = Wrappers.lambdaUpdate(EtlQualityBatchDO.class).eq(EtlQualityBatchDO::getId, candidate.getId())
                .eq(EtlQualityBatchDO::getAttemptCount, candidate.getAttemptCount())
                .eq(EtlQualityBatchDO::getRegistrationStatus, candidate.getRegistrationStatus());
        if ("REGISTERING".equals(candidate.getRegistrationStatus())) {
            claim.le(EtlQualityBatchDO::getLeaseUntil, now);
        } else {
            claim.and(w -> w.isNull(EtlQualityBatchDO::getNextAttemptAt).or()
                    .le(EtlQualityBatchDO::getNextAttemptAt, now));
        }
        int attempt = candidate.getAttemptCount() + 1;
        if (batchMapper.update(null, claim.set(EtlQualityBatchDO::getRegistrationStatus, "REGISTERING")
                .set(EtlQualityBatchDO::getLeaseToken, token).set(EtlQualityBatchDO::getLeaseUntil, now.plusMinutes(2))
                .set(EtlQualityBatchDO::getAttemptCount, attempt)) != 1) {
            return;
        }
        try {
            InlineQualityResultReqDTO request = new InlineQualityResultReqDTO();
            request.setExecutionUserId(candidate.getExecutionUserId());
            request.setRunId(String.valueOf(candidate.getRecordId()));
            request.setNodeId(candidate.getNodeNo());
            request.setBatchId(String.valueOf(candidate.getId()));
            request.setPlanHash(candidate.getPlanHash());
            request.setIdempotencyKey("dataforge:etl-quality:" + candidate.getId());
            request.setTotalCount(candidate.getInputCount());
            request.setPassCount(candidate.getPassCount());
            request.setWarnCount(candidate.getWarnCount());
            request.setRejectCount(candidate.getRejectCount());
            request.setIssueReferences(List.of("dataforge:etl-quality-batch:" + candidate.getId()));
            if (candidate.getIdentityHash() != null) {
                request.setIdentityHash(candidate.getIdentityHash());
                request.setIdentityFields(JsonUtils.parseArray(candidate.getIdentityFieldsJson(), String.class));
            }
            var receipt = governanceApi.acceptInlineQualityResult(request).getCheckedData();
            if (receipt == null || receipt.getReportId() == null || !Set.of("ACCEPTED", "REPLAY")
                    .contains(receipt.getStatus())) {
                throw new IllegalStateException("治理报告尚未确认");
            }
            batchMapper.update(null,
                    Wrappers.lambdaUpdate(EtlQualityBatchDO.class).eq(EtlQualityBatchDO::getId, candidate.getId())
                            .eq(EtlQualityBatchDO::getLeaseToken, token)
                            .eq(EtlQualityBatchDO::getRegistrationStatus, "REGISTERING")
                            .set(EtlQualityBatchDO::getRegistrationStatus, "REGISTERED")
                            .set(EtlQualityBatchDO::getGovernanceReportId, receipt.getReportId())
                            .set(EtlQualityBatchDO::getLeaseToken, null).set(EtlQualityBatchDO::getLeaseUntil, null));
        } catch (RuntimeException failure) {
            batchMapper.update(null,
                    Wrappers.lambdaUpdate(EtlQualityBatchDO.class).eq(EtlQualityBatchDO::getId, candidate.getId())
                            .eq(EtlQualityBatchDO::getLeaseToken, token)
                            .eq(EtlQualityBatchDO::getRegistrationStatus, "REGISTERING")
                            .set(EtlQualityBatchDO::getRegistrationStatus, attempt >= 10 ? "FAILED" : "RETRY")
                            .set(EtlQualityBatchDO::getNextAttemptAt,
                                    now.plusSeconds(Math.min(3600, 30L << Math.min(attempt, 6))))
                            .set(EtlQualityBatchDO::getLeaseToken, null).set(EtlQualityBatchDO::getLeaseUntil, null));
        }
    }

    /**
     * 获取ETL质量证据的批次。
     *
     * @param recordId 记录编号
     * @return ETL质量证据的批次结果集合
     */
    public List<EtlQualityBatchRespVO> batches(Long recordId) {
        var record = requireRecord(recordId);
        return batchMapper.selectList(
                new LambdaQueryWrapperX<EtlQualityBatchDO>().eq(EtlQualityBatchDO::getRecordId, recordId)
                        .orderByAsc(EtlQualityBatchDO::getId)).stream().map(b -> {
            var response = BeanUtils.toBean(b, EtlQualityBatchRespVO.class);
            if ("COLLECTING".equals(b.getRegistrationStatus()) && record.getRunStatus() != null
                    && Set.of(3, 4, 5, 6).contains(record.getRunStatus())) {
                response.setRegistrationStatus("INCOMPLETE");
            }
            return response;
        }).toList();
    }

    /**
     * 获取ETL质量证据的治理报告。
     *
     * @param batchId 批次编号
     * @return ETL治理报告响应数据
     */
    public EtlGovernanceReportRespVO governanceReport(Long batchId) {
        var batch = requireBatch(batchId);
        requireRecord(batch.getRecordId());
        if (!"REGISTERED".equals(batch.getRegistrationStatus()) || batch.getGovernanceReportId() == null) {
            throw exception(ETL_QUALITY_REGISTRATION_CHANGED);
        }
        var request = new cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityReportQueryReqDTO();
        request.setReportId(batch.getGovernanceReportId());
        var report = governanceApi.queryInlineQualityReport(request).getCheckedData();
        if (report == null || !Objects.equals(report.getReportId(), batch.getGovernanceReportId())
                || !"ETL_INLINE".equals(report.getSourceType())
                || !Objects.equals(report.getBatchId(), String.valueOf(batch.getId()))
                || !Objects.equals(report.getRunId(), String.valueOf(batch.getRecordId()))
                || !Objects.equals(report.getNodeId(), batch.getNodeNo()) || !Objects.equals(report.getPlanHash(),
                batch.getPlanHash())
                || !Objects.equals(report.getTotalCount(), batch.getInputCount()) || !Objects.equals(
                report.getPassCount(), batch.getPassCount())
                || !Objects.equals(report.getWarnCount(), batch.getWarnCount()) || !Objects.equals(
                report.getRejectCount(), batch.getRejectCount())) {
            throw new IllegalStateException("治理报告关联未确认");
        }
        return BeanUtils.toBean(report, EtlGovernanceReportRespVO.class);
    }

    /**
     * 创建工单。
     *
     * @param req 请求参数
     * @return 生成或保存的记录编号
     */
    public Long createWorkOrder(EtlQualityWorkOrderReqVO req) {
        var report = governanceReport(req.getBatchId());
        var request = BeanUtils.toBean(req,
                cn.iocoder.yudao.module.governance.api.etl.dto.CreateInlineQualityWorkOrderReqDTO.class);
        request.setReportId(report.getReportId());
        // 同步调用不包本地事务；超时后从报告 workOrderId 查询最终结果。
        return governanceApi.createInlineQualityWorkOrder(request).getCheckedData();
    }

    /**
     * 获取ETL质量证据的问题。
     *
     * @param req 请求参数
     * @return ETL质量证据的问题结果
     */
    public PageResult<EtlQualityIssueRespVO> issues(EtlQualityIssuePageReqVO req) {
        var batch = requireBatch(req.getBatchId());
        requireRecord(batch.getRecordId());
        var page = issueMapper.selectPage(req,
                new LambdaQueryWrapperX<EtlQualityIssueDO>().eq(EtlQualityIssueDO::getBatchId, req.getBatchId())
                        .select(EtlQualityIssueDO::getId, EtlQualityIssueDO::getBatchId,
                                EtlQualityIssueDO::getRowNumber,
                                EtlQualityIssueDO::getQualityStatus, EtlQualityIssueDO::getViolationsJson,
                                EtlQualityIssueDO::getCreateTime)
                        .orderByAsc(EtlQualityIssueDO::getRowNumber));
        return new PageResult<>(
                page.getList().stream().map(i -> BeanUtils.toBean(i, EtlQualityIssueRespVO.class)).toList(),
                page.getTotal());
    }

    /**
     * 获取ETL质量证据的问题。
     *
     * @param id 记录编号
     * @return ETL质量问题响应数据
     */
    public EtlQualityIssueRespVO issue(Long id) {
        var issue = issueMapper.selectById(id);
        if (issue == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        var batch = requireBatch(issue.getBatchId());
        var record = requireRecord(batch.getRecordId());
        archives.requireEvidenceAccess(record.getId());
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        access.requireUserPermission(actor, "dataforge:etl:query");
        if (issue.getProvenanceJson() != null) {
            var verified = sourceEvidence.verify(record, batch, issue.getProvenanceJson(), actor);
            var response = BeanUtils.toBean(issue, EtlQualityIssueRespVO.class);
            response.setRowJson(JsonUtils.toJsonString(verified));
            response.setSourceVerified(true);
            return response;
        }
        // 无可复核来源的转换结果展示已留存快照，仍遵守运行记录查询权限与证据保留期。
        return BeanUtils.toBean(issue, EtlQualityIssueRespVO.class);
    }

    /**
     * 校验批次。
     *
     * @param id 记录编号
     * @return ETL质量批次
     */
    private EtlQualityBatchDO requireBatch(Long id) {
        var batch = batchMapper.selectById(id);
        if (batch == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        return batch;
    }

    /**
     * 校验记录。
     *
     * @param id 记录编号
     * @return ETL 运行记录
     */
    private EtlRecordDO requireRecord(Long id) {
        var record = recordMapper.selectById(id);
        if (record == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        return record;
    }
}
