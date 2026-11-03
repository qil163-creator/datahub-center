package cn.iocoder.yudao.framework.dbswitch.product.neo4j;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbe;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbeException;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.Neo4jException;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bolt 握手与认证后，在指定数据库执行常量返回，不读取业务节点。 */
public final class Neo4jConnectionProbe implements ConnectionProbe {

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    @Override
    public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
        execute(request, new java.util.ArrayList<>());
    }

    /** 与传统测连共用 Driver/Session，只返回实际完成的分项证据。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult diagnose(
            ConnectionProbeRequest request, JdbcOpener opener) {
        long started = System.nanoTime();
        var checks = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check>();
        try {
            execute(request, checks);
        } catch (Exception failure) {
            String key = switch (checks.size()) { case 0 -> "AUTH"; case 1 -> "TARGET"; case 2 -> "CURRENT_USER"; default -> "CLEANUP"; };
            String group = switch (key) { case "AUTH" -> "BASIC"; case "TARGET" -> "TARGET"; case "CURRENT_USER" -> "METADATA"; default -> "ADVANCED"; };
            String reason = failure instanceof ConnectionProbeException classified ? classified.reason().name() : "PROTOCOL_ERROR";
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    group, key, key, "CLEANUP".equals(key) ? "WARN" : "FAIL", 0,
                    "未取得本项完整响应；不暴露厂商原始消息", null, null, reason));
        }
        for (String key : java.util.List.of("AUTH", "TARGET", "CURRENT_USER")) {
            if (checks.stream().noneMatch(c -> key.equals(c.key()))) {
                skipped(checks, "AUTH".equals(key) ? "BASIC" : "TARGET".equals(key) ? "TARGET" : "METADATA",
                        key, "前置协议或目标检查未成功，未执行本项", "PREREQUISITE_FAILED");
            }
        }
        skipped(checks, "READ", "GRAPH_READ", "常量返回与账号目录不是业务节点读取授权证据", "UNVERIFIED");
        skipped(checks, "WRITE", "WRITE", "未收到显式写入授权，不创建节点或修改图", "NOT_AUTHORIZED");
        skipped(checks, "METADATA", "COMMENTS", "没有关系表列的原生 COMMENT 目录", "NOT_APPLICABLE");
        skipped(checks, "ADVANCED", "CDC", "未验证版本、许可及 CDC 权限，不启动变更流", "UNVERIFIED");
        boolean failed = checks.stream().anyMatch(c -> "FAIL".equals(c.status()));
        boolean warned = checks.stream().anyMatch(c -> "WARN".equals(c.status()));
        boolean connected = checks.stream().anyMatch(c -> "TARGET".equals(c.key()) && "PASS".equals(c.status()));
        return new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult(failed ? "FAIL" : warned ? "WARN" : "PASS",
                connected, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checks);
    }

    private static void passed(java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks,
            String group, String key, String message) {
        checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(group, key, key, "PASS", 0, message, null, null));
    }

    private static void skipped(java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks,
            String group, String key, String message, String reason) {
        checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(group, key, key, "SKIPPED", 0, message, null, null, reason));
    }

    private void execute(ConnectionProbeRequest request,
            java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks) throws Exception {
        URI uri = endpoint(request.endpoint());
        if (request.database() == null || request.database().isBlank()) {
            throw new IllegalArgumentException("缺少 Neo4j 数据库");
        }
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("缺少 Neo4j 用户名或密码");
        }
        var config = Config.builder().withConnectionTimeout(request.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .withConnectionAcquisitionTimeout(request.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .withMaxTransactionRetryTime(0, TimeUnit.MILLISECONDS).withMaxConnectionPoolSize(1)
                .withEventLoopThreads(1).withFetchSize(1).withTelemetryDisabled(true)
                .withLogging(Logging.none()).build();
        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(request.username(), request.password()), config)) {
            driver.verifyConnectivity();
            passed(checks, "BASIC", "AUTH", "Bolt 认证握手与服务器响应成功，不推断任意图授权");
            var sessionConfig = SessionConfig.builder().withDatabase(request.database())
                    .withDefaultAccessMode(AccessMode.READ).build();
            try (var session = driver.session(sessionConfig)) {
                var result = session.run("RETURN 1 AS connected",
                        TransactionConfig.builder().withTimeout(request.timeout()).build());
                if (result.single().get("connected").asInt() != 1) {
                    throw new IllegalStateException("Neo4j 目标探测失败");
                }
                passed(checks, "TARGET", "TARGET", "指定数据库已执行只读常量返回");
                var identity = session.run("SHOW CURRENT USER YIELD user RETURN user",
                        TransactionConfig.builder().withTimeout(request.timeout()).build());
                if (identity.single().get("user").asString().isBlank()) {
                    throw new IllegalStateException("Neo4j 未返回当前账号元数据");
                }
                passed(checks, "METADATA", "CURRENT_USER", "已读取当前账号目录，未推断图数据权限");
            }
        } catch (RuntimeException failure) {
            throw classify(failure);
        }
    }

    /**
     * 返回当前端点。
     */
    private static URI endpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Neo4j 地址格式不合法");
        }
        if (uri.getScheme() == null || !Set.of("bolt", "bolt+s", "neo4j", "neo4j+s").contains(uri.getScheme())
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getRawAuthority().endsWith(":")
                || uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) {
            throw new IllegalArgumentException("Neo4j 需要合法的 Bolt 或 Neo4j 地址，数据库请单独填写");
        }
        return uri;
    }

    /** 仅根据稳定错误码与异常类型分类，厂商消息可能含地址或账号，不直接返回。 */
    private static Exception classify(RuntimeException failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof SSLException || cause instanceof CertificateException) {
                return new ConnectionProbeException(ConnectionProbeException.Reason.TLS, failure);
            }
            if (cause instanceof Neo4jException vendor) {
                String code = vendor.code();
                if (code != null && Set.of("Neo.ClientError.Security.Unauthorized",
                        "Neo.ClientError.Security.CredentialsExpired",
                        "Neo.ClientError.Security.AuthenticationRateLimit").contains(code)) {
                    return new ConnectionProbeException(ConnectionProbeException.Reason.AUTHENTICATION, failure);
                }
                if ("Neo.ClientError.Security.Forbidden".equals(code)) {
                    return new ConnectionProbeException(ConnectionProbeException.Reason.AUTHORIZATION, failure);
                }
                if ("Neo.ClientError.Database.DatabaseNotFound".equals(code)) {
                    return new ConnectionProbeException(ConnectionProbeException.Reason.TARGET_NOT_FOUND, failure);
                }
            }
        }
        return failure;
    }
}
