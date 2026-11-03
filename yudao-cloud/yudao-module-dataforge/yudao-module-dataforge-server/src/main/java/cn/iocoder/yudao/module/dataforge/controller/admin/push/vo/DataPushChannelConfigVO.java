package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 数据推送通道配置（按类型只填对应节点）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 数据推送通道配置")
@Data
public class DataPushChannelConfigVO {

    @Schema(description = "HTTP 配置")
    private HttpConfig http;

    @Schema(description = "数据库配置")
    private DbConfig db;

    @Schema(description = "消息队列配置")
    private MqConfig mq;

    @Schema(description = "HTTP 配置")
    @Data
    public static class HttpConfig {

        @Schema(description = "推送地址", example = "https://consumer.example.com/push")
        private String url;

        @Schema(description = "请求头")
        private List<KeyValue> headers;

        @Schema(description = "查询参数")
        private List<KeyValue> params;

        @Schema(description = "请求超时（毫秒），空则用服务默认")
        private Integer timeoutMs;

        @Schema(description = "失败重试次数（不含首次），空则用服务默认")
        private Integer retryTimes;

        @Schema(description = "重试间隔（毫秒），空则用服务默认")
        private Integer retryIntervalMs;

        @Schema(description = "请求体是否 gzip")
        private Boolean gzip;

        @Schema(description = "信任库绝对路径（JKS / PKCS12）")
        private String truststorePath;

        @Schema(description = "信任库密码")
        private String truststorePassword;

        @Schema(description = "客户端证书库绝对路径（mTLS）")
        private String keystorePath;

        @Schema(description = "客户端证书库密码")
        private String keystorePassword;
    }

    @Schema(description = "数据库配置")
    @Data
    public static class DbConfig {

        @Schema(description = "目标数据源编号", example = "2")
        private Long databaseId;

        @Schema(description = "目标表名（空则用对象名/源表名）", example = "ods_person")
        private String targetTableName;

        @Schema(description = "同步前是否删除重建目标表", example = "false")
        private Boolean targetDrop;

        @Schema(description = "目标表已存在时是否继续同步", example = "true")
        private Boolean syncExist;

        @Schema(description = "目标表已存在时按主键做变化量同步", example = "true")
        private Boolean changeDataSync;

        @Schema(description = "显式写入模式 CREATE_NEW/STRICT_COPY，空表示历史兼容")
        private String writeMode;

        @Schema(description = "源 schema")
        private String sourceSchema;

        @Schema(description = "目标 schema")
        private String targetSchema;
    }

    @Schema(description = "消息队列配置")
    @Data
    public static class MqConfig {

        @Schema(description = "队列类型：kafka / rabbitmq", example = "kafka")
        private String mqType;

        @Schema(description = "Kafka 配置")
        private KafkaConfig kafka;

        @Schema(description = "RabbitMQ 配置")
        private RabbitConfig rabbit;
    }

    @Schema(description = "Kafka 配置")
    @Data
    public static class KafkaConfig {

        @Schema(description = "bootstrap.servers", example = "127.0.0.1:9092")
        private String bootstrapServers;

        @Schema(description = "Topic", example = "data-push")
        private String topic;

        @Schema(description = "安全协议：PLAINTEXT / SASL_PLAINTEXT / SASL_SSL / SSL", example = "PLAINTEXT")
        private String securityProtocol;

        @Schema(description = "SASL 机制：PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512")
        private String saslMechanism;

        @Schema(description = "SASL 用户名")
        private String saslUsername;

        @Schema(description = "SASL 密码")
        private String saslPassword;

        @Schema(description = "acks：默认 all（幂等重试）；1 仅兼容旧配置且不重试；禁止 0", example = "all")
        private String acks;

        @Schema(description = "信任库绝对路径（SSL / SASL_SSL）")
        private String truststorePath;

        @Schema(description = "信任库密码")
        private String truststorePassword;

        @Schema(description = "客户端证书库绝对路径（mTLS）")
        private String keystorePath;

        @Schema(description = "客户端证书库密码")
        private String keystorePassword;

        @Schema(description = "是否关闭主机名校验（仅内网自签证书）")
        private Boolean disableHostnameVerification;
    }

    @Schema(description = "RabbitMQ 配置")
    @Data
    public static class RabbitConfig {

        @Schema(description = "主机", example = "127.0.0.1")
        private String host;

        @Schema(description = "端口", example = "5672")
        private Integer port;

        @Schema(description = "用户名")
        private String username;

        @Schema(description = "密码")
        private String password;

        @Schema(description = "虚拟主机", example = "/")
        private String virtualHost;

        @Schema(description = "交换机")
        private String exchange;

        @Schema(description = "路由键")
        private String routingKey;

        @Schema(description = "是否启用 TLS（AMQPS）")
        private Boolean useSsl;

        @Schema(description = "信任库绝对路径")
        private String truststorePath;

        @Schema(description = "信任库密码")
        private String truststorePassword;

        @Schema(description = "客户端证书库绝对路径（mTLS）")
        private String keystorePath;

        @Schema(description = "客户端证书库密码")
        private String keystorePassword;
    }

    @Schema(description = "键值对")
    @Data
    public static class KeyValue {

        @Schema(description = "键")
        private String key;

        @Schema(description = "值")
        private String value;
    }

}
