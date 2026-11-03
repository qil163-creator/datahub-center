package cn.iocoder.yudao.framework.etl.core.script;

import cn.iocoder.yudao.framework.etl.core.http.EtlBoundedHttp;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 数据与认证代理共用的 mTLS 传输，完整响应字节与总等待时间均有上限。 */
final class EtlScriptBrokerTransport {
    private final EtlScriptBrokerClient.Binding binding;
    private final HttpClient client;
    EtlScriptBrokerTransport(EtlScriptBrokerClient.Binding binding) {
        this.binding = binding;
        client = HttpClient.newBuilder().sslContext(EtlScriptTls.load(binding.privateKeyRef(), binding.certificateRef(), binding.authorityRef()))
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() { @Override public List<Proxy> select(URI address) { return List.of(Proxy.NO_PROXY); } @Override public void connectFailed(URI address, SocketAddress endpoint, IOException failure) { } }).build();
    }
    DataInputStream exchange(byte[] bytes, int timeoutSeconds) {
        var request = HttpRequest.newBuilder(URI.create(binding.endpoint())).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        try {
            var response = EtlBoundedHttp.send(client, request, EtlScriptBrokerProtocol.MAX_BYTES, timeoutSeconds * 1000); byte[] body = response.body();
            if (response.statusCode() != 200 || body.length > EtlScriptBrokerProtocol.MAX_BYTES || !response.headers().firstValue("Content-Type").orElse("").equals("application/octet-stream")) throw unavailable();
            var input = new DataInputStream(new ByteArrayInputStream(body));
            if (input.readInt() != EtlScriptBrokerProtocol.VERSION || input.readUnsignedByte() != EtlScriptProtocol.OK) throw unavailable();
            return input;
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (IOException failure) { throw unavailable(); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("脚本 broker 不可用、运行实例失效或请求未获当前任务身份授权"); }
}
