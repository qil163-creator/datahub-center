package cn.iocoder.yudao.module.dataforge.websocket.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillCollabService;
import cn.iocoder.yudao.module.dataforge.service.fill.FillWorkItemService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * 在线填报-加入工作项房间
 *
 * 校验查看权限后登记在线状态，并回执在线成员与当前 serverVersion
 * （客户端据此判断是否需要 afterVersion 增量拉取）
 *
 * @author Li
 */
@Component
public class FillJoinRoomListener extends AbstractFillCollabListener<FillJoinRoomListener.Message> {

    public static final String TYPE = "fill-work-item-join";

    public static final String REPLY_TYPE = "fill-presence-snapshot";

    @Resource
    private FillWorkItemService workItemService;

    @Resource
    private FillCollabService collabService;

    /**
     * 校验工作项查看权限后加入协作房间，返回服务端版本及在线成员。
     *
     * @param session 会话
     * @param userId 用户编号
     * @param message 消息
     */
    @Override
    protected void onMessage(WebSocketSession session, Long userId, Message message) {
        FillWorkItemDO item = workItemService.getWorkItemValidated(message.getWorkItemId());
        workItemService.checkViewPermission(item, userId);
        collabService.joinRoom(item.getId(), userId);
        reply(session, REPLY_TYPE, Map.of(
                "workItemId", item.getId(),
                "serverVersion", item.getServerVersion(),
                "memberUserIds", collabService.getRoomMembers(item.getId())));
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
