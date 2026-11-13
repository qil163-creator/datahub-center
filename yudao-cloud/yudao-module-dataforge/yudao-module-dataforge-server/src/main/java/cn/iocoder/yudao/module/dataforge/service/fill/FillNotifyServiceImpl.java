package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 在线填报-通知服务实现类
 *
 * 站内信模板需在【系统管理-站内信模板】按 FILL_TEMPLATE_CODES 预置；
 * 模板缺失或发送失败仅记录（sendStatus=2），不影响业务事务
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
            Map.entry(FillNotifyTypeEnum.TASK_CLOSED, "fill_task_closed"));

    private static final int SEND_STATUS_SUCCESS = 1;

    private static final int SEND_STATUS_FAILED = 2;

    @Resource
    private FillNotifyRecordMapper notifyRecordMapper;

    @Resource
    private NotifyMessageSendApi notifyMessageSendApi;

    /**
     * 向一组用户发送填报通知
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
        String summary = buildSummary(type, task, workItem);
        for (Long userId : userIds) {
            FillNotifyRecordDO record = FillNotifyRecordDO.builder()
                    .notifyType(type.getType())
                    .taskId(task != null ? task.getId() : null)
                    .workItemId(workItem != null ? workItem.getId() : null)
                    .receiverUserId(userId)
                    .channel(FillNotifyChannelEnum.NOTIFY_MESSAGE.getChannel())
                    .templateCode(templateCode)
                    .contentSummary(summary)
                    .sendStatus(0)
                    .build();
            notifyRecordMapper.insert(record);

            FillNotifyRecordDO update = new FillNotifyRecordDO();
            update.setId(record.getId());
            update.setSendTime(LocalDateTime.now());
            try {
                NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
                reqDTO.setUserId(userId);
                reqDTO.setTemplateCode(templateCode);
                reqDTO.setTemplateParams(params);
                notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
                update.setSendStatus(SEND_STATUS_SUCCESS);
            } catch (Exception ex) {
                update.setSendStatus(SEND_STATUS_FAILED);
                log.warn("[sendToUsers][通知({}) 用户({}) 站内信发送失败：{}]", type, userId, ex.getMessage());
            }
            notifyRecordMapper.updateById(update);
        }
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
