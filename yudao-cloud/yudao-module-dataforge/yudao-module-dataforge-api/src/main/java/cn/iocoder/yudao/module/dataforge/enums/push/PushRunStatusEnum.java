package cn.iocoder.yudao.module.dataforge.enums.push;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据推送运行状态（与数据同步语义一致）
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum PushRunStatusEnum {

    WAITING(1, "等待中"),
    RUNNING(2, "运行中"),
    SUCCESS(3, "成功"),
    FAILED(4, "失败"),
    UNKNOWN(5, "结果未知，待核查"),
    RESOLVED(6, "已人工核查处置");

    private final Integer status;

    private final String name;

}
