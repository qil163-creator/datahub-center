package cn.iocoder.yudao.module.governance.enums.master;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 主数据派发类型枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum MasterDistributeTypeEnum {

    /** 派发到外部数据库（dbswitch 直写） */
    DB(1, "数据库"),
    /** 派发到 HTTP 接口（分批 POST JSON 数组） */
    API(2, "API 接口"),
    /** 派发到消息队列（Kafka / RabbitMQ） */
    MQ(3, "消息队列");

    private final Integer type;

    private final String name;

    /**
     * 按分发类型编码查找对应枚举。
     */
    public static MasterDistributeTypeEnum of(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.getType().equals(type))
                .findFirst().orElse(null);
    }

}
