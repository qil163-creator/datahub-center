package cn.iocoder.yudao.module.asset.enums;

import cn.iocoder.yudao.framework.common.enums.RpcConstants;

public final class ApiConstants {

    public static final String NAME = "asset-server";

    public static final String PREFIX = RpcConstants.RPC_API_PREFIX + "/asset";

    /**
     * 限制API常量工具类的直接实例化。
     */
    private ApiConstants() {
    }
}
