package cn.iocoder.yudao.framework.file.core.remote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 仅支持显式配置的 WebHDFS 简单认证，不使用 Kerberos 或 HTTP 密码认证。 */
final class WebHdfsRemoteFileClient implements RemoteFileClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RemoteFileConfig config;
    private final URI endpoint;
    private final Set<String> dataNodes = new HashSet<>();
    private final RemoteFileSessionDeadline deadline;
    private volatile boolean closed;
    private volatile HttpURLConnection activeConnection;

    WebHdfsRemoteFileClient(RemoteFileConfig config) throws IOException {
        this.config = config;
        try {
            endpoint = URI.create(config.webHdfsUrl());
            validateEndpoint(endpoint);
            if (!(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")
                    || endpoint.getPath().equals("/webhdfs/v1"))) throw new IllegalArgumentException("WebHDFS 地址路径不合法");
            dataNodes.add(authority(endpoint));
            for (String allowed : config.allowedDataNodeAuthorities()) {
                URI node = URI.create(endpoint.getScheme() + "://" + allowed);
                validateEndpoint(node);
                if (!node.getPath().isEmpty()) throw new IllegalArgumentException("DataNode 地址不合法");
                dataNodes.add(authority(node));
            }
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalArgumentException("WebHDFS 连接配置不合法");
        }
        deadline = new RemoteFileSessionDeadline(() -> {
            closed = true;
            HttpURLConnection connection = activeConnection;
            if (connection != null) connection.disconnect();
        });
        try { guard("/", true); }
        catch (IOException | RuntimeException error) { close(); throw error; }
    }

    @Override
    public List<RemoteFileEntry> list(String path) throws IOException {
        path = RemoteFilePaths.normalize(path);
        String absolute = guard(path, true);
        List<RemoteFileEntry> result = new ArrayList<>();
        for (JsonNode node : listAbsolute(absolute)) {
            String type = node.path("type").asString();
            if (type.equals("SYMLINK")) continue;
            result.add(entry(RemoteFilePaths.child(path, node.path("pathSuffix").asString()), node));
        }
        return result;
    }

    @Override
    public RemoteFileEntry stat(String path) throws IOException {
        path = RemoteFilePaths.normalize(path);
        String absolute = guard(path, false);
        return entry(path, requestJson("GET", absolute, "GETFILESTATUS", Map.of()).path("FileStatus"));
    }

    @Override
    public void upload(String path, InputStream input, long size, boolean overwrite) throws IOException {
        RemoteFilePaths.requireMutable(path);
        RemoteFileStreams.validateUploadSize(size);
        guard(RemoteFilePaths.parent(path), true);
        RemoteFileOperations.checkUploadTarget(this, path, overwrite);
        String temporary = RemoteFilePaths.child(RemoteFilePaths.parent(path), ".remote-file-upload-" + UUID.randomUUID());
        String absolute = RemoteFilePaths.resolve(config.rootPath(), temporary);
        URI initial = operationUri(absolute, "CREATE", Map.of("overwrite", "false", "createparent", "false"));
        HttpURLConnection first = open(initial, "PUT");
        URI location;
        try {
            if (first.getResponseCode() != 307) throw new IOException("WebHDFS 创建请求未返回受控重定向");
            location = redirect(initial, first.getHeaderField("Location"), "CREATE");
        } finally { first.disconnect(); }
        boolean created = false;
        try {
            HttpURLConnection upload = open(location, "PUT");
            try {
                upload.setDoOutput(true);
                upload.setFixedLengthStreamingMode(size);
                upload.setRequestProperty("Content-Type", "application/octet-stream");
                long written;
                try (OutputStream stream = upload.getOutputStream()) { written = RemoteFileStreams.copy(input, stream, size); }
                if (written != size) throw new IOException("上传大小与声明不一致");
                if (upload.getResponseCode() != 201) throw new IOException("WebHDFS 上传失败");
                created = true;
            } finally { upload.disconnect(); }
            RemoteFileOperations.commitUpload(this, temporary, path, overwrite);
        } finally {
            // 暂存文件只可能属于当前请求，失败时尝试回收；提交后的原件备份由提交器管理。
            try { delete(temporary); } catch (FileNotFoundException ignored) {
                // 提交成功后暂存名称已不存在。
            } catch (IOException ignored) {
                if (!created) closed = true;
            }
        }
    }

    @Override
    public void download(String path, OutputStream output, long maxBytes) throws IOException {
        RemoteFileEntry file = stat(path);
        if (file.directory()) throw new IOException("目录需要打包下载");
        if (file.size() > maxBytes) throw new IOException("文件超过下载上限");
        URI initial = operationUri(RemoteFilePaths.resolve(config.rootPath(), path), "OPEN", Map.of());
        HttpURLConnection connection = open(initial, "GET");
        try {
            int status = connection.getResponseCode();
            if (status == 307) {
                URI target = redirect(initial, connection.getHeaderField("Location"), "OPEN");
                connection.disconnect();
                connection = open(target, "GET");
                status = connection.getResponseCode();
            }
            if (status != 200) throw new IOException("WebHDFS 下载失败");
            try (InputStream input = connection.getInputStream()) { RemoteFileStreams.copy(input, output, maxBytes); }
        } finally { connection.disconnect(); }
    }

    @Override
    public void rename(String path, String target) throws IOException {
        RemoteFilePaths.requireMutable(path);
        RemoteFilePaths.requireMutable(target);
        guard(path, false);
        guard(RemoteFilePaths.parent(target), true);
        requireAbsent(target);
        requireTrue(requestJson("PUT", RemoteFilePaths.resolve(config.rootPath(), path), "RENAME",
                Map.of("destination", RemoteFilePaths.resolve(config.rootPath(), target))));
    }

    @Override
    public void mkdir(String path) throws IOException {
        RemoteFilePaths.requireMutable(path);
        guard(RemoteFilePaths.parent(path), true);
        requireAbsent(path);
        requireTrue(requestJson("PUT", RemoteFilePaths.resolve(config.rootPath(), path), "MKDIRS", Map.of()));
    }

    @Override
    public void delete(String path) throws IOException {
        RemoteFilePaths.requireMutable(path);
        guard(path, false);
        requireTrue(requestJson("DELETE", RemoteFilePaths.resolve(config.rootPath(), path), "DELETE", Map.of("recursive", "false")));
    }

    private String guard(String virtualPath, boolean directory) throws IOException {
        deadline.check();
        if (closed) throw new IOException("文件连接已关闭");
        String absolute = RemoteFilePaths.resolve(config.rootPath(), virtualPath);
        String current = "/";
        JsonNode found = null;
        if (!absolute.equals("/")) {
            for (String part : absolute.substring(1).split("/")) {
                found = null;
                for (JsonNode node : listAbsolute(current)) {
                    if (node.path("pathSuffix").asString().equals(part)) { found = node; break; }
                }
                if (found == null) throw new FileNotFoundException("文件不存在");
                current = RemoteFilePaths.child(current, part);
                String type = found.path("type").asString();
                if (!type.equals("FILE") && !type.equals("DIRECTORY")) throw new IOException("禁止访问符号链接或特殊文件");
                if (!current.equals(absolute) && !type.equals("DIRECTORY")) throw new IOException("父路径不是目录");
            }
        }
        if (directory && found != null && !found.path("type").asString().equals("DIRECTORY")) throw new IOException("路径不是目录");
        return absolute;
    }

    private List<JsonNode> listAbsolute(String path) throws IOException {
        JsonNode nodes = requestJson("GET", path, "LISTSTATUS", Map.of()).path("FileStatuses").path("FileStatus");
        if (!nodes.isArray() || nodes.size() > RemoteFileStreams.MAX_ENTRIES) throw new IOException("目录响应不合法或超过上限");
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            RemoteFilePaths.child(path, node.path("pathSuffix").asString());
            result.add(node);
        }
        return result;
    }

    private RemoteFileEntry entry(String path, JsonNode node) throws IOException {
        String type = node.path("type").asString();
        if (!type.equals("FILE") && !type.equals("DIRECTORY")) throw new IOException("禁止访问符号链接或特殊文件");
        long size = node.path("length").asLong(0);
        if (size < 0) throw new IOException("文件大小不合法");
        return new RemoteFileEntry(RemoteFilePaths.name(path), path, type.equals("DIRECTORY"),
                type.equals("DIRECTORY") ? 0 : size, node.path("modificationTime").asLong(0));
    }

    private JsonNode requestJson(String method, String absolute, String operation, Map<String, String> args) throws IOException {
        HttpURLConnection connection = open(operationUri(absolute, operation, args), method);
        try {
            int status = connection.getResponseCode();
            if (status == 404) throw new FileNotFoundException("文件不存在");
            if (status != 200) throw new IOException("WebHDFS 操作失败，HTTP " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) { RemoteFileStreams.copy(input, bytes, 4L * 1024 * 1024); }
            try { return JSON.readTree(bytes.toByteArray()); }
            catch (RuntimeException error) { throw new IOException("WebHDFS 响应格式不合法"); }
        } finally { connection.disconnect(); }
    }

    private URI operationUri(String absolute, String operation, Map<String, String> args) {
        StringBuilder query = new StringBuilder("op=").append(operation);
        if (config.username() != null && !config.username().isBlank()) query.append("&user.name=").append(encode(config.username()));
        args.forEach((key, value) -> query.append('&').append(encode(key)).append('=').append(encode(value)));
        try {
            String path = new URI(null, null, "/webhdfs/v1" + absolute, null).toASCIIString();
            return URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path + "?" + query);
        } catch (java.net.URISyntaxException error) { throw new IllegalArgumentException("文件路径不合法"); }
    }

    private URI redirect(URI initial, String location, String operation) throws IOException {
        try {
            URI target = URI.create(location);
            validateEndpoint(target, true);
            if (!dataNodes.contains(authority(target)) || !target.getScheme().equalsIgnoreCase(initial.getScheme())
                    || !target.getRawPath().equals(initial.getRawPath()) || !queryValue(target, "op").equals(operation)) {
                throw new IOException("WebHDFS 重定向不在授权范围");
            }
            if (!queryValue(target, "user.name").equals(queryValue(initial, "user.name"))
                    || (operation.equals("CREATE") && (!queryValue(target, "overwrite").equals("false")
                    || !queryValue(target, "createparent").equals("false")))) {
                throw new IOException("WebHDFS 重定向修改了认证或写入约束");
            }
            return target;
        } catch (IllegalArgumentException | NullPointerException error) { throw new IOException("WebHDFS 重定向不合法"); }
    }

    private HttpURLConnection open(URI uri, String method) throws IOException {
        deadline.check();
        if (closed) throw new IOException("文件连接已关闭");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        // 禁止回退到应用注册的全局 HTTP 密码认证器。
        connection.setAuthenticator(new java.net.Authenticator() { });
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        activeConnection = connection;
        return connection;
    }

    private void requireAbsent(String path) throws IOException {
        try { stat(path); } catch (FileNotFoundException missing) { return; }
        throw new IOException("目标名称已存在");
    }

    private static void requireTrue(JsonNode response) throws IOException {
        if (!response.path("boolean").asBoolean(false)) throw new IOException("WebHDFS 操作未成功");
    }

    private static void validateEndpoint(URI uri) { validateEndpoint(uri, false); }

    private static void validateEndpoint(URI uri, boolean queryAllowed) {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                || (!queryAllowed && uri.getRawQuery() != null) || uri.getPort() > 65535 || uri.getPort() == 0) {
            throw new IllegalArgumentException("WebHDFS 地址不合法");
        }
    }

    private static String authority(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT) + ":" + (uri.getPort() < 0 ? uri.getScheme().equalsIgnoreCase("https") ? 443 : 80 : uri.getPort());
    }

    private static String queryValue(URI uri, String name) throws IOException {
        String result = null;
        for (String entry : uri.getRawQuery().split("&")) {
            String[] pair = entry.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                if (result != null || pair.length != 2) throw new IOException("WebHDFS 重定向参数重复");
                result = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return result == null ? "" : result;
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    @Override public void close() {
        closed = true;
        deadline.close();
        HttpURLConnection connection = activeConnection;
        activeConnection = null;
        if (connection != null) connection.disconnect();
    }
}
