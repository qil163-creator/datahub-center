package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.websocket.core.sender.WebSocketMessageSender;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在线填报-协同服务实现类
 *
 * Redis 结构：
 *   在线状态  fill:collab:room:{workItemId}   Hash(field=userId, value=lastActiveMillis)，TTL 120s 心跳续期
 *   单元格锁  fill:collab:lock:{workItemId}:{rowId}:{fieldCode}   String(userId)，TTL 30s 编辑中续租
 *
 * WebSocket 发送器缺席（未启用 websocket）时全部广播降级为空操作，不影响 REST 主链路
 *
 * @author Li
 */
@Slf4j
@Service
public class FillCollabServiceImpl implements FillCollabService {

    private static final String ROOM_KEY = "fill:collab:room:%d";

    private static final String LOCK_KEY = "fill:collab:lock:%d:%s:%s";

    private static final Duration ROOM_TTL = Duration.ofSeconds(120);

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    public static final String MESSAGE_TYPE_PRESENCE_JOINED = "fill-presence-joined";

    public static final String MESSAGE_TYPE_PRESENCE_LEFT = "fill-presence-left";

    public static final String MESSAGE_TYPE_LOCK_CHANGED = "fill-lock-changed";

    public static final String MESSAGE_TYPE_OPERATION_APPLIED = "fill-operation-applied";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectProvider<WebSocketMessageSender> webSocketMessageSenderProvider;

