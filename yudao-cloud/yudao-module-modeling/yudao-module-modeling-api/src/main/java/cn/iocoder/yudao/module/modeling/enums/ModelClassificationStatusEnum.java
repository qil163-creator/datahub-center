package cn.iocoder.yudao.module.modeling.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 模型分类确认状态枚举
 *
 * 归属（数仓分层 + 表类型）改为「草稿可暂缺、发布必须补齐」后，需要一个状态把没补齐的模型标出来：
 * 存量数据和逆向接入的表先落 UNCLASSIFIED，可在列表中筛出来逐步补齐，发布时强制 CONFIRMED。
 *
 * 该状态由分层与表类型是否齐备派生，不由用户直接设置。
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum ModelClassificationStatusEnum {

    UNCLASSIFIED("UNCLASSIFIED", "待分类"),
    CONFIRMED("CONFIRMED", "已确认");

    private final String status;

    private final String name;

    /**
     * 判断是否已确认分类
     */
    public static boolean isConfirmed(String status) {
        return CONFIRMED.getStatus().equals(status);
    }

}
