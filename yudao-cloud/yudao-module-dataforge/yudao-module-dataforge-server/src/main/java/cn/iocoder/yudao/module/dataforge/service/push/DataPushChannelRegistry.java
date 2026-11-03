package cn.iocoder.yudao.module.dataforge.service.push;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推送通道白名单：启动时收集 Bean，重复类型直接失败
 *
 * @author Li
 */
@Component
public class DataPushChannelRegistry {

    @Resource
    private List<DataPushChannel> channels;

    private final Map<Integer, DataPushChannel> channelMap = new HashMap<>();

    /**
     * 初始化数据推送通道注册表。
     */
    @PostConstruct
    public void init() {
        for (DataPushChannel channel : channels) {
            DataPushChannel exists = channelMap.putIfAbsent(channel.getType(), channel);
            if (exists != null) {
                throw new IllegalStateException("重复的数据推送通道类型：" + channel.getType());
            }
        }
    }

    /**
     * 读取数据推送通道。
     *
     * @param type 类型标识
     * @return 数据推送通道
     */
    public DataPushChannel get(Integer type) {
        return channelMap.get(type);
    }

}
