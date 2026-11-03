package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-校验问题级别枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillValidationSeverityEnum {

    ERROR(1, "错误（阻断提交）"),
    WARNING(2, "警告");

    private final Integer severity;

    private final String name;

}
