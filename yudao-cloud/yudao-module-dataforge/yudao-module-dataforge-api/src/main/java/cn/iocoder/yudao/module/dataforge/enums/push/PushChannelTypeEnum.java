package cn.iocoder.yudao.module.dataforge.enums.push;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 数据推送通道类型
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum PushChannelTypeEnum {

    HTTP(1, "HTTP"),
    DB(2, "数据库"),
    MQ(3, "消息队列");

    private final Integer type;

    private final String name;

    /**
     * 根据输入构造推送通道类型。
     *
     * @param type 类型标识
     * @return 推送通道类型
     */
    public static PushChannelTypeEnum of(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.getType().equals(type))
                .findFirst().orElse(null);
    }

}
