package cn.iocoder.yudao.module.dataforge.service.access.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

/** 仅用于部署配置的授权与密钥；绝不复制到任务、运行 JSON 或响应中。 */
@Component
@ConfigurationProperties(prefix = "yudao.dataforge.access.group-rewrite")
public class AccessGroupRewriteProperties {

    private boolean enabled;

    private String currentKeyId;

    private Map<String, String> keyring = Map.of();

    /** 任务编号授权声明外部 DML/DDL 已完全结束；租约时长绝不能作为这一事实的凭据。 */
    private Set<String> exclusiveTasks = Set.of();

    private String controlSchema = "datahub-center";

    private int transactionTimeoutSeconds = 10;

    /**
     * 返回同步表组重写的启用状态。
     *
     * @return 判断结果
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置同步表组重写的启用状态。
     *
     * @param value 待处理的值
     */
    public void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * 返回同步表组重写的当前键编号。
     *
     * @return 当前键编号
     */
    public String getCurrentKeyId() {
        return currentKeyId;
    }

    /**
     * 设置同步表组重写的当前键编号。
     *
     * @param value 待处理的值
     */
    public void setCurrentKeyId(String value) {
        currentKeyId = value;
    }

    /**
     * 读取密钥环。
     *
     * @return 密钥环
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Map<String, String> getKeyring() {
        return Map.copyOf(keyring);
    }

    /**
     * 设置密钥环。
     *
     * @param value 待处理的值
     */
    public void setKeyring(Map<String, String> value) {
        keyring = value == null ? Map.of() : Map.copyOf(value);
    }

    /**
     * 读取排他任务。
     *
     * @return 排他任务
     */
    public Set<String> getExclusiveTasks() {
        return Set.copyOf(exclusiveTasks);
    }

    /**
     * 设置排他任务。
     *
     * @param value 待处理的值
     */
    public void setExclusiveTasks(Set<String> value) {
        exclusiveTasks = value == null ? Set.of() : Set.copyOf(value);
    }

    /**
     * 返回同步表组重写的控制结构。
     *
     * @return 控制结构
     */
    public String getControlSchema() {
        return controlSchema;
    }

    /**
     * 设置同步表组重写的控制结构。
     *
     * @param value 待处理的值
     */
    public void setControlSchema(String value) {
        controlSchema = value;
    }

    /**
     * 返回同步表组重写的事务超时秒数。
     *
     * @return 事务超时秒数
     */
    public int getTransactionTimeoutSeconds() {
        return transactionTimeoutSeconds;
    }

    /**
     * 设置同步表组重写的事务超时秒数。
     *
     * @param value 待处理的值
     */
    public void setTransactionTimeoutSeconds(int value) {
        transactionTimeoutSeconds = value;
    }

    /**
     * 校验同步表组重写所需的访问权限。
     *
     * @param task 任务记录
     * @return 部署
     */
    public Deployment authorize(long task) {
        if (!enabled || !exclusiveTasks.contains(Long.toString(task))) {
            throw new IllegalArgumentException("TDengine 组替换未获部署级目标独占授权");
        }
        if (controlSchema == null || !controlSchema.matches("[a-z][a-z0-9_-]{0,62}")
                || transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 60
                || keyring.isEmpty() || keyring.size() > 16 || !keyring.containsKey(currentKeyId)) {
            throw invalid();
        }
        var keys = new LinkedHashMap<String, SecretKey>();
        try {
            keyring.forEach((id, value) -> {
                if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw invalid();
                }
                byte[] bytes = Base64.getDecoder().decode(value);
                try {
                    if (bytes.length != 32) {
                        throw invalid();
                    }
                    keys.put(id, new SecretKeySpec(bytes, "AES"));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            });
        } catch (RuntimeException invalid) {
            throw invalid();
        }
        return new Deployment(controlSchema, currentKeyId, Map.copyOf(keys), transactionTimeoutSeconds);
    }

    public record Deployment(String schema, String currentKeyId, Map<String, SecretKey> keys, int timeout) {

        /**
         * 创建部署实例。
         *
         * @param schema 结构
         * @param currentKeyId 当前键编号
         * @param keys 键
         * @param timeout 超时
         */
        public Deployment {
            keys = Map.copyOf(keys);
        }

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessGroupRewriteDeployment[configured=true]";
        }
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @return 非法参数异常
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("TDengine 组替换部署配置无效");
    }

    /**
     * 返回当前对象的字符串表示，供日志或展示使用。
     *
     * @return 当前对象的字符串表示，供日志或展示使用文本
     */
    @Override
    public String toString() {
        return "AccessGroupRewriteProperties[enabled=" + enabled + "]";
    }
}
