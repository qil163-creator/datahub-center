package cn.iocoder.yudao.module.dataforge.enums.database;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据源连通状态：最近一次连接测试结果
 */
@Getter
@AllArgsConstructor
public enum DatabaseConnectionStatusEnum {

    UNTESTED(0, "未检测"),
    ONLINE(1, "在线"),
    OFFLINE(2, "离线");

    private final Integer status;

    private final String name;

}
