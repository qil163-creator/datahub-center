package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-可靠事件发件箱状态枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillOutboxStatusEnum {

    PENDING(0, "待发送"),
    SENDING(1, "发送中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败（等待重试）");

    private final Integer status;

    private final String name;

}
