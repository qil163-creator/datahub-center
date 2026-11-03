package cn.iocoder.yudao.module.dataforge.enums.access;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据同步-运行状态枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum AccessRunStatusEnum {

    WAITING(1, "等待中"),
    RUNNING(2, "运行中"),
    SUCCESS(3, "正常结束"),
    FAILED(4, "异常结束"),
    UNKNOWN(5, "结果待核查"),
    STOPPED(6, "已停止");

    private final Integer status;

    private final String name;

}
