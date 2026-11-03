package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-外部事件收件箱状态枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillInboxStatusEnum {

    RECEIVED(0, "已接收"),
    PROCESSED(1, "已处理"),
    IGNORED(2, "已忽略（重复/乱序/过期）"),
    FAILED(3, "处理失败");

    private final Integer status;

    private final String name;

}
