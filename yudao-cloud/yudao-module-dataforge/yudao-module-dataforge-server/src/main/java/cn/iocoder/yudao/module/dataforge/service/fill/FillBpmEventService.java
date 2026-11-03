package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;

/**
 * 在线填报-BPM 状态事件处理接口（Inbox 幂等消费）
 *
 * @author Li
 */
public interface FillBpmEventService {

    /**
     * 处理 BPM 流程实例状态事件（通过/驳回/取消）
     *
     * 幂等：去重键 {processInstanceId}:{status} 唯一约束；重复、乱序、找不到业务的
     * 事件只记录不执行副作用；处理异常记录 Inbox 失败，不向 BPM 抛错（避免影响 BPM 事件链）
     */
    void handleStatusEvent(BpmProcessInstanceStatusEvent event);

}
