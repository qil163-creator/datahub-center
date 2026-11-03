package cn.iocoder.yudao.module.dataforge.websocket.fill;

import cn.iocoder.yudao.module.dataforge.service.fill.FillCollabService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 在线填报-离开工作项房间
 *
 * @author Li
 */
@Component
public class FillLeaveRoomListener extends AbstractFillCollabListener<FillLeaveRoomListener.Message> {

    public static final String TYPE = "fill-work-item-leave";

    @Resource
    private FillCollabService collabService;

    /**
     * 将当前用户移出指定填报工作项的协作房间。
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
        collabService.leaveRoom(message.getWorkItemId(), userId);
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
