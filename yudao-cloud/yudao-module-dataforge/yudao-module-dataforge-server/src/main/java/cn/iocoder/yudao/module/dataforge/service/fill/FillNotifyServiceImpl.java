package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillNotifyRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillNotifyRecordMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyChannelEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 在线填报-通知服务实现类
 *
 * 站内信模板需在【系统管理-站内信模板】按 FILL_TEMPLATE_CODES 预置；
 * 模板缺失或发送失败仅记录（sendStatus=2），不影响业务事务；失败的通知由事件投递任务按次数与时间窗重试
 *
 * @author Li
 */
@Slf4j
@Service
public class FillNotifyServiceImpl implements FillNotifyService {

    private static final DateTimeFormatter DEADLINE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 通知类型 → 站内信模板编号 */
    private static final Map<FillNotifyTypeEnum, String> FILL_TEMPLATE_CODES = Map.ofEntries(
            Map.entry(FillNotifyTypeEnum.ASSIGNED, "fill_assigned"),
            Map.entry(FillNotifyTypeEnum.TRANSFERRED, "fill_transferred"),
            Map.entry(FillNotifyTypeEnum.TASK_STARTED, "fill_task_started"),
            Map.entry(FillNotifyTypeEnum.DEADLINE_APPROACHING, "fill_deadline_approaching"),
            Map.entry(FillNotifyTypeEnum.OVERDUE, "fill_overdue"),
            Map.entry(FillNotifyTypeEnum.SUBMITTED_BY_OTHER, "fill_submitted_by_other"),
            Map.entry(FillNotifyTypeEnum.REJECTED, "fill_rejected"),
            Map.entry(FillNotifyTypeEnum.APPROVED, "fill_approved"),
            Map.entry(FillNotifyTypeEnum.MERGE_SUCCEEDED, "fill_merge_succeeded"),
            Map.entry(FillNotifyTypeEnum.MERGE_FAILED, "fill_merge_failed"),
            Map.entry(FillNotifyTypeEnum.URGE, "fill_urge"),
            Map.entry(FillNotifyTypeEnum.CORRECTION_CREATED, "fill_correction_created"),
            Map.entry(FillNotifyTypeEnum.DEADLINE_EXTENDED, "fill_deadline_extended"),
            Map.entry(FillNotifyTypeEnum.TASK_CLOSED, "fill_task_closed"),
            Map.entry(FillNotifyTypeEnum.MERGE_RETURNED, "fill_merge_returned"));

    private static final int SEND_STATUS_PENDING = 0;

    private static final int SEND_STATUS_SUCCESS = 1;

    private static final int SEND_STATUS_FAILED = 2;

    /** 发送失败后的重试次数上限 */
    private static final int MAX_RETRY = 3;

    /** 两次发送之间的最小间隔（分钟） */
    private static final long RETRY_INTERVAL_MINUTES = 5;

    /** 只重试创建后该时长内的通知（小时），过期通知不再补发 */
    private static final long RETRY_WINDOW_HOURS = 24;

    private static final int RETRY_SCAN_LIMIT = 200;

    @Resource
    private FillNotifyRecordMapper notifyRecordMapper;

