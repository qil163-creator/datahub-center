package cn.iocoder.yudao.module.dataforge.websocket.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.websocket.core.sender.WebSocketMessageSender;
import cn.iocoder.yudao.framework.websocket.core.listener.WebSocketMessageListener;
import cn.iocoder.yudao.framework.websocket.core.util.WebSocketFrameworkUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * 在线填报-协同消息监听器基类
 *
 * 统一从会话取登录用户（WebSocket 消息线程没有 SecurityContext）、
 * 统一异常兜底并以 fill-error 消息回执，避免单条消息异常中断连接
 *
 * @author Li
 */
@Slf4j
public abstract class AbstractFillCollabListener<T> implements WebSocketMessageListener<T> {

    public static final String MESSAGE_TYPE_ERROR = "fill-error";

    @Resource
    protected WebSocketMessageSender messageSender;

    /**
     * 从认证 WebSocket 会话解析用户，分派填报协作消息并统一返回处理错误。
     *
     * @param session 会话
     * @param message 消息
     */
    @Override
    public void onMessage(WebSocketSession session, T message) {
        Long userId = WebSocketFrameworkUtils.getLoginUserId(session);
        if (userId == null) {
            return;
        }
        try {
            onMessage(session, userId, message);
        } catch (ServiceException ex) {
            reply(session, MESSAGE_TYPE_ERROR, Map.of("type", getType(), "message", ex.getMessage()));
        } catch (Exception ex) {
            log.error("[onMessage][协同消息({}) 用户({}) 处理异常]", getType(), userId, ex);
            reply(session, MESSAGE_TYPE_ERROR, Map.of("type", getType(), "message", "操作失败，请重试"));
        }
    }

    /**
     * 从认证 WebSocket 会话解析用户，分派填报协作消息并统一返回处理错误。
     *
     * @param session 会话
     * @param userId 用户编号
     * @param message 消息
     */
    protected abstract void onMessage(WebSocketSession session, Long userId, T message);

    /**
     * 回复抽象填报协作。
     *
     * @param session 会话
     * @param messageType 消息类型
     * @param payload 载荷
     */
    protected void reply(WebSocketSession session, String messageType, Object payload) {
        messageSender.sendObject(session.getId(), messageType, payload);
    }

}
