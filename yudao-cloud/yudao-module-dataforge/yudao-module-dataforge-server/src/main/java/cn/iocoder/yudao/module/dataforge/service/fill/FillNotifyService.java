package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;

import java.util.Collection;
import java.util.Map;

/**
 * 在线填报-通知服务接口
 *
 * 职责：所有通知先落 fill_notify_record（发送事实与频控依据），再尽力投递站内信；
 * 投递失败只记录不抛出，不阻塞业务主流程
 *
 * @author Li
 */
public interface FillNotifyService {

    /**
     * 向一组用户发送填报通知
     *
     * @param type        通知类型
     * @param task        所属任务（可空）
     * @param workItem    所属工作项（可空）
     * @param userIds     接收人集合
     * @param extraParams 附加模板参数（可空）
     */
    void sendToUsers(FillNotifyTypeEnum type, FillTaskDO task, FillWorkItemDO workItem,
            Collection<Long> userIds, Map<String, Object> extraParams);

}
