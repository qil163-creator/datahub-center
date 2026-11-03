package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbe;

import java.sql.Connection;
import java.sql.SQLException;

/** 共享既有原生协议探测，独立连接能力不构造元数据工厂。 */
public final class ElasticsearchConnectionProbe implements ConnectionProbe {

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    @Override
    public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
        var result = diagnose(request, opener);
        if (!"PASS".equals(result.status())) {
            throw new SQLException("Elasticsearch 分项探测失败；请读取诊断结果中的安全证据");
        }
    }

    /** 生产 SPI 返回分项证据；后续索引拒绝不能抹去已取得的会话与逻辑资源响应。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult diagnose(
            ConnectionProbeRequest request, JdbcOpener opener) {
        long started = System.nanoTime();
        var checks = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check>();
        boolean connected = false;
        boolean logical = false;
        String index = request.properties().get("index");
        int timeout = (int) request.timeout().toSeconds();
        try (var connection = opener.open()) {
            connected = stage(checks, "BASIC", "AUTH", "连接与服务器响应", () -> probe(connection, timeout));
            logical = connected && stage(checks, "TARGET", "SCHEMA", "逻辑命名空间", () -> {
                if (request.database() != null && !request.database().isBlank() && !"es".equals(request.database())
                        || request.schema() != null && !request.schema().isBlank() && !"es".equals(request.schema())) {
                    throw new IllegalArgumentException("逻辑命名空间必须为 es");
                }
            });
            if (!connected) { skipped(checks, "TARGET", "SCHEMA", "前置会话检查未成功", "PREREQUISITE_FAILED"); }
            var product = new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseElasticsearchImpl();
            if (logical && index != null && !index.isBlank()) {
                boolean mapping = stage(checks, "METADATA", "INDEX_MAPPING", "精确索引 mapping", () ->
                        product.probeIndexMapping(connection, index, timeout));
                if (mapping) {
                    stage(checks, "TARGET", "INDEX_PRIVILEGES", "索引读取与元数据权限", () ->
                            product.probeIndexPrivileges(connection, index, timeout));
                } else {
                    skipped(checks, "TARGET", "INDEX_PRIVILEGES", "索引 mapping 未成功", "PREREQUISITE_FAILED");
                }
            } else {
                String reason = logical ? "未指定物理索引，仅证明逻辑资源响应" : "前置逻辑资源检查未成功";
                String code = logical ? "UNVERIFIED" : "PREREQUISITE_FAILED";
                skipped(checks, "METADATA", "INDEX_MAPPING", reason, code);
                skipped(checks, "TARGET", "INDEX_PRIVILEGES", reason, code);
            }
        } catch (Exception failure) {
            boolean authenticationRecorded = checks.stream().anyMatch(check -> "AUTH".equals(check.key()));
            if (authenticationRecorded) {
                checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                        "ADVANCED", "CLEANUP", "诊断连接关闭", "WARN", 0,
                        "诊断连接关闭未得到确认，需检查资源回收", safeState(failure),
                        failure instanceof SQLException sql ? sql.getErrorCode() : null, null));
            } else {
                failure(checks, "BASIC", "AUTH", failure);
            }
        }
        boolean failed = checks.stream().anyMatch(check -> "FAIL".equals(check.status()));
        boolean warned = checks.stream().anyMatch(check -> "WARN".equals(check.status()));
        return new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult(failed ? "FAIL" : warned ? "WARN" : "PASS",
                connected && logical, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checks);
    }

    /** 只返回固定安全描述与稳定 SQL 状态，不泄漏端点、凭据或厂商原始消息。 */
    private static boolean stage(java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks,
            String group, String key, String label, Checked action) {
        long started = System.nanoTime();
        try {
            action.run();
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(group, key, label,
                    "PASS", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    "AUTH".equals(key) ? "已取得服务器身份响应；不代表任意索引读取授权" : "已取得本项只读证据", null, null));
            return true;
        } catch (Exception failure) {
            failure(checks, group, key, failure);
            return false;
        }
    }

    private static void failure(java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks,
            String group, String key, Exception failure) {
        String state = safeState(failure);
        Integer vendor = failure instanceof SQLException sql ? sql.getErrorCode() : null;
        checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(group, key, key,
                "FAIL", 0, "本项产品探测未取得完整授权或有效响应", state, vendor, null));
    }

    /** 只保留标准五位大写 SQLState，驱动可将任意原文放入该字段。 */
    private static String safeState(Exception failure) {
        String state = failure instanceof SQLException sql ? sql.getSQLState() : null;
        return state != null && state.matches("[A-Z0-9]{5}") ? state : null;
    }

    private static void skipped(java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks,
            String group, String key, String message, String reason) {
        checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(group, key, key,
                "SKIPPED", 0, message, null, null, reason));
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    public static void probe(Connection connection, int timeoutSeconds) throws SQLException {
        var identity = ElasticsearchNativeSession.open(connection, 64 * 1024, timeoutSeconds)
                .request("GET", "/", null).requireSuccess();
        if (!identity.path("version").path("number").isTextual() || !identity.path("cluster_name").isTextual()) {
            throw new java.sql.SQLException("Elasticsearch 探测未返回服务器身份");
        }
    }
}
