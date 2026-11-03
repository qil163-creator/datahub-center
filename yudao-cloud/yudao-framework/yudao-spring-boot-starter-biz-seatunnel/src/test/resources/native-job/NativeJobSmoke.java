import cn.iocoder.yudao.framework.seatunnel.NativeSeatunnelConfig;
import cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** 原生提交验收：透明回环代理记录请求字节，再交给真实 SeaTunnel 引擎。 */
class NativeJobSmoke {
    public static void main(String[] args) throws Exception {
        String upstream = args[0];
        byte[] original = Files.readAllBytes(Path.of(args[1]));
        String format = NativeSeatunnelConfig.normalizeFormat(args[2]);
        String jobName = args[3];
        Path reportFile = Path.of(args[4]);
        var submittedBody = new AtomicReference<byte[]>();
        var proxyFailure = new AtomicReference<String>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (HttpClient forwarder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            proxy.createContext("/", exchange -> {
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    if ("/submit-job".equals(exchange.getRequestURI().getPath())) submittedBody.set(body);
                    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upstream + exchange.getRequestURI()))
                            .timeout(Duration.ofSeconds(40))
                            .method(exchange.getRequestMethod(), body.length == 0
                                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (contentType != null) request.header("Content-Type", contentType);
                    var response = forwarder.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (Exception failure) {
                    proxyFailure.set(failure.getClass().getSimpleName());
                    exchange.sendResponseHeaders(502, -1);
                } finally {
                    exchange.close();
                }
            });
            proxy.start();
            SeatunnelRestApi.SERVER_URL = "http://127.0.0.1:" + proxy.getAddress().getPort();
            NativeSeatunnelConfig.validate(new String(original, StandardCharsets.UTF_8), format);
            var submitted = SeatunnelRestApi.submitJob(new String(original, StandardCharsets.UTF_8), format, jobName);
            require(proxyFailure.get() == null, "回环转发失败");
            require(Arrays.equals(original, submittedBody.get()), "原文请求字节被改写");
            require(jobName.equals(submitted.getStr("jobName")), "查询参数未覆盖原文作业名");
            String jobId = submitted.getStr("jobId");
            require(jobId != null && !jobId.isBlank(), "提交未返回作业身份");
            System.out.println("submitted format=" + format + " jobId=" + jobId + " jobName=" + jobName);
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (true) {
                var state = SeatunnelRestApi.getJobInfo(jobId);
                String status = state.getStr("jobStatus", "");
                if (Set.of("FAILED", "CANCELED", "CANCEL").contains(status)) {
                    throw new IllegalStateException("原生作业未成功完成：" + status);
                }
                if ("FINISHED".equals(status)) {
                    require(state.getStr("errorMsg", "").isBlank(), "远端作业包含错误");
                    require(jobName.equals(state.getStr("jobName")), "终态作业名称与查询参数不一致");
                    var metrics = state.getJSONObject("metrics");
                    require(metrics != null, "终态缺少执行指标");
                    long rows = Long.parseLong(metrics.getStr("SinkWriteCount", "-1"));
                    require(rows == 3, "源、转换和目标链路未输出预期的 3 行");
                    var report = new LinkedHashMap<String, Object>();
                    report.put("format", format);
                    report.put("jobId", jobId);
                    report.put("jobName", jobName);
                    report.put("status", status);
                    report.put("sinkWriteCount", rows);
                    report.put("rawBodyPreserved", true);
                    report.put("originalBytes", original.length);
                    report.put("originalSha256", hash(original));
                    report.put("requestSha256", hash(submittedBody.get()));
                    report.put("queryJobNameOverridesOriginal", true);
                    report.put("pipeline", "FakeSource -> Sql -> Console");
                    Files.writeString(reportFile, JsonMapper.builder().build().writeValueAsString(report));
                    System.out.println("passed format=" + format + " status=" + status + " rows=" + rows);
                    return;
                }
                require(System.nanoTime() < deadline, "等待原生作业终态超时");
                Thread.sleep(1_000);
            }
        } finally {
            proxy.stop(0);
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
