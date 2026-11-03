package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunCommandDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunCommandMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.execution.*;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 持久化 ACCESS 领域协议；按任务 → 运行 → 子记录的顺序加锁，RPC 在事务外调用。 */
@Service
@lombok.extern.slf4j.Slf4j
public class AccessRunCoordinator {

    @Resource
    private DataAccessMapper tasks;

    @Resource
    private DataAccessTaskMapper runs;

    @Resource
    private RunCommandMapper commands;

    @Resource
    private RunExecutionProperties properties;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics metrics;

    @Resource
    private AccessRunAuthorization authorization;

    @Resource
    private AccessGroupRewriteCoordinator groupRewriteCoordinator;

    @Resource
    private javax.sql.DataSource dataSource;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<DataAccessExecutor> executor;

    private TransactionTemplate transactions;

    /** 先结束全局只读发现，再进行各对象的授权校验及短事务处理。 */
    public int scanScheduled(int limit) {
        int bounded = bounded(limit);
        List<DataAccessDO> candidates = tasks.selectList(Wrappers.lambdaQuery(DataAccessDO.class)
                .select(DataAccessDO::getId, DataAccessDO::getScheduleGeneration, DataAccessDO::getNextRunTime)
                .eq(DataAccessDO::getStatus, 1).eq(DataAccessDO::getTaskType, 3)
                .apply("next_run_time <= clock_timestamp()").orderByAsc(DataAccessDO::getNextRunTime)
                .last("LIMIT " + bounded));
        int accepted = 0, failures = 0;
        for (DataAccessDO candidate : candidates) {
            try {
                {
                    int count = 0;
                    DataAccessDO due = candidate;
                    for (int i = 0; i < properties.getPerTaskCatchUp(); i++) {
                        var receipt = enqueueScheduled(due.getId(), due.getScheduleGeneration(), due.getNextRunTime());
                        if (receipt.isEmpty()) {
                            break;
                        }
                        if (!receipt.get().replayed()) {
                            count++;
                        }
                        due = task(due.getId(), false);
                    }
                    accepted += count;
                }
            } catch (RuntimeException failure) {
                failures++;
                log.warn("ACCESS 受理扫描失败 task={} error={}", candidate.getId(), failure.getClass().getSimpleName());
            }
        }
        scanResult("accept", candidates.size(), accepted, failures);
        return accepted;
    }

    /**
     * 按平台及数据源容量有界扫描等待运行，逐项提交到本地执行器并汇报扫描结果。
     *
     * @param limit 读取或处理上限
     * @return 分发等待中数值
     */
    public int dispatchWaiting(int limit) {
        List<DataAccessTaskDO> candidates = runs.waitingCandidates(bounded(limit), properties.getPlatformActive(),
                properties.getPerDataSourceActive());
        int dispatched = 0, failures = 0;
        for (DataAccessTaskDO candidate : candidates) {
            try {
                if (executor.getObject().submit(candidate.getId())) {
                    dispatched++;
                }
            } catch (RuntimeException failure) {
                failures++;
                log.warn("ACCESS 派发失败 record={} error={}", candidate.getId(), failure.getClass().getSimpleName());
            }
        }
        scanResult("dispatch", candidates.size(), dispatched, failures);
        return dispatched;
    }

    /**
     * 分批扫描持有过期执行租约的同步运行，逐项恢复并报告本轮失败。
     *
     * @param limit 读取或处理上限
     * @return 恢复过期数值
     */
    public int recoverExpired(int limit) {
        List<DataAccessTaskDO> candidates = runs.selectList(Wrappers.lambdaQuery(DataAccessTaskDO.class)
                .select(DataAccessTaskDO::getId).eq(DataAccessTaskDO::getProtocolVersion, 1)
                .eq(DataAccessTaskDO::getOccupiesSlot, true).in(DataAccessTaskDO::getRunStatus, 1, 2)
                .apply("lease_until <= clock_timestamp()").orderByAsc(DataAccessTaskDO::getLeaseUntil)
                .last("LIMIT " + bounded(limit)));
        int recovered = 0, failures = 0;
        for (DataAccessTaskDO candidate : candidates) {
            try {
                if (recoverExpiredRecord(candidate.getId())) {
                    recovered++;
                }
            } catch (RuntimeException failure) {
                failures++;
                log.warn("ACCESS 恢复失败 record={}", candidate.getId());
            }
        }
        scanResult("recover", candidates.size(), recovered, failures);
        return recovered;
    }

    /**
     * 有界扫描到期且可自动对账的未知运行，逐项核对远程执行结果。
     *
     * @param limit 读取或处理上限
     * @return 对账到期数值
     */
    public int reconcileDue(int limit) {
        List<DataAccessTaskDO> candidates = runs.selectList(Wrappers.lambdaQuery(DataAccessTaskDO.class)
                .select(DataAccessTaskDO::getId).eq(DataAccessTaskDO::getRunStatus, 5)
                .eq(DataAccessTaskDO::getOccupiesSlot, true)
                .lt(DataAccessTaskDO::getReconcileAttempts, properties.getAutomaticFailureLimit())
                .apply("(next_reconcile_at IS NULL OR next_reconcile_at <= clock_timestamp())")
                .apply("(seatunnel_job_id IS NOT NULL OR execution_stage='SUBMIT_SENT')")
                .orderByAsc(DataAccessTaskDO::getId).last("LIMIT " + Math.min(bounded(limit), 8)));
        int processed = 0, failures = 0;
        for (DataAccessTaskDO candidate : candidates) {
            try {
                if (reconcileRecord(candidate.getId())) {
                    processed++;
                }
            } catch (RuntimeException failure) {
                failures++;
                log.warn("ACCESS 对账失败 record={}", candidate.getId());
            }
        }
        scanResult("reconcile", candidates.size(), processed, failures);
        return processed;
    }

    /**
     * 使用持久执行或停止操作者身份核对远程任务状态，推进同步运行的对账结果。
     *
     * @param id 记录编号
     * @return 处理是否成功
     */
    public boolean reconcileRecord(Long id) {
        DataAccessTaskDO observed = requireRun(id);
        // 此身份仅用于控制已知的远程任务，不能用于重放运行
        // 或授权数据源操作。历史记录可能没有执行者信息或执行快照。
        boolean authorizedStop = Boolean.TRUE.equals(observed.getStopRequested()) && observed.getStopActorId() != null
                && cn.hutool.core.util.StrUtil.isNotBlank(observed.getSeatunnelJobId());
        Long controlActor = authorizedStop ? observed.getStopActorId() : observed.getExecutionUserId();
        boolean policyStop = false;
        DataAccessDO remoteDefinition = null;
        if (observed.getConfigJson() != null) {
            try {
                DataAccessDO snapshot = AccessRunSnapshot.restore(observed.getConfigJson());
                if (Integer.valueOf(2).equals(snapshot.getEngineType())) {
                    remoteDefinition = snapshot;
                }
            } catch (RuntimeException invalidSnapshot) {
                // 历史运行仍按原有控制身份查询，绝不将损坏定义用于新提交。
            }
        }
        if (remoteDefinition != null) {
            try {
                authorization.execution(remoteDefinition, observed.getExecutionUserId());
            } catch (RuntimeException revoked) {
                policyStop = true;
            }
        } else {
            authorization.action(task(observed.getDataAccessId(), false), controlActor, "dataforge:access:run");
        }
        var claim = claimReconciliation(id);
        if (claim.isEmpty()) {
            return false;
        }
        Reconciliation lease = claim.get();
        String jobId = lease.jobId();
        try {
            if (jobId == null || jobId.isBlank()) {
                String name = "access-" + id;
                var jobs = cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.getAllJobs();
                List<String> matches = new ArrayList<>();
                for (int i = 0; i < jobs.size(); i++) {
                    var job = jobs.getJSONObject(i);
                    if (name.equals(job.getStr("jobName")) && job.getStr("jobId") != null) {
                        matches.add(job.getStr("jobId"));
                    }
                }
                if (matches.size() != 1) {
                    return completeReconciliation(lease, null, null, RunProtocol.Effect.UNKNOWN, false, true);
                }
                jobId = matches.getFirst();
            }
            // 系统只控制该持久记录绑定的已有作业；撤权后重试停止不能依赖已撤销的运行权限。
            if (policyStop || Boolean.TRUE.equals(requireRun(id).getStopRequested())) {
                cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.stopJob(
                        JSONUtil.toJsonStr(Map.of("jobId", jobId, "isStopWithSavePoint", false)));
            }
            var remote = cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.getJobInfo(jobId);
            if (!jobId.equals(remote.getStr("jobId"))) {
                return completeReconciliation(lease, jobId, null, RunProtocol.Effect.UNKNOWN, false, true);
            }
            String state = remote.getStr("jobStatus", "");
            // 非终态必须保留 null，避免嵌套数字三元表达式拆箱导致对账异常。
            Integer terminal = switch (state) {
                case "FINISHED" -> cn.hutool.core.util.StrUtil.isBlank(remote.getStr("errorMsg")) ? 3 : 4;
                case "FAILED" -> 4;
                case "CANCELED", "CANCEL" -> 6;
                default -> null;
            };
            return completeReconciliation(lease, jobId, terminal, terminal != null && terminal == 3
                    ? RunProtocol.Effect.COMPLETE : RunProtocol.Effect.PARTIAL, terminal != null, false);
        } catch (RuntimeException unavailable) {
            completeReconciliation(lease, jobId, null, RunProtocol.Effect.UNKNOWN, false, true);
            throw unavailable;
        }
    }

