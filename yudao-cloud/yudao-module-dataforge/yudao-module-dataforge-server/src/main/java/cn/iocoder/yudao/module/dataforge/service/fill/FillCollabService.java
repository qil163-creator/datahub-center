package cn.iocoder.yudao.module.dataforge.service.fill;

import java.util.Set;

/**
 * 在线填报-协同服务接口（在线状态 / 单元格软锁 / 房间广播）
 *
 * 一致性铁律：Redis 锁与广播只改善协同体验；数据不覆盖的最终保障是
 * 工作项 serverVersion 与草稿行 rowVersion 的数据库 CAS。
 * 广播只是"有新操作"的提示，客户端一律以 GET operations?afterVersion 拉取补齐
 *
 * @author Li
 */
public interface FillCollabService {

    /** 加入工作项房间（在线状态登记，TTL 由心跳续期） */
    void joinRoom(Long workItemId, Long userId);

    /** 离开工作项房间 */
    void leaveRoom(Long workItemId, Long userId);

    /** 心跳：续期在线状态 */
    void refreshPresence(Long workItemId, Long userId);

    /** 房间当前在线用户 */
    Set<Long> getRoomMembers(Long workItemId);

    /**
     * 获取单元格软锁（SETNX + TTL），已被他人持有返回 false
     */
    boolean acquireLock(Long workItemId, String rowId, String fieldCode, Long userId);

    /** 续租本人持有的锁 */
    boolean renewLock(Long workItemId, String rowId, String fieldCode, Long userId);

    /** 释放本人持有的锁 */
    void releaseLock(Long workItemId, String rowId, String fieldCode, Long userId);

    /** 查询锁持有人（无锁返回 null） */
    Long getLockHolder(Long workItemId, String rowId, String fieldCode);

    /** 向房间内全部在线用户广播消息 */
    void broadcast(Long workItemId, String messageType, Object payload);

    /** 草稿操作提交后的提示广播（客户端收到后按 afterVersion 增量拉取） */
    void notifyOperationApplied(Long workItemId, Long serverVersion, Long operatorUserId);

}
