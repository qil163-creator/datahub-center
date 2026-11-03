package cn.iocoder.yudao.framework.etl.odps.fixture;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedOdps;
import com.aliyun.odps.TableSchema;
import com.aliyun.odps.PartitionSpec;
import com.aliyun.odps.data.ArrayRecord;
import com.aliyun.odps.tunnel.TunnelTableSchema;
import com.aliyun.odps.tunnel.io.CompressOption;
import com.aliyun.odps.commons.proto.ProtobufRecordStreamReader;
import com.aliyun.odps.commons.proto.ProtobufRecordStreamWriter;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 隔离协议夹具，不是 MaxCompute 服务端；使用官方 SDK 编解码真实 Tunnel protobuf/CRC 帧。 */
public final class OdpsWireFixture {
    private static final LinkedHashMap<String, String> TYPES = new LinkedHashMap<>();
    static { TYPES.put("id", "BIGINT"); TYPES.put("amount", "DECIMAL(24,4)"); TYPES.put("day", "DATE"); TYPES.put("label", "STRING"); TYPES.put("empty", "STRING"); TYPES.put("optional", "STRING"); TYPES.put("reading", "FLOAT"); }
    private static final Map<String, Upload> UPLOADS = new ConcurrentHashMap<>();
    private static final List<Map<String, String>> SOURCE = new CopyOnWriteArrayList<>(), TARGET = new CopyOnWriteArrayList<>();
    private static volatile String mode = "normal";
    private static final AtomicInteger requests = new AtomicInteger(), signedRequests = new AtomicInteger(), downloadDataRequests = new AtomicInteger(), uploadedBlocks = new AtomicInteger(), commitRequests = new AtomicInteger();
    private record Upload(boolean overwrite, Map<Long, List<Map<String, String>>> blocks) { }

