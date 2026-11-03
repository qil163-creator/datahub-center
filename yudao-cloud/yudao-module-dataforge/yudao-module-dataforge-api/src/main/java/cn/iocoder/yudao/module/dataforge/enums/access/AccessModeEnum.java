package cn.iocoder.yudao.module.dataforge.enums.access;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 数据同步-接入方式枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum AccessModeEnum {

    /**
     * 同步到中台库，目标表自动添加 ods_ 前缀
     */
    ODS(1, "入中台库"),
    /**
     * 自定义接入，目标端为任意数据源
     */
    CUSTOM(2, "自定义接入");

    private final Integer mode;

    private final String name;

    /**
     * 根据输入构造访问模式。
     *
     * @param mode 处理模式
     * @return 访问模式
     */
    public static AccessModeEnum of(Integer mode) {
        return Arrays.stream(values()).filter(item -> item.getMode().equals(mode)).findFirst().orElse(null);
    }

}
