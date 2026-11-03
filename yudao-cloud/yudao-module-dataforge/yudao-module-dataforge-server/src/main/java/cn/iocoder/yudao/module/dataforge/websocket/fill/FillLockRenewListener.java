package cn.iocoder.yudao.module.dataforge.websocket.fill;

import cn.iocoder.yudao.module.dataforge.service.fill.FillCollabService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

/**
 * 在线填报-续租单元格软锁（编辑中每 10~15 秒续租一次）
 *
 * @author Li
 */
@Component
public class FillLockRenewListener extends AbstractFillCollabListener<FillLockRenewListener.Message> {

    public static final String TYPE = "fill-lock-renew";

    @Resource
    private FillCollabService collabService;

    /**
     * 续约当前用户的单元格协作锁，并返回续约结果及当前持有者。
     *
     * @param session 会话
     * @param userId 用户编号
     * @param message 消息
     */
    @Override
    protected void onMessage(WebSocketSession session, Long userId, Message message) {
        boolean renewed = collabService.renewLock(
                message.getWorkItemId(), message.getRowId(), message.getFieldCode(), userId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("workItemId", message.getWorkItemId());
        payload.put("rowId", message.getRowId());
        payload.put("fieldCode", message.getFieldCode());
        payload.put("granted", renewed);
        payload.put("holderUserId", renewed ? userId
                : collabService.getLockHolder(message.getWorkItemId(), message.getRowId(), message.getFieldCode()));
        reply(session, FillLockAcquireListener.REPLY_TYPE, payload);
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
