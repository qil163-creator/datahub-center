package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcUrlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.file.core.client.s3.S3FileClient;
import cn.iocoder.yudao.framework.file.core.client.s3.S3FileClientConfig;
import cn.iocoder.yudao.framework.file.core.client.sftp.SftpFileClient;
import cn.iocoder.yudao.framework.file.core.client.sftp.SftpFileClientConfig;
import cn.iocoder.yudao.framework.mq.core.connection.ExternalMqConnectionConfig;
import cn.iocoder.yudao.framework.mq.core.connection.ExternalMqConnectionProbe;
import cn.iocoder.yudao.framework.redis.core.connection.ExternalRedisConnectionConfig;
import cn.iocoder.yudao.framework.redis.core.connection.ExternalRedisConnectionProbe;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 仅承担领域记录与组件探测参数的映射，不在业务层实现底层协议。 */
@Component
public class ConnectionOnlyDiagnosis {

    @Resource
    private DbSwitchRuntime runtime;

    /**
     * 诊断连接仅诊断。
     *
     * @param db 数据库
     * @param type 类型标识
     * @param password 密码
     * @param startedAt 已开始时间
     * @return 数据源诊断响应数据
     */
    public DatabaseDiagnosisRespVO diagnose(DatabaseDO db, DatabaseTypeDO type, String password,
            LocalDateTime startedAt) {
        return diagnose(db, type, password, startedAt, db.getJdbcUrl());
    }

    /** 诊断专用 URL 由领域服务解析，避免修改持久化快照或清空既有 ES JDBC 配置。 */
    public DatabaseDiagnosisRespVO diagnose(DatabaseDO db, DatabaseTypeDO type, String password,
            LocalDateTime startedAt, String jdbcUrl) {
        long start = System.nanoTime();
        ConnectionDiagnosticResult result;
        boolean configurationValid = false;
        try {
            ConnectionSettings settings = ConnectionSettings.readForDiagnosis(db, type);
            settings.validateCredentials(db, password);
            configurationValid = true;
            if (!ConnectionSettings.external(type)) {
                boolean elasticsearch = "ELASTICSEARCH".equals(settings.code());
                var product = elasticsearch
                        ? ProductTypeEnum.ELASTICSEARCH
                        : runtime.products().resolve(type.getTypeCode())
                                .orElseThrow(() -> new IllegalArgumentException("数据库连接器未安装"));
                var properties = new LinkedHashMap<>(settings.properties());
                properties.remove("jdbcParams");
                properties.put("org", value(db.getUserName()));
                properties.put("bucket", value(db.getDatabaseName()));
                properties.put("project", value(db.getDatabaseName()));
                String username = db.getUserName();
                if ("MILVUS".equals(settings.code()) && "TOKEN".equals(
                        settings.get("authenticationMode", "PASSWORD"))) {
                    username = null;
                }
                if (ConnectionSettings.passwordless(db, type)) {
                    username = null;
                    password = null;
                }
                result = runtime.probeConnection(new ConnectionProbeRequest(product, null,
                        elasticsearch ? JdbcUrlUtils.appendParams(product, jdbcUrl, settings.get("jdbcParams", ""))
                                : ConnectionSettings.jdbc(type) ? JdbcUrlUtils.appendParams(product, jdbcUrl,
                                        db.getExtendConfig()) : null,
                        settings.get("endpoint", ""), username, password, db.getDatabaseName(),
                        db.getDatabaseSchema(), properties, settings.timeout()));
            } else {
                testExternal(db, settings, password);
                result = externalResult("PASS", "协议连接、认证与配置的目标资源验证通过", start);
            }
        } catch (LinkageError unavailable) {
            result = externalResult("SKIPPED", "所需连接客户端未安装，请检查服务端依赖配置", start);
        } catch (IllegalArgumentException invalid) {
            result = externalResult("FAIL", "连接配置无效，请检查所选协议的必填字段", start, configurationValid);
        } catch (Exception failed) {
            // 厂商异常可能带地址和凭据；诊断边界不回显源端异常内容。
            result = externalResult("FAIL", "协议认证或目标资源检查失败，请检查服务、凭据及资源权限", start);
        }
        return DatabaseDiagnosisConverter.convertConnectionOnly(result, startedAt,
                "ELASTICSEARCH".equals(ConnectionSettings.code(type)));
    }