    /**
     * 校验扫描数量为正，并限制在配置的单批扫描上限内。
     *
     * @param requested 已请求
     * @return 有界数值
     */
    private int bounded(int requested) {
        if (requested < 1) {
            throw new IllegalArgumentException("scan limit must be positive");
        }
        return Math.min(requested, properties.getScanBatch());
    }

    /**
     * 记录本轮扫描进展，存在单项失败时向调度入口报告失败。
     *
     * @param phase 阶段
     * @param scanned 已扫描
     * @param progressed 已推进
     * @param failures 失败
     */
    private void scanResult(String phase, int scanned, int progressed, int failures) {
        log.info("ACCESS scan phase={} scanned={} progressed={} failures={}", phase, scanned, progressed, failures);
        if (failures != 0) {
            throw new IllegalStateException("ACCESS " + phase + " 部分失败: " + failures + ", 已推进: " + progressed);
        }
    }

    /**
     * 校验发布和执行权限后冻结任务快照，在事务内受理发布及对应的运行调度。
     *
     * @param taskId 任务编号
     */
    public void publish(Long taskId) {
        DataAccessDO observed = task(taskId, false);
        Long user = actor();
        authorization.action(observed, user, "dataforge:access:release");
        if (Objects.equals(observed.getStatus(), 1)) {
            return;
        }
        DataAccessDO prepared = AccessRunSnapshot.restore(AccessRunSnapshot.freeze(observed));
        authorization.execution(prepared, user);
        String frozen = AccessRunSnapshot.freeze(prepared);
        boolean periodic = Objects.equals(observed.getTaskType(), 3);
        if (periodic && (observed.getCron() == null || !CronExpression.isValidExpression(observed.getCron()))) {
            throw exception(DATA_ACCESS_CRON_INVALID, observed.getCron());
        }
        transactions.executeWithoutResult(tx -> {
            DataAccessDO locked = task(taskId, true);
            if (Objects.equals(locked.getStatus(), 1)) {
                return;
            }
            requireDefinition(locked, observed);
            if (runs.countSlots(taskId) > 0 || pending(taskId) > 0) {
                throw exception(DATA_ACCESS_RUNNING);
            }
            long generation = locked.getScheduleGeneration() + 1;
            LocalDateTime next = periodic ? CronExpression.parse(locked.getCron()).next(runs.databaseNow()) : null;
            tasks.update(null, Wrappers.lambdaUpdate(DataAccessDO.class).eq(DataAccessDO::getId, taskId)
                    .set(DataAccessDO::getStatus, 1)
                    .set(DataAccessDO::getPublishedRevision, locked.getRevision())
                    .set(DataAccessDO::getPublishedExecutionUserId, user)
                    .setSql("published_config_json=CAST({0} AS jsonb),release_time=clock_timestamp()", frozen)
                    .set(DataAccessDO::getReleaseUserId, user).set(DataAccessDO::getScheduleGeneration, generation)
                    .set(DataAccessDO::getNextRunTime, next));
            locked.setScheduleGeneration(generation);
            locked.setPublishedRevision(locked.getRevision());
            if (!periodic) {
                insert(locked, frozen, user, "publish:" + taskId + ":" + generation,
                        hash("PUBLISH", taskId, generation, user), 1, null);
            }
            scheduleAudit(locked, user, "发布运行定义", observed.getScheduleGeneration(), observed.getNextRunTime());
        });
    }

    /**
     * 校验发布权限后取消任务发布，推进调度代次并处理已受理的自动运行。
     *
     * @param taskId 任务编号
     */
    public void unpublish(Long taskId) {
        DataAccessDO observed = task(taskId, false);
        Long user = actor();
        authorization.action(observed, user, "dataforge:access:release");
        transactions.executeWithoutResult(tx -> {
            DataAccessDO locked = task(taskId, true);
            long generation = locked.getScheduleGeneration() + 1;
            tasks.update(null, Wrappers.lambdaUpdate(DataAccessDO.class).eq(DataAccessDO::getId, taskId)
                    .set(DataAccessDO::getStatus, 0)
                    .set(DataAccessDO::getNextRunTime, null).set(DataAccessDO::getScheduleGeneration, generation));
            var active = runs.selectList(
                    Wrappers.lambdaQuery(DataAccessTaskDO.class).eq(DataAccessTaskDO::getDataAccessId, taskId)
                            .in(DataAccessTaskDO::getRunStatus, 1, 2, 5).orderByAsc(DataAccessTaskDO::getId));
            for (DataAccessTaskDO run : active) {
                String request = "unpublish:" + UUID.randomUUID();
                String before = audit(run);
                boolean safe = Objects.equals(run.getProtocolVersion(), 1) && "PREPARE".equals(run.getExecutionStage())
                        && run.getExecutionToken() == null && !Boolean.TRUE.equals(run.getOccupiesSlot());
                var update = Wrappers.lambdaUpdate(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, run.getId())
                        .setSql("row_version=row_version+1");
                String hash = hash("UNPUBLISH", taskId, run.getId(), generation, user);
                if (!Boolean.TRUE.equals(run.getStopRequested())) {
                    update.set(DataAccessTaskDO::getStopRequested, true)
                            .set(DataAccessTaskDO::getStopRequestId, request)
                            .set(DataAccessTaskDO::getStopRequestHash, hash)
                            .set(DataAccessTaskDO::getStopActorId, user).setSql("stop_requested_at=clock_timestamp()");
                }
                if (safe) {
                    update.set(DataAccessTaskDO::getRunStatus, 6).setSql("end_time=clock_timestamp()");
                }
                runs.update(null, update);
                saveCommand(requireRun(run.getId()), request, hash, "STOP", user, run.getRowVersion(), before,
                        "撤销发布", null);
                if (safe) {
                    summary(taskId, run.getId(), 6, true);
                }
            }
            locked.setScheduleGeneration(generation);
            scheduleAudit(locked, user, "撤销发布；停止未开始记录，对活动记录持久请求停止",
                    observed.getScheduleGeneration(), observed.getNextRunTime());
        });
    }

