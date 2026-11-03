package cn.iocoder.yudao.module.governance.enums;

import cn.iocoder.yudao.framework.common.enums.RpcConstants;

/**
 * API 相关的枚举
 *
 * @author Li
 */
public class ApiConstants {

    /**
     * 服务名
     *
     * 注意，需要保证和 spring.application.name 保持一致
     */
    public static final String NAME = "governance-server";

    public static final String PREFIX = RpcConstants.RPC_API_PREFIX + "/governance";

    public static final String VERSION = "1.0.0";

}
