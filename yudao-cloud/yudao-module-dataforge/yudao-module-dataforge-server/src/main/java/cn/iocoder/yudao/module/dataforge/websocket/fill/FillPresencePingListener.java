package cn.iocoder.yudao.module.dataforge.websocket.fill;

import cn.iocoder.yudao.module.dataforge.service.fill.FillCollabService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 在线填报-在线心跳（建议客户端每 20 秒发送，超时 120 秒自动离线）
 *
 * @author Li
 */
@Component
public class FillPresencePingListener extends AbstractFillCollabListener<FillPresencePingListener.Message> {

    public static final String TYPE = "fill-presence-ping";

    @Resource
    private FillCollabService collabService;

    /**
     * 刷新当前用户在指定填报工作项中的在线状态。
     *
     * @param session 会话
     * @param userId 用户编号
     * @param message 消息
     */
    @Override
    protected void onMessage(WebSocketSession session, Long userId, Message message) {
        if (message.getWorkItemId() == null) {
            return;
        }
        collabService.refreshPresence(message.getWorkItemId(), userId);
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
    }

}
