package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.logging.Filter;
import java.util.logging.Logger;

/** 已知桥接缺陷：WrappedMongoClient 在构造客户端时记录含凭据的 URI。 */
final class DriverVendorLogPolicy {

    private static final String MONGO_ENTRY = "com.gitee.jdbc.mongodb.JdbcDriver";

    // 使用强引用，使此精确厂商日志器及过滤器在 JUL 的弱引用日志器注册表中保持存活。
    // 此限制贯穿进程生命周期，不是连接期间临时改变全局日志级别。
    private static final Logger MONGO_CLIENT = Logger.getLogger("com.gitee.jdbc.mongodb.wrappers.WrappedMongoClient");

    private static final Filter DENY_UNSAFE_BRIDGE_MESSAGES = record -> false;

    /**
     * 初始化 {@code DriverVendorLogPolicy}。
     */
    private DriverVendorLogPolicy() {
    }

    /**
     * 在组件初始化前完成必要的注册与环境检查。
     */
    static synchronized void beforeInitialization(ProductTypeEnum product, String entry) {
        if (product == ProductTypeEnum.MONGODB && MONGO_ENTRY.equals(entry)
                && MONGO_CLIENT.getFilter() != DENY_UNSAFE_BRIDGE_MESSAGES) {
            MONGO_CLIENT.setFilter(DENY_UNSAFE_BRIDGE_MESSAGES);
        }
    }
}
