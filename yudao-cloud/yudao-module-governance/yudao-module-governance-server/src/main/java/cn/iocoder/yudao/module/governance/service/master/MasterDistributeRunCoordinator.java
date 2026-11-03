package cn.iocoder.yudao.module.governance.service.master;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.governance.enums.master.MasterRunTypeEnum;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Function;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 主数据派发领域状态机。只持有短本地事务；外部搬数仍委托DB Switch。 */
@Service
public class MasterDistributeRunCoordinator {

    @Resource
    private MasterDistributeMapper tasks;

    @Resource
    private MasterDistributeLogMapper runs;

    @Resource
    private MasterModelMapper models;

    @Resource
    private MasterDistributeAlertService alertService;

    @Resource
    private MasterDistributeCheckpointMapper checkpoints;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 在平台事务边界内完成主数据分发运行的本地读写。
     */
    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return tx;
    }

    /**
     * 构造运行身份与有效租约条件，限制更新为当前执行者持有的运行。
     */
    private void owned(MasterDistributeLogDO run) {
        if (run == null) {
            throw new IllegalStateException("运行不存在");
        }
    }

    /**
     * 读取并锁定主数据分发运行记录，供事务内更新使用。
     */
    private MasterDistributeDO lock(Long id) {
        var task = tasks.lockOwned(id);
        if (task == null) {
            throw exception(MASTER_DISTRIBUTE_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 判断主数据分发运行状态是否阻止后续运行。
     */
    public int blockingState(Long task) {
        return runs.blockingState(task);
    }

    /** 回调仅用于本领域配置/删除，不允许RPC或外部数据库访问。 */
    public <T> T withIdleTask(Long id, Function<MasterDistributeDO, T> action) {
        return transaction().execute(status -> {
            var task = lock(id);
            if (blockingState(id) != 0) {
                throw exception(MASTER_DISTRIBUTE_RUNNING);
            }
            return action.apply(task);
        });
    }

    /**
     * 校验模型已发布并创建待执行的分发运行记录。
     */
    private MasterDistributeLogDO insert(MasterDistributeDO task, MasterRunTypeEnum type, LocalDateTime due) {
        var model = models.selectById(task.getMasterModelId());
        if (model == null || !Objects.equals(model.getStatus(), MasterModelDO.STATUS_RELEASED)) {
            throw exception(MASTER_DISTRIBUTE_MODEL_NOT_RELEASED);
        }
        var run = MasterDistributeLogDO.builder().distributeId(task.getId()).masterModelId(task.getMasterModelId())
                .runType(type.getType()).runStatus(1).startTime(runs.databaseNow()).runProtocol(1)
                .attempts(0).availableAt(runs.databaseNow()).scheduleGeneration(task.getScheduleGeneration())
                .scheduledFor(due).dataCount(0L).byteCount("0").totalCount(0L).successCount(0L).failureCount(0L)
                .unknownCount(0L).build();
        var api = apiConfig(task);
        if (api != null) {
            run.setPushScope(api.getPushScope());
            run.setPushMode(api.getPushMode());
        }

        runs.insert(run);
        return run;
    }

    /**
     * 受理主数据分发运行请求并保存可恢复的执行记录。
     */
    public MasterDistributeLogDO accept(Long id, MasterRunTypeEnum type) {
        Objects.requireNonNull(type);
        return transaction().execute(status -> {
            var task = lock(id);
            if (blockingState(id) != 0 || (type == MasterRunTypeEnum.AUTO && !Objects.equals(task.getStatus(), 1))) {
                return null;
            }
            return insert(task, type, null);
        });
    }

    /**
     * 受理API。
     */
    public MasterDistributeLogDO acceptApi(Long id) {
        return transaction().execute(status -> {
            var task = lock(id);
            if (!Objects.equals(task.getDistributeType(), 2) || !Objects.equals(task.getStatus(), 1)) {
                throw exception(MASTER_DISTRIBUTE_NOT_RELEASED);
            }
            if (blockingState(id) != 0) {
                return null;
            }
            return insert(task, MasterRunTypeEnum.HAND, null);
        });
    }

    /** API 启停只变更配置状态；不受理发送，不影响已经开始的运行。 */
    public void updateApiStatus(Long id, int enabled, Long actor) {
        if (enabled != 0 && enabled != 1) {
            throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID, "启用状态无效");
        }
        withIdleTask(id, task -> {
            if (!Objects.equals(task.getDistributeType(), 2)) {
                throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID, "仅 REST 推送支持此启停入口");
            }
            if (Objects.equals(task.getStatus(), enabled)) {
                return null;
            }
            var model = models.selectById(task.getMasterModelId());
            if (enabled == 1 && (model == null || !Objects.equals(model.getStatus(), 1)
                    || model.getPublishState() != null && !MasterModelDO.PUBLISH_READY.equals(
                    model.getPublishState()))) {
                throw exception(MASTER_DISTRIBUTE_MODEL_NOT_RELEASED);
            }
            tasks.update(null, Wrappers.lambdaUpdate(MasterDistributeDO.class).eq(MasterDistributeDO::getId, id)
                    .set(MasterDistributeDO::getStatus, enabled).set(MasterDistributeDO::getNextRunTime,
                            enabled == 1 && Objects.equals(task.getTaskType(), 2) ? CronExpression.parse(task.getCron())
                                    .next(runs.databaseNow()) : null)
                    .set(MasterDistributeDO::getReleaseUserId, actor)
                    .set(MasterDistributeDO::getReleaseTime, runs.databaseNow())
                    .setSql("schedule_generation=schedule_generation+1"));
            return null;
        });
    }

    /**
     * 仅为发送前失败且满足发布条件的任务创建人工重试运行。
     */
    public MasterDistributeLogDO retry(Long id) {
        return withIdleTask(id, task -> {
            var latest = runs.latest(id);
            if (latest == null || !Integer.valueOf(4).equals(latest.getRunStatus())) {
                throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID, "仅发送前失败运行可以重试；未知结果必须先核查");
            }
            if (Objects.equals(task.getDistributeType(), 2) && !Objects.equals(task.getStatus(), 1)) {
                throw exception(MASTER_DISTRIBUTE_NOT_RELEASED);
            }
            return insert(task, MasterRunTypeEnum.HAND, null);
        });
    }

    /** 每批回执与检查点在短事务内共同提交，过期或停止工作者不能推进增量。 */
    public boolean progress(MasterDistributeLogDO run,
            java.util.List<MasterDistributeCheckpointMapper.Checkpoint> receipts) {
        return Boolean.TRUE.equals(transaction().execute(status -> {
            lock(run.getDistributeId());
            if (runs.progress(run) != 1) {
                return false;
            }
            if (!receipts.isEmpty()) {
                checkpoints.confirm(run.getDistributeId(), run.getId(), receipts,
                        run.getCreator() == null ? "0" : run.getCreator());
            }
            return true;
        }));
    }

    /**
     * 读取 REST 分发配置并补齐执行默认值。
     */
    private cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO.ApiConfig apiConfig(
            MasterDistributeDO task) {
        if (task == null || !Objects.equals(task.getDistributeType(), 2)) {
            return null;
        }
        var config = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(task.getDistributeJson(),
                cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO.class);
        return config == null || config.getApi() == null ? null : MasterDistributeApiPolicy.defaults(config.getApi());
    }

    /**
     * 受理达到计划时间的主数据分发运行运行。
     */
    public MasterDistributeLogDO scheduled(MasterDistributeDO observed) {
        return transaction().execute(status -> {
            var task = lock(observed.getId());
            LocalDateTime due = observed.getNextRunTime();
            if (due == null || !Objects.equals(task.getStatus(), 1) || !Objects.equals(task.getTaskType(), 2)
                    || !Objects.equals(task.getScheduleGeneration(), observed.getScheduleGeneration())
                    || !Objects.equals(task.getNextRunTime(), due) || due.isAfter(runs.databaseNow()) || blockingState(
                    task.getId()) != 0) {
                return null;
            }
            if (task.getCron() == null || !CronExpression.isValidExpression(task.getCron())) {
                throw exception(MASTER_DISTRIBUTE_CRON_INVALID);
            }
            var run = insert(task, MasterRunTypeEnum.AUTO, due);
            tasks.update(null,
                    Wrappers.lambdaUpdate(MasterDistributeDO.class).eq(MasterDistributeDO::getId, task.getId())
                            .set(MasterDistributeDO::getNextRunTime, CronExpression.parse(task.getCron()).next(due)));
            return run;
        });
    }

    /**
     * 校验模型与周期配置后发布分发任务，推进调度代次及下次运行时间。
     */
    public MasterDistributeLogDO release(Long id, Long actor) {
        return withIdleTask(id, task -> {
            if (Objects.equals(task.getStatus(), 1)) {
                throw exception(MASTER_DISTRIBUTE_RELEASED_CANNOT_MODIFY);
            }
            var model = models.selectById(task.getMasterModelId());
            if (model == null || !Objects.equals(model.getStatus(), 1)) {
                throw exception(MASTER_DISTRIBUTE_MODEL_NOT_RELEASED);
            }
            boolean periodic = Objects.equals(task.getTaskType(), 2);
            if (periodic && (task.getCron() == null || !CronExpression.isValidExpression(task.getCron()))) {
                throw exception(MASTER_DISTRIBUTE_CRON_INVALID);
            }
            LocalDateTime now = runs.databaseNow();
            task.setScheduleGeneration(task.getScheduleGeneration() + 1);
            tasks.update(null, Wrappers.lambdaUpdate(MasterDistributeDO.class).eq(MasterDistributeDO::getId, id)
                    .set(MasterDistributeDO::getStatus, 1).set(MasterDistributeDO::getReleaseUserId, actor)
                    .set(MasterDistributeDO::getReleaseTime, now)
                    .set(MasterDistributeDO::getScheduleGeneration, task.getScheduleGeneration())
                    .set(MasterDistributeDO::getNextRunTime,
                            periodic ? CronExpression.parse(task.getCron()).next(now) : null));
            return periodic ? null : insert(task, MasterRunTypeEnum.AUTO, null);
        });
    }

    /**
     * 取消主数据分发运行当前申请或运行。
     */
    public void cancel(Long id) {
        transaction().executeWithoutResult(status -> {
            var task = lock(id);
            if (!Objects.equals(task.getStatus(), 1)) {
                throw exception(MASTER_DISTRIBUTE_NOT_RELEASED);
            }
            tasks.update(null, Wrappers.lambdaUpdate(MasterDistributeDO.class).eq(MasterDistributeDO::getId, id)
                    .set(MasterDistributeDO::getStatus, 0).set(MasterDistributeDO::getNextRunTime, null)
                    // 取消停用当前排期，保留最近发布人和时间，避免丢失发布记录。
                    .set(MasterDistributeDO::getScheduleGeneration, task.getScheduleGeneration() + 1));
            runs.cancel(id);
            alertService.enqueue(task, null, "UNKNOWN");
        });
    }

    /** 只停止不可变运行编号；不取消发布、不消耗或改写排期。回执与未知告警原子提交。 */
    MasterDistributeLogDO stop(
            cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeStopReqVO request,
            Long actor) {

        if (actor == null || actor <= 0 || request == null || request.getRecordId() == null
                || request.getRecordId() <= 0 || request.getReason() == null
                || request.getReason().isBlank() || request.getReason().length() > 500) {
            throw exception(MASTER_DISTRIBUTE_STOP_CONFLICT);
        }
        String reason = request.getReason().strip();
        var observed = runs.selectById(request.getRecordId());
        if (observed == null) {
            throw exception(MASTER_DISTRIBUTE_LOG_NOT_EXISTS);
        }
        return transaction().execute(status -> {
            var task = lock(observed.getDistributeId());
            var run = runs.selectById(observed.getId());
            if (run == null
                    || !Objects.equals(run.getRunProtocol(), 1)) {
                throw exception(MASTER_DISTRIBUTE_STOP_CONFLICT);
            }
            // 每次运行最多一份停止凭证；重试不能更换操作者/原因，也绝不能停止后续运行。
            if (run.getStopRequestedAt() != null) {
                if (!Objects.equals(run.getStopRequestedBy(), actor) || !Objects.equals(run.getStopReason(), reason)) {
                    throw exception(MASTER_DISTRIBUTE_STOP_CONFLICT);
                }
                return run;
            }
            if (runs.stopOwned(run.getId(), task.getId(), actor, reason) != 1) {
                throw exception(MASTER_DISTRIBUTE_STOP_CONFLICT);
            }
            var receipt = runs.selectById(run.getId());
            if (Objects.equals(receipt.getRunStatus(), 5)) {
                alertService.enqueue(task, run.getId(), "UNKNOWN");
            }
            // 保留租约审计；旧工作者心跳/提交 CAS 均失败，核查仍需等租约结束并核实远端。
            return receipt;
        });
    }

    /**
     * 认领满足调度条件的分发运行，并记录本次执行租约。
     */
    public MasterDistributeLogDO claim(MasterDistributeLogDO candidate) {
        owned(candidate);
        return transaction().execute(status -> {
            // 已删除任务的在途记录仍须转未知；不假设任务永远存在。
            var task = tasks.lockOwned(candidate.getDistributeId());
            if (runs.recoverUnknown(candidate.getId()) == 1) {
                alertService.enqueue(alertTask(task, candidate.getDistributeId()), candidate.getId(), "UNKNOWN");
                return null;
            }
            if (task == null) {
                runs.cancel(candidate.getDistributeId());
                alertService.enqueue(alertTask(null, candidate.getDistributeId()), null, "UNKNOWN");
                return null;
            }
            var api = apiConfig(task);
            int maximum = api == null ? 5 : Boolean.TRUE.equals(api.getRetryEnabled()) ? api.getRetryCount() + 1 : 1;
            if (runs.exhaust(candidate.getId(), maximum, exhaustionReason(api)) == 1) {
                exhausted(task, candidate.getId(), api);
                return null;
            }
            if (runs.claim(candidate.getId(), IdUtil.fastSimpleUUID(), maximum) == 0) {
                return null;
            }
            return runs.selectById(candidate.getId());
        });
    }

    /**
     * 核验运行所有权并将已认领分发运行推进到执行阶段。
     */
    public boolean begin(MasterDistributeLogDO run) {
        owned(run);
        return Boolean.TRUE.equals(transaction().execute(s -> runs.begin(run) == 1));
    }

    /** 只能由已鉴权核查服务调用；本事务不访问远端，证据由授权操作者核验。 */
    MasterDistributeLogDO resolve(
            cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeResolveReqVO request,
            Long actor) {

        if (actor == null || actor <= 0 || !Boolean.TRUE.equals(request.getConfirmedStopped())
                || !Boolean.TRUE.equals(request.getOutputReviewed())
                || !("NO_EFFECT".equals(request.getOutcome()) || "COMPENSATED".equals(request.getOutcome()))
                || request.getReason() == null || request.getReason().isBlank()
                || request.getStoppedEvidenceRef() == null || request.getStoppedEvidenceRef().isBlank()
                || request.getOutputEvidenceRef() == null || request.getOutputEvidenceRef().isBlank()) {
            throw exception(MASTER_DISTRIBUTE_REVIEW_REQUIRED);
        }
        var observed = runs.selectById(request.getRecordId());
        if (observed == null) {
            throw exception(MASTER_DISTRIBUTE_LOG_NOT_EXISTS);
        }
        try {
            return transaction().execute(status -> {
                // 和受理、发布、取消共用任务行锁，防止解除阻塞与新运行同时通过。
                var task = lock(observed.getDistributeId());
                var replay = runs.selectResolution(request.getRequestId());
                if (replay != null) {
                    if (!Objects.equals(replay.getId(), request.getRecordId()) || !Objects.equals(
                            replay.getResolvedBy(), actor)
                            || !Objects.equals(replay.getResolutionOutcome(), request.getOutcome())
                            || !Objects.equals(replay.getResolutionReason(), request.getReason())
                            || !Objects.equals(replay.getStoppedEvidenceRef(), request.getStoppedEvidenceRef())
                            || !Objects.equals(replay.getOutputEvidenceRef(), request.getOutputEvidenceRef())) {
                        throw exception(MASTER_DISTRIBUTE_REVIEW_CONFLICT);
                    }
                    return replay;
                }
                var run = runs.selectById(observed.getId());
                if (run == null || !Objects.equals(run.getRunStatus(), 5) || run.getResolutionRequestId() != null) {
                    throw exception(MASTER_DISTRIBUTE_REVIEW_CONFLICT);
                }
                // 处置前补齐历史事件，避免扫描与人工核查竞争导致漏告警。
                alertService.enqueue(task, run.getId(), "UNKNOWN");
                if (runs.countActive(task.getId()) > 0 || runs.resolveOwned(actor, request) != 1) {
                    throw exception(MASTER_DISTRIBUTE_REVIEW_REQUIRED);
                }
                // 保留原始错误、计数和租约审计；核查绝不是成功或自动重发指令。
                int changed = tasks.update(null, Wrappers.lambdaUpdate(MasterDistributeDO.class)
                        .eq(MasterDistributeDO::getId, task.getId())
                        .set(MasterDistributeDO::getStatus, 0).set(MasterDistributeDO::getNextRunTime, null)
                        // 核查停用排期，不清除最近发布元数据；再次发布时由 release 覆盖。
                        .set(MasterDistributeDO::getUpdater, actor.toString())
                        .setSql("schedule_generation=schedule_generation+1"));
                if (changed != 1) {
                    throw exception(MASTER_DISTRIBUTE_REVIEW_CONFLICT);
                }
                // 核查覆盖本次运行整体；人工确认无效或已补偿后，其已确认前缀也不能再作为增量凭据。
                checkpoints.clearResolvedRun(task.getId(), run.getId());
                return runs.selectById(run.getId());
            });
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            throw exception(MASTER_DISTRIBUTE_REVIEW_CONFLICT);
        }
    }

    /**
     * 核验运行所有权并续约分发租约，返回续约是否成功。
     */
    public boolean heartbeat(MasterDistributeLogDO run) {
        owned(run);
        return Boolean.TRUE.equals(transaction().execute(s -> runs.heartbeat(run) == 1));
    }

    /**
     * 构造主数据分发运行请求被拒绝时的业务异常。
     */
    public void rejected(MasterDistributeLogDO run) {
        owned(run);
        transaction().executeWithoutResult(s -> runs.rejected(run));
    }

    /**
     * 保存主数据分发运行最终处理状态。
     */
    public boolean finish(MasterDistributeLogDO run, boolean sending, int result, String error) {
        owned(run);
        if ((sending && result != 3 && result != 5) || (!sending && result != 4)) {
            throw new IllegalArgumentException("非法派发终态");
        }
        return Boolean.TRUE.equals(transaction().execute(s -> {
            var task = tasks.lockOwned(run.getDistributeId());
            var api = apiConfig(task);
            if (!sending && result == 4 && api != null && Objects.equals(task.getStatus(), 1)
                    && Boolean.TRUE.equals(
                    api.getRetryEnabled()) && run.getAttempts() != null && run.getAttempts() <= api.getRetryCount()) {
                return runs.retryBeforeSend(run, api.getRetryIntervalMinutes(), error) == 1;
            }
            String terminalError = !sending && api != null && Objects.equals(task.getStatus(), 1) ? exhaustionReason(
                    api) : error;
            if (runs.finish(run, sending ? 2 : 1, result, terminalError) != 1) {
                return false;
            }
            if (result == 5) {
                alertService.enqueue(alertTask(task, run.getDistributeId()), run.getId(), "UNKNOWN");
            }
            if (!sending && api != null && Objects.equals(task.getStatus(), 1)) {
                exhausted(task, run.getId(), api);
            }
            return true;
        }));
    }

    /**
     * 判断主数据分发运行是否已耗尽自动重试次数。
     */
    private void exhausted(MasterDistributeDO task, Long runId,
            cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO.ApiConfig api) {
        if (api != null && "STOP".equals(api.getExhaustedAction())) {
            tasks.update(null, Wrappers.lambdaUpdate(MasterDistributeDO.class)
                    .eq(MasterDistributeDO::getId, task.getId()).set(MasterDistributeDO::getStatus, 0)
                    .set(MasterDistributeDO::getNextRunTime, null).setSql("schedule_generation=schedule_generation+1"));
        } else {
            alertService.enqueue(task, runId, "DISPATCH_EXHAUSTED");
        }
    }

    /**
     * 生成主数据分发运行自动重试耗尽的原因说明。
     */
    private String exhaustionReason(
            cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO.ApiConfig api) {
        if (api == null) {
            return "发送前领取次数耗尽，未执行发送";
        }
        return "STOP".equals(
                api.getExhaustedAction()) ? "发送前重试耗尽，任务已停止，未执行发送" : "发送前重试耗尽，转人工处理，未执行发送";
    }

    /** 全局发现；任务锁串行化核查/取消，补账绝不改变运行结果。 */
    public void reconcileAlert(MasterDistributeLogDO observed) {
        owned(observed);
        transaction().executeWithoutResult(s -> {
            var task = tasks.lockOwned(observed.getDistributeId());
            var run = runs.selectById(observed.getId());
            if (run == null) {
                return;
            }
            String kind = Objects.equals(run.getRunStatus(), 5) ? "UNKNOWN" : "DISPATCH_EXHAUSTED";
            alertService.enqueue(alertTask(task, run.getDistributeId()), run.getId(), kind);
        });
    }

    /**
     * 为告警读取任务上下文，任务缺失时仅保留编号供人工接管。
     */
    private MasterDistributeDO alertTask(MasterDistributeDO task, Long id) {
        if (task != null) {
            return task;
        }
        // 孤立日志不能猜测接收者；保留死信由值守接管。
        var missing = new MasterDistributeDO();
        missing.setId(id);
        return missing;
    }
}
