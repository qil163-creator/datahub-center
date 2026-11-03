package cn.iocoder.yudao.module.governance.service.metadata.core;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 平台持久采集队列；数据库事务内只做状态变更，不执行外部连接调用。 */
@Component
public class MetaCollectQueue {

    @Resource
    private MetaCollectTaskMapper tasks;

    @Resource
    private MetaCollectInstanceMapper instances;

    @Resource
    private TransactionTemplate transactions;

    /**
     * 判断指定采集任务是否已有未结束实例。
     */
    public boolean active(Long taskId) {
        return instances.selectCount(new LambdaQueryWrapperX<MetaCollectInstanceDO>()
                .eq(MetaCollectInstanceDO::getTaskId, taskId).in(MetaCollectInstanceDO::getStatus, 0, 1)) > 0;
    }

    /**
     * 保存待执行的元数据采集队列请求。
     */
    public MetaCollectInstanceDO enqueue(Long taskId, int trigger, Long actorId) {
        return enqueue(taskId, trigger, actorId, null, null);
    }

    /**
     * 保存待执行的元数据采集队列请求。
     */
    public MetaCollectInstanceDO enqueue(Long taskId, int trigger, Long actorId, LocalDateTime expected,
            LocalDateTime next) {
        return transactions.execute(tx -> {
            instances.lockQueue();
            MetaCollectTaskDO task = tasks.lockTask(taskId);
            if (task == null) {
                throw exception(META_COLLECT_TASK_NOT_EXISTS);
            }
            if (!Objects.equals(task.getEnabled(), 1)) {
                throw exception(META_COLLECT_TASK_DISABLED_CANNOT_RUN);
            }
            if (expected != null) {
                if (!Objects.equals(task.getScheduleEnabled(), 1) || !Objects.equals(task.getNextRunTime(), expected)) {
                    return null;
                }
                tasks.update(null, Wrappers.lambdaUpdate(MetaCollectTaskDO.class).eq(MetaCollectTaskDO::getId, taskId)
                        .set(MetaCollectTaskDO::getNextRunTime, next));
            }
            boolean busy = active(taskId);
            boolean discard = busy && "SERIAL_DISCARD".equals(task.getExecuteStrategy());
            if ("SERIAL_PRIORITY".equals(task.getExecuteStrategy())) {
                instances.update(MetaCollectInstanceDO.builder().status(5).stage("DONE")
                                .endTime(LocalDateTime.now()).remark("被较新的等待请求替代").build(),
                        new LambdaQueryWrapperX<MetaCollectInstanceDO>().eq(MetaCollectInstanceDO::getTaskId, taskId)
                                .eq(MetaCollectInstanceDO::getStatus, 0));
            }
            if (!discard && instances.selectCount(new LambdaQueryWrapperX<MetaCollectInstanceDO>()
                    .eq(MetaCollectInstanceDO::getStatus, 0)) >= 100) {
                throw exception(META_COLLECT_QUEUE_FULL);
            }
            MetaCollectInstanceDO instance = MetaCollectInstanceDO.builder().taskId(taskId).taskName(task.getName())
                    .datasourceId(task.getDatasourceId()).datasourceName(task.getDatasourceName())
                    .sourceSystemId(task.getSourceSystemId()).sourceSystemName(task.getSourceSystemName())
                    .collectType(task.getCollectType()).ownerUserId(task.getOwnerUserId())
                    .ownerName(task.getOwnerName())
                    .ownerPhone(task.getOwnerPhone()).description(task.getDescription()).triggerType(trigger)
                    .executionUserId(actorId != null ? actorId : creator(task.getCreator()))
                    .status(discard ? 5 : 0).stage(discard ? "DONE" : "QUEUED")
                    .endTime(discard ? LocalDateTime.now() : null)
                    .remark(discard ? "已有活动实例，本次按策略丢弃" : null)
                    .attemptCount(0).fileCount(0).dbCount(0).tableCount(0).columnCount(0)
                    .tableAdd(0).tableUpdate(0).tableDelete(0).columnAdd(0).columnUpdate(0).columnDelete(0).build();
            if (instance.getExecutionUserId() != null) {
                instance.setCreator(instance.getExecutionUserId().toString());
            }
            instances.insert(instance);
            tasks.updateById(MetaCollectTaskDO.builder().id(taskId).lastInstanceId(instance.getId()).build());
            return instance;
        });
    }

