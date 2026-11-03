package cn.iocoder.yudao.framework.mq.core.connection;

import lombok.Data;
import lombok.ToString;

/** 独立外部消息服务的连接参数；仅用于协议连通性验证。 */
@Data
public class ExternalMqConnectionConfig {
    public enum Protocol { KAFKA, RABBITMQ, ROCKETMQ }

    private Protocol protocol;
    private String addresses;
    private String username;
    @ToString.Exclude
    private String password;
    private String topic;
    private String virtualHost = "/";
    private String securityProtocol = "PLAINTEXT";
    private String saslMechanism = "PLAIN";
    private boolean ssl;
    private int timeoutMillis = 10000;
}
