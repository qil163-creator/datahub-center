package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-周期任务状态枚举（由下属工作项状态汇总得出）
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillTaskStatusEnum {

    PENDING(0, "待开始"),
    RUNNING(1, "进行中"),
    FINISHED(2, "已完成"),
    CANCELLED(3, "已取消");

    private final Integer status;

    private final String name;

}
