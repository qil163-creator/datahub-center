package cn.iocoder.yudao.framework.file.core.remote;

import cn.iocoder.yudao.framework.file.config.YudaoFileAutoConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 真实 HTTP 协议 fixture；不将其声明为真实 Hadoop 集群验证。 */
class WebHdfsRemoteFileClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Set<String> directories = ConcurrentHashMap.newKeySet();
    private final Set<String> symlinks = ConcurrentHashMap.newKeySet();
    private HttpServer nameNode;
    private HttpServer dataNode;
    private String nameUrl;
    private String dataUrl;
    private String redirectOverride;
    private final AtomicInteger dataRequests = new AtomicInteger();
    private final AtomicInteger authHeaders = new AtomicInteger();
    private volatile String lastDataRawPath;
    private boolean refuseCommit;

    @BeforeEach
    void start() throws Exception {
        directories.add("/");
        directories.add("/scope");
        files.put("/outside", new byte[]{99});
        nameNode = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        dataNode = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nameUrl = "http://127.0.0.1:" + nameNode.getAddress().getPort();
        dataUrl = "http://127.0.0.1:" + dataNode.getAddress().getPort();
        nameNode.createContext("/webhdfs/v1", this::nameRequest);
        dataNode.createContext("/webhdfs/v1", this::dataRequest);
        nameNode.start();
        dataNode.start();
    }

    @AfterEach void stop() { nameNode.stop(0); dataNode.stop(0); }

    private RemoteFileConfig config(boolean allowDataNode) {
        return new RemoteFileConfig(RemoteFileConfig.Protocol.HDFS, null, 0, "test-reader", "unused-secret", "/scope", nameUrl,
                allowDataNode ? Set.of("127.0.0.1:" + dataNode.getAddress().getPort()) : Set.of());
    }

    @Test
    void simpleAuthStreamingCrudUsesExplicitDataNodeAndNoPasswordHeader() throws Exception {
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config(true))) {
            client.mkdir("/中文目录");
            client.upload("/中文目录/a.txt", new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);
            assertThat(client.list("/中文目录")).extracting(RemoteFileEntry::name).containsExactly("a.txt");
            assertThatThrownBy(() -> client.upload("/中文目录/a.txt", new ByteArrayInputStream(new byte[]{4}), 1)).isInstanceOf(IOException.class);
            client.upload("/中文目录/a.txt", new ByteArrayInputStream(new byte[]{7, 8}), 2, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            client.download("/中文目录/a.txt", bytes, 5);
            assertThat(bytes.toByteArray()).containsExactly((byte) 7, (byte) 8);
            client.rename("/中文目录/a.txt", "/中文目录/b.txt");
            RemoteFileOperations.deleteRecursively(client, "/中文目录", 20, 5);
            assertThat(client.list("/")).isEmpty();
        }
        assertThat(files.get("/outside")).containsExactly((byte) 99);
        assertThat(authHeaders).hasValue(0);
        assertThat(dataRequests.get()).isPositive();
    }

    @Test
    void literalPercentPathIsEncodedOnceAcrossRedirect() throws Exception {
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config(true))) {
            client.upload("/100%+中文.txt", new ByteArrayInputStream(new byte[]{5}), 1);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            client.download("/100%+中文.txt", output, 1);
            assertThat(output.toByteArray()).containsExactly((byte) 5);
            assertThat(lastDataRawPath).isEqualTo("/webhdfs/v1/scope/100%25+%E4%B8%AD%E6%96%87.txt");
            assertThat(files).containsKey("/scope/100%+中文.txt");
        }
    }

    @Test
    void redirectRejectsUnknownAuthorityChangedPathChangedOperationAndScheme() throws Exception {
        files.put("/scope/a", new byte[]{1});
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config(false))) {
            assertThatThrownBy(() -> client.download("/a", new ByteArrayOutputStream(), 10)).isInstanceOf(IOException.class).hasMessageContaining("授权范围");
        }
        for (String target : List.of(dataUrl + "/webhdfs/v1/outside?op=OPEN",
                dataUrl + "/webhdfs/v1/scope/a?op=DELETE", dataUrl.replace("http:", "https:") + "/webhdfs/v1/scope/a?op=OPEN",
                dataUrl + "/webhdfs/v1/scope/a?op=OPEN&op=DELETE", "http://name:secret@127.0.0.1:1/webhdfs/v1/scope/a?op=OPEN")) {
            redirectOverride = target;
            try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config(true))) {
                assertThatThrownBy(() -> client.download("/a", new ByteArrayOutputStream(), 10)).isInstanceOf(IOException.class);
            }
        }
        assertThat(dataRequests).hasValue(0);
    }

    @Test
    void refusesSymlinkAncestorsRootDeletionAndOversizeBeforeSendingBytes() throws Exception {
        symlinks.add("/scope/link");
        files.put("/scope/a", new byte[]{1, 2, 3});
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config(true))) {
            assertThat(client.list("/")).extracting(RemoteFileEntry::name).containsExactly("a");
            assertThatThrownBy(() -> client.stat("/link")).isInstanceOf(IOException.class).hasMessageContaining("符号链接");
            assertThatThrownBy(() -> client.list("/link/child")).isInstanceOf(IOException.class).hasMessageContaining("符号链接");
            assertThatThrownBy(() -> client.delete("/")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.stat("/%2e%2e/outside")).isInstanceOf(IllegalArgumentException.class);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThatThrownBy(() -> client.download("/a", output, 2)).isInstanceOf(IOException.class);
            assertThat(output.size()).isZero();
        }
    }

    @Test
    void failedCommitRestoresOriginalAndCleansCurrentTemporaryFile() throws Exception {
        files.put("/scope/a", new byte[]{1, 2, 3});
        refuseCommit = true;
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config(true))) {
            assertThatThrownBy(() -> client.upload("/a", new ByteArrayInputStream(new byte[]{7}), 1, true)).isInstanceOf(IOException.class);
        }
        assertThat(files.get("/scope/a")).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(files.keySet()).containsExactlyInAnyOrder("/scope/a", "/outside");
    }

    @Test
    void minimalSpringContextAndJackson3RecordContract() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(YudaoFileAutoConfiguration.class);
            context.registerBean(RemoteFileConfig.class, () -> config(true));
            context.registerBean(RemoteFileClient.class, () -> {
                try { return context.getBean(RemoteFileClientFactory.class).open(context.getBean(RemoteFileConfig.class)); }
                catch (IOException error) { throw new IllegalStateException(error); }
            });
            context.refresh();
            assertThat(context.getBean(RemoteFileClient.class).list("/")).isEmpty();
            RemoteFileEntry entry = new RemoteFileEntry("中文.txt", "/中文.txt", false, 123, 1770000000000L);
            String json = JSON.writeValueAsString(entry);
            assertThat(JSON.readValue(json, RemoteFileEntry.class)).isEqualTo(entry);
            assertThat(json).contains("1770000000000");
            assertThat(config(true).toString()).doesNotContain("unused-secret", "test-reader", nameUrl);
        }
    }

    private void nameRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath().substring("/webhdfs/v1".length());
            Map<String, String> query = query(exchange.getRequestURI());
            String operation = query.get("op");
            if (!"test-reader".equals(query.get("user.name"))) { respond(exchange, 403, Map.of()); return; }
            switch (operation) {
                case "LISTSTATUS" -> {
                    if (!directories.contains(path)) { respond(exchange, 404, Map.of()); return; }
                    List<Map<String, Object>> entries = java.util.stream.Stream.of(directories, files.keySet(), symlinks)
                            .flatMap(Set::stream).filter(item -> !item.equals(path) && RemoteFilePaths.parent(item).equals(path))
                            .sorted().map(item -> status(item, true)).toList();
                    respond(exchange, 200, Map.of("FileStatuses", Map.of("FileStatus", entries)));
                }
                case "GETFILESTATUS" -> {
                    if (!exists(path)) { respond(exchange, 404, Map.of()); return; }
                    respond(exchange, 200, Map.of("FileStatus", status(path, false)));
                }
                case "MKDIRS" -> { directories.add(path); respond(exchange, 200, Map.of("boolean", true)); }
                case "RENAME" -> {
                    String target = query.get("destination");
                    boolean accepted = !exists(target) && exists(path) && !(refuseCommit && path.contains(".remote-file-upload-"));
                    if (accepted) {
                        if (files.containsKey(path)) files.put(target, files.remove(path));
                        else { directories.remove(path); directories.add(target); }
                    }
                    respond(exchange, 200, Map.of("boolean", accepted));
                }
                case "DELETE" -> {
                    boolean accepted = files.remove(path) != null || directories.remove(path);
                    respond(exchange, 200, Map.of("boolean", accepted));
                }
                case "OPEN", "CREATE" -> {
                    String location = redirectOverride == null ? dataUrl + exchange.getRequestURI() : redirectOverride;
                    exchange.getResponseHeaders().set("Location", location);
                    exchange.sendResponseHeaders(307, -1);
                }
                default -> respond(exchange, 400, Map.of());
            }
        } finally { exchange.close(); }
    }

    private void dataRequest(HttpExchange exchange) throws IOException {
        try {
            lastDataRawPath = exchange.getRequestURI().getRawPath();
            dataRequests.incrementAndGet();
            if (exchange.getRequestHeaders().containsKey("Authorization") || exchange.getRequestHeaders().containsKey("Cookie")) authHeaders.incrementAndGet();
            String path = exchange.getRequestURI().getPath().substring("/webhdfs/v1".length());
            Map<String, String> query = query(exchange.getRequestURI());
            if (query.get("op").equals("CREATE")) {
                if (!"false".equals(query.get("overwrite")) || exists(path)) { respond(exchange, 409, Map.of()); return; }
                files.put(path, exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(201, -1);
            } else {
                byte[] content = files.get(path);
                if (content == null) { respond(exchange, 404, Map.of()); return; }
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            }
        } finally { exchange.close(); }
    }

    private boolean exists(String path) { return files.containsKey(path) || directories.contains(path) || symlinks.contains(path); }

    private Map<String, Object> status(String path, boolean suffix) {
        return Map.of("pathSuffix", suffix ? RemoteFilePaths.name(path) : "", "type", directories.contains(path) ? "DIRECTORY" : symlinks.contains(path) ? "SYMLINK" : "FILE",
                "length", files.containsKey(path) ? files.get(path).length : 0, "modificationTime", 1770000000000L);
    }

    private Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
