package cn.iocoder.yudao.module.dataforge.service.push;

/**
 * 数据推送通道：按 Spring Bean 白名单注册，禁止反射加载任意类
 *
 * @author Li
 */
public interface DataPushChannel {

    /**
     * @return {@link cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum#getType()}
     */
    Integer getType();

    /** 无外部副作用的配置预检，在持久发送边界之前执行。 */
    default void validateConfiguration(String channelJson) {
    }

    /**
     * 一次执行共用的连接；默认无状态
     */
    default DataPushSession openSession(String channelJson) {
        return DataPushSession.NOOP;
    }

    /**
     * 推送一批数据
     */
    void push(PushPayload payload, String channelJson, StringBuilder log);

}