    /**
     * 测试外部。
     *
     * @param db 数据库
     * @param settings 处理设置
     * @param password 密码
     */
    private void testExternal(DatabaseDO db, ConnectionSettings settings, String password) throws Exception {
        Duration timeout = settings.timeout();
        switch (settings.code()) {
            case "OSS", "MINIO" -> {
                S3FileClientConfig config = new S3FileClientConfig();
                config.setEndpoint(settings.get("endpoint", ""));
                config.setBucket(db.getDatabaseName());
                config.setAccessKey(db.getUserName());
                config.setAccessSecret(password);
                config.setRegion(settings.get("region", null));
                config.setEnablePathStyleAccess(settings.flag("pathStyle", "MINIO".equals(settings.code())));
                config.setEnablePublicAccess(false);
                try (S3FileClient client = new S3FileClient(0L, config)) {
                    client.init();
                    client.testConnection(timeout);
                }
            }
            case "SFTP" -> {
                SftpFileClientConfig config = new SftpFileClientConfig();
                config.setHost(db.getDatabaseIp());
                config.setPort(Integer.parseInt(db.getDatabasePort()));
                config.setUsername(db.getUserName());
                config.setPassword(password);
                config.setBasePath(settings.get("basePath", "/"));
                config.setHostKeyFingerprint(settings.get("hostKeyFingerprint", ""));
                try (SftpFileClient client = new SftpFileClient(0L, config)) {
                    client.init();
                    client.testConnection(timeout);
                }
            }
            case "REDIS" -> {
                ExternalRedisConnectionConfig config = new ExternalRedisConnectionConfig();
                config.setHost(db.getDatabaseIp());
                config.setPort(Integer.parseInt(db.getDatabasePort()));
                config.setDatabase(Integer.parseInt(blank(db.getDatabaseName()) ? "0" : db.getDatabaseName()));
                config.setUsername(db.getUserName());
                config.setPassword(password);
                config.setSsl(settings.flag("tls", false));
                config.setTimeoutMillis((int) timeout.toMillis());
                ExternalRedisConnectionProbe.test(config);
            }
            case "KAFKA", "RABBITMQ", "ROCKETMQ" -> {
                ExternalMqConnectionConfig config = new ExternalMqConnectionConfig();
                config.setProtocol(ExternalMqConnectionConfig.Protocol.valueOf(settings.code()));
                String addresses = "RABBITMQ".equals(settings.code())
                        ? db.getDatabaseIp() + ":" + db.getDatabasePort() : settings.get("servers", "");
                if (!"RABBITMQ".equals(settings.code())) {
                    String delimiter = "ROCKETMQ".equals(settings.code()) ? ";" : ",";
                    addresses = java.util.Arrays.stream(addresses.split("[,;]", -1))
                            .map(String::trim).collect(java.util.stream.Collectors.joining(delimiter));
                }
                config.setAddresses(addresses);
                config.setUsername(db.getUserName());
                config.setPassword(password);
                if ("ROCKETMQ".equals(settings.code()) && "NONE".equals(settings.get("authenticationMode", "NONE"))) {
                    config.setUsername(null);
                    config.setPassword(null);
                }
                config.setTopic(settings.get("topic", null));
                config.setVirtualHost(settings.get("virtualHost", "/"));
                config.setSecurityProtocol(settings.get("securityProtocol", "PLAINTEXT"));
                config.setSaslMechanism(settings.get("saslMechanism", "PLAIN"));
                config.setSsl(settings.flag("tls", false));
                config.setTimeoutMillis((int) timeout.toMillis());
                ExternalMqConnectionProbe.test(config);
            }
            default -> throw new IllegalArgumentException("连接协议未接入");
        }
    }

    /**
     * 获取连接仅诊断的外部结果。
     *
     * @param status 目标状态
     * @param message 消息
     * @param start 开始位置或时间
     * @return 连接诊断结果
     */
    private static ConnectionDiagnosticResult externalResult(String status, String message, long start) {
        return externalResult(status, message, start, true);
    }

    /**
     * 获取连接仅诊断的外部结果。
     *
     * @param status 目标状态
     * @param message 消息
     * @param start 开始位置或时间
     * @param configurationValid 配置有效
     * @return 连接诊断结果
     */
    private static ConnectionDiagnosticResult externalResult(String status, String message, long start,
            boolean configurationValid) {
        long elapsed = Math.max(0, (System.nanoTime() - start) / 1_000_000);
        boolean passed = "PASS".equals(status);
        List<ConnectionDiagnosticResult.Check> checks = new ArrayList<>();
        checks.add(check("BASIC", "CONFIG", "连接配置",
                !configurationValid ? "FAIL" : "SKIPPED".equals(status) ? "SKIPPED" : "PASS",
                configurationValid ? "仅使用服务端已接入的协议与白名单配置" : message));
        checks.add(check("BASIC", "AUTH", "协议与认证", configurationValid ? status : "SKIPPED",
                configurationValid ? message : "配置检查未通过，未发起协议认证"));
        checks.add(check("TARGET", "VALIDATION", "协议响应", passed ? "PASS" : "SKIPPED",
                passed ? message : "前置检查未通过，未确认协议响应"));
        checks.add(check("TARGET", "SCHEMA", "目标资源", passed ? "PASS" : "SKIPPED",
                passed ? message : "前置检查未通过，未确认目标资源"));
        return new ConnectionDiagnosticResult(status, passed, elapsed, checks);
    }

    /**
     * 检查连接仅诊断。
     *
     * @param group 分组
     * @param key 标识键
     * @param label 标签
     * @param status 目标状态
     * @param message 消息
     * @return 检查
     */
    private static ConnectionDiagnosticResult.Check check(String group, String key, String label, String status,
            String message) {
        return new ConnectionDiagnosticResult.Check(group, key, label, status, 0, message, null, null);
    }

    /**
     * 提取连接仅诊断字段值。
     *
     * @param text 待处理文本
     * @return 提取连接仅诊断字段值文本
     */
    private static String value(String text) {
        return text == null ? "" : text;
    }

    /**
     * 判断文本是否为空或只包含空白字符。
     *
     * @param text 待处理文本
     * @return 处理是否成功
     */
    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
