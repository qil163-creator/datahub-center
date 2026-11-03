package cn.iocoder.yudao.module.modeling.enums;

import cn.iocoder.yudao.framework.common.enums.RpcConstants;

/**
 * 数据建模 API 相关的枚举
 *
 * @author Li
 */
public class ApiConstants {

    /**
     * 服务名，需要保证和 spring.application.name 保持一致
     */
    public static final String NAME = "modeling-server";

    public static final String PREFIX = RpcConstants.RPC_API_PREFIX + "/modeling";

    public static final String VERSION = "1.0.0";

}
