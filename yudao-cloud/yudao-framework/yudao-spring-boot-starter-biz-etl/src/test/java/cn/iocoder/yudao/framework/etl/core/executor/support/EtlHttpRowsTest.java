package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EtlHttpRowsTest {
    @Test void readsMultipleExplicitPathsWithDefaultsAndStrictDeclaredDateFormat() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new java.util.concurrent.atomic.AtomicReference<>("{\"left\":{\"rows\":[{\"date\":\"2026/11/03\"}]},\"right\":{\"rows\":[{\"date\":null,\"id\":9007199254740993}]}}");
        server.createContext("/rows", exchange -> { byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/rows";
            var api = JSONUtil.createObj().set("requestUrl", endpoint).set("requestMethod", "GET").set("dataKey", "ignoredLegacy")
                    .set("dataKeys", List.of("$.left.rows", "right.rows"));
            var date = JSONUtil.createObj().set("sqlType", "DATE").set("nullable", false).set("format", "yyyy/MM/dd").set("defaultValue", "2026/01/01");
            var types = JSONUtil.createObj().set("date", date).set("id", JSONUtil.createObj().set("sqlType", "BIGINT").set("defaultValue", "9007199254740992"));
            var plan = JSONUtil.createObj().set("apiConfig", api).set("resultTypes", types);
            var rows = new ArrayList<String>(); EtlHttpRows.read(plan, Set.of(endpoint), rows::add);
            assertEquals(2, rows.size());
            assertEquals("2026-11-03", JSONUtil.parseObj(rows.get(0)).getStr("date"));
            assertEquals("2026-01-01", JSONUtil.parseObj(rows.get(1)).getStr("date"));
            assertEquals("9007199254740993", JSONUtil.parseObj(rows.get(1)).getStr("id"));
            body.set("{\"left\":{\"rows\":[{\"date\":\"2026/02/30\"}]},\"right\":{\"rows\":[]}}");
            assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.read(plan, Set.of(endpoint), ignored -> fail()));
            api.set("dataKeys", List.of("$.left.rows", "left.rows"));
            assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.dataPaths(api));
            api.set("dataKeys", List.of("left.rows[*]"));
            assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.dataPaths(api));
        } finally { server.stop(0); }
    }

    @Test void refusesMissingAmbiguousAndWrongShapeInsteadOfReportingEmptySuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new java.util.concurrent.atomic.AtomicReference<>("{}");
        server.createContext("/rows", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/rows";
            var api = JSONUtil.createObj().set("requestUrl", endpoint).set("requestMethod", "GET").set("dataKey", "rows");
            var plan = JSONUtil.createObj().set("apiConfig", api);
            for (String response : List.of("{}", "{\"rows\":null}", "{\"rows\":{\"id\":1}}", "{\"rows\":[],\"rows\":[]}", "{\"left\":{\"rows\":[]},\"right\":{\"rows\":[]}}")) {
                body.set(response); var rows = new ArrayList<String>();
                assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.read(plan, Set.of(endpoint), rows::add)); assertTrue(rows.isEmpty());
            }
            body.set("{\"left\":{\"rows\":[]},\"right\":{\"rows\":[]}}"); api.set("dataKey", "left.rows");
            assertDoesNotThrow(() -> EtlHttpRows.read(plan, Set.of(endpoint), ignored -> fail("空数组不能产生行")));
        } finally { server.stop(0); }
    }
    @Test void resolvesAuthenticationOnlyAtRequestTimeAndDoesNotExposeSecretOnFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String secret = "Bearer " + java.util.UUID.randomUUID();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/rows", exchange -> {
            if (!secret.equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401, -1); exchange.close(); return; }
            calls.incrementAndGet();
            byte[] bytes = "[{\"ok\":true}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/rows";
            var plan = JSONUtil.createObj().set("apiConfig", JSONUtil.createObj().set("requestUrl", endpoint).set("requestMethod", "GET"))
                    .set("headerSecretRefs", java.util.Map.of("Authorization", "apiAccess"));
            var rows = new ArrayList<String>();
            EtlHttpRows.read(plan, Set.of(endpoint), reference -> { assertEquals("apiAccess", reference); return secret; }, rows::add);
            assertEquals(1, calls.get()); assertEquals(1, rows.size()); assertFalse(plan.toString().contains(secret));
            var failure = assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.read(plan, Set.of(endpoint), reference -> "invalid", rows::add));
            assertFalse(failure.toString().contains(secret)); assertFalse(failure.toString().contains("invalid"));
            assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.read(plan, Set.of(endpoint), rows::add));
        } finally { server.stop(0); }
    }
    @Test void readsEveryPageWithoutLosingChineseOrLargeIdentifiers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rows", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String body = query.contains("pageNo=1") ? "{\"data\":{\"rows\":[{\"id\":9007199254740993,\"label\":\"河道😀\"}]}}" : "{\"data\":{\"rows\":[]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/rows";
            var api = JSONUtil.createObj().set("requestUrl", endpoint).set("requestMethod", "GET").set("ifPage", 1)
                    .set("pageKey", "pageNo").set("pageSizeKey", "pageSize").set("dataKey", "data.rows");
            var rows = new ArrayList<String>();
            EtlHttpRows.read(JSONUtil.createObj().set("apiConfig", api).set("fetchSize", 1), Set.of(endpoint), rows::add);
            assertEquals(1, rows.size()); assertTrue(rows.get(0).contains("9007199254740993")); assertTrue(rows.get(0).contains("河道😀"));
        } finally { server.stop(0); }
    }

    @Test void rejectsUnboundEndpointsRedirectsOversizedAndUnterminatedPagination() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.endpoint("http://127.0.0.1/private", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.endpoint("http://user:password@127.0.0.1/private", Set.of("http://user:password@127.0.0.1/private")));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "http://127.0.0.1/private"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/large", exchange -> { byte[] bytes = "x".repeat(500).getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
        server.createContext("/pages", exchange -> { byte[] bytes = "[{\"id\":1}]".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String path : List.of("/redirect", "/large", "/pages")) {
                String endpoint = base + path;
                var api = JSONUtil.createObj().set("requestUrl", endpoint).set("requestMethod", "GET");
                if (path.equals("/pages")) api.set("ifPage", 1).set("pageKey", "pageNo").set("pageSizeKey", "pageSize");
                var plan = JSONUtil.createObj().set("apiConfig", api).set("fetchSize", 1).set("maxPages", 1).set("maxResponseBytes", 100);
                assertThrows(IllegalArgumentException.class, () -> EtlHttpRows.read(plan, Set.of(endpoint), ignored -> { }));
            }
        } finally { server.stop(0); }
    }

    @Test void postsJsonAndFormBodiesAndAppliesQueryEncoding() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var observed = new ArrayList<String>();
        server.createContext("/rows", exchange -> {
            observed.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawQuery() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "[{\"ok\":true}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/rows";
            for (String bodyType : List.of("raw", "urlencoded", "form-data")) {
                var api = JSONUtil.createObj().set("requestUrl", endpoint).set("requestMethod", "POST").set("bodyType", bodyType)
                        .set("bodyRaw", "{\"name\":\"水情\"}").set("requestParam", List.of(java.util.Map.of("name", "query", "value", "a&b")))
                        .set("bodyUrlencoded", List.of(java.util.Map.of("name", "label", "value", "水情")))
                        .set("bodyFormData", List.of(java.util.Map.of("name", "label", "value", "水情")));
                var rows = new ArrayList<String>();
                EtlHttpRows.read(JSONUtil.createObj().set("apiConfig", api), Set.of(endpoint), rows::add);
                assertEquals(1, rows.size());
            }
            assertTrue(observed.stream().allMatch(value -> value.startsWith("POST query=a%26b")));
            assertTrue(observed.get(0).contains("水情")); assertTrue(observed.get(1).contains("%E6%B0%B4%E6%83%85"));
            assertTrue(observed.get(2).contains("Content-Disposition: form-data; name=\"label\""));
        } finally { server.stop(0); }
    }
}