    /**
     * 领取待处理的元数据采集队列任务或记录。
     */
    public MetaCollectInstanceDO claim() {
        return transactions.execute(tx -> {
            instances.lockQueue();
            if (instances.selectCount(new LambdaQueryWrapperX<MetaCollectInstanceDO>()
                    .eq(MetaCollectInstanceDO::getStatus, 1)) >= 2) {
                return null;
            }
            for (var queued : instances.selectList(new LambdaQueryWrapperX<MetaCollectInstanceDO>()
                    .eq(MetaCollectInstanceDO::getStatus, 0).orderByAsc(MetaCollectInstanceDO::getId)
                    .last("LIMIT 100"))) {
                MetaCollectTaskDO task = tasks.lockTask(queued.getTaskId());
                if (task == null || !Objects.equals(task.getEnabled(), 1)) {
                    instances.updateById(MetaCollectInstanceDO.builder().id(queued.getId()).status(5)
                            .stage("DONE").endTime(LocalDateTime.now()).remark("任务已下线或删除").build());
                    continue;
                }
                if (!"PARALLEL".equals(task.getExecuteStrategy()) && instances.selectRunningByTaskId(
                        task.getId()) != null) {
                    continue;
                }
                queued.setStatus(1);
                queued.setStage("CONNECT");
                queued.setStartTime(LocalDateTime.now());
                queued.setExecutionToken(UUID.randomUUID().toString());
                queued.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
                queued.setAttemptCount(Objects.requireNonNullElse(queued.getAttemptCount(), 0) + 1);
                instances.updateById(queued);
                return queued;
            }
            return null;
        });
    }

    /**
     * 恢复已过期。
     */
    public void recoverExpired() {
        transactions.executeWithoutResult(tx -> {
            instances.lockQueue();
            for (var instance : instances.selectList(new LambdaQueryWrapperX<MetaCollectInstanceDO>()
                    .eq(MetaCollectInstanceDO::getStatus, 1)
                    .le(MetaCollectInstanceDO::getLeaseUntil, LocalDateTime.now()).last("LIMIT 100"))) {
                boolean retry = Objects.requireNonNullElse(instance.getAttemptCount(), 0) < 3;
                instances.update(null, Wrappers.lambdaUpdate(MetaCollectInstanceDO.class)
                        .eq(MetaCollectInstanceDO::getId, instance.getId()).eq(MetaCollectInstanceDO::getStatus, 1)
                        .le(MetaCollectInstanceDO::getLeaseUntil, LocalDateTime.now())
                        .set(MetaCollectInstanceDO::getStatus, retry ? 0 : 3)
                        .set(MetaCollectInstanceDO::getStage, retry ? "QUEUED" : "DONE")
                        .set(MetaCollectInstanceDO::getExecutionToken, null)
                        .set(MetaCollectInstanceDO::getLeaseUntil, null)
                        .set(MetaCollectInstanceDO::getEndTime, retry ? null : LocalDateTime.now())
                        .set(MetaCollectInstanceDO::getErrorType, "WORKER_LOST")
                        .set(MetaCollectInstanceDO::getRemark,
                                retry ? "工作进程租约过期，重新扫描" : "连续三次工作进程丢失，请人工检查后重新触发"));
            }
        });
    }

    /**
     * 解析采集任务的创建人编号。
     */
    private Long creator(String value) {
        try {
            return Long.valueOf(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
