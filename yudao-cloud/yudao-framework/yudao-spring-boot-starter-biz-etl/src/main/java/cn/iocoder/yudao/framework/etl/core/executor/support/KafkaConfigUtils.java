package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;

import java.util.Properties;

/**
 * Kafka 节点公共配置：连接与 SASL 安全参数
 *
 * @author Li
 */
public class KafkaConfigUtils {

    /**
     * 初始化 {@code KafkaConfigUtils}。
     */
    private KafkaConfigUtils() {
    }

    /**
     * 从节点配置组装 Kafka 客户端安全参数
     */
    public static Properties buildSecurityProps(JSONObject config) {
        Properties props = new Properties();
        String securityProtocol = config.getStr("securityProtocol", "PLAINTEXT");
        if (!"PLAINTEXT".equalsIgnoreCase(securityProtocol)) {
            props.put("security.protocol", securityProtocol);
            String mechanism = config.getStr("saslMechanism", "PLAIN");
            props.put("sasl.mechanism", mechanism);
            String username = config.getStr("saslUsername", "");
            String password = config.getStr("saslPassword", "");
            if (StrUtil.isNotBlank(username)) {
                String module = "SCRAM-SHA-256".equalsIgnoreCase(mechanism)
                        || "SCRAM-SHA-512".equalsIgnoreCase(mechanism)
                        ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                        : "org.apache.kafka.common.security.plain.PlainLoginModule";
                props.put("sasl.jaas.config", String.format(
                        "%s required username=\"%s\" password=\"%s\";", module, username, password));
            }
        }
        return props;
    }

}
