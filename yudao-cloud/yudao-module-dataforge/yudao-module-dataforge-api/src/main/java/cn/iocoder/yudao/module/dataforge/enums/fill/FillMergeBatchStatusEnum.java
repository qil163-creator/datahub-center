package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-合并批次状态枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillMergeBatchStatusEnum {

    PENDING(0, "待执行"),
    RUNNING(1, "执行中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败（等待自动或人工重试）");

    private final Integer status;

    private final String name;

}
