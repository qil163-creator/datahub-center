package cn.iocoder.yudao.framework.dbswitch.product.milvus;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbe;
import cn.iocoder.yudao.framework.dbswitch.spi.HttpConnectionProbeSupport;

import java.io.IOException;

/** 使用 Milvus 2.4+ REST v2；仅检查指定数据库，不获取集合元数据和业务数据。 */
public final class MilvusConnectionProbe implements ConnectionProbe {

    /** REST 一次成功响应同时证明目标库及集合目录可见，不扩大为向量查询/写入权限。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult diagnose(
            ConnectionProbeRequest request, JdbcOpener opener) {
        long started = System.nanoTime();
        var checks = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check>();
        boolean connected = false;
        try {
            probe(request, opener);
            connected = true;
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    "BASIC", "AUTH", "REST 授权响应", "PASS", 0, "携带凭据的请求已获成功响应；不推断服务端是否强制认证", null, null));
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    "TARGET", "TARGET", "目标数据库", "PASS", 0, "指定数据库的集合列表请求成功", null, null));
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    "METADATA", "COLLECTIONS", "集合目录", "PASS", 0, "已验证集合目录响应结构，空库也是有效证据", null, null));
        } catch (Exception failure) {
            String reason = failure instanceof cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbeException classified
                    ? classified.reason().name() : failure instanceof IllegalArgumentException ? "CONFIGURATION" : "PROTOCOL_ERROR";
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    "TARGET", "AUTH_TARGET", "认证及目标库目录", "FAIL", 0, "联合请求未取得有效授权目录响应，不能分别推断认证或库存在性", null, null, reason));
        }
        if (!connected) {
            skipped(checks, "BASIC", "AUTH", "联合目录请求失败，未独立证明认证成功", "PREREQUISITE_FAILED");
            skipped(checks, "TARGET", "TARGET", "联合目录请求失败，未独立证明数据库可见", "PREREQUISITE_FAILED");
            skipped(checks, "METADATA", "COLLECTIONS", "联合目录请求失败，未取得有效集合目录", "PREREQUISITE_FAILED");
        }
        skipped(checks, "READ", "VECTOR_READ", "未指定并授权集合及查询，不执行向量或标量数据读取", "UNVERIFIED");
        skipped(checks, "WRITE", "WRITE", "没有显式写入授权，不创建集合或修改向量", "NOT_AUTHORIZED");
        skipped(checks, "METADATA", "COMMENTS", "没有关系表列的原生 COMMENT 目录", "NOT_APPLICABLE");
        skipped(checks, "ADVANCED", "CDC", "未验证持续变更订阅能力", "UNVERIFIED");
        return new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult(connected ? "PASS" : "FAIL", connected,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checks);
    }

    private static void skipped(java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check> checks,
            String group, String key, String message, String reason) {
        checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                group, key, key, "SKIPPED", 0, message, null, null, reason));
    }

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    @Override
    public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
        String database = request.database();
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("缺少 Milvus 数据库");
        }
        String authorization = null;
        if (request.password() != null && !request.password().isBlank()) {
            String token = request.username() == null || request.username().isBlank()
                    ? request.password() : request.username() + ":" + request.password();
            authorization = "Bearer " + token;
        }
        var body = HttpConnectionProbeSupport.object().put("dbName", database);
        var result = HttpConnectionProbeSupport.json(request, "/v2/vectordb/collections/list", "POST", authorization,
                body);
        if (!result.path("code").isIntegralNumber() || !result.path("code").canConvertToInt()
                || result.path("code").intValue() != 0
                || !result.path("data").isArray()) {
            throw new IOException("Milvus 认证或目标数据库探测失败");
        }
    }
}
