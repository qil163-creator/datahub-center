package cn.iocoder.yudao.framework.dbswitch.product.influxdb2;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbe;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbeException;
import cn.iocoder.yudao.framework.dbswitch.spi.HttpConnectionProbeSupport;

import java.io.IOException;

/** 通过 Token 探测组织与桶，拒绝把无需认证的 health 成功当成连接成功。 */
public final class Influxdb2ConnectionProbe implements ConnectionProbe {

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    @Override
    public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
        execute(request, new java.util.ArrayList<>());
    }

    /** 沿用同一 Token/组织/桶协议路径，后续拒绝不会覆盖前序授权响应。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult diagnose(
            ConnectionProbeRequest request, JdbcOpener opener) {
        long started = System.nanoTime();
        var checks = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check>();
        try {
            execute(request, checks);
        } catch (Exception failure) {
            String key = checks.isEmpty() ? "AUTH" : checks.size() == 1 ? "ORGANIZATION" : "BUCKET";
            String reason = failure instanceof ConnectionProbeException classified ? classified.reason().name()
                    : failure instanceof IllegalArgumentException ? "CONFIGURATION" : "PROTOCOL_ERROR";
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    "AUTH".equals(key) ? "BASIC" : "TARGET", key, key, "FAIL", 0,
                    "未取得完整组织或桶授权证据", null, null, reason));
        }
        for (String key : java.util.List.of("AUTH", "ORGANIZATION", "BUCKET", "BUCKET_METADATA")) {
            if (checks.stream().noneMatch(c -> key.equals(c.key()))) {
                skipped(checks, "AUTH".equals(key) ? "BASIC" : "BUCKET_METADATA".equals(key) ? "METADATA" : "TARGET",
                        key, "前置组织或桶授权检查未成功，未取得本项证据", "PREREQUISITE_FAILED");
            }
        }
        skipped(checks, "READ", "QUERY", "桶目录可见不代表 Flux 数据查询权限；未执行数据查询", "UNVERIFIED");
        skipped(checks, "WRITE", "WRITE", "未收到显式写入授权，不创建桶或写入数据", "NOT_AUTHORIZED");
        skipped(checks, "METADATA", "COMMENTS", "此产品没有原生表列 COMMENT 目录", "NOT_APPLICABLE");
        skipped(checks, "ADVANCED", "CDC", "未验证产品变更订阅或持续消费能力", "UNVERIFIED");
        boolean failed = checks.stream().anyMatch(c -> "FAIL".equals(c.status()));
        boolean connected = checks.stream().anyMatch(c -> "BUCKET".equals(c.key()) && "PASS".equals(c.status()));
        return new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult(failed ? "FAIL" : "PASS",
                connected, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checks);
    }

    private static void passed(java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks,
            String group, String key, String message) {
        if (checks.stream().noneMatch(c -> key.equals(c.key()))) {
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    group, key, key, "PASS", 0, message, null, null));
        }
    }

    private static void skipped(java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks,
            String group, String key, String message, String reason) {
        checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                group, key, key, "SKIPPED", 0, message, null, null, reason));
    }

    private void execute(ConnectionProbeRequest request,
            java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks) throws Exception {
        if (request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("缺少 InfluxDB Token");
        }
        String org = request.requiredProperty("org");
        String bucket = request.requiredProperty("bucket");
        String auth = "Token " + request.password();
        // 优先按名称解析，避免把恰好为 16 位十六进制的组织名称误当成 ID。
        String orgId;
        try {
            orgId = resolveOrganization(request, org, auth);
            passed(checks, "BASIC", "AUTH", "Token 携带请求已获服务器授权响应，不推断任意桶数据权限");
            if (orgId != null && !orgId.isBlank()) {
                passed(checks, "TARGET", "ORGANIZATION", "已从组织目录确认组织身份");
            }
        } catch (ConnectionProbeException failure) {
            if (failure.reason() != ConnectionProbeException.Reason.TARGET_NOT_FOUND) {
                throw failure;
            }
            orgId = null;
        }
        if (orgId == null && org.matches("[0-9a-fA-F]{16}")) {
            // 限权 Token 可能看不到组织列表；后续桶响应必须再次核对此 ID。
            orgId = org.toLowerCase(java.util.Locale.ROOT);
        }
        if (orgId == null || orgId.isBlank()) {
            throw new ConnectionProbeException(ConnectionProbeException.Reason.TARGET_NOT_FOUND, null);
        }
        var buckets = HttpConnectionProbeSupport.json(request, "/api/v2/buckets?orgID="
                        + HttpConnectionProbeSupport.query(orgId) + "&name=" + HttpConnectionProbeSupport.query(bucket), "GET",
                auth, null);
        if (!buckets.path("buckets").isArray()) {
            throw new IOException("InfluxDB 返回的存储桶列表格式无效");
        }
        for (var value : buckets.path("buckets")) {
            if (bucket.equals(value.path("name").asText()) && orgId.equals(value.path("orgID").asText())) {
                passed(checks, "BASIC", "AUTH", "Token 携带请求已获服务器授权响应");
                passed(checks, "TARGET", "ORGANIZATION", "已根据桶所有者精确核对组织身份");
                passed(checks, "TARGET", "BUCKET", "已精确确认目标桶及其所属组织");
                passed(checks, "METADATA", "BUCKET_METADATA", "已取得目标桶目录元数据，不代表数据读取权限");
                return;
            }
        }
        throw new ConnectionProbeException(ConnectionProbeException.Reason.TARGET_NOT_FOUND, null);
    }

    /**
     * 解析组织。
     */
    private static String resolveOrganization(ConnectionProbeRequest request, String org, String auth)
            throws IOException, ConnectionProbeException {
        var organizations = HttpConnectionProbeSupport.json(request,
                "/api/v2/orgs?org=" + HttpConnectionProbeSupport.query(org), "GET", auth, null);
        if (!organizations.path("orgs").isArray()) {
            throw new IOException("InfluxDB 返回的组织列表格式无效");
        }
        for (var value : organizations.path("orgs")) {
            if (org.equals(value.path("name").asText())) {
                return value.path("id").asText();
            }
        }
        return null;
    }
}