    /**
     * 加入工作项房间（在线状态登记，TTL 由心跳续期）
     *
     * @param workItemId 工作项编号
     * @param userId 用户编号
     */
    @Override
    public void joinRoom(Long workItemId, Long userId) {
        String key = roomKey(workItemId);
        stringRedisTemplate.opsForHash().put(key, String.valueOf(userId),
                String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.expire(key, ROOM_TTL);
        broadcast(workItemId, MESSAGE_TYPE_PRESENCE_JOINED,
                Map.of("workItemId", workItemId, "userId", userId));
    }

    /**
     * 离开工作项房间
     *
     * @param workItemId 工作项编号
     * @param userId 用户编号
     */
    @Override
    public void leaveRoom(Long workItemId, Long userId) {
        stringRedisTemplate.opsForHash().delete(roomKey(workItemId), String.valueOf(userId));
        broadcast(workItemId, MESSAGE_TYPE_PRESENCE_LEFT,
                Map.of("workItemId", workItemId, "userId", userId));
    }

    /**
     * 心跳：续期在线状态
     *
     * @param workItemId 工作项编号
     * @param userId 用户编号
     */
    @Override
    public void refreshPresence(Long workItemId, Long userId) {
        String key = roomKey(workItemId);
        stringRedisTemplate.opsForHash().put(key, String.valueOf(userId),
                String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.expire(key, ROOM_TTL);
    }

    /**
     * 房间当前在线用户
     *
     * @param workItemId 工作项编号
     * @return 房间当前在线用户结果集合
     */
    @Override
    public Set<Long> getRoomMembers(Long workItemId) {
        Set<Object> fields = stringRedisTemplate.opsForHash().keys(roomKey(workItemId));
        if (fields == null || fields.isEmpty()) {
            return Collections.emptySet();
        }
        return fields.stream().map(field -> Long.valueOf(String.valueOf(field)))
                .collect(Collectors.toSet());
    }

    /**
     * 获取单元格软锁（SETNX + TTL），已被他人持有返回 false
     *
     * @param workItemId 工作项编号
     * @param rowId 行编号
     * @param fieldCode 字段编码
     * @param userId 用户编号
     * @return 处理是否成功
     */
    @Override
    public boolean acquireLock(Long workItemId, String rowId, String fieldCode, Long userId) {
        String key = lockKey(workItemId, rowId, fieldCode);
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, String.valueOf(userId), LOCK_TTL);
        if (Boolean.TRUE.equals(success)) {
            broadcastLockChanged(workItemId, rowId, fieldCode, userId);
            return true;
        }
        // 本人重复获取视为续租成功
        return renewLock(workItemId, rowId, fieldCode, userId);
    }

    /**
     * 续租本人持有的锁
     *
     * @param workItemId 工作项编号
     * @param rowId 行编号
     * @param fieldCode 字段编码
     * @param userId 用户编号
     * @return 处理是否成功
     */
    @Override
    public boolean renewLock(Long workItemId, String rowId, String fieldCode, Long userId) {
        String key = lockKey(workItemId, rowId, fieldCode);
        String holder = stringRedisTemplate.opsForValue().get(key);
        if (!String.valueOf(userId).equals(holder)) {
            return false;
        }
        stringRedisTemplate.expire(key, LOCK_TTL);
        return true;
    }

    /**
     * 释放本人持有的锁
     *
     * @param workItemId 工作项编号
     * @param rowId 行编号
     * @param fieldCode 字段编码
     * @param userId 用户编号
     */
    @Override
    public void releaseLock(Long workItemId, String rowId, String fieldCode, Long userId) {
        String key = lockKey(workItemId, rowId, fieldCode);
        String holder = stringRedisTemplate.opsForValue().get(key);
        // 简单比较后删除：极小概率误删他人刚获取的锁，由数据库行版本 CAS 兜底，不引入 Lua 复杂度
        if (String.valueOf(userId).equals(holder)) {
            stringRedisTemplate.delete(key);
            broadcastLockChanged(workItemId, rowId, fieldCode, null);
        }
    }

    /**
     * 查询锁持有人（无锁返回 null）
     *
     * @param workItemId 工作项编号
     * @param rowId 行编号
     * @param fieldCode 字段编码
     * @return 读取锁持有者数值
     */
    @Override
    public Long getLockHolder(Long workItemId, String rowId, String fieldCode) {
        String holder = stringRedisTemplate.opsForValue().get(lockKey(workItemId, rowId, fieldCode));
        return StrUtil.isBlank(holder) ? null : Long.valueOf(holder);
    }

    /**
     * 向房间内全部在线用户广播消息
     *
     * @param workItemId 工作项编号
     * @param messageType 消息类型
     * @param payload 载荷
     */
    @Override
    public void broadcast(Long workItemId, String messageType, Object payload) {
        WebSocketMessageSender sender = webSocketMessageSenderProvider.getIfAvailable();
        if (sender == null) {
            return;
        }
        for (Long memberUserId : getRoomMembers(workItemId)) {
            try {
                sender.sendObject(UserTypeEnum.ADMIN.getValue(), memberUserId, messageType, payload);
            } catch (Exception ex) {
                log.warn("[broadcast][工作项({}) 用户({}) 消息({}) 发送失败：{}]",
                        workItemId, memberUserId, messageType, ex.getMessage());
            }
        }
    }

    /**
     * 草稿操作提交后的提示广播（客户端收到后按 afterVersion 增量拉取）
     *
     * @param workItemId 工作项编号
     * @param serverVersion 服务端版本
     * @param operatorUserId 操作人用户编号
     */
    @Override
    public void notifyOperationApplied(Long workItemId, Long serverVersion, Long operatorUserId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("workItemId", workItemId);
        payload.put("serverVersion", serverVersion);
        payload.put("operatorUserId", operatorUserId);
        broadcast(workItemId, MESSAGE_TYPE_OPERATION_APPLIED, payload);
    }

    /**
     * 广播锁变化。
     *
     * @param workItemId 工作项编号
     * @param rowId 行编号
     * @param fieldCode 字段编码
     * @param holderUserId 持有者用户编号
     */
    private void broadcastLockChanged(Long workItemId, String rowId, String fieldCode, Long holderUserId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("workItemId", workItemId);
        payload.put("rowId", rowId);
        payload.put("fieldCode", fieldCode);
        payload.put("holderUserId", holderUserId);
        broadcast(workItemId, MESSAGE_TYPE_LOCK_CHANGED, payload);
    }

    /**
     * 获取填报协作的协作房间键。
     *
     * @param workItemId 工作项编号
     * @return 填报协作的协作房间键文本
     */
    private String roomKey(Long workItemId) {
        return String.format(ROOM_KEY, workItemId);
    }

    /**
     * 锁定键。
     *
     * @param workItemId 工作项编号
     * @param rowId 行编号
     * @param fieldCode 字段编码
     * @return 锁定键文本
     */
    private String lockKey(Long workItemId, String rowId, String fieldCode) {
        return String.format(LOCK_KEY, workItemId, rowId, StrUtil.nullToDefault(fieldCode, "-"));
    }

}