    /**
     * 持久化调度代次变化及未受理时刻的命令审计，保留操作人和变更原因。
     *
     * @param task 任务记录
     * @param user 用户
     * @param reason 操作原因
     * @param previousGeneration 先前代次
     * @param previousDue 先前到期
     */
    private void scheduleAudit(DataAccessDO task, Long user, String reason, Long previousGeneration,
            LocalDateTime previousDue) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("generation", previousGeneration);
        before.put("unacceptedFrom", previousDue == null ? null : previousDue.toString());
        String request = "schedule:" + UUID.randomUUID();
        runs.insertCommand(RunCommandDO.builder().domain("ACCESS").recordId(0L).requestId(request)
                .action("SCHEDULE_CHANGE").requestHash(hash(task.getId(), request)).actorId(user)
                .beforeJson(JSONUtil.toJsonStr(before)).afterJson(JSONUtil.toJsonStr(Map.of("taskId", task.getId(),
                        "generation", task.getScheduleGeneration(), "reason", reason))).resultJson("{}").build());
    }

    /**
     * 锁定并复核任务定义及空闲状态，更新草稿并推进修订和调度代次。
     *
     * @param observed 先前读取的状态
     * @param replacement 替换
     */
    public void updateDraft(DataAccessDO observed, DataAccessDO replacement) {
        transactions.executeWithoutResult(tx -> {
            DataAccessDO current = task(observed.getId(), true);
            requireDefinition(current, observed);
            requireIdle(current);
            replacement.setRevision(current.getRevision() + 1);
            replacement.setScheduleGeneration(current.getScheduleGeneration() + 1);
            tasks.updateById(replacement);
        });
    }

    /**
     * 锁定并确认任务可删除后删除定义，保留执行身份、审计和历史记录。
     *
     * @param id 记录编号
     */
    public void deleteDefinition(Long id) {
        transactions.executeWithoutResult(tx -> {
            DataAccessDO current = task(id, true);
            requireIdle(current);
            tasks.deleteById(id);
            // 完整保留执行标识、审计与历史记录。
        });
    }

    /**
     * 拒绝修改已发布、仍有运行占用或待执行记录的任务定义。
     *
     * @param task 任务记录
     */
    private void requireIdle(DataAccessDO task) {
        if (Objects.equals(task.getStatus(), 1)) {
            throw exception(DATA_ACCESS_RELEASED_CANNOT_MODIFY);
        }
        if (runs.countSlots(task.getId()) > 0 || pending(task.getId()) > 0) {
            throw exception(DATA_ACCESS_RUNNING);
        }
    }

    /**
     * 按稳定的父任务与运行锁序批量删除允许清理的记录，拒绝占用中的运行。
     *
     * @param ids 记录编号集合
     */
    public void deleteRuns(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.size() > properties.getScanBatch()) {
            throw exception(DATA_ACCESS_RUN_PENDING_LIMIT);
        }
        // 稳定的加锁顺序可避免批量删除时因反向锁定父记录而死锁。
        var observed = ids.stream().distinct().map(this::requireRun)
                .sorted(Comparator.comparing(DataAccessTaskDO::getDataAccessId).thenComparing(DataAccessTaskDO::getId))
                .toList();
        transactions.executeWithoutResult(tx -> {
            for (DataAccessTaskDO item : observed) {
                DataAccessTaskDO run = lockRun(item.getId());
                if (run == null || Boolean.TRUE.equals(run.getOccupiesSlot()) || Boolean.TRUE.equals(
                        run.getReviewRequired())
                        || List.of(1, 2, 5).contains(run.getRunStatus())) {
                    throw exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
                }
                runs.deleteById(run.getId());
            }
        });
    }

    public record Reconciliation(Long recordId, String token, long version, String jobId) {

    }

    /**
     * 为未知运行认领独立对账租约，并限制自动对账次数及执行时机。
     *
     * @param id 记录编号
     * @return 对账
     */
    public Optional<Reconciliation> claimReconciliation(Long id) {
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(id);
            if (run == null) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            int changed = runs.update(null, Wrappers.lambdaUpdate(DataAccessTaskDO.class)
                    .eq(DataAccessTaskDO::getId, id).eq(DataAccessTaskDO::getRunStatus, 5)
                    .eq(DataAccessTaskDO::getOccupiesSlot, true)
                    .eq(DataAccessTaskDO::getRowVersion, run.getRowVersion())
                    .lt(DataAccessTaskDO::getReconcileAttempts, properties.getAutomaticFailureLimit())
                    .apply("(next_reconcile_at IS NULL OR next_reconcile_at <= clock_timestamp())")
                    .apply("(reconcile_lease_until IS NULL OR reconcile_lease_until <= clock_timestamp())")
                    .set(DataAccessTaskDO::getReconcileToken, token)
                    .setSql("reconcile_lease_until=clock_timestamp()+({0} * interval '1 second'),row_version=row_version+1",
                            properties.getLease().toSeconds()));
            return changed == 1 ? Optional.of(
                    new Reconciliation(id, token, run.getRowVersion() + 1, run.getSeatunnelJobId())) : Optional.empty();
        });
    }

    /**
     * 核对对账令牌、修订号和有效期后保存远程终态或下一轮对账状态。
     *
     * @param lease 租约
     * @param jobId 任务编号
     * @param status 目标状态
     * @param effect 副作用
     * @param terminated 已终止
     * @param failed 失败
     * @return 处理是否成功
     */
    public boolean completeReconciliation(Reconciliation lease, String jobId, Integer status,
            RunProtocol.Effect effect, boolean terminated, boolean failed) {
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(lease.recordId());
            if (run == null) {
                return false;
            }
            var guard = Wrappers.lambdaUpdate(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, lease.recordId())
                    .eq(DataAccessTaskDO::getRunStatus, 5)
                    .eq(DataAccessTaskDO::getReconcileToken, lease.token())
                    .eq(DataAccessTaskDO::getRowVersion, lease.version())
                    .apply("reconcile_lease_until > clock_timestamp()").set(DataAccessTaskDO::getReconcileToken, null)
                    .set(DataAccessTaskDO::getReconcileLeaseUntil, null).setSql("row_version=row_version+1");
            if (jobId != null) {
                guard.set(DataAccessTaskDO::getSeatunnelJobId, jobId);
            }
            if (terminated && status != null && List.of(3, 4, 6).contains(status)) {
                boolean release = RunProtocol.canRelease(true, effect, false, false);
                guard.set(DataAccessTaskDO::getRunStatus, status)
                        .set(DataAccessTaskDO::getEffectState, effect == null ? "UNKNOWN" : effect.name())
                        .set(DataAccessTaskDO::getOccupiesSlot, !release)
                        .set(DataAccessTaskDO::getReviewRequired, !release)
                        .setSql("end_time=clock_timestamp()");
            } else {
                int failures = failed ? run.getReconcileAttempts() + 1 : 0;
                guard.set(DataAccessTaskDO::getReconcileAttempts, failures)
                        .setSql("next_reconcile_at=clock_timestamp()+({0} * interval '1 second')",
                                RunProtocol.retryDelaySeconds(failures, 0));
            }
            int changed = runs.update(null, guard);
            if (changed == 1 && terminated && status != null) {
                summary(run.getDataAccessId(), run.getId(), status, true);
                observeStop(run);
            }
            if (changed == 1 && failed) {
                metrics.event(
                        cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                        cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.RECONCILE_FAILED);
            }
            return changed == 1;
        });
    }

    /**
     * 配置独立且采用读已提交隔离级别的本地状态事务模板。
     *
     * @param manager 管理器
     */
    @Resource
    public void configureTransactions(PlatformTransactionManager manager) {
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 校验执行权限并冻结当前配置，以请求标识及内容摘要幂等受理手工运行。
     *
     * @param taskId 任务编号
     * @param requestId 请求编号
     * @return 运行回执
     */
    public RunReceipt enqueueManual(Long taskId, String requestId) {
        validateRequest(requestId);
        DataAccessDO observed = task(taskId, false);
        Long actor = actor();
        authorization.action(observed, actor, "dataforge:access:run");
        String key = "manual:" + taskId + ":" + requestId;
        String hash = hash("RUN", taskId, actor, requestId);
        RunReceipt replay = replay(key, hash);
        if (replay != null) {
            return replay;
        }
        DataAccessDO prepared = AccessRunSnapshot.restore(snapshot(observed));
        authorization.execution(prepared, actor);
        String snapshot = AccessRunSnapshot.freeze(prepared);
        return transactions.execute(tx -> {
            DataAccessDO locked = task(taskId, true);
            RunReceipt existing = replay(key, hash);
            if (existing != null) {
                return existing;
            }
            requireDefinition(locked, observed);
            if (pending(taskId) >= properties.getPerTaskPending()) {
                throw exception(DATA_ACCESS_RUN_PENDING_LIMIT);
            }
            return insert(locked, snapshot, actor, key, hash, 2, null);
        });
    }

    /**
     * 复核发布快照、调度代次和计划时刻，幂等受理本次定时运行。
     *
     * @param taskId 任务编号
     * @param generation 代次
     * @param scheduled 定时
     * @return 运行回执
     */
    public Optional<RunReceipt> enqueueScheduled(Long taskId, long generation, LocalDateTime scheduled) {
        DataAccessDO observed = task(taskId, false);
        if (scheduled == null) {
            return Optional.empty();
        }
        Long user = observed.getPublishedExecutionUserId();
        authorization.action(observed, user, "dataforge:access:run");
        String key = "scheduled:" + taskId + ":" + generation + ":" + scheduled;
        String hash = hash("SCHEDULED", taskId, generation, scheduled);
        RunReceipt old = replay(key, hash);
        if (old != null) {
            return Optional.of(old);
        }
        if (observed.getPublishedConfigJson() == null) {
            throw exception(DATA_ACCESS_RUN_DEFINITION_CHANGED);
        }
        DataAccessDO prepared = AccessRunSnapshot.restore(observed.getPublishedConfigJson());
        authorization.execution(prepared, user);
        String snapshot = AccessRunSnapshot.freeze(prepared);
        return transactions.execute(tx -> {
            DataAccessDO locked = task(taskId, true);
            RunReceipt existing = replay(key, hash);
            if (existing != null) {
                return Optional.of(existing);
            }
            if (!Objects.equals(locked.getScheduleGeneration(), generation)
                    || !Objects.equals(locked.getNextRunTime(), scheduled) || !Objects.equals(locked.getStatus(), 1)
                    || !sameDefinition(locked, observed) || scheduled.isAfter(runs.databaseNow())
                    || pending(taskId) >= properties.getPerTaskPending()) {
                return Optional.empty();
            }
            String cron = AccessRunSnapshot.restore(snapshot).getCron();
            if (cron == null || !CronExpression.isValidExpression(cron)) {
                throw exception(DATA_ACCESS_CRON_INVALID, cron);
            }
            LocalDateTime next = CronExpression.parse(cron).next(scheduled);
            if (next == null) {
                throw exception(DATA_ACCESS_CRON_INVALID, cron);
            }
            RunReceipt receipt = insert(locked, snapshot, user, key, hash, 1, scheduled);
            int updated = tasks.update(null, Wrappers.lambdaUpdate(DataAccessDO.class)
                    .set(DataAccessDO::getNextRunTime, next).eq(DataAccessDO::getId, taskId)
                    .eq(DataAccessDO::getScheduleGeneration, generation)
                    .eq(DataAccessDO::getNextRunTime, scheduled));
            if (updated != 1) {
                throw exception(DATA_ACCESS_RUN_DEFINITION_CHANGED);
            }
            return Optional.of(receipt);
        });
    }

    /**
     * 保存带冻结配置、数据源资源范围和执行用户的待运行记录，并返回受理回执。
     *
     * @param task 任务记录
     * @param snapshot 快照
     * @param user 用户
     * @param key 标识键
     * @param hash 摘要
     * @param type 类型标识
     * @param scheduled 定时
     * @return 运行回执
     */
    private RunReceipt insert(DataAccessDO task, String snapshot, Long user, String key, String hash, int type,
            LocalDateTime scheduled) {
        if (user == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        DataAccessTaskDO run = DataAccessTaskDO.builder().protocolVersion(1).dataAccessId(task.getId())
                .taskName(task.getTaskName()).orgId(task.getOrgId()).runKey(key).requestHash(hash).configJson(snapshot)
                .resourceIds(RunResourceScope.access(snapshot))
                .executionUserId(user).releaseRevision(task.getPublishedRevision()).scheduledTime(scheduled)
                .scheduleGeneration(task.getScheduleGeneration()).executionStage("PREPARE").effectState("NONE")
                .runStatus(1).runType(type).occupiesSlot(false).stopRequested(false).reviewRequired(false)
                .rowVersion(0L).attemptCount(0).reconcileAttempts(0).dataCount(0L).tableSuccessCount(0L)
                .tableFailCount(0L).build();
        runs.insert(run);
        return receipt(run, false);
    }

    /**
     * 在任务锁、队首顺序和容量限制内认领运行，授予带有效期的执行令牌。
     *
     * @param recordId 记录编号
     * @param node 节点
     * @return 运行租约
     */
    public Optional<RunLease> claim(Long recordId, String node) {
        if (node == null || node.isBlank() || node.length() > 128) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "节点身份");
        }
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(recordId);
            if (run == null || runs.countSlots(run.getDataAccessId()) != 0) {
                return Optional.empty();
            }
            if (!Objects.equals(runs.oldestWaiting(run.getDataAccessId()), recordId)) {
                return Optional.empty();
            }
            // 自动运行要求定义仍处于已发布状态；手动执行仍支持草稿。
            if (Objects.equals(run.getRunType(), 1) && !Objects.equals(task(run.getDataAccessId(), false).getStatus(),
                    1)) {
                return Optional.empty();
            }
            String resourceIds = run.getResourceIds() == null
                    ? RunResourceScope.access(run.getConfigJson()) : run.getResourceIds();
            if (!runs.hasCapacity(properties.getPlatformActive(), properties.getPerDataSourceActive(),
                    RunResourceScope.decode(resourceIds))) {
                metrics.event(cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                        cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.CAPACITY_REJECTED);
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            int changed = runs.update(null, Wrappers.lambdaUpdate(DataAccessTaskDO.class)
                    .eq(DataAccessTaskDO::getId, recordId)
                    .eq(DataAccessTaskDO::getProtocolVersion, 1).eq(DataAccessTaskDO::getRunStatus, 1)
                    .eq(DataAccessTaskDO::getExecutionStage, "PREPARE")
                    .eq(DataAccessTaskDO::getOccupiesSlot, false).eq(DataAccessTaskDO::getStopRequested, false)
                    .eq(DataAccessTaskDO::getReviewRequired, false).isNull(DataAccessTaskDO::getExecutionToken)
                    .apply("(next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())")
                    .set(DataAccessTaskDO::getExecutionToken, token).set(DataAccessTaskDO::getLeaseOwner, node)
                    .set(DataAccessTaskDO::getOccupiesSlot, true)
                    .set(DataAccessTaskDO::getResourceIds, resourceIds,
                            "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                    .setSql("lease_until=clock_timestamp()+({0} * interval '1 second'),attempt_count=attempt_count+1,row_version=row_version+1",
                            properties.getLease().toSeconds()));
            if (changed != 1) {
                return Optional.empty();
            }
            tasks.update(null, Wrappers.lambdaUpdate(DataAccessDO.class).eq(DataAccessDO::getId, run.getDataAccessId())
                    .set(DataAccessDO::getLatestRunId, recordId).set(DataAccessDO::getRunStatus, 1));
            metrics.event(cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                    cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.CLAIMED);
            return Optional.of(new RunLease(recordId, token, run(recordId).getLeaseUntil()));
        });
    }

    /**
     * 仅为仍持有有效执行令牌的同步运行续约。
     *
     * @param lease 租约
     * @return 处理是否成功
     */
    public boolean renew(RunLease lease) {
        return transactions.execute(tx -> lockRun(lease.recordId()) != null && runs.update(null, owned(lease)
                .setSql("lease_until=clock_timestamp()+({0} * interval '1 second')",
                        properties.getLease().toSeconds())) == 1);
    }

    /**
     * 判断指定令牌是否仍拥有运行占位及未过期的执行租约。
     *
     * @param lease 租约
     * @return 处理是否成功
     */
    public boolean owns(RunLease lease) {
        return lease != null && lease.token() != null && runs.selectCount(Wrappers.lambdaQuery(DataAccessTaskDO.class)
                .eq(DataAccessTaskDO::getId, lease.recordId())
                .eq(DataAccessTaskDO::getProtocolVersion, 1).eq(DataAccessTaskDO::getExecutionToken, lease.token())
                .eq(DataAccessTaskDO::getOccupiesSlot, true).in(DataAccessTaskDO::getRunStatus, 1, 2)
                .apply("lease_until > clock_timestamp()")) == 1;
    }

    /**
     * 在租约失效、运行不存在或已请求停止时要求执行器退出。
     *
     * @param lease 租约
     * @return 判断结果
     */
    public boolean shouldStop(RunLease lease) {
        if (!owns(lease)) {
            return true;
        }
        DataAccessTaskDO run = run(lease.recordId());
        return run == null || Boolean.TRUE.equals(run.getStopRequested());
    }

    /**
     * 在外部副作用开始前记录执行阶段及未知效果状态。
     *
     * @param lease 租约
     * @param stage 阶段
     * @return 处理是否成功
     */
    public boolean startEffects(RunLease lease, RunProtocol.Stage stage) {
        if (stage == null || stage == RunProtocol.Stage.PREPARE) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "执行阶段");
        }
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(lease.recordId());
            if (run == null) {
                return false;
            }
            int changed = runs.update(null, owned(lease).eq(DataAccessTaskDO::getStopRequested, false)
                    .eq(DataAccessTaskDO::getExecutionStage, "PREPARE")
                    .set(DataAccessTaskDO::getExecutionStage, stage.name())
                    .set(DataAccessTaskDO::getEffectState, "UNKNOWN").set(DataAccessTaskDO::getRunStatus, 2)
                    .setSql("start_time=clock_timestamp(),row_version=row_version+1"));
            if (changed == 1) {
                summary(run.getDataAccessId(), lease.recordId(), 2, false);
            }
            return changed == 1;
        });
    }

    /** 回调仅持久化本地平台状态，不执行 RPC、引擎调用或阻塞操作。 */
    public boolean writeOwned(RunLease lease, Runnable localWrites) {
        return transactions.execute(tx -> {
            if (lockRun(lease.recordId()) == null || !owns(lease)) {
                return false;
            }
            localWrites.run();
            if (!owns(lease)) {
                tx.setRollbackOnly();
                return false;
            }
            return true;
        });
    }

    /** 与 lease 检查在同一短事务冻结实际连接绑定；重复或旧节点不能覆盖。 */
    public boolean recordSyncBinding(RunLease lease, SyncBatchEvidenceDTO binding) {
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(lease.recordId());
            if (run == null || !owns(lease)) {
                return false;
            }
            if (binding == null || !Objects.equals(binding.schemaVersion(), 1)
                    || !Objects.equals(binding.runId(), run.getId())
                    || !Objects.equals(binding.attempt(), run.getAttemptCount())
                    || !"EXECUTION_STARTED".equals(run.getExecutionStage())
                    || !"RUNNING".equals(binding.termination()) || binding.manifestComplete()
                    || !binding.tables().isEmpty() || run.getSyncEvidence() != null
                    || binding.targetDatabaseId() == null || binding.targetDatabaseId() <= 0) {
                throw exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
            }
            // 同目标的提交边界和启动共用事务顺序；必须先于引擎实例化/任何源读取。
            runs.ensureSyncClock(binding.targetDatabaseId());
            Long position = runs.nextSyncPosition(binding.targetDatabaseId());
            if (position == null || position <= 0) {
                throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
            }
            int changed = runs.update(null, owned(lease).isNull(DataAccessTaskDO::getSyncEvidence)
                    .isNull(DataAccessTaskDO::getSyncStartPosition)
                    .set(DataAccessTaskDO::getSyncStartPosition, position)
                    .set(DataAccessTaskDO::getSyncEvidence, JsonUtils.toJsonString(binding),
                            "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                    .setSql("row_version=row_version+1"));
            if (changed != 1) {
                tx.setRollbackOnly();
            }
            return changed == 1;
        });
    }

    /**
     * 校验当前执行权及冻结证据绑定，保存终态或未知结果，并处理资源占位。
     *
     * @param lease 租约
     * @param result 处理结果
     * @param terminated 已终止
     * @param effect 副作用
     * @return 处理是否成功
     */
    public boolean finish(RunLease lease, DataAccessTaskDO result, boolean terminated, RunProtocol.Effect effect) {
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(lease.recordId());
            if (run == null || !owns(lease)) {
                return false;
            }
            // 不允许完成回调修改冻结绑定；无新清单时保留原 RUNNING 证据供未知结果对账。
            if (result.getSyncEvidence() != null && !AccessSyncEvidence.sameBinding(
                    JsonUtils.parseObject(run.getSyncEvidence(), SyncBatchEvidenceDTO.class),
                    JsonUtils.parseObject(result.getSyncEvidence(), SyncBatchEvidenceDTO.class))) {
                throw exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
            }
            int status = terminated ? result.getRunStatus() : 5;
            if (!List.of(3, 4, 5, 6).contains(status)) {
                throw exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
            }
            RunProtocol.Effect actual = effect == null ? RunProtocol.Effect.UNKNOWN : effect;
            boolean release = status != 5 && RunProtocol.canRelease(terminated, actual, false, false);
            int changed = runs.update(null, owned(lease).set(DataAccessTaskDO::getRunStatus, status)
                    .set(DataAccessTaskDO::getEffectState, actual.name())
                    .set(DataAccessTaskDO::getOccupiesSlot, !release)
                    .set(DataAccessTaskDO::getReviewRequired, !release).set(DataAccessTaskDO::getExecutionToken, null)
                    .set(DataAccessTaskDO::getLeaseUntil, null).set(DataAccessTaskDO::getLeaseOwner, null)
                    .set(DataAccessTaskDO::getDataCount, result.getDataCount())
                    .set(DataAccessTaskDO::getTableSuccessCount, result.getTableSuccessCount())
                    .set(DataAccessTaskDO::getTableFailCount, result.getTableFailCount())
                    .set(DataAccessTaskDO::getByteCount, result.getByteCount())
                    .set(DataAccessTaskDO::getErrorInfo, result.getErrorInfo())
                    .set(DataAccessTaskDO::getRealTimeLog, result.getRealTimeLog())
                    .set(result.getSyncEvidence() != null, DataAccessTaskDO::getSyncEvidence, result.getSyncEvidence(),
                            "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                    .setSql("end_time=clock_timestamp(),row_version=row_version+1"));
            if (changed == 1) {
                summary(run.getDataAccessId(), lease.recordId(), status, true);
                if (terminated && status != 5) {
                    observeStop(run);
                }
            }
            return changed == 1;
        });
    }

    /**
     * 仅重新排队尚未开始副作用的运行，保留停止意图并记录释放原因。
     *
     * @param lease 租约
     * @param reason 操作原因
     * @return 处理是否成功
     */
    public boolean releaseBeforeExecution(RunLease lease, String reason) {
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(lease.recordId());
            boolean changed = run != null && runs.update(null,
                    requeue(owned(lease), run).set(DataAccessTaskDO::getErrorInfo, reason)) == 1;
            if (changed) {
                observeStop(run);
            }
            return changed;
        });
    }

    /** 本地容量拒绝属于背压，不属于执行失败。 */
    public boolean releaseRejected(RunLease lease) {
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(lease.recordId());
            if (run == null) {
                return false;
            }
            run.setAttemptCount(Math.max(0, run.getAttemptCount() - 1));
            int changed = runs.update(null, requeue(owned(lease), run)
                    .set(DataAccessTaskDO::getAttemptCount, run.getAttemptCount())
                    .set(DataAccessTaskDO::getErrorInfo, "本机执行容量不足，保留排队"));
            if (changed == 1) {
                metrics.event(cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                        cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.CAPACITY_REJECTED);
                observeStop(run);
            }
            return changed == 1;
        });
    }

    /**
     * 校验操作者权限并幂等持久化停止意图，返回当前运行回执。
     *
     * @param recordId 记录编号
     * @param requestId 请求编号
     * @param reason 操作原因
     * @return 运行回执
     */
    public RunReceipt requestStop(Long recordId, String requestId, String reason) {
        validateRequest(requestId);
        if (reason != null && reason.length() > 500) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "reason");
        }
        DataAccessTaskDO observed = requireRun(recordId);
        Long user = actor();
        authorization.action(task(observed.getDataAccessId(), false), user, "dataforge:access:run");
        String hash = hash("STOP", recordId, user, reason);
        return commandTransaction(requestId, hash, () -> {
            DataAccessTaskDO run = lockRun(recordId);
            if (run == null) {
                throw exception(DATA_ACCESS_TASK_NOT_EXISTS);
            }
            RunReceipt replay = commandReplay(requestId, hash);
            if (replay != null) {
                return replay;
            }
            String before = audit(run);
            if (List.of(1, 2, 5).contains(run.getRunStatus()) && !Boolean.TRUE.equals(run.getStopRequested())) {
                boolean noEffects = run.getExecutionToken() == null && !Boolean.TRUE.equals(run.getOccupiesSlot())
                        && "PREPARE".equals(run.getExecutionStage()) && Objects.equals(run.getProtocolVersion(), 1);
                var update = Wrappers.lambdaUpdate(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, recordId)
                        .eq(DataAccessTaskDO::getRowVersion, run.getRowVersion())
                        .set(DataAccessTaskDO::getStopRequested, true)
                        .set(DataAccessTaskDO::getStopRequestId, requestId)
                        .set(DataAccessTaskDO::getStopRequestHash, hash).set(DataAccessTaskDO::getStopActorId, user)
                        .setSql("stop_requested_at=clock_timestamp(),row_version=row_version+1");
                if (noEffects) {
                    update.set(DataAccessTaskDO::getRunStatus, 6).setSql("end_time=clock_timestamp()");
                }
                if (runs.update(null, update) != 1) {
                    throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
                }
                if (noEffects) {
                    summary(run.getDataAccessId(), recordId, 6, true);
                    metrics.stopConfirmed(cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                            () -> runs.selectStopTiming(recordId, run.getRowVersion() + 1));
                }
            }
            return saveCommand(requireRun(recordId), requestId, hash, "STOP", user, null, before, reason, null);
        });
    }

    /**
     * 校验人工核查权限、预期修订和停止及产出证据，幂等记录未知运行的处置结论。
     *
     * @param recordId 记录编号
     * @param requestId 请求编号
     * @param expectedVersion 预期版本
     * @param outcome 结果
     * @param confirmedStopped 已确认已停止
     * @param outputReviewed 输出已复核
     * @param reason 操作原因
     * @param evidenceRef 证据引用
     * @return 运行回执
     */
    public RunReceipt resolve(Long recordId, String requestId, long expectedVersion, String outcome,
            boolean confirmedStopped, boolean outputReviewed, String reason, String evidenceRef) {
        validateRequest(requestId);
        if (!List.of("SUCCESS", "FAILED", "STOPPED").contains(outcome == null ? "" : outcome)
                || reason == null || reason.isBlank() || reason.length() > 500 || evidenceRef == null
                || evidenceRef.isBlank() || evidenceRef.length() > 1000) {
            throw exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
        }
        DataAccessTaskDO observed = requireRun(recordId);
        Long user = actor();
        authorization.action(task(observed.getDataAccessId(), false), user, "dataforge:access:resolve");
        String hash = hash("RESOLVE", recordId, user, expectedVersion, outcome, confirmedStopped, outputReviewed,
                reason, evidenceRef);
        return commandTransaction(requestId, hash, () -> {
            DataAccessTaskDO run = lockRun(recordId);
            if (run == null) {
                throw exception(DATA_ACCESS_TASK_NOT_EXISTS);
            }
            RunReceipt replay = commandReplay(requestId, hash);
            if (replay != null) {
                return replay;
            }
            if (!Objects.equals(run.getRowVersion(), expectedVersion)) {
                throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
            }
            if (!confirmedStopped || !outputReviewed || !Boolean.TRUE.equals(run.getReviewRequired())
                    || !RunProtocol.canRelease(confirmedStopped, RunProtocol.Effect.UNKNOWN, false, outputReviewed)) {
                throw exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
            }
            withPlatformConnection(connection -> {
                groupRewriteCoordinator.assertResolved(connection, run.getDataAccessId(), recordId);
                return null;
            });
            String before = audit(run);
            int status = "SUCCESS".equals(outcome) ? 3 : "FAILED".equals(outcome) ? 4 : 6;
            String evidence = JSONUtil.toJsonStr(Map.of("confirmedStopped", true, "outputReviewed", true,
                    "reason", reason, "evidenceRef", evidenceRef, "actorId", user));
            runs.update(null, Wrappers.lambdaUpdate(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, recordId)
                    .eq(DataAccessTaskDO::getRowVersion, expectedVersion)
                    .set(DataAccessTaskDO::getRunStatus, status).set(DataAccessTaskDO::getOccupiesSlot, false)
                    .set(DataAccessTaskDO::getReviewRequired, false).set(DataAccessTaskDO::getExecutionToken, null)
                    .set(DataAccessTaskDO::getLeaseUntil, null).set(DataAccessTaskDO::getReconcileToken, null)
                    .set(DataAccessTaskDO::getReconcileLeaseUntil, null)
                    .setSql("resolution_json=CAST({0} AS jsonb),end_time=clock_timestamp(),row_version=row_version+1",
                            evidence));
            summary(run.getDataAccessId(), recordId, status, true);
            metrics.event(cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                    cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.RESOLVED);
            observeStop(run);
            return saveCommand(requireRun(recordId), requestId, hash, "RESOLVE", user, expectedVersion, before, reason,
                    evidenceRef);
        });
    }

    /** 仅提供平台状态提示；命令执行时始终重复授权及准入校验。 */
    public cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.AccessGroupRecoveryRespVO groupRecoveryStatus(
            Long recordId) {
        DataAccessTaskDO observed = requireRun(recordId);
        authorization.action(task(observed.getDataAccessId(), false), actor(), "dataforge:access:resolve");
        return transactions.execute(tx -> {
            DataAccessTaskDO current = requireRun(recordId);
            var status = withPlatformConnection(connection ->
                    groupRewriteCoordinator.recoveryStatus(connection, current.getDataAccessId(), recordId));
            return new cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.AccessGroupRecoveryRespVO(
                    current.getId(), current.getRowVersion(), status.applicable(), status.ready(), status.released(),
                    status.message());
        });
    }

    /** 显式恢复复用不可变运行记录及命令台账；外部 I/O 在事务提交后开始。 */
    public RunReceipt recoverGroups(Long recordId, String requestId, long expectedVersion,
            String reason, String evidenceRef) {
        validateRequest(requestId);
        if (expectedVersion < 0 || reason == null || reason.isBlank() || reason.length() > 500
                || evidenceRef == null || evidenceRef.isBlank() || evidenceRef.length() > 1000) {
            throw exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
        }
        DataAccessTaskDO observed = requireRun(recordId);
        Long user = actor();
        authorization.action(task(observed.getDataAccessId(), false), user, "dataforge:access:resolve");
        String requestHash = hash("GROUP_RECOVER", recordId, user, expectedVersion, reason, evidenceRef);
        RunReceipt existing = commandReplay(requestId, requestHash);
        if (existing != null) {
            return existing;
        }
        DataAccessDO snapshot = AccessRunSnapshot.restore(observed.getConfigJson());

        authorization.execution(snapshot, user);
        String token = UUID.randomUUID().toString();
        DataAccessExecutor worker = executor.getObject();
        var prepared = worker.prepareGroupRecovery(snapshot, observed, token);
        var admitted = new java.util.concurrent.atomic.AtomicReference<AccessGroupRewriteCoordinator.RecoveryHandle>();
        RunReceipt receipt;
        try {
            receipt = commandTransaction(requestId, requestHash, () -> {
                DataAccessTaskDO run = lockRun(recordId);
                if (run == null) {
                    throw exception(DATA_ACCESS_TASK_NOT_EXISTS);
                }
                RunReceipt replay = commandReplay(requestId, requestHash);
                if (replay != null) {
                    return replay;
                }
                if (!Objects.equals(run.getRowVersion(), expectedVersion)) {
                    throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
                }
                if (!Objects.equals(run.getProtocolVersion(), 1) || !Objects.equals(run.getRunStatus(), 5)
                        || !Boolean.TRUE.equals(run.getOccupiesSlot()) || !Boolean.TRUE.equals(run.getReviewRequired())
                        || !Objects.equals(observed.getConfigJson(), run.getConfigJson())) {
                    throw exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
                }
                String before = audit(run);
                int changed = runs.update(null, Wrappers.lambdaUpdate(DataAccessTaskDO.class)
                        .eq(DataAccessTaskDO::getId, recordId)
                        .eq(DataAccessTaskDO::getRowVersion, expectedVersion)
                        .apply("(reconcile_lease_until IS NULL OR reconcile_lease_until <= clock_timestamp())")
                        .set(DataAccessTaskDO::getReconcileToken, token).set(DataAccessTaskDO::getStopRequested, false)
                        .set(DataAccessTaskDO::getStopRequestId, null).set(DataAccessTaskDO::getStopRequestHash, null)
                        .set(DataAccessTaskDO::getStopActorId, null).set(DataAccessTaskDO::getStopRequestedAt, null)
                        .setSql("reconcile_lease_until=clock_timestamp()+({0} * interval '1 second'),row_version=row_version+1",
                                properties.getLease().toSeconds()));
                if (changed != 1) {
                    throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
                }
                admitted.set(withPlatformConnection(connection ->
                        groupRewriteCoordinator.admitRecovery(connection, prepared.admission())));
                return saveCommand(requireRun(recordId), requestId, requestHash, "GROUP_RECOVER", user,
                        expectedVersion, before, reason, evidenceRef);
            });
        } catch (RuntimeException | Error unknownCommit) {
            // 本方法尚未派发执行器。如果 COMMIT 已生效但
            // 响应丢失，保留的运行时句柄可证明新归属者尚未发送
            // 目标端请求。若准入事务回滚，该句柄将无法通过持久化归属者校验。
            if (admitted.get() != null) {
                try {
                    boolean applied = admitted.get().finished(true);
                    finishGroupRecovery(recordId, token, true, applied);
                } catch (RuntimeException | Error unavailable) {
                    log.warn("ACCESS 组恢复受理结果待核查 record={}", recordId);
                }
            }
            throw unknownCommit;
        }
        if (!receipt.replayed()) {
            worker.submitGroupRecovery(recordId, token, user, prepared, admitted.get());
        }
        return receipt;
    }

    /**
     * 为仍持有表组恢复令牌且未请求停止的运行延长对账租约。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @return 处理是否成功
     */
    public boolean renewGroupRecovery(Long recordId, String token) {
        return transactions.execute(tx -> runs.update(null, groupRecoveryOwned(recordId, token)
                .eq(DataAccessTaskDO::getStopRequested, false).apply("reconcile_lease_until > clock_timestamp()")
                .setSql("reconcile_lease_until=clock_timestamp()+({0} * interval '1 second'),row_version=row_version+1",
                        properties.getLease().toSeconds())) == 1);
    }

    /**
     * 检查表组恢复令牌、运行占位、停止标记及对账租约，判断是否应退出。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @return 判断结果
     */
    public boolean shouldStopGroupRecovery(Long recordId, String token) {
        return runs.selectCount(Wrappers.lambdaQuery(DataAccessTaskDO.class)
                .eq(DataAccessTaskDO::getId, recordId)
                .eq(DataAccessTaskDO::getProtocolVersion, 1).eq(DataAccessTaskDO::getRunStatus, 5)
                .eq(DataAccessTaskDO::getReconcileToken, token).eq(DataAccessTaskDO::getStopRequested, false)
                .eq(DataAccessTaskDO::getOccupiesSlot, true).apply("reconcile_lease_until > clock_timestamp()")) != 1;
    }

    /** 完成确认不能将中断的全表运行改为成功，也不能释放其待复核槽位。 */
    public void finishGroupRecovery(Long recordId, String token, boolean terminated, boolean groupsApplied) {
        transactions.executeWithoutResult(tx -> {
            DataAccessTaskDO run = lockRun(recordId);
            if (run == null) {
                return;
            }
            var update = groupRecoveryOwned(recordId, token)
                    .set(DataAccessTaskDO::getReviewRequired, true).set(DataAccessTaskDO::getEffectState, "UNKNOWN")
                    .set(DataAccessTaskDO::getErrorInfo, !terminated ? "组恢复物理退出尚未确认，保留占位"
                            : groupsApplied ? "数据组恢复已完成；仍须核查原同步的完整输出" : "数据组仍待恢复，保留目标占位")
                    .setSql("row_version=row_version+1");
            if (terminated) {
                update.set(DataAccessTaskDO::getReconcileToken, null)
                        .set(DataAccessTaskDO::getReconcileLeaseUntil, null);
            }
            runs.update(null, update);
        });
    }

    /**
     * 构造绑定未知运行及表组恢复令牌的状态更新条件。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @return Lambda更新条件包装器数据同步任务任务
     */
    private LambdaUpdateWrapper<DataAccessTaskDO> groupRecoveryOwned(Long recordId, String token) {
        if (token == null || token.isBlank()) {
            throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
        }
        return Wrappers.lambdaUpdate(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, recordId)
                .eq(DataAccessTaskDO::getProtocolVersion, 1)
                .eq(DataAccessTaskDO::getRunStatus, 5).eq(DataAccessTaskDO::getOccupiesSlot, true)
                .eq(DataAccessTaskDO::getReconcileToken, token);
    }

    /**
     * 确认连接属于当前平台事务后执行数据库回调，并按 Spring 连接契约释放连接。
     *
     * @param <T> 处理数据的类型
     * @param operation 操作
     * @return 数据同步运行
     */
    private <T> T withPlatformConnection(java.util.function.Function<java.sql.Connection, T> operation) {
        java.sql.Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try {
            if (!org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(connection,
                    dataSource)) {
                throw new IllegalStateException("Group recovery requires the current platform transaction");
            }
            return operation.apply(connection);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * 在独立事务内应用命令，唯一键冲突回滚后读取匹配的幂等回执。
     *
     * @param id 记录编号
     * @param hash 摘要
     * @param mutation 变更
     * @return 运行回执
     */
    private RunReceipt commandTransaction(String id, String hash, java.util.function.Supplier<RunReceipt> mutation) {
        try {
            return transactions.execute(tx -> mutation.get());
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            // 查询标识之前，PostgreSQL 中失败的事务已完成回滚。
            RunReceipt existing = commandReplay(id, hash);
            if (existing == null) {
                throw conflict;
            }
            return existing;
        }
    }

    /**
     * 按请求标识读取持久命令回执，内容摘要不一致时拒绝复用。
     *
     * @param request 请求参数
     * @param hash 摘要
     * @return 运行回执
     */
    private RunReceipt commandReplay(String request, String hash) {
        Map<String, Object> stored = runs.commandIdentity(request);
        if (stored == null) {
            return null;
        }
        if (!Objects.equals(stored.get("request_hash"), hash)) {
            throw exception(DATA_ACCESS_RUN_REQUEST_CONFLICT);
        }
        var json = JSONUtil.parseObj(stored.get("result_json").toString());
        return new RunReceipt(json.getLong("recordId"), true, json.getInt("runStatus"), json.getBool("stopRequested"));
    }

    /**
     * 保存命令身份、前后状态、处置依据及可重放的运行回执。
     *
     * @param run 运行
     * @param request 请求参数
     * @param hash 摘要
     * @param action 待执行的操作
     * @param user 用户
     * @param version 版本
     * @param before 变更前状态
     * @param reason 操作原因
     * @param evidence 处置依据
     * @return 运行回执
     */
    private RunReceipt saveCommand(DataAccessTaskDO run, String request, String hash, String action, Long user,
            Long version, String before, String reason, String evidence) {
        RunReceipt receipt = receipt(run, false);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", JSONUtil.parseObj(audit(run)));
        after.put("reason", reason);
        after.put("evidenceRef", evidence);
        runs.insertCommand(RunCommandDO.builder().domain("ACCESS").recordId(run.getId()).requestId(request)
                .requestHash(hash).action(action).actorId(user).expectedVersion(version).beforeJson(before)
                .afterJson(JSONUtil.toJsonStr(after))
                .resultJson(JSONUtil.toJsonStr(Map.of("recordId", receipt.recordId(),
                        "runStatus", receipt.runStatus(), "stopRequested", receipt.stopRequested()))).build());
        return receipt;
    }

    /**
     * 将运行状态、修订、执行阶段、副作用及占位信息编码为审计快照。
     *
     * @param run 运行
     * @return 将运行状态、修订、执行阶段、副作用及占位信息编码为审计快照文本
     */
    private String audit(DataAccessTaskDO run) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", run.getRunStatus());
        state.put("version", run.getRowVersion());
        state.put("stage", run.getExecutionStage());
        state.put("effect", run.getEffectState());
        state.put("occupiesSlot", run.getOccupiesSlot());
        state.put("stopRequested", run.getStopRequested());
        return JSONUtil.toJsonStr(state);
    }

    /**
     * 读取指定同步运行，不存在时抛出业务错误。
     *
     * @param id 记录编号
     * @return 数据同步任务任务
     */
    public DataAccessTaskDO requireRun(Long id) {
        DataAccessTaskDO run = run(id);
        if (run == null) {
            throw exception(DATA_ACCESS_TASK_NOT_EXISTS);
        }
        return run;
    }

    /**
     * 在短事务内回收过期准备阶段运行，已开始执行的失联运行保留占位等待核查。
     *
     * @param id 记录编号
     * @return 处理是否成功
     */
    public boolean recoverExpiredRecord(Long id) {
        return transactions.execute(tx -> {
            DataAccessTaskDO run = lockRun(id);
            if (run == null || !Objects.equals(run.getProtocolVersion(), 1)) {
                return false;
            }
            var guard = Wrappers.lambdaUpdate(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, id)
                    .eq(DataAccessTaskDO::getRowVersion, run.getRowVersion())
                    .eq(DataAccessTaskDO::getOccupiesSlot, true).in(DataAccessTaskDO::getRunStatus, 1, 2)
                    .apply("lease_until <= clock_timestamp()");
            if ("PREPARE".equals(run.getExecutionStage())) {
                int changed = runs.update(null, requeue(guard, run));
                if (changed == 1) {
                    metrics.event(cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                            cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.RECOVERED);
                    observeStop(run);
                }
                return changed == 1;
            }
            int changed = runs.update(null,
                    guard.set(DataAccessTaskDO::getRunStatus, 5).set(DataAccessTaskDO::getEffectState, "UNKNOWN")
                            .set(DataAccessTaskDO::getReviewRequired, true)
                            .set(DataAccessTaskDO::getExecutionToken, null)
                            .set(DataAccessTaskDO::getLeaseUntil, null).set(DataAccessTaskDO::getLeaseOwner, null)
                            .set(DataAccessTaskDO::getErrorInfo, "执行租约失效；外部资源与输出需核查，禁止重投")
                            .setSql("row_version=row_version+1"));
            if (changed == 1) {
                summary(run.getDataAccessId(), id, 5, false);
                metrics.event(cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                        cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.RECOVERED);
            }
            return changed == 1;
        });
    }

    /**
     * 为准备阶段构造释放令牌及占位的更新，按停止意图、重试上限和退避时间重新排队。
     *
     * @param update 更新
     * @param run 运行
     * @return Lambda更新条件包装器数据同步任务任务
     */
    private LambdaUpdateWrapper<DataAccessTaskDO> requeue(LambdaUpdateWrapper<DataAccessTaskDO> update,
            DataAccessTaskDO run) {
        boolean stopped = Boolean.TRUE.equals(run.getStopRequested());
        boolean exhausted = run.getAttemptCount() >= properties.getAutomaticFailureLimit();
        long delay = RunProtocol.retryDelaySeconds(run.getAttemptCount(), 0);
        update.eq(DataAccessTaskDO::getExecutionStage, "PREPARE").set(DataAccessTaskDO::getExecutionToken, null)
                .set(DataAccessTaskDO::getLeaseUntil, null).set(DataAccessTaskDO::getLeaseOwner, null)
                .set(DataAccessTaskDO::getOccupiesSlot, false).set(DataAccessTaskDO::getEffectState, "NONE")
                .set(DataAccessTaskDO::getReviewRequired, exhausted && !stopped)
                .set(DataAccessTaskDO::getRunStatus, stopped ? 6 : 1)
                .setSql("row_version=row_version+1");
        if (stopped) {
            update.setSql("end_time=clock_timestamp()").set(DataAccessTaskDO::getNextAttemptAt, null);
        } else {
            update.setSql("next_attempt_at=clock_timestamp()+({0} * interval '1 second')", delay);
        }
        return update;
    }

    /**
     * 构造绑定当前执行权的状态筛选或更新条件。
     *
     * @param lease 租约
     * @return Lambda更新条件包装器数据同步任务任务
     */
    private LambdaUpdateWrapper<DataAccessTaskDO> owned(RunLease lease) {
        if (lease == null || lease.token() == null || lease.token().isBlank()) {
            throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
        }
        return Wrappers.lambdaUpdate(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, lease.recordId())
                .eq(DataAccessTaskDO::getProtocolVersion, 1)
                .eq(DataAccessTaskDO::getExecutionToken, lease.token()).eq(DataAccessTaskDO::getOccupiesSlot, true)
                .in(DataAccessTaskDO::getRunStatus, 1, 2).apply("lease_until > clock_timestamp()");
    }

    /**
     * 按编号读取当前同步运行记录。
     *
     * @param id 记录编号
     * @return 数据同步任务任务
     */
    public DataAccessTaskDO run(Long id) {
        return runs.selectOne(Wrappers.lambdaQuery(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, id));
    }

    /**
     * 先锁定所属任务再锁定运行，维持统一的父子加锁顺序。
     *
     * @param id 记录编号
     * @return 数据同步任务任务
     */
    private DataAccessTaskDO lockRun(Long id) {
        DataAccessTaskDO observed = run(id);
        if (observed == null) {
            return null;
        }
        task(observed.getDataAccessId(), true);
        return runs.selectOne(Wrappers.lambdaQuery(DataAccessTaskDO.class).eq(DataAccessTaskDO::getId, id)
                .last("FOR UPDATE"));
    }

    /**
     * 读取任务定义并按需加行锁，不存在时抛出业务错误。
     *
     * @param id 记录编号
     * @param lock 锁
     * @return 数据同步任务
     */
    private DataAccessDO task(Long id, boolean lock) {
        var query = Wrappers.lambdaQuery(DataAccessDO.class).eq(DataAccessDO::getId, id);
        if (lock) {
            query.last("FOR UPDATE");
        }
        DataAccessDO task = tasks.selectOne(query);
        if (task == null) {
            throw exception(DATA_ACCESS_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 统计指定任务当前待执行的运行数量。
     *
     * @param id 记录编号
     * @return 待处理数值
     */
    private long pending(Long id) {
        return runs.selectCount(Wrappers.lambdaQuery(DataAccessTaskDO.class)
                .eq(DataAccessTaskDO::getDataAccessId, id).eq(DataAccessTaskDO::getRunStatus, 1));
    }

    /**
     * 优先读取已发布配置，否则冻结当前任务草稿作为执行快照。
     *
     * @param task 任务记录
     * @return 优先读取已发布配置，否则冻结当前任务草稿作为执行快照文本
     */
    private String snapshot(DataAccessDO task) {
        return Objects.equals(task.getStatus(),
                1) && task.getPublishedConfigJson() != null ? task.getPublishedConfigJson() : AccessRunSnapshot.freeze(
                task);
    }

    /**
     * 比较任务修订、发布修订、调度代次及发布状态是否一致。
     *
     * @param a 首个值
     * @param b 第二个值
     * @return 判断结果
     */
    private boolean sameDefinition(DataAccessDO a, DataAccessDO b) {
        return Objects.equals(a.getRevision(), b.getRevision()) && Objects.equals(a.getPublishedRevision(),
                b.getPublishedRevision())
                && Objects.equals(a.getScheduleGeneration(), b.getScheduleGeneration()) && Objects.equals(a.getStatus(),
                b.getStatus());
    }

    /**
     * 拒绝使用在准备期间已变更的任务定义。
     *
     * @param a 首个值
     * @param b 第二个值
     */
    private void requireDefinition(DataAccessDO a, DataAccessDO b) {
        if (!sameDefinition(a, b)) {
            throw exception(DATA_ACCESS_RUN_DEFINITION_CHANGED);
        }
    }

    /**
     * 读取既有运行身份并核对请求摘要，返回带重放标记的受理回执。
     *
     * @param key 标识键
     * @param hash 摘要
     * @return 运行回执
     */
    private RunReceipt replay(String key, String hash) {
        Map<String, Object> identity = runs.identity(key);
        if (identity == null) {
            return null;
        }
        DataAccessTaskDO run = run(((Number) identity.get("id")).longValue());
        if (run == null || !Objects.equals(run.getRequestHash(), hash)) {
            throw exception(DATA_ACCESS_RUN_REQUEST_CONFLICT);
        }
        return receipt(run, true);
    }

    /**
     * 仅为仍是任务最新运行的记录更新展示状态及起止时间。
     *
     * @param task 任务记录
     * @param run 运行
     * @param status 目标状态
     * @param end 结束位置或时间
     */
    private void summary(Long task, Long run, int status, boolean end) {
        var update = Wrappers.lambdaUpdate(DataAccessDO.class).eq(DataAccessDO::getId, task)
                .eq(DataAccessDO::getLatestRunId, run).set(DataAccessDO::getRunStatus, status);
        if (end) {
            update.setSql("end_time=clock_timestamp()");
        } else if (status == 2) {
            update.setSql("start_time=clock_timestamp(),end_time=NULL");
        }
        tasks.update(null, update);
    }

    /**
     * 从持久运行记录构造状态、停止标记及幂等重放信息。
     *
     * @param run 运行
     * @param replay 重放
     * @return 运行回执
     */
    private static RunReceipt receipt(DataAccessTaskDO run, boolean replay) {
        return new RunReceipt(run.getId(), replay, run.getRunStatus(), Boolean.TRUE.equals(run.getStopRequested()));
    }

    /**
     * 计算输入内容摘要。
     *
     * @param values 字段值集合
     * @return 计算后的摘要或签名字符串
     */
    private static String hash(Object... values) {
        return DigestUtil.sha256Hex(JSONUtil.toJsonStr(values));
    }

    /**
     * 为具有停止意图的运行记录停止确认耗时指标。
     *
     * @param before 变更前状态
     */
    private void observeStop(DataAccessTaskDO before) {
        if (!Boolean.TRUE.equals(before.getStopRequested()) || !List.of(1, 2, 5).contains(before.getRunStatus())) {
            return;
        }
        metrics.stopConfirmed(cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ACCESS,
                () -> runs.selectStopTiming(before.getId(), before.getRowVersion() + 1));
    }

    /**
     * 读取当前登录用户编号，未登录时拒绝业务操作。
     *
     * @return 操作人数值
     */
    private static Long actor() {
        Long id = SecurityFrameworkUtils.getLoginUserId();
        if (id == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        return id;
    }

    /**
     * 校验请求标识非空且不超过允许长度。
     *
     * @param id 记录编号
     */
    private static void validateRequest(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw exception(DATA_ACCESS_PREVIEW_PARAM_MISSING, "requestId");
        }
    }
}
