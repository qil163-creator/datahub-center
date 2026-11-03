package cn.iocoder.yudao.module.dataforge.enums.access;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据同步-同步引擎类型枚举
 *
 * 同一个「数据同步」页面通过该类型区分不同引擎的任务
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum AccessEngineTypeEnum {

    /**
     * dbswitch 离线库表同步
     */
    DBSWITCH(1, "离线同步"),
    /**
     * Seatunnel 实时同步
     */
    SEATUNNEL(2, "实时同步"),
    /**
     * 外部 HTTP API 拉取入库
     */
    API_ACCESS(3, "API接入");

    private final Integer type;

    private final String name;

}
