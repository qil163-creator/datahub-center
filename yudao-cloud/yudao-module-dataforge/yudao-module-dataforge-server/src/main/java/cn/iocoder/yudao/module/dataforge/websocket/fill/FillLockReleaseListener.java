package cn.iocoder.yudao.module.dataforge.websocket.fill;

import cn.iocoder.yudao.module.dataforge.service.fill.FillCollabService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 在线填报-释放单元格软锁（离开单元格/提交完成时）
 *
 * @author Li
 */
@Component
public class FillLockReleaseListener extends AbstractFillCollabListener<FillLockReleaseListener.Message> {

    public static final String TYPE = "fill-lock-release";

    @Resource
    private FillCollabService collabService;

    /**
     * 按工作项、行及字段释放当前用户持有的协作锁。
     *
     * @param session 会话
     * @param userId 用户编号
     * @param message 消息
     */
    @Override
    protected void onMessage(WebSocketSession session, Long userId, Message message) {
        collabService.releaseLock(
                message.getWorkItemId(), message.getRowId(), message.getFieldCode(), userId);
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