    public static void main(String[] arguments) throws Exception {
        SOURCE.add(row("9007199254740993", "12345678901234567890.1234", "2026-11-02", "河道,a \"B\"\nline", "", null));
        SOURCE.add(row("9007199254740994", "2.0001", "2026-11-03", "水情".repeat(2200), "", "present"));
        TARGET.add(row("7", "1.0000", "2026-10-01", "EXISTING", "", null));
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", Integer.parseInt(arguments[0])), 0);
        server.createContext("/", OdpsWireFixture::handle); server.setExecutor(Executors.newCachedThreadPool()); server.start();
    }
    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath(); var params = parameters(exchange.getRequestURI().getRawQuery());
            if (path.equals("/__control")) { mode = params.getOrDefault("mode", "normal"); respond(exchange, 200, "{}"); return; }
            if (path.equals("/__report")) {
                var report = JSONUtil.createObj().set("fixtureKind", "OFFICIAL_SDK_TUNNEL_WIRE_ONLY").set("cloudAcceptance", "OPEN")
                        .set("mode", mode).set("requests", requests.get()).set("signedRequests", signedRequests.get()).set("downloadDataRequests", downloadDataRequests.get())
                        .set("uploadedBlocks", uploadedBlocks.get()).set("commitRequests", commitRequests.get()).set("targetRows", TARGET);
                respond(exchange, 200, report.toString()); return;
            }
            requests.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.startsWith("ODPS ")) signedRequests.incrementAndGet();
            else { respond(exchange, 403, "{\"Code\":\"AccessDenied\",\"Message\":\"private-fixture-signature-error\"}"); return; }
            if (!path.equals("/projects/fixture/tables/source") && !path.equals("/projects/fixture/tables/target")) { respond(exchange, 404, "{}"); return; }
            if (!params.containsKey("partition") || !"ds='20261102'".equals(new PartitionSpec(params.get("partition")).toString())) { respond(exchange, 403, "{\"Code\":\"AccessDenied\",\"Message\":\"private-fixture-partition-error\"}"); return; }
            if (mode.equals("403")) { respond(exchange, 403, "{\"Code\":\"AccessDenied\",\"Message\":\"private-fixture-denied\"}"); return; }
            boolean target = path.endsWith("/target");
            if (params.containsKey("downloads")) {
                respond(exchange, 200, JSONUtil.createObj().set("DownloadID", target ? "target-download" : "source-download").set("Status", "NORMAL")
                        .set("RecordCount", target ? TARGET.size() : SOURCE.size()).set("Schema", schemaObject(mode.equals("schema"))).toString()); return;
            }
            if (params.containsKey("downloadid") && params.containsKey("data")) {
                downloadDataRequests.incrementAndGet(); byte[] data = encode(target ? TARGET : SOURCE);
                if (mode.equals("crc")) data[data.length - 1] ^= 1;
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream"); exchange.sendResponseHeaders(200, data.length);
                try (var output = exchange.getResponseBody()) { output.write(data); } return;
            }
            if (params.containsKey("uploads")) {
                String id = "upload-" + UUID.randomUUID(); Upload upload = new Upload(params.getOrDefault("overwrite", "false").equals("true"), new LinkedHashMap<>()); UPLOADS.put(id, upload);
                respond(exchange, 200, upload(id, upload, false)); return;
            }
            String id = params.get("uploadid"); Upload upload = UPLOADS.get(id);
            if (upload == null) { respond(exchange, 404, "{}"); return; }
            if (exchange.getRequestMethod().equals("PUT") && params.containsKey("blockid")) {
                byte[] bytes = exchange.getRequestBody().readNBytes(16777217); if (bytes.length > 16777216) { respond(exchange, 413, "{}"); return; }
                var rows = new ArrayList<Map<String, String>>();
                try (var reader = new ProtobufRecordStreamReader(schema(), new ByteArrayInputStream(bytes), new CompressOption(CompressOption.CompressAlgorithm.ODPS_RAW, 0, 0))) {
                    com.aliyun.odps.data.Record record;
                    while ((record = reader.read()) != null) {
                        var row = new LinkedHashMap<String, String>();
                        for (String field : TYPES.keySet()) {
                            Object value = record.get(field);
                            row.put(field, value == null ? null : value instanceof byte[] raw ? new String(raw, StandardCharsets.UTF_8)
                                    : value instanceof java.math.BigDecimal decimal ? decimal.toPlainString()
                                    : value instanceof Float number ? Double.toString(number.doubleValue()) : value.toString());
                        }
                        rows.add(row);
                    }
                }
                upload.blocks().put(Long.parseLong(params.get("blockid")), rows); uploadedBlocks.incrementAndGet(); respond(exchange, 200, "{}"); return;
            }
            if (exchange.getRequestMethod().equals("POST")) {
                commitRequests.incrementAndGet();
                if (mode.equals("commit500")) { respond(exchange, 500, "{\"Code\":\"InternalServerError\",\"Message\":\"private-fixture-commit-error\"}"); return; }
                if (upload.overwrite()) TARGET.clear();
                upload.blocks().values().forEach(TARGET::addAll); respond(exchange, 200, upload(id, upload, true)); return;
            }
            respond(exchange, 200, upload(id, upload, false));
        } catch (Exception failure) { respond(exchange, 500, "{\"Code\":\"FixtureProtocolFailure\"}"); }
        finally { exchange.close(); }
    }
    private static String upload(String id, Upload upload, boolean closed) {
        return JSONUtil.createObj().set("UploadID", id).set("Status", closed ? "CLOSED" : "NORMAL").set("MaxFieldSize", 1048576)
                .set("UploadedBlockList", upload.blocks().keySet().stream().map(value -> Map.of("BlockID", value)).toList()).set("Schema", schemaObject(false)).toString();
    }
    private static Object schemaObject(boolean mismatch) {
        return Map.of("columns", TYPES.entrySet().stream().map(field -> Map.of("name", field.getKey(), "type", mismatch && field.getKey().equals("id") ? "STRING" : field.getValue(), "nullable", "true")).toList(),
                "partitionKeys", List.of(Map.of("name", "ds", "type", "STRING", "nullable", "false")));
    }
    private static TableSchema schema() { return new TunnelTableSchema(new JsonParser().parse(JSONUtil.toJsonStr(schemaObject(false))).getAsJsonObject()); }
    private static byte[] encode(List<Map<String, String>> rows) throws Exception {
        var bytes = new ByteArrayOutputStream(); TableSchema schema = schema();
        try (var writer = new ProtobufRecordStreamWriter(schema, bytes, new CompressOption(CompressOption.CompressAlgorithm.ODPS_RAW, 0, 0))) {
            for (var row : rows) {
                var record = new ArrayRecord(schema); row.forEach((name, value) -> record.set(name, EtlManagedOdps.value(TYPES.get(name), value))); writer.write(record);
            }
        }
        return bytes.toByteArray();
    }
    private static Map<String, String> row(String id, String amount, String day, String label, String empty, String optional) {
        var row = new LinkedHashMap<String, String>(); row.put("id", id); row.put("amount", amount); row.put("day", day); row.put("label", label); row.put("empty", empty); row.put("optional", optional); row.put("reading", id.equals("7") ? "0.0" : id.endsWith("94") ? Double.toString((double) Float.MIN_VALUE) : Double.toString((double) 1.1f)); return row;
    }
    private static Map<String, String> parameters(String query) {
        var result = new LinkedHashMap<String, String>(); if (query == null) return result;
        for (String part : query.split("&")) { String[] pair = part.split("=", 2); result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
        return result;
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
