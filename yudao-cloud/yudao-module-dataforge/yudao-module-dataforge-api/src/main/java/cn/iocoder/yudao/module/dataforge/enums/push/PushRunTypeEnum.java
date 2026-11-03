package cn.iocoder.yudao.module.dataforge.enums.push;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据推送触发方式
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum PushRunTypeEnum {

    HAND(1, "手动"),
    AUTO(2, "自动");

    private final Integer type;

    private final String name;

}
