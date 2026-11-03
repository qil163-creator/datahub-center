package cn.iocoder.yudao.framework.etl.core.script;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** 只供受管原生算子使用的 mTLS 客户端；配置可序列化，私钥和 SSLContext 只在运行时加载。 */
public final class EtlScriptBrokerClient implements AutoCloseable {
    public record Binding(String endpoint, String epoch, String bindingId, String sourceHash, String task,
                          String workerGroup, String engine, String privateKeyRef, String certificateRef,
                          String authorityRef) implements Serializable {
        public Binding {
            try {
                URI address = URI.create(endpoint);
                if (!"https".equals(address.getScheme()) || address.getHost() == null || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null
                        || !"/v1/script".equals(address.getPath()) || address.getPort() == 0 || address.getPort() > 65535) throw new IllegalArgumentException();
                new EtlScriptBrokerProtocol.Claim(bindingId, sourceHash, epoch, task, workerGroup, engine, "validate", "validate");
                for (String reference : List.of(privateKeyRef, certificateRef, authorityRef)) if (!reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException();
            } catch (RuntimeException failure) { throw new IllegalArgumentException("脚本客户端的受管地址、身份或引用无效"); }
        }
    }
    public record BatchResult(List<Map<String, Object>> rows, String authenticationContext) { }
    private final Binding binding;
    private final EtlScriptBrokerTransport transport;
    private final String session;
    private boolean closed;

    public EtlScriptBrokerClient(Binding binding, String run, String partition, String restoredSession) {
        this.binding = Objects.requireNonNull(binding);
        transport = new EtlScriptBrokerTransport(binding);
        try {
            var claim = new EtlScriptBrokerProtocol.Claim(binding.bindingId, binding.sourceHash, binding.epoch, binding.task, binding.workerGroup, binding.engine, run, partition);
            try (var response = exchange(EtlScriptBrokerProtocol.request(EtlScriptBrokerProtocol.OPEN, claim::write), 45)) {
                session = EtlScriptProtocol.readText(response, 36); EtlScriptProtocol.requireEnd(response);
            }
            if (restoredSession != null && !restoredSession.equals(session)) { close(); throw unavailable(); }
        } catch (IOException failure) { throw unavailable(); }
    }

    public String sessionReference() { return session; }
    public BatchResult execute(long sequence, List<Map<String, Object>> rows) {
        if (closed || sequence < 0) throw unavailable();
        try {
            byte[] request = EtlScriptBrokerProtocol.request(EtlScriptBrokerProtocol.BATCH, output -> {
                EtlScriptProtocol.writeText(output, session, 36); output.writeLong(sequence); EtlScriptProtocol.writeRows(output, rows, EtlScriptBrokerProtocol.MAX_ROWS, EtlScriptBrokerProtocol.MAX_BYTES);
            });
            try (var response = exchange(request, 45)) {
                if (response.readLong() != sequence) throw unavailable();
                String authentication = EtlScriptProtocol.readText(response, 36);
                var result = EtlScriptProtocol.readRows(response, EtlScriptBrokerProtocol.MAX_ROWS, EtlScriptBrokerProtocol.MAX_BYTES); EtlScriptProtocol.requireEnd(response);
                return new BatchResult(result, authentication);
            }
        } catch (IOException failure) { throw unavailable(); }
    }
    public void acknowledge(long nextSequence) {
        if (closed || nextSequence < 0) throw unavailable();
        try (var response = exchange(EtlScriptBrokerProtocol.request(EtlScriptBrokerProtocol.ACK, output -> { EtlScriptProtocol.writeText(output, session, 36); output.writeLong(nextSequence); }), 15)) { EtlScriptProtocol.requireEnd(response); }
        catch (IOException failure) { throw unavailable(); }
    }
    @Override public void close() { stop(EtlScriptBrokerProtocol.CLOSE); }
    public void cancel() { stop(EtlScriptBrokerProtocol.CANCEL); }
    private void stop(int operation) {
        if (closed) return;
        try (var response = exchange(EtlScriptBrokerProtocol.request(operation, output -> EtlScriptProtocol.writeText(output, session, 36)), 15)) { EtlScriptProtocol.requireEnd(response); closed = true; }
        catch (IOException failure) { throw unavailable(); }
    }
    private DataInputStream exchange(byte[] bytes, int timeoutSeconds) {
        return transport.exchange(bytes, timeoutSeconds);
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("脚本 broker 不可用、运行实例失效或请求未获当前任务身份授权"); }
}
