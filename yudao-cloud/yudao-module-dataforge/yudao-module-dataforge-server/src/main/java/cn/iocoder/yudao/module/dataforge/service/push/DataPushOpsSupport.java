package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskLogDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskLogMapper;
import cn.iocoder.yudao.module.dataforge.enums.push.PushRunStatusEnum;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 推送运行时运维：心跳超时回收、失败告警、执行记录清理
 *
 * @author Li
 */
@Slf4j
@Component
public class DataPushOpsSupport {

    public static final String ALERT_TEMPLATE = "dataforge_push_fail";

    public static final String QUEUE_ALERT_TEMPLATE = "dataforge_push_queue_full";

    public static final String STALE_RUNNING_ERROR = "心跳超时，判定运行中断";

    private final ConcurrentMap<Long, LocalDateTime> lastQueueAlert = new ConcurrentHashMap<>();

    @Resource
    private DataPushTaskLogMapper logMapper;

    @Resource
    private DataPushProperties pushProperties;

    @Resource
    private NotifyMessageSendApi notifyMessageSendApi;

    @Resource
    @Lazy
    private DataPushExecutor pushExecutor;

    @Resource
    private DataPushRunCoordinator runCoordinator;

    /**
     * 恢复事件就绪。
     */
    @EventListener(ApplicationReadyEvent.class)

    public void recoverOnReady() {
        this.recoverStaleRunningLogs();
    }

    /**
     * 续约与恢复。
     */
    @Scheduled(fixedDelayString = "${yudao.dataforge.push.heartbeat-interval-ms:30000}")

    public void heartbeatAndRecover() {
        {
            pushExecutor.touchRunningHeartbeats();
            recoverStaleRunningLogs();
        }
        ;
    }

    /**
     * 恢复过期运行中日志。
     */
    public void recoverStaleRunningLogs() {
        int minutes = pushProperties == null ? 10 : pushProperties.getStaleRunningMinutes();
        // 新协议发送后按租约到期保留 UNKNOWN，不受旧心跳回收开关影响；WAITING 交给 XXL-Job 重新领取。
        List<DataPushTaskLogDO> stale = logMapper.selectStaleRunning(minutes);
        if (CollUtil.isEmpty(stale)) {
            return;
        }
        int updated = 0;
        for (DataPushTaskLogDO item : stale) {

            try {
                int changed = runCoordinator.recoverStale(item, minutes);
                if (changed == 1) {
                    pushExecutor.abortLocal(item.getPushTaskId(), item.getId());
                }
                updated += changed;
            } catch (Exception ex) {
                log.warn("[recoverStaleRunningLogs][运行({}) 恢复失败，保留持久状态等待下一轮；类型={}]",
                        item.getId(), ex.getClass().getSimpleName());
            }
        }
        if (updated > 0) {
            log.warn("[recoverStaleRunningLogs][心跳超时回收 {} 条运行中记录]", updated);
        }
    }

    /**
     * 按任务及告警间隔限制发送推送队列满额通知。
     *
     * @param task 任务记录
     */
    public void alertQueueFull(DataPushTaskDO task) {
        if (task == null || task.getId() == null) {
            return;
        }
        int interval = pushProperties == null ? 10 : pushProperties.getQueueAlertIntervalMinutes();
        if (interval <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = lastQueueAlert.get(task.getId());
        if (last != null && last.plusMinutes(interval).isAfter(now)) {
            return;
        }
        lastQueueAlert.put(task.getId(), now);
        Long userId = resolveNotifyUser(task);
        if (userId == null) {
            return;
        }
        Runnable send = () -> {
            try {
                NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
                reqDTO.setUserId(userId);
                reqDTO.setTemplateCode(QUEUE_ALERT_TEMPLATE);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("taskName", StrUtil.blankToDefault(task.getName(), String.valueOf(task.getId())));
                int queueSize = pushProperties == null || pushProperties.getExecutor() == null
                        ? 32 : pushProperties.getExecutor().getQueueSize();
                params.put("queueSize", String.valueOf(queueSize));
                reqDTO.setTemplateParams(params);
                notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
            } catch (Exception ex) {
                log.warn("[alertQueueFull][推送任务({}) 队列满告警发送失败：{}]", task.getId(), ex.getMessage());
            }
        };
        send.run();
    }

    /**
     * 完成后处理已完成。
     *
     * @param task 任务记录
     * @param logDO 日志
     */
    public void afterFinished(DataPushTaskDO task, DataPushTaskLogDO logDO) {
        if (task == null || logDO == null) {
            return;
        }
        tryTrimLogs(task.getId());
        // UNKNOWN 及领取耗尽由持久告警链路处理，不再依赖原节点回调或连续失败开关。
        if (PushRunStatusEnum.FAILED.getStatus().equals(logDO.getRunStatus())
                && !"任务已被手动停止".equals(logDO.getErrorInfo())) {
            tryAlert(task, logDO);
        }
    }

    /**
     * 按任务配置保留最近执行日志，清理失败只记录告警。
     *
     * @param taskId 任务编号
     */
    private void tryTrimLogs(Long taskId) {
        int retain = pushProperties == null ? 50 : pushProperties.getLogRetain();
        if (retain <= 0) {
            return;
        }
        try {
            int deleted = logMapper.deleteOldByTaskId(taskId, retain);
            if (deleted > 0) {
                log.info("[tryTrimLogs][推送任务({}) 清理 {} 条历史执行记录]", taskId, deleted);
            }
        } catch (Exception ex) {
            log.warn("[tryTrimLogs][推送任务({}) 清理执行记录失败：{}]", taskId, ex.getMessage());
        }
    }

    /**
     * 在连续失败达到阈值或结果未知时发送推送任务告警。
     *
     * @param task 任务记录
     * @param logDO 日志
     */
    private void tryAlert(DataPushTaskDO task, DataPushTaskLogDO logDO) {
        int threshold = pushProperties == null ? 3 : pushProperties.getAlertFailCount();
        if (threshold <= 0) {
            return;
        }
        int consecutive = logMapper.countConsecutiveFailures(task.getId(), threshold * 3);
        if (!PushRunStatusEnum.UNKNOWN.getStatus().equals(logDO.getRunStatus())
                && (consecutive < threshold || consecutive % threshold != 0)) {
            return;
        }
        Long userId = resolveNotifyUser(task);
        if (userId == null) {
            return;
        }
        try {
            NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
            reqDTO.setUserId(userId);
            reqDTO.setTemplateCode(ALERT_TEMPLATE);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("taskName", StrUtil.blankToDefault(task.getName(), String.valueOf(task.getId())));
            params.put("failCount", String.valueOf(consecutive));
            params.put("errorInfo", StrUtil.maxLength(StrUtil.nullToEmpty(logDO.getErrorInfo()), 200));
            reqDTO.setTemplateParams(params);
            notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
        } catch (Exception ex) {
            log.warn("[tryAlert][推送任务({}) 失败告警发送失败：{}]", task.getId(), ex.getMessage());
        }
    }

    /**
     * 解析通知用户。
     *
     * @param task 任务记录
     * @return 解析通知用户数值
     */
    private static Long resolveNotifyUser(DataPushTaskDO task) {
        if (task.getReleaseUserId() != null) {
            return task.getReleaseUserId();
        }
        if (StrUtil.isNumeric(task.getCreator())) {
            return Long.parseLong(task.getCreator());
        }
        return null;
    }

}
