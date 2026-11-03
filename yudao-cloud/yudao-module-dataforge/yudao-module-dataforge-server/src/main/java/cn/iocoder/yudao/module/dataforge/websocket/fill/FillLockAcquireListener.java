package cn.iocoder.yudao.module.dataforge.websocket.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillCollabService;
import cn.iocoder.yudao.module.dataforge.service.fill.FillWorkItemService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

/**
 * 在线填报-获取单元格软锁（进入单元格编辑时）
 *
 * 锁只改善体验（他人编辑中提示），防覆盖的最终保障是草稿行版本 CAS
 *
 * @author Li
 */
@Component
public class FillLockAcquireListener extends AbstractFillCollabListener<FillLockAcquireListener.Message> {

    public static final String TYPE = "fill-lock-acquire";

    public static final String REPLY_TYPE = "fill-lock-result";

    @Resource
    private FillWorkItemService workItemService;

    @Resource
    private FillCollabService collabService;

    /**
     * 校验工作项编辑权限后申请单元格锁，向当前会话返回锁结果及持有者。
     *
     * @param session 会话
     * @param userId 用户编号
     * @param message 消息
     */
    @Override
    protected void onMessage(WebSocketSession session, Long userId, Message message) {
        FillWorkItemDO item = workItemService.getWorkItemValidated(message.getWorkItemId());
        workItemService.checkEditPermission(item, userId);
        boolean granted = collabService.acquireLock(
                message.getWorkItemId(), message.getRowId(), message.getFieldCode(), userId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("workItemId", message.getWorkItemId());
        payload.put("rowId", message.getRowId());
        payload.put("fieldCode", message.getFieldCode());
        payload.put("granted", granted);
        payload.put("holderUserId", granted ? userId
                : collabService.getLockHolder(message.getWorkItemId(), message.getRowId(), message.getFieldCode()));
        reply(session, REPLY_TYPE, payload);
    }

    /**
     * 返回当前实现对应的类型标识。
     *
     * @return 类型
     */
    @Override
    public String getType() {
        return TYPE;
    }

    @Data
    public static class Message {

        private Long workItemId;

        private String rowId;

        private String fieldCode;
    }

}
