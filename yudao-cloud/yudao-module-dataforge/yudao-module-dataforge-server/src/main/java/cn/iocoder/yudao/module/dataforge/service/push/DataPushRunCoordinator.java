package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.*;
import cn.iocoder.yudao.module.dataforge.enums.push.*;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Function;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 推送领域持久防重。锁顺序始终为任务 -> 运行；锁内禁止外部发送/RPC。 */
@Service
public class DataPushRunCoordinator {

    @Resource
    private DataPushTaskMapper tasks;

    @Resource
    private DataPushTaskLogMapper runs;

    @Resource
    private DataPushIncreaseLogMapper watermarks;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private DataPushProperties properties;

    @Resource
    private DataPushAlertService alerts;

    /**
     * 在当前事务边界内完成数据推送运行处理。
     *
     * @return 事务模板
     */
    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return tx;
    }


    /**
     * 锁定数据推送运行当前记录。
     *
     * @param taskId 任务编号
     * @return 数据推送任务
     */
    private DataPushTaskDO lock(Long taskId) {
        DataPushTaskDO task = tasks.lockOwned(taskId);
        if (task == null) {
            throw exception(DATA_PUSH_TASK_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 判断是否满足阻塞条件。
     *
     * @param taskId 任务编号
     * @return 判断结果
     */
    public boolean isBlocked(Long taskId) {
        return runs.countUnresolved(taskId) > 0;
    }

    /**
     * 获取数据推送运行的阻塞状态。
     *
     * @param taskId 任务编号
     * @return 阻塞状态数值
     */
    public int blockingState(Long taskId) {
        return runs.blockingState(taskId);
    }

    /** 只允许本模块的短本地写事务回调；校验/RPC 在调用前完成。 */
    public <T> T withIdleTask(Long taskId, Function<DataPushTaskDO, T> action) {

        return transaction().execute(status -> {
            DataPushTaskDO task = lock(taskId);
            if (isBlocked(taskId)) {
                throw exception(DATA_PUSH_RUNNING);
            }
            return action.apply(task);
        });
    }

    /**
     * 认领数据推送运行。
     *
     * @param taskId 任务编号
     * @param runType 运行类型
     * @return 数据推送任务日志
     */
    public DataPushTaskLogDO claim(Long taskId, PushRunTypeEnum runType) {
        Objects.requireNonNull(runType);

        return transaction().execute(status -> {
            DataPushTaskDO task = lock(taskId);
            if (isBlocked(taskId) || (runType == PushRunTypeEnum.AUTO
                    && !Objects.equals(task.getStatus(), DataPushTaskDO.STATUS_RELEASED))) {
                return null;
            }
            return insert(task, runType, null);
        });
    }

    /** 执行意图与原始到期槽位一起提交；逐槽补跑，不从扫描当前时间跳过积压。 */
    public DataPushTaskLogDO enqueueScheduled(Long taskId, Long generation, LocalDateTime due) {

        if (generation == null || due == null) {
            return null;
        }
        return transaction().execute(status -> {
            DataPushTaskDO task = lock(taskId);
            if (!Objects.equals(task.getStatus(), DataPushTaskDO.STATUS_RELEASED)
                    || !PushTaskTypeEnum.isPeriodic(task.getTaskType())
                    || !Objects.equals(task.getScheduleGeneration(), generation)
                    || !Objects.equals(task.getNextRunTime(), due)
                    || due.isAfter(runs.databaseNow()) || isBlocked(taskId)) {
                return null;
            }
            if (task.getCron() == null || !CronExpression.isValidExpression(task.getCron())) {
                throw exception(DATA_PUSH_CRON_INVALID, task.getCron());
            }
            LocalDateTime next = CronExpression.parse(task.getCron()).next(due);
            DataPushTaskLogDO run = insert(task, PushRunTypeEnum.AUTO, due);
            int changed = tasks.update(null, Wrappers.lambdaUpdate(DataPushTaskDO.class)
                    .eq(DataPushTaskDO::getId, taskId)
                    .eq(DataPushTaskDO::getScheduleGeneration, generation).eq(DataPushTaskDO::getNextRunTime, due)
                    .set(DataPushTaskDO::getNextRunTime, next));
            if (changed != 1) {
                throw new IllegalStateException("推送排期受理冲突");
            }
            return run;
        });
    }

    /** 一次性发布也在同一事务写入执行意图；外部配置验证在调用前完成。 */
    public DataPushTaskLogDO publish(DataPushTaskDO observed, Long actor) {

        return withIdleTask(observed.getId(), current -> {
            if (!Objects.equals(current.getStatus(), DataPushTaskDO.STATUS_UNRELEASED)) {
                throw exception(DATA_PUSH_RELEASED_CANNOT_MODIFY);
            }
            if (!Objects.equals(current.getUpdateTime(), observed.getUpdateTime())) {
                throw exception(DATA_PUSH_RUNNING);
            }
            LocalDateTime now = runs.databaseNow();
            boolean periodic = PushTaskTypeEnum.isPeriodic(current.getTaskType());
            if (periodic && (current.getCron() == null || !CronExpression.isValidExpression(current.getCron()))) {
                throw exception(DATA_PUSH_CRON_INVALID, current.getCron());
            }
            LocalDateTime next = periodic ? CronExpression.parse(current.getCron()).next(now) : null;
            if (periodic && next == null) {
                throw exception(DATA_PUSH_CRON_INVALID, current.getCron());
            }
            int changed = tasks.update(null, Wrappers.lambdaUpdate(DataPushTaskDO.class)
                    .eq(DataPushTaskDO::getId, current.getId())
                    .set(DataPushTaskDO::getStatus, DataPushTaskDO.STATUS_RELEASED)
                    .set(DataPushTaskDO::getReleaseTime, now).set(DataPushTaskDO::getReleaseUserId, actor)
                    .set(DataPushTaskDO::getNextRunTime, next).setSql("schedule_generation=schedule_generation+1"));
            if (changed != 1) {
                throw new IllegalStateException("推送发布受理冲突");
            }
            return periodic ? null : insert(current, PushRunTypeEnum.AUTO, null);
        });
    }

    /**
     * 新增数据推送运行记录。
     *
     * @param task 任务记录
     * @param type 类型标识
     * @param due 到期
     * @return 数据推送任务日志
     */
    private DataPushTaskLogDO insert(DataPushTaskDO task, PushRunTypeEnum type, LocalDateTime due) {
        var now = runs.databaseNow();
        var row = DataPushTaskLogDO.builder().pushTaskId(task.getId()).runType(type.getType())
                .runStatus(PushRunStatusEnum.WAITING.getStatus()).startTime(now).heartbeatTime(now)
                .executeNo(IdUtil.fastSimpleUUID()).dataCount(0L).orgId(task.getOrgId()).realTimeLog("")
                .dispatchProtocol(1).dispatchAttempts(0).nextDispatchAt(now).scheduledTime(due).build();

        runs.insert(row);
        return row;
    }

    /** 领取同一持久意图，而非另建一次执行；只允许发送前失联接管。 */
    public DataPushTaskLogDO acquireDispatch(DataPushTaskLogDO candidate) {
        owned(candidate);
        return transaction().execute(status -> {
            DataPushTaskDO task = lock(candidate.getPushTaskId());
            if (runs.exhaustDispatch(candidate, maxAttempts()) == 1) {
                alerts.enqueue(task, candidate.getId(), "DISPATCH_EXHAUSTED");
                return null;
            }
            String token = IdUtil.fastSimpleUUID();
            if (runs.acquireDispatch(candidate, token, leaseSeconds(), maxAttempts()) != 1) {
                return null;
            }
            return runs.selectById(candidate.getId());
        });
    }

    /**
     * 延后分发。
     *
     * @param run 运行
     */
    public void deferDispatch(DataPushTaskLogDO run) {
        owned(run);
        transaction().executeWithoutResult(status -> runs.deferDispatch(run, retrySeconds()));
    }

    /**
     * 续约数据推送运行。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @return 处理是否成功
     */
    public boolean heartbeat(Long id, String token) {
        return runs.touchHeartbeat(id, token, leaseSeconds()) == 1;
    }

    /**
     * 更新进度数据推送运行。
     *
     * @param run 运行
     * @param count 数量
     * @param message 消息
     * @return 处理是否成功
     */
    public boolean progress(DataPushTaskLogDO run, long count, String message) {
        owned(run);
        return runs.progressOwned(run, leaseSeconds(), count, message) == 1;
    }

    /**
     * 获取数据推送运行的租约秒数。
     *
     * @return 租约秒数数值
     */
    private int leaseSeconds() {
        DataPushProperties cfg = properties == null ? new DataPushProperties() : properties;
        long heartbeatSeconds = Math.max(1L, ((long) cfg.getHeartbeatIntervalMs() + 999) / 1000);
        return Math.toIntExact(Math.max(30L, Math.max(cfg.getDispatchLeaseSeconds(), heartbeatSeconds * 3)));
    }

    /**
     * 获取数据推送运行的最大值尝试。
     *
     * @return 最大值尝试数值
     */
    private int maxAttempts() {
        return Math.max(1, properties == null ? 5 : properties.getDispatchMaxAttempts());
    }

    /**
     * 重试秒数。
     *
     * @return 重试秒数数值
     */
    private int retrySeconds() {
        return Math.max(1, properties == null ? 30 : properties.getDispatchRetrySeconds());
    }

    /**
     * 启动发送中。
     *
     * @param run 运行
     * @return 处理是否成功
     */
    public boolean startSending(DataPushTaskLogDO run) {
        owned(run);
        return Boolean.TRUE.equals(transaction().execute(status -> runs.startSending(run) == 1));
    }

    /** 结果持久化失败时原占位仍在；水位只与确证成功的同次运行一起提交。 */
    public boolean finish(DataPushTaskLogDO run, int expectedStatus, DataPushIncreaseLogDO watermark) {
        owned(run);
        int result = run.getRunStatus();
        if ((expectedStatus != 1 && expectedStatus != 2) || (result != 3 && result != 4 && result != 5)
                || (expectedStatus == 2 && result == 4)
                || (watermark != null && (result != 3 || !Objects.equals(run.getPushTaskId(),
                watermark.getPushTaskId())))) {
            throw new IllegalArgumentException("无效的推送终态转换");
        }
        return Boolean.TRUE.equals(transaction().execute(status -> {
            DataPushTaskDO task = lock(run.getPushTaskId());
            if (runs.finishOwned(run, expectedStatus) != 1) {
                return false;
            }
            if (watermark != null) {
                watermarks.upsert(watermark);
            }
            if (result == 5) {
                alerts.enqueue(task, run.getId(), "UNKNOWN");
            }
            return true;
        }));
    }

    /** 发送前取消可以释放；发送后只保留未知，不宣称远端已经停止。 */
    public boolean cancel(Long taskId) {

        return Boolean.TRUE.equals(transaction().execute(status -> {
            DataPushTaskDO task = lock(taskId);
            boolean changed = runs.cancelOwned(taskId) > 0;
            if (changed) {
                alerts.enqueue(task, null, "UNKNOWN");
            }
            return changed;
        }));
    }

    /**
     * 取消发布。
     *
     * @param taskId 任务编号
     */
    public void cancelPublication(Long taskId) {

        transaction().executeWithoutResult(status -> {
            DataPushTaskDO task = lock(taskId);
            if (!Objects.equals(task.getStatus(), DataPushTaskDO.STATUS_RELEASED)) {
                throw exception(DATA_PUSH_NOT_RELEASED);
            }
            tasks.update(null, Wrappers.lambdaUpdate(DataPushTaskDO.class)
                    .eq(DataPushTaskDO::getId, taskId)
                    .set(DataPushTaskDO::getStatus, DataPushTaskDO.STATUS_UNRELEASED)
                    .set(DataPushTaskDO::getReleaseTime, null).set(DataPushTaskDO::getReleaseUserId, null)
                    .set(DataPushTaskDO::getNextRunTime, null).setSql("schedule_generation=schedule_generation+1"));
            runs.cancelOwned(taskId);
            alerts.enqueue(task, null, "UNKNOWN");
        });
    }

    /**
     * 恢复过期。
     *
     * @param run 运行
     * @param minutes 分钟数
     * @return 恢复过期数值
     */
    public int recoverStale(DataPushTaskLogDO run, int minutes) {
        owned(run);
        return transaction().execute(status -> {
            DataPushTaskDO task = tasks.lockOwned(run.getPushTaskId());
            if (task == null) {
                task = new DataPushTaskDO();
                task.setId(run.getPushTaskId());
            }
            int changed = runs.recoverStaleById(run, minutes);
            if (changed == 1) {
                alerts.enqueue(task, run.getId(), "UNKNOWN");
            }
            return changed;
        });
    }

    /** 旧未知日志/历史遗漏通知的有界对账；不更改原执行状态，不猜测远端结果。 */
    public void reconcileAlert(DataPushTaskLogDO run) {
        owned(run);
        transaction().executeWithoutResult(status -> {
            var task = tasks.lockOwned(run.getPushTaskId());
            if (task == null) {
                task = new DataPushTaskDO();
                task.setId(run.getPushTaskId());
            }
            alerts.enqueue(task, run.getId(), "UNKNOWN");
        });
    }

    /** 仅供鉴权后的人工核查服务调用。外部证据由授权核查人验证，本地时钟不能证明远端停止。 */
    DataPushTaskLogDO resolve(cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushResolveReqVO request,
            Long actor) {

        if (actor == null || actor <= 0 || !Boolean.TRUE.equals(request.getConfirmedStopped())
                || !Boolean.TRUE.equals(request.getOutputReviewed())
                || !("NO_EFFECT".equals(request.getOutcome()) || "COMPENSATED".equals(request.getOutcome()))
                || request.getReason() == null || request.getReason().isBlank()
                || request.getStoppedEvidenceRef() == null || request.getStoppedEvidenceRef().isBlank()
                || request.getOutputEvidenceRef() == null || request.getOutputEvidenceRef().isBlank()) {
            throw exception(DATA_PUSH_REVIEW_REQUIRED);
        }
        DataPushTaskLogDO observed = runs.selectById(request.getRecordId());
        if (observed == null) {
            throw exception(DATA_PUSH_LOG_NOT_EXISTS);
        }
        try {
            return transaction().execute(status -> {
                DataPushTaskDO task = lock(observed.getPushTaskId());
                DataPushTaskLogDO replay = runs.selectResolution(request.getRequestId());
                if (replay != null) {
                    if (!Objects.equals(replay.getId(), request.getRecordId()) || !Objects.equals(
                            replay.getResolvedBy(), actor)
                            || !Objects.equals(replay.getResolutionOutcome(), request.getOutcome())
                            || !Objects.equals(replay.getResolutionReason(), request.getReason())
                            || !Objects.equals(replay.getStoppedEvidenceRef(), request.getStoppedEvidenceRef())
                            || !Objects.equals(replay.getOutputEvidenceRef(), request.getOutputEvidenceRef())) {
                        throw exception(DATA_PUSH_REVIEW_CONFLICT);
                    }
                    return replay;
                }
                DataPushTaskLogDO run = runs.selectById(observed.getId());
                if (run == null || !Objects.equals(run.getRunStatus(), 5) || run.getResolutionRequestId() != null) {
                    throw exception(DATA_PUSH_REVIEW_CONFLICT);
                }
                alerts.enqueue(task, run.getId(), "UNKNOWN");
                if (runs.countActive(task.getId()) > 0 || runs.resolveOwned(actor, request) != 1) {
                    throw exception(DATA_PUSH_REVIEW_REQUIRED);
                }
                // 防止解除占位后旧排期自动补跑；人工重新发布/执行是独立动作，旧水位原样保留。
                int changed = tasks.update(null, Wrappers.lambdaUpdate(DataPushTaskDO.class)
                        .eq(DataPushTaskDO::getId, task.getId())
                        .set(DataPushTaskDO::getStatus, DataPushTaskDO.STATUS_UNRELEASED)
                        .set(DataPushTaskDO::getNextRunTime, null).set(DataPushTaskDO::getReleaseTime, null)
                        .set(DataPushTaskDO::getReleaseUserId, null).set(DataPushTaskDO::getUpdater, actor.toString())
                        .setSql("schedule_generation=schedule_generation+1"));
                if (changed != 1) {
                    throw exception(DATA_PUSH_REVIEW_CONFLICT);
                }
                return runs.selectById(run.getId());
            });
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            // 不同任务同时复用请求号时唯一约束回滚本次状态与排期，禁止覆盖已有证据。
            throw exception(DATA_PUSH_REVIEW_CONFLICT);
        }
    }

    /**
     * 构造绑定当前执行权的状态筛选或更新条件。
     *
     * @param run 运行
     */
    private void owned(DataPushTaskLogDO run) {
        if (run == null || run.getId() == null) {
            throw new IllegalArgumentException("推送执行身份无效");
        }
    }
}
