package cn.iocoder.yudao.framework.etl.core.executor.support;

import com.aliyun.odps.commons.transport.*;

import java.io.*;
import java.net.*;
import java.util.*;

/** 官方 MaxCompute SDK 的受管传输：固定服务/Tunnel地址与单表路径，禁止重定向和节点代理覆盖。 */
public final class EtlOdpsTransport implements Transport {
    private final List<URI> endpoints; private final String resource; private final boolean output;
    public EtlOdpsTransport(URI service, URI tunnel, String project, String table, boolean output) {
        if (project == null || !project.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("ODPS 项目或表身份无效");
        this.endpoints = List.of(endpoint(service), endpoint(tunnel)); this.resource = "/projects/" + project + "/tables/" + table; this.output = output;
    }
    public static URI endpoint(URI endpoint) {
        if (endpoint == null || !Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535 || !endpoint.getPath().matches("(?:/[A-Za-z0-9_-]+)*/?"))
            throw new IllegalArgumentException("ODPS 必须使用明确、无凭据的服务端点");
        return endpoint;
    }
    @Override public Response request(Request request) throws IOException {
        try (var connection = new BoundConnection(request)) {
            if (request.getBody() != null) try (var body = request.getBody(); var target = connection.getOutputStream()) { body.transferTo(target); }
            var response = connection.response();
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(16777217); if (body.length > 16777216) throw new IOException("ODPS 元数据响应超出预算");
                return new Result(response.getStatus(), response.getMessage(), response.getHeaders(), body);
            }
        }
    }
    @Override public Connection connect(Request request) throws IOException { return new BoundConnection(request); }
    @Override public void setProxy(Proxy proxy) { throw new UnsupportedOperationException("ODPS 不允许任务覆盖执行节点代理"); }

    private final class BoundConnection implements Connection, AutoCloseable {
        private HttpURLConnection connection;
        BoundConnection(Request request) throws IOException { connect(request); }
        @Override public void connect(Request request) throws IOException {
            URI actual = request.getURI();
            if (actual == null) throw new IOException("ODPS SDK 请求缺少明确地址");
            boolean permitted = endpoints.stream().anyMatch(endpoint -> {
                String prefix = endpoint.getPath().replaceAll("/+$", "") + resource;
                return Objects.equals(endpoint.getScheme(), actual.getScheme()) && Objects.equals(endpoint.getHost(), actual.getHost())
                        && port(endpoint) == port(actual) && actual.getRawUserInfo() == null && actual.getRawFragment() == null
                        && actual.getRawPath().equals(prefix);
            });
            if (!permitted || !Set.of(Request.Method.GET, Request.Method.POST, Request.Method.PUT).contains(request.getMethod())
                    || !output && request.getMethod() == Request.Method.PUT) throw new IOException("ODPS SDK 请求超出受管表或地址范围");
            connection = (HttpURLConnection) actual.toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(10000); connection.setReadTimeout(60000); connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(request.getMethod().name()); request.getHeaders().forEach(connection::setRequestProperty);
            if (request.getMethod() != Request.Method.GET) {
                connection.setDoOutput(true);
                boolean chunked = request.getHeaders().entrySet().stream().anyMatch(entry -> entry.getKey().equalsIgnoreCase("Transfer-Encoding") && entry.getValue().equalsIgnoreCase("chunked"));
                if (chunked) connection.setChunkedStreamingMode(8192);
                else connection.setFixedLengthStreamingMode(request.getBody() == null ? 0 : request.getBodyLength());
            }
        }
        @Override public OutputStream getOutputStream() throws IOException { return connection.getOutputStream(); }
        private Result response() throws IOException {
            int status = connection.getResponseCode(); if (status >= 300 && status < 400) throw new IOException("ODPS 受管传输不允许重定向");
            // SDK 0.52 的 TunnelRetryHandler 对 429/5xx 无视配置重试策略，最终提交可能被重复发送。
            // 在传输边界转换为无状态码的受控 IO 失败，由作业明确报告未知提交状态，禁止隐式重放。
            if (status == 429 || status >= 500) throw new IOException("ODPS 服务暂不可用，已停止隐式重试；请核查上传会话的最终提交状态");
            var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            connection.getHeaderFields().forEach((name, values) -> { if (name != null && values != null && !values.isEmpty()) headers.put(name, values.get(0)); });
            return new Result(status, connection.getResponseMessage(), headers, null);
        }
        @Override public Response getResponse() throws IOException { return response(); }
        @Override public InputStream getInputStream() throws IOException {
            response(); InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return InputStream.nullInputStream();
            return "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new java.util.zip.GZIPInputStream(stream) : stream;
        }
        @Override public void disconnect() { if (connection != null) connection.disconnect(); }
        @Override public void close() { disconnect(); }
    }
    private static final class Result extends Response {
        Result(int status, String message, Map<String, String> headers, byte[] body) { this.status = status; this.message = message; this.headers = headers; this.body = body; }
    }
    private static int port(URI endpoint) { return endpoint.getPort() >= 0 ? endpoint.getPort() : endpoint.getScheme().equals("https") ? 443 : 80; }
}
