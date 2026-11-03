package cn.iocoder.yudao.framework.etl.core.script;

import java.io.*;
import java.net.URI;
import java.util.*;

/** 原生 HTTP 与脚本 broker 的纯 JDK 桥；跨 JAR 公共签名不携带 JSON 实现或明文 token。 */
public final class EtlScriptAuthenticatedHttp {
    public static final String RUNTIME_CONTEXT_KEY = "datahub.etl.script.runtime-context";
    public static final int MAXIMUM_HTTP_BYTES = 16 * 1024 * 1024;
    private static final int CONTEXT_BYTES = 32 * 1024;
    private static final ThreadLocal<String> RUNTIME_CONTEXT = new ThreadLocal<>();
    private EtlScriptAuthenticatedHttp() { }

    /** 仅可信引擎主程序设置不含原始令牌的作用域；任务之间和子线程不会继承。 */
    public static RuntimeScope openRuntimeContext(Context context) {
        if (RUNTIME_CONTEXT.get() != null) throw invalid();
        RUNTIME_CONTEXT.set(encode(Objects.requireNonNull(context)));
        return new RuntimeScope(Thread.currentThread());
    }

    public static String currentRuntimeContext() { return RUNTIME_CONTEXT.get(); }

    public static final class RuntimeScope implements AutoCloseable {
        private final Thread owner;
        private boolean closed;
        private RuntimeScope(Thread owner) { this.owner = owner; }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw invalid();
            if (!closed) { RUNTIME_CONTEXT.remove(); closed = true; }
        }
        @Override public String toString() { return "ManagedScriptRuntimeScope"; }
    }

    public record Request(String resourceRef, String endpoint, String method, Map<String, String> query,
                          Map<String, String> headers, byte[] body, int maximumBytes, int timeoutMillis) {
        public Request {
            URI address = URI.create(endpoint);
            if (resourceRef == null || !resourceRef.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || !Set.of("http", "https").contains(address.getScheme())
                    || address.getHost() == null || address.getRawFragment() != null || address.getRawUserInfo() != null || endpoint.length() > 4096
                    || !Set.of("GET", "POST").contains(method) || maximumBytes < 1 || maximumBytes > MAXIMUM_HTTP_BYTES
                    || timeoutMillis < 100 || timeoutMillis > 30_000 || body == null || body.length > maximumBytes) throw invalid();
            query = values(query); headers = values(headers); body = body.clone();
            var headerNames = new HashSet<String>();
            for (var entry : headers.entrySet()) if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9-]{0,99}") || !headerNames.add(entry.getKey().toLowerCase(Locale.ROOT))
                    || entry.getValue().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        }
        @Override public byte[] body() { return body.clone(); }
        @Override public String toString() { return "ScriptHttpRequest[resource=" + resourceRef + ", method=" + method + "]"; }
    }
    public record Response(int status, String contentType, byte[] body) {
        public Response {
            if (status < 100 || status > 599 || contentType == null || contentType.length() > 512 || contentType.codePoints().anyMatch(Character::isISOControl)
                    || body == null || body.length > MAXIMUM_HTTP_BYTES) throw invalid();
            body = body.clone();
        }
        @Override public byte[] body() { return body.clone(); }
        @Override public String toString() { return "ScriptHttpResponse[status=" + status + ", bytes=" + body.length + "]"; }
    }
    public record HeaderReference(String tokenKey, String prefix) {
        public HeaderReference {
            if (tokenKey == null || !tokenKey.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")) throw invalid();
            prefix = prefix == null ? "" : prefix;
            if (!prefix.isEmpty() && !prefix.matches("[A-Za-z][A-Za-z0-9_-]{0,31} ")) throw invalid();
        }
    }
    public record Context(EtlScriptBrokerClient.Binding identity, String run, Map<String, String> contextReferences) {
        public Context {
            Objects.requireNonNull(identity);
            if (run == null || !run.matches("[A-Za-z0-9_.-]{1,128}") || contextReferences == null || contextReferences.size() > 16) throw invalid();
            contextReferences = Map.copyOf(contextReferences);
            for (var entry : contextReferences.entrySet()) { new HeaderReference(entry.getKey(), ""); if (!entry.getValue().matches("[a-f0-9-]{36}")) throw invalid(); }
        }
        @Override public String toString() { return "ScriptRuntimeContext[task=" + identity.task() + ", engine=" + identity.engine() + "]"; }
    }
    @FunctionalInterface public interface ResourceExecutor { Response exchange(Request request); }
    record ProtectedHeader(String contextReference, String prefix) { }

    /** 缺少由可信 stage 设置的私有上下文时必须拒绝，不能把 tokenKey 当作明文 header 值。 */
    public static Response exchange(String runtimeDescriptor, Request request, Map<String, HeaderReference> headerContextRefs) {
        Context context = decode(runtimeDescriptor); var resolved = new LinkedHashMap<String, ProtectedHeader>();
        if (headerContextRefs == null || headerContextRefs.size() > 64) throw invalid();
        for (var entry : headerContextRefs.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9-]{0,99}") || request.headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(entry.getKey()))
                    || resolved.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(entry.getKey()))) throw invalid();
            String reference = context.contextReferences.get(entry.getValue().tokenKey);
            if (reference == null) throw invalid();
            resolved.put(entry.getKey(), new ProtectedHeader(reference, entry.getValue().prefix));
        }
        var binding = context.identity;
        var claim = new EtlScriptBrokerProtocol.Claim(binding.bindingId(), binding.sourceHash(), binding.epoch(), binding.task(), binding.workerGroup(), binding.engine(), context.run, "http");
        try {
            byte[] requestBytes = EtlScriptBrokerProtocol.request(EtlScriptBrokerProtocol.HTTP, output -> { claim.write(output); write(output, request); output.writeInt(resolved.size());
                for (var entry : resolved.entrySet()) { text(output, entry.getKey()); text(output, entry.getValue().contextReference); text(output, entry.getValue().prefix); } });
            try (var response = new EtlScriptBrokerTransport(binding).exchange(requestBytes, Math.min(45, request.timeoutMillis / 1000 + 5))) {
                int status = response.readInt(); String type = text(response); byte[] bytes = bytes(response, request.maximumBytes); EtlScriptProtocol.requireEnd(response);
                return new Response(status, type, bytes);
            }
        } catch (IOException failure) { throw invalid(); }
    }
    public static String encode(Context context) {
        try {
            byte[] bytes = EtlScriptProtocol.encode(CONTEXT_BYTES, output -> {
                output.writeInt(1); var binding = context.identity;
                for (String value : List.of(binding.endpoint(), binding.epoch(), binding.bindingId(), binding.sourceHash(), binding.task(), binding.workerGroup(), binding.engine(), binding.privateKeyRef(), binding.certificateRef(), binding.authorityRef(), context.run)) text(output, value);
                map(output, context.contextReferences);
            });
            return Base64.getUrlEncoder().encodeToString(bytes);
        } catch (IOException failure) { throw invalid(); }
    }
    public static Context decode(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || descriptor.length() > CONTEXT_BYTES * 2) throw invalid();
        try (var input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(descriptor)))) {
            if (input.readInt() != 1) throw invalid();
            var binding = new EtlScriptBrokerClient.Binding(text(input), text(input), text(input), text(input), text(input), text(input), text(input), text(input), text(input), text(input));
            Context result = new Context(binding, text(input), map(input)); EtlScriptProtocol.requireEnd(input); return result;
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    static void write(DataOutputStream output, Request request) throws IOException {
        text(output, request.resourceRef); text(output, request.endpoint); text(output, request.method); map(output, request.query); map(output, request.headers);
        output.writeInt(request.body.length); output.write(request.body); output.writeInt(request.maximumBytes); output.writeInt(request.timeoutMillis);
    }
    static Request read(DataInputStream input) throws IOException {
        String resource = text(input), endpoint = text(input), method = text(input); var query = map(input); var headers = map(input);
        byte[] body = bytes(input, MAXIMUM_HTTP_BYTES);
        return new Request(resource, endpoint, method, query, headers, body, input.readInt(), input.readInt());
    }
    static void write(DataOutputStream output, Response response) throws IOException { output.writeInt(response.status); text(output, response.contentType); output.writeInt(response.body.length); output.write(response.body); }
    static String text(DataInputStream input) throws IOException { return EtlScriptProtocol.readText(input, 8192); }
    static void text(DataOutputStream output, String text) throws IOException { EtlScriptProtocol.writeText(output, text, 8192); }
    private static Map<String, String> values(Map<String, String> values) {
        if (values == null || values.size() > 256) throw invalid();
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        for (var entry : values.entrySet()) if (entry.getKey() == null || entry.getKey().length() > 4096 || entry.getValue() == null || entry.getValue().length() > 8192) throw invalid();
        return values;
    }
    private static void map(DataOutputStream output, Map<String, String> values) throws IOException { output.writeInt(values.size()); for (var entry : values.entrySet()) { text(output, entry.getKey()); text(output, entry.getValue()); } }
    private static Map<String, String> map(DataInputStream input) throws IOException {
        int size = input.readInt(); if (size < 0 || size > 256) throw invalid(); var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < size; i++) if (result.putIfAbsent(text(input), text(input)) != null) throw invalid();
        return result;
    }
    private static byte[] bytes(DataInputStream input, int maximum) throws IOException { int size = input.readInt(); if (size < 0 || size > maximum || size > input.available()) throw invalid(); byte[] bytes = new byte[size]; input.readFully(bytes); return bytes; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("HTTP认证上下文、资源或请求不满足受管运行合同"); }
}
