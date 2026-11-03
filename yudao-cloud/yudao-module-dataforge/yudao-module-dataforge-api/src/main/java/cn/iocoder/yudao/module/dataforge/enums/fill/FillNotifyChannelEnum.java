package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-通知渠道枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillNotifyChannelEnum {

    NOTIFY_MESSAGE(1, "站内信"),
    SMS(2, "短信"),
    MAIL(3, "邮件");

    private final Integer channel;

    private final String name;

}
