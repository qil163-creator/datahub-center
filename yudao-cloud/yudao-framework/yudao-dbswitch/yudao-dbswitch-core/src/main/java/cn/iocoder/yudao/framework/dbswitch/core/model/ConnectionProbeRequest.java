package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** 独立测连及 SDK 只读目录的连接参数；秘密仅通过 password 传入，不参与日志和字符串输出。 */
public record ConnectionProbeRequest(ProductTypeEnum product, String profileId, String jdbcUrl, String endpoint,
                                     String username, String password, String database, String schema,
                                     Map<String, String> properties, Duration timeout) {

    /**
     * 校验构造参数并初始化 {@code ConnectionProbeRequest}。
     */
    public ConnectionProbeRequest {
        Objects.requireNonNull(product, "product");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("测连超时必须介于 1 到 30 秒");
        }
    }

    /**
     * 读取必需属性，缺失时拒绝继续处理。
     */
    public String requiredProperty(String name) {
        String value = properties.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少测连配置：" + name);
        }
        return value;
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "ConnectionProbeRequest[product=" + product + "]";
    }
}
