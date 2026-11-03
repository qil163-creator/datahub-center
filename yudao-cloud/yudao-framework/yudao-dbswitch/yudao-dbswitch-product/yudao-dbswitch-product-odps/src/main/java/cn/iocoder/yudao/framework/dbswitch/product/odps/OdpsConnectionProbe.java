package cn.iocoder.yudao.framework.dbswitch.product.odps;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbe;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbeException;
import cn.iocoder.yudao.framework.dbswitch.spi.HttpConnectionProbeSupport;
import com.aliyun.odps.Odps;
import com.aliyun.odps.account.AliyunAccount;
import com.aliyun.odps.rest.RestClient;

import java.net.URI;
import java.time.Duration;
import javax.net.ssl.SSLException;

/** 使用 SDK 项目授权与签名，通过公开 Transport 扩展约束读取大小和重定向。 */
public final class OdpsConnectionProbe implements ConnectionProbe {

    /** SDK 项目元数据响应是联合授权证据；不提交 SQL 作业或创建任何资源。 */
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
                    "BASIC", "AUTH", "签名授权响应", "PASS", 0, "SDK 签名请求取得目标项目响应，不推断表级数据权限", null, null));
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    "TARGET", "PROJECT", "目标项目", "PASS", 0, "已精确核对返回的项目身份", null, null));
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    "METADATA", "PROJECT_METADATA", "项目目录", "PASS", 0, "已通过 SDK Project.reload 读取项目元数据", null, null));
        } catch (Exception failure) {
            String reason = failure instanceof ConnectionProbeException classified ? classified.reason().name()
                    : failure instanceof IllegalArgumentException ? "CONFIGURATION" : "PROTOCOL_ERROR";
            checks.add(new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                    "TARGET", "AUTH_PROJECT", "签名及目标项目", "FAIL", 0, "联合请求未取得完整项目授权证据，不推断账号或项目存在性", null, null, reason));
        }
        if (!connected) {
            skipped(checks, "BASIC", "AUTH", "联合项目请求失败，未独立证明签名认证成功", "PREREQUISITE_FAILED");
            skipped(checks, "TARGET", "PROJECT", "联合项目请求失败，未独立证明目标项目可访问", "PREREQUISITE_FAILED");
            skipped(checks, "METADATA", "PROJECT_METADATA", "联合项目请求失败，未取得完整项目元数据", "PREREQUISITE_FAILED");
        }
        skipped(checks, "READ", "TABLE_READ", "未指定授权表及受控查询，不提交可能计费的 SQL 作业", "UNVERIFIED");
        skipped(checks, "WRITE", "WRITE", "未收到显式写入授权，不提交任务或修改项目资源", "NOT_AUTHORIZED");
        skipped(checks, "METADATA", "TABLE_COMMENTS", "项目元数据不证明表列注释目录权限", "UNVERIFIED");
        skipped(checks, "ADVANCED", "CDC", "未验证变更读取协议、版本和账号权限", "UNVERIFIED");
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
        URI endpoint = HttpConnectionProbeSupport.endpoint(request.endpoint());
        if (request.username() == null || request.username()
                .isBlank() || request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("缺少 MaxCompute 访问密钥");
        }
        String project = request.requiredProperty("project");
        if (!project.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("MaxCompute 项目名称不合法");
        }
        var odps = new ProbeOdps(new AliyunAccount(request.username(), request.password()), endpoint,
                request.timeout(), project);
        odps.setEndpoint(endpoint.toString());
        odps.setDefaultProject(project);
        odps.getRestClient().setConnectTimeout((int) request.timeout().toSeconds());
        odps.getRestClient().setReadTimeout((int) request.timeout().toSeconds());
        odps.getRestClient().setIgnoreCerts(false);
        odps.getRestClient().setRetryTimes(0);
        var target = odps.projects().get(project);
        try {
            target.reload();
        } catch (com.aliyun.odps.OdpsException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConnectionProbeException classified) {
                    throw classified;
                }
                if (cause instanceof SSLException invalidCertificate) {
                    throw new ConnectionProbeException(ConnectionProbeException.Reason.TLS, failure);
                }
            }
            throw failure;
        }
        if (!project.equals(target.getName())) {
            throw new ConnectionProbeException(ConnectionProbeException.Reason.TARGET_NOT_FOUND, null);
        }
    }

    private static final class ProbeOdps extends Odps {

        /**
         * 初始化 {@code ProbeOdps}及其父类状态。
         */
        private ProbeOdps(AliyunAccount account, URI endpoint, Duration timeout, String project) {
            super(account);
            client = new RestClient(new OdpsReadTransport(endpoint, timeout,
                    java.util.Set.of(OdpsReadTransport.path(endpoint, "/projects/" + project)), java.util.Set.of()));
            client.setAccount(account);
        }
    }

}
