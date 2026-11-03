package cn.iocoder.yudao.framework.dbswitch.common.util;

import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * UUID工具类
 */
@UtilityClass
public final class UuidUtils {

    /**
     * 生成随机 UUID 标识。
     */
    public static String generateUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

}
