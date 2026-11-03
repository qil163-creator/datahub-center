package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 主数据派发配置（按派发类型只填对应节点，整体序列化为 JSON 落库）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 主数据派发配置 VO")
@Data
public class MasterDistributeConfigVO {

    @Schema(description = "数据库派发配置（distributeType=1）")
    private DbConfig db;

    @Schema(description = "API 派发配置（distributeType=2）")
    private ApiConfig api;

    @Schema(description = "消息队列派发配置（distributeType=3）")
    private MqConfig mq;

    @Schema(description = "数据库派发配置")
    @Data
    public static class DbConfig {

        @Schema(description = "目标数据源编号", example = "2")
        private Long databaseId;

        @Schema(description = "目标表名（空表示与源表同名）", example = "ods_md_person")
        private String targetTableName;

        @Schema(description = "同步前是否删除重建目标表", example = "false")
        private Boolean targetDrop;

        @Schema(description = "目标表已存在时是否继续同步", example = "true")
        private Boolean syncExist;

        @Schema(description = "仅创建表结构，不同步数据", example = "false")
        private Boolean onlyCreate;

        @Schema(description = "目标表已存在时按主键做变化量同步", example = "true")
        private Boolean changeDataSync;

        @Schema(description = "批次大小", example = "1000")
        private Integer fetchSize;

    }

    @Schema(description = "API 派发配置")
    @Data
    public static class ApiConfig {

        private String method;

        private String authType;

        @lombok.ToString.Exclude
        private String token;

        private Boolean tokenConfigured;

        private Integer timeoutSeconds;

        private String pushScope;

        private String statusField;

        private String enabledValue;

        private String pushMode;

        private Boolean retryEnabled;

        private Integer retryCount;

        private Integer retryIntervalMinutes;

        private String exhaustedAction;

        private List<FieldMapping> mappings;

        @Schema(description = "推送地址（POST，JSON 数组请求体）", example = "https://consumer.example.com/master/person")
        private String url;

        @Schema(description = "请求头")
        private List<KeyValue> headers;

        @Schema(description = "查询参数")
        private List<KeyValue> params;

        @Schema(description = "单次请求记录数", example = "100")
        private Integer fetchSize;

    }

    @Data
    public static class FieldMapping {

        private String sourceField;

        private String targetField;

        private Boolean enabled;

        private String transform;

        private String defaultValue;
    }

    @Schema(description = "消息队列派发配置")
    @Data
    public static class MqConfig {

        /** kafka / rabbitmq */
        @Schema(description = "队列类型：kafka / rabbitmq", example = "kafka")
        private String mqType;

        @Schema(description = "Kafka 配置")
        private KafkaConfig kafka;

        @Schema(description = "RabbitMQ 配置")
        private RabbitConfig rabbit;

        @Schema(description = "单条消息记录数", example = "100")
        private Integer fetchSize;

    }

    @Schema(description = "Kafka 配置")
    @Data
    public static class KafkaConfig {

        @Schema(description = "bootstrap.servers", example = "127.0.0.1:9092")
        private String bootstrapServers;

        @Schema(description = "Topic", example = "master-person")
        private String topic;

    }

    @Schema(description = "RabbitMQ 配置")
    @Data
    public static class RabbitConfig {

        @Schema(description = "主机", example = "127.0.0.1")
        private String host;

        @Schema(description = "端口", example = "5672")
        private Integer port;

        @Schema(description = "用户名", example = "admin")
        private String username;

        @Schema(description = "密码")
        private String password;

        @Schema(description = "虚拟主机", example = "/")
        private String virtualHost;

        @Schema(description = "交换机", example = "master.exchange")
        private String exchange;

        @Schema(description = "路由键", example = "person")
        private String routingKey;

    }

    @Schema(description = "键值对")
    @Data
    public static class KeyValue {

        @Schema(description = "键", example = "Authorization")
        private String key;

        @Schema(description = "值", example = "Bearer xxx")
        @lombok.ToString.Exclude
        private String value;

    }

}
