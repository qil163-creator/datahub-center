package cn.iocoder.yudao.module.dataforge.framework.push.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据推送运行时配置：执行器容量、日志、水位、HTTP 安全与默认超时
 *
 * @author Li
 */
@ConfigurationProperties(prefix = "yudao.dataforge.push")
@Data
public class DataPushProperties {

    /**
     * 实时日志最大字符数，超出保留尾部
     */
    private int maxLogChars = 32_000;

    /**
     * 增量水位 start/end 最大字符数，须与库字段 varchar(256) 对齐
     */
    private int watermarkMaxLength = 256;

    /**
     * Blob / byte[] 超过该字节数则省略，只记长度
     */
    private int maxLobBytes = 65_536;

    /**
     * 字符串 / Clob 超过该字符数则截断
     */
    private int maxStringChars = 16_384;

    /**
     * 单批 JSON 估算上限（字节），超出则提前刷盘
     */
    private int maxBatchBytes = 2_097_152;

    /**
     * 单批最大行数上限（与任务 batchSize 取较小值）
     */
    private int maxBatchSize = 1_000;

    /**
     * 每个任务保留的最近执行记录数，超出删除更早的。0 表示不清理
     */
    private int logRetain = 50;

    /**
     * 连续失败达到该次数时发站内信。0 表示不告警
     */
    private int alertFailCount = 3;

    /**
     * 运行中日志心跳间隔（毫秒）
     */
    private int heartbeatIntervalMs = 30_000;

    /**
     * 旧协议心跳回收分钟数；新协议按领取期限判断，发送后只转未知。0 关闭旧协议回收
     */
    private int staleRunningMinutes = 10;

    /** 新协议领取期限（秒），实际值至少为心跳间隔三倍和 30 秒。 */
    private int dispatchLeaseSeconds = 600;

    /** 发送前队列拒绝后的退避秒数；不涉及外部发送重试。 */
    private int dispatchRetrySeconds = 30;

    /** 同一持久意图最多领取次数，耗尽时发送前失败留痕。 */
    private int dispatchMaxAttempts = 5;

    /**
     * 试跑预览最多返回行数
     */
    private int previewMaxRows = 50;

    /**
     * 队列满告警最小间隔（分钟）。同一任务在间隔内只告警一次。0 表示不告警
     */
    private int queueAlertIntervalMinutes = 10;

    private final Executor executor = new Executor();

    private final Http http = new Http();

    @Data
    public static class Executor {

        private int coreSize = 4;

        private int maxSize = 16;

        private int queueSize = 32;
    }

    @Data
    public static class Http {

        /**
         * 允许的主机。支持精确匹配和 {@code *.example.com}。
         * 为空时允许公网地址，并拦截回环 / 私网 / 链路本地 / 云元数据。
         * 非空时主机必须命中白名单；命中后允许解析到内网（便于推到内网消费方）。
         */
        private List<String> allowedHosts = new ArrayList<>();

        /**
         * 白名单为空时是否仍允许私网地址。生产环境请保持 false，或改用 allowed-hosts。
         */
        private boolean allowPrivate = false;

        /**
         * 通道未单独配置时的默认请求超时（毫秒）
         */
        private int timeoutMs = 30_000;

        /**
         * 通道未单独配置时的默认重试次数（不含首次）
         */
        private int retryTimes = 2;

        /**
         * 重试间隔（毫秒）
         */
        private int retryIntervalMs = 1_000;
    }

}