    @Resource
    private NotifyMessageSendApi notifyMessageSendApi;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 向一组用户发送填报通知。
     *
     * <p>调用方处于事务中时，通知推迟到事务提交之后发送：业务事务里不做远程调用，事务回滚也不会发出通知。
     * 通知内容在调用时定稿。发送失败只记录，由 {@link #retryFailed} 重试，不影响业务结果。</p>
     *
     * @param type 类型标识
     * @param task 任务记录
     * @param workItem 工作项
     * @param userIds 用户编号集合
     * @param extraParams 附加参数
     */
    @Override
    public void sendToUsers(FillNotifyTypeEnum type, FillTaskDO task, FillWorkItemDO workItem,
            Collection<Long> userIds, Map<String, Object> extraParams) {
        if (CollUtil.isEmpty(userIds)) {
            return;
        }
        String templateCode = FILL_TEMPLATE_CODES.get(type);
        Map<String, Object> params = buildParams(task, workItem, extraParams);
        String paramsJson = JsonUtils.toJsonString(params);
        String summary = buildSummary(type, task, workItem);
        Long taskId = task != null ? task.getId() : null;
        Long workItemId = workItem != null ? workItem.getId() : null;
        List<Long> receivers = userIds.stream().filter(Objects::nonNull).distinct().toList();
        Runnable delivery = () -> {
            for (Long userId : receivers) {
                try {
                    FillNotifyRecordDO record = FillNotifyRecordDO.builder()
                            .notifyType(type.getType())
                            .taskId(taskId)
                            .workItemId(workItemId)
                            .receiverUserId(userId)
                            .channel(FillNotifyChannelEnum.NOTIFY_MESSAGE.getChannel())
                            .templateCode(templateCode)
                            .contentSummary(summary)
                            .templateParams(paramsJson)
                            .sendStatus(SEND_STATUS_PENDING)
                            .retryCount(0)
                            .build();
                    inNewTransaction(() -> notifyRecordMapper.insert(record));
                    deliver(record.getId(), userId, templateCode, params);
                } catch (Exception ex) {
                    log.warn("[sendToUsers][通知({}) 用户({}) 记录失败：{}]", type, userId, ex.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delivery.run();
                }
            });
        } else {
            delivery.run();
        }
    }

    /**
     * 重试发送失败或中断遗留的通知。
     *
     * <p>每条通知最多重试 {@value #MAX_RETRY} 次、只在创建后 {@value #RETRY_WINDOW_HOURS} 小时内重试；
     * 站内信用通知记录编号作幂等键，重复发送不会产生第二条消息。</p>
     *
     * @param now 当前时间
     * @return 本次重试后发送成功的条数
     */
    @Override
    public int retryFailed(LocalDateTime now) {
        List<FillNotifyRecordDO> records = notifyRecordMapper.selectListToRetry(
                now.minusMinutes(RETRY_INTERVAL_MINUTES), now.minusHours(RETRY_WINDOW_HOURS),
                MAX_RETRY, RETRY_SCAN_LIMIT);
        int succeeded = 0;
        for (FillNotifyRecordDO record : records) {
            try {
                if (notifyRecordMapper.claimForRetry(record.getId(), record.getRetryCount()) == 0) {
                    continue;
                }
                Map<String, Object> params = JsonUtils.parseObject(record.getTemplateParams(),
                        new TypeReference<Map<String, Object>>() { });
                if (deliver(record.getId(), record.getReceiverUserId(), record.getTemplateCode(), params)) {
                    succeeded++;
                }
            } catch (Exception ex) {
                log.warn("[retryFailed][通知记录({}) 重试失败：{}]", record.getId(), ex.getMessage());
            }
        }
        return succeeded;
    }

    /** 发送一条站内信并回写结果；返回是否发送成功 */
    private boolean deliver(Long recordId, Long userId, String templateCode, Map<String, Object> params) {
        FillNotifyRecordDO update = new FillNotifyRecordDO();
        update.setId(recordId);
        update.setSendTime(LocalDateTime.now());
        boolean success;
        try {
            NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
            reqDTO.setUserId(userId);
            reqDTO.setTemplateCode(templateCode);
            reqDTO.setTemplateParams(params);
            reqDTO.setIdempotencyKey("dataforge:fill-notify:" + recordId);
            notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO).checkError();
            success = true;
        } catch (Exception ex) {
            success = false;
            log.warn("[deliver][通知记录({}) 用户({}) 模板({}) 站内信发送失败：{}]",
                    recordId, userId, templateCode, ex.getMessage());
        }
        update.setSendStatus(success ? SEND_STATUS_SUCCESS : SEND_STATUS_FAILED);
        inNewTransaction(() -> notifyRecordMapper.updateById(update));
        return success;
    }

    /**
     * 通知记录的写入使用独立事务：事务提交后的回调里原事务的连接仍然绑定在线程上，直接写入不保证提交。
     */
    private void inNewTransaction(Runnable work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> work.run());
    }

    /**
     * 构造参数。
     *
     * @param task 任务记录
     * @param workItem 工作项
     * @param extraParams 附加参数
     * @return 处理后的键值映射
     */
    private Map<String, Object> buildParams(FillTaskDO task, FillWorkItemDO workItem,
            Map<String, Object> extraParams) {
        Map<String, Object> params = new HashMap<>();
        if (task != null) {
            params.put("taskName", task.getName());
            params.put("periodKey", task.getPeriodKey());
        }
        if (workItem != null) {
            params.put("orgName", workItem.getOrgName());
            if (workItem.getDeadlineTime() != null) {
                params.put("deadline", DEADLINE_FORMATTER.format(workItem.getDeadlineTime()));
            }
        }
        if (CollUtil.isNotEmpty(extraParams)) {
            params.putAll(extraParams);
        }
        return params;
    }

    /**
     * 构造汇总。
     *
     * @param type 类型标识
     * @param task 任务记录
     * @param workItem 工作项
     * @return 构造汇总文本
     */
    private String buildSummary(FillNotifyTypeEnum type, FillTaskDO task, FillWorkItemDO workItem) {
        StringBuilder summary = new StringBuilder(type.getName());
        if (task != null) {
            summary.append("：").append(task.getName());
        }
        if (workItem != null) {
            summary.append(" / ").append(workItem.getOrgName());
        }
        return summary.length() > 500 ? summary.substring(0, 500) : summary.toString();
    }

}
