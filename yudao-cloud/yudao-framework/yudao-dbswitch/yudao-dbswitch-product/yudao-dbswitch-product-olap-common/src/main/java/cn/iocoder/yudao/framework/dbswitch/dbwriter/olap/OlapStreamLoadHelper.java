package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Doris/StarRocks Stream Load（HTTP）批量写入辅助器（移植自 dbswitch 2.0.2 的
 * DorisUtils/StarRocksUtils 并增强）
 * <p>
 * 工作方式：
 * <ol>
 * <li>初始化时从 JDBC URL 提取 FE 主机、复用数据库账号做 Basic 认证，
 * 通过同一连接的 {@code SHOW FRONTENDS}/{@code SHOW BACKENDS} 验证端点并固定解析地址，
 * 目录权限、端点或端口无法确认时，在写入前选择 JDBC；</li>
 * <li>写入时把一批记录组装为 {@code {"data":[{...}]}} JSON，PUT 到
 * {@code /api/{db}/{table}/_stream_load}；响应丢失不能证明远端未写入，禁止回退重写；</li>
 * <li>只有 HTTP 200 且完整 JSON 的 Status 明确为 Success（可见）才确认成功；
 * Publish Timeout（已提交未可见）、label 已使用、Fail、空响应和无法识别的响应均交调用方核查，不推断终止或成功；</li>
 * <li>每个请求携带唯一 label（FE 跳转前后相同），服务端按 label 去重；结果未知时只记录接收状态与 label 供核对，绝不换 label 重投。</li>
 * </ol>
 *
 * @author yudao
 */
public final class OlapStreamLoadHelper {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OlapStreamLoadHelper.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final int MAX_RECEIPT_BYTES = 65_536;

    private static final ScheduledThreadPoolExecutor DEADLINES;

    static {
        DEADLINES = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = Thread.ofPlatform().name("dbswitch-stream-load-deadline").daemon()
                    .inheritInheritableThreadLocals(false).unstarted(task);
            thread.setContextClassLoader(OlapStreamLoadHelper.class.getClassLoader());
            return thread;
        });
        DEADLINES.setRemoveOnCancelPolicy(true);
        DEADLINES.setKeepAliveTime(30, TimeUnit.SECONDS);
        DEADLINES.allowCoreThreadTimeOut(true);
    }

    private static final DateTimeFormatter DATETIME_FORMATTER =
            new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter(java.util.Locale.ROOT);

    private final String productName;

    private final OlapStreamLoadProfile profile;

    private final int queryTimeoutSeconds;

    private final String dbName;

    private final String tbName;

    private final String host;

    private final String httpPort;

    private final String username;

    private final String password;

    private final Set<Endpoint> backends;

    private final Map<String, InetAddress[]> pinnedAddresses;

    // 保持私有：测试替换套接字工厂，以对应精确 Docker NAT 映射。
    // 任何运行时选项都不能将不可信重定向变为允许的端点。
    private Function<RequestConfig, CloseableHttpClient> clientFactory = this::newClient;

    // 每个请求使用一个服务器端幂等标签；重复标签绝不代表确认。
    private java.util.function.Supplier<String> labelFactory =
            () -> "dbswitch_" + java.util.UUID.randomUUID().toString().replace("-", "");

    private final AtomicBoolean sending = new AtomicBoolean();

    private volatile boolean unconfirmed;

    private volatile String lastLabel;

    private volatile ReceiptState lastObservation = ReceiptState.NO_RECEIPT;

    /**
     * 使用给定参数初始化 {@code OlapStreamLoadHelper}。
     */
    private OlapStreamLoadHelper(String productName, String dbName, String tbName,
            String host, String httpPort, String username, String password, int queryTimeoutSeconds) {
        this(productName, dbName, tbName, host, httpPort, username, password, queryTimeoutSeconds,
                Set.of(), Map.of(host.toLowerCase(Locale.ROOT), resolve(host)));
    }

    /**
     * 校验构造参数并初始化 {@code OlapStreamLoadHelper}。
     */
    private OlapStreamLoadHelper(String productName, String dbName, String tbName,
            String host, String httpPort, String username, String password, int queryTimeoutSeconds,
            Set<Endpoint> backends, Map<String, InetAddress[]> addresses) {
        if (queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Query timeout must be positive");
        }
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.productName = productName;
        this.profile = OlapStreamLoadProfiles.of(productName);
        this.dbName = dbName;
        this.tbName = tbName;
        this.host = host;
        this.httpPort = httpPort;
        this.username = username;
        this.password = password;
        this.backends = Set.copyOf(backends);
        this.pinnedAddresses = Map.copyOf(addresses);
    }

    /**
     * 尝试创建 Stream Load 辅助器：任何一步失败（URL 无法解析、SHOW FRONTENDS 失败等）
     * 都返回 null 并输出告警，由调用方回退 JDBC 写入
     */
    public static OlapStreamLoadHelper tryCreate(DataSource dataSource, String schemaName,
            String tableName, String productName) {
        return tryCreate(dataSource, schemaName, tableName, productName,
                DbSwitchOptions.defaults().queryTimeoutSeconds());
    }

    /**
     * 根据当前输入构建并返回 {@code OlapStreamLoadHelper}。
     */
    public static OlapStreamLoadHelper tryCreate(DataSource dataSource, String schemaName,
            String tableName, String productName, int queryTimeoutSeconds) {
        if (queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Query timeout must be positive");
        }
        try {
            ConnectionInfo info = resolveConnectionInfo(dataSource);
            if (info == null || info.jdbcUrl == null) {
                log.warn("[{}] StreamLoad init skipped: can not resolve jdbc url from datasource", productName);
                return null;
            }
            URI jdbc = URI.create(info.jdbcUrl.startsWith("jdbc:") ? info.jdbcUrl.substring(5) : "");
            if (!"mysql".equalsIgnoreCase(jdbc.getScheme()) || jdbc.getHost() == null
                    || jdbc.getUserInfo() != null || jdbc.getPort() < 1 || jdbc.getPort() > 65535) {
                log.warn("[{}] StreamLoad init skipped: unsupported JDBC host format", productName);
                return null;
            }
            String host = jdbc.getHost().toLowerCase(Locale.ROOT);
            Topology topology = discover(dataSource, host, queryTimeoutSeconds);
            String httpPort = Integer.toString(topology.frontendPort());
            log.info("[{}] StreamLoad enabled with pinned FE/BE endpoints", productName);
            return new OlapStreamLoadHelper(productName, schemaName, tableName,
                    host, httpPort, info.username, info.password, queryTimeoutSeconds,
                    topology.backends(), topology.addresses());
        } catch (Exception t) {
            log.warn(
                    "[{}] StreamLoad discovery unavailable (catalog permission, endpoint or metadata validation); no HTTP request submitted; causeType={}",
                    productName, t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 通过 Stream Load 写入一批数据；失败或结果不确定时抛出异常，不允许自动回退重写。
     *
     * @return 写入的行数
     */
    public long write(List<String> fieldNames, List<Object[]> recordValues) throws Exception {
        return write(fieldNames, recordValues, java.util.Set.of());
    }

    /** 产品从临时文本 JSON 列中解码原生二进制列。 */
    public long write(List<String> fieldNames, List<Object[]> recordValues,
            java.util.Set<String> binaryFields) throws Exception {
        return writeEncoded(encode(fieldNames, recordValues, binaryFields, BatchLimits.defaults()));
    }

    /** 写入器借用批次预检连接前，先限制冻结的传输字节大小。 */
    public EncodedBatch encode(List<String> fieldNames, List<Object[]> recordValues, Set<String> binaryFields,
            BatchLimits limits) {
        requireUsable();
        if (binaryFields == null || fieldNames == null && !binaryFields.isEmpty() || fieldNames != null && !fieldNames.containsAll(
                binaryFields)
                || !binaryFields.isEmpty() && !profile.nativeBinaryColumns()) {
            throw new IllegalArgumentException("Invalid native binary Stream Load fields");
        }
        if (recordValues == null) {
            throw new IllegalArgumentException("Stream Load records are required");
        }
        if (recordValues.isEmpty()) {
            return new EncodedBatch(new byte[0], 0, Map.of());
        }
        if (recordValues.size() > limits.maxRows()) {
            throw new BatchRejectedException("Stream Load row budget exceeded");
        }
        if (fieldNames == null || fieldNames.isEmpty() || fieldNames.size() > 1024 || fieldNames.stream()
                .anyMatch(f -> f == null || f.isEmpty())
                || new HashSet<>(
                fieldNames.stream().map(f -> f.toLowerCase(Locale.ROOT)).toList()).size() != fieldNames.size()
                || recordValues.stream().anyMatch(r -> r == null || r.length != fieldNames.size())) {
            throw new IllegalArgumentException("Stream Load rows must match distinct prepared fields");
        }
        try {
            var output = new BoundedPayload(limits.maxBatchBytes());
            try (var json = OBJECT_MAPPER.getFactory().createGenerator(output)) {
                json.writeStartObject();
                json.writeArrayFieldStart("data");
                for (Object[] row : recordValues) {
                    json.writeStartObject();
                    for (int index = 0; index < fieldNames.size(); index++) {
                        json.writeFieldName(fieldNames.get(index));
                        json.flush();
                        output.cellLimit(limits.maxCellBytes());
                        json.writeObject(normalizeJsonValue(row[index]));
                        json.flush();
                        output.endCell();
                    }
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeEndObject();
            }
            return new EncodedBatch(output.toByteArray(), recordValues.size(),
                    binaryFields.isEmpty() ? Map.of() : profile.binaryColumnHeaders(fieldNames, binaryFields));
        } catch (BatchRejectedException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new BatchRejectedException("Stream Load payload is outside its wire contract");
        }
    }

    public static final class EncodedBatch {

        private final byte[] bytes;

        private final long rows;

        private final Map<String, String> headers;

        /**
         * 初始化 {@code EncodedBatch}。
         */
        private EncodedBatch(byte[] bytes, long rows, Map<String, String> headers) {
            this.bytes = bytes;
            this.rows = rows;
            this.headers = Map.copyOf(headers);
        }
    }

    private static final class BoundedPayload extends java.io.ByteArrayOutputStream {

        private final long maximum;

        private long cellMaximum = Long.MAX_VALUE;

        /**
         * 初始化 {@code BoundedPayload}。
         */
        private BoundedPayload(long maximum) {
            this.maximum = maximum;
        }

        /**
         * 为当前单元格设置相对于已写字节数的容量上限。
         */
        void cellLimit(long maximum) {
            this.cellMaximum = count + maximum;
        }

        /**
         * 结束单元格局部容量限制，恢复输出流的整体预算。
         */
        void endCell() {
            cellMaximum = Long.MAX_VALUE;
        }

        /**
         * 检查长度是否满足当前操作的约定。
         */
        private void require(int length) {
            if (length < 0 || (long) count + length > Math.min(maximum, cellMaximum)) {
                throw new BatchRejectedException("Stream Load encoded payload exceeds the byte budget");
            }
        }

        /**
         * 按当前字段投影编码行载荷并提交 Stream Load，请求后核对写入回执。
         */
        @Override
        public synchronized void write(int value) {
            require(1);
            super.write(value);
        }

        /**
         * 按当前字段投影编码行载荷并提交 Stream Load，请求后核对写入回执。
         */
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            require(length);
            super.write(bytes, offset, length);
        }
    }

    /**
     * 检查上次写入不存在未确认结果后允许继续使用写入器。
     */
    public void requireUsable() {
        if (unconfirmed) {
            throw new UnconfirmedWriteException(lastLabel,
                    UnconfirmedWriteException.ReceiptObservation.valueOf(lastObservation.name()));
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new BatchRejectedException("Stream Load cancelled before submission");
        }
    }

    /** UNKNOWN 实例不能提交另一个标签。由所属任务负责核对。 */
    public long writeEncoded(EncodedBatch batch) {
        requireUsable();
        if (!sending.compareAndSet(false, true)) {
            throw new BatchRejectedException("Concurrent Stream Load submission is unsupported");
        }
        try {
            requireUsable();
            if (batch.rows == 0) {
                return 0;
            }
            sendData(batch.bytes, batch.rows, batch.headers);
            return batch.rows;
        } finally {
            sending.set(false);
        }
    }

    /**
     * 发送数据。
     */
    private void sendData(byte[] content, long expectedRows, Map<String, String> nativeHeaders) {
        String authority = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        URI initial = URI.create("http://" + authority + ":" + httpPort + "/api/"
                + pathSegment(dbName) + "/" + pathSegment(tbName) + "/_stream_load");
        // 直接提交 BE 是原生 Stream Load 操作。必须在任何 HTTP 请求
        // 之前选择固定节点，绝不能在 FE 响应未确认后作为重试。
        boolean directBackend = profile.directBackendForReservedNames() && (reservedPathName(
                dbName) || reservedPathName(tbName));
        if (directBackend) {
            Endpoint backend = backends.stream().sorted(java.util.Comparator.comparing(Endpoint::host)
                            .thenComparingInt(Endpoint::port)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(productName + " direct BE route is not prepared"));
            try {
                initial = URI.create(new URI("http", null, backend.host(), backend.port(), null, null, null)
                        .toASCIIString() + initial.getRawPath());
            } catch (java.net.URISyntaxException failure) {
                throw new IllegalArgumentException("Invalid " + productName + " backend endpoint");
            }
        }
        String label = labelFactory.get();
        if (label == null || !label.matches("[A-Za-z0-9_:-]{1,128}")) {
            throw new IllegalStateException("Invalid Stream Load label");
        }
        lastLabel = label;
        int timeoutMs = (int) Math.min((long) queryTimeoutSeconds * 1000L, Integer.MAX_VALUE);
        RequestConfig config = RequestConfig.custom().setConnectTimeout(Math.min(timeoutMs, 10_000))
                .setConnectionRequestTimeout(Math.min(timeoutMs, 10_000)).setSocketTimeout(timeoutMs)
                .setRedirectsEnabled(false).build();
        var active = new AtomicReference<HttpPut>();
        var expired = new AtomicBoolean();
        var observed = new AtomicReference<>(ReceiptState.NO_RECEIPT);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(queryTimeoutSeconds);
        var cancellation = DEADLINES.schedule(() -> {
            expired.set(true);
            HttpPut request = active.get();
            if (request != null) {
                request.abort();
            }
        }, queryTimeoutSeconds, TimeUnit.SECONDS);
        Thread owner = Thread.currentThread();
        var interrupt = DEADLINES.scheduleAtFixedRate(() -> {
            if (owner.isInterrupted()) {
                expired.set(true);
                HttpPut request = active.get();
                if (request != null) {
                    request.abort();
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        try (CloseableHttpClient client = clientFactory.apply(config)) {
            URI destination = initial;
            for (int hop = 0; hop < (directBackend ? 1 : 2); hop++) {
                HttpPut put = new HttpPut(destination);
                put.setConfig(config);
                put.setHeader(HttpHeaders.EXPECT, "100-continue");
                put.setHeader(HttpHeaders.AUTHORIZATION, basicAuthHeader(username, password));
                put.setHeader("strip_outer_array", "true");
                put.setHeader("format", "JSON");
                put.setHeader("json_root", "$.data");
                put.setHeader("Content-Type", "application/json");
                put.setHeader("strict_mode", "true");
                put.setHeader("max_filter_ratio", "0");
                profile.requestHeaders().forEach(put::setHeader);
                put.setHeader("timeout", Integer.toString(queryTimeoutSeconds));
                put.setHeader("label", label);
                nativeHeaders.forEach(put::setHeader);
                put.setEntity(new ByteArrayEntity(content));
                active.set(put);
                if (expired.get() || owner.isInterrupted() || System.nanoTime() - deadline >= 0) {
                    throw new UnconfirmedWriteException();
                }
                try (CloseableHttpResponse response = client.execute(put)) {
                    int status = response.getStatusLine().getStatusCode();
                    if (!directBackend && hop == 0 && (status == 307 || status == 308)) {
                        var location = response.getHeaders("Location");
                        if (location.length != 1) {
                            throw new UnconfirmedWriteException();
                        }
                        destination = validatedRedirect(initial, initial.resolve(location[0].getValue()));
                        continue;
                    }
                    if (status != 200 || response.getEntity() == null
                            || response.getEntity().getContentLength() > MAX_RECEIPT_BYTES) {
                        throw new UnconfirmedWriteException();
                    }
                    byte[] bytes = response.getEntity().getContent().readNBytes(MAX_RECEIPT_BYTES + 1);
                    if (bytes.length > MAX_RECEIPT_BYTES || expired.get() || owner.isInterrupted() || System.nanoTime() - deadline >= 0) {
                        throw new UnconfirmedWriteException();
                    }
                    String receipt = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    observed.set(receiptState(receipt, expectedRows));
                    JsonNode envelope = OBJECT_MAPPER.readTree(receipt);
                    if (observed.get() == ReceiptState.VISIBLE && (!envelope.path("Label").isTextual()
                            || !label.equals(envelope.path("Label").asText()) || !envelope.path("TxnId")
                            .isIntegralNumber()
                            || !envelope.path("TxnId").canConvertToLong() || envelope.path("TxnId").longValue() <= 0)) {
                        observed.set(ReceiptState.VISIBLE_INCOMPLETE);
                    }
                    if (observed.get() != ReceiptState.VISIBLE || owner.isInterrupted()) {
                        throw new UnconfirmedWriteException();
                    }
                    return;
                }
            }
            throw new UnconfirmedWriteException();
        } catch (Exception failure) {
            lastObservation = observed.get();
            unconfirmed = true;
            HttpPut request = active.get();
            if (request != null) {
                request.abort();
            }
            // 仅记录回执状态和标签：不包含载荷、URL、凭据或厂商消息。
            log.warn("[{}] Stream Load outcome unconfirmed (receipt {}); reconcile label {} before any retry",
                    productName, observed.get(), label);
            // 绝不在异常原因链保留远端载荷、凭据、URL 或厂商消息。
            throw new UnconfirmedWriteException(label,
                    UnconfirmedWriteException.ReceiptObservation.valueOf(observed.get().name()));
        } finally {
            cancellation.cancel(false);
            interrupt.cancel(false);
        }
    }

    /**
     * 判断对象名称是否包含 URL 路径中的保留字符。
     */
    private static boolean reservedPathName(String name) {
        return name.indexOf('%') >= 0 || name.indexOf('?') >= 0 || name.indexOf('#') >= 0;
    }

    /**
     * 创建关闭自动重试和重定向的 Stream Load HTTP 客户端。
     */
    private CloseableHttpClient newClient(RequestConfig config) {
        return HttpClients.custom().setDefaultRequestConfig(config).disableAutomaticRetries()
                .disableRedirectHandling().disableContentCompression().disableCookieManagement()
                .setDnsResolver(name -> {
                    InetAddress[] addresses = pinnedAddresses.get(name.toLowerCase(Locale.ROOT));
                    if (addresses == null) {
                        throw new java.net.UnknownHostException("Unpinned Stream Load endpoint");
                    }
                    return addresses.clone();
                }).build();
    }

    /**
     * 回显的 Basic 账号仅是待比较证据，绝不是新的凭据。
     */
    private URI validatedRedirect(URI initial, URI target) {
        if (!"http".equalsIgnoreCase(target.getScheme())
                || target.getRawQuery() != null || target.getFragment() != null || target.getHost() == null
                || !initial.getRawPath().equals(target.getRawPath())
                || !backends.contains(new Endpoint(target.getHost().toLowerCase(Locale.ROOT), target.getPort()))) {
            throw new UnconfirmedWriteException();
        }
        if (target.getRawUserInfo() != null) {
            // 回显的 Basic 账号仅是待比较证据，绝不是新的凭据
            // 来源，也绝不传给 HTTP 客户端。
            String supplied = decodedUserInfo(target.getRawUserInfo());
            String expected = (username == null ? "" : username) + ":" + (password == null ? "" : password);
            if (!profile.redirectEchoesBasicAccount() || supplied.codePoints().anyMatch(Character::isISOControl)
                    || !java.security.MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                throw new UnconfirmedWriteException();
            }
        }
        try {
            URI authority = new URI("http", null, target.getHost(), target.getPort(), null, null, null);
            return URI.create(authority.toASCIIString() + initial.getRawPath());
        } catch (java.net.URISyntaxException failure) {
            throw new UnconfirmedWriteException();
        }
    }

    /**
     * 按受限大小解析并解码 URL 用户信息。
     */
    private static String decodedUserInfo(String raw) {
        if (raw.length() > 16_384) {
            throw new UnconfirmedWriteException();
        }
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            for (int index = 0; index < raw.length(); ) {
                char value = raw.charAt(index);
                if (value == '%') {
                    if (index + 2 >= raw.length()) {
                        throw new UnconfirmedWriteException();
                    }
                    int high = Character.digit(raw.charAt(index + 1), 16), low = Character.digit(raw.charAt(index + 2),
                            16);
                    if (high < 0 || low < 0) {
                        throw new UnconfirmedWriteException();
                    }
                    bytes.write(high * 16 + low);
                    index += 3;
                } else {
                    int codePoint = raw.codePointAt(index);
                    ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(Character.toChars(codePoint)));
                    while (encoded.hasRemaining()) {
                        bytes.write(encoded.get());
                    }
                    index += Character.charCount(codePoint);
                }
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new UnconfirmedWriteException();
        }
    }

    /**
     * 校验并编码 Stream Load 请求中的数据库或表名路径段。
     */
    private static String pathSegment(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Empty Stream Load relation");
        }
        if (value.equals(".") || value.equals("..")) {
            return "%2E".repeat(value.length());
        }
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            StringBuilder encoded = new StringBuilder();
            while (bytes.hasRemaining()) {
                int b = bytes.get() & 255;
                if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '-' || b == '_' || b == '.' || b == '~') {
                    encoded.append((char) b);
                } else {
                    encoded.append('%').append("0123456789ABCDEF".charAt(b >>> 4))
                            .append("0123456789ABCDEF".charAt(b & 15));
                }
            }
            return encoded.toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid Stream Load relation encoding");
        }
    }

    /**
     * 回执状态须来自报告，绝不推断。只有 VISIBLE（Status Success 且此精确
     * 批次的回执完整）才确认。已接收但无回执的请求、Publish Timeout（已提交但不可见）、
     * 标签复用和报告失败都保持 UNKNOWN：不改写为 JDBC，也不使用任何标签发送第二次请求。
     */
    enum ReceiptState {NO_RECEIPT, UNRECOGNIZED, REPORTED_FAILURE, LABEL_ALREADY_USED, COMMITTED_NOT_VISIBLE, VISIBLE_INCOMPLETE, VISIBLE}

    /**
     * 请求成功仍可能过滤或遗漏记录。只有本次提交批次的完整回执。
     */
    static ReceiptState receiptState(String loadResult, long expectedRows) {
        final JsonNode node;
        try {
            node = loadResult == null || loadResult.isBlank() ? null
                    : OBJECT_MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(loadResult);
        } catch (Exception parseEx) {
            return ReceiptState.UNRECOGNIZED;
        }
        if (node == null || !node.isObject() || !node.path("Status").isTextual()) {
            return ReceiptState.UNRECOGNIZED;
        }
        switch (node.path("Status").asText().toLowerCase(Locale.ROOT)) {
            case "success":
                break;
            case "publish timeout":
                return ReceiptState.COMMITTED_NOT_VISIBLE;
            case "label already exists":
                return ReceiptState.LABEL_ALREADY_USED;
            case "fail":
                return ReceiptState.REPORTED_FAILURE;
            default:
                return ReceiptState.UNRECOGNIZED;
        }
        // 请求成功仍可能过滤或遗漏记录。只有本次提交批次的完整回执
        // 才可计数；任何其他结果都可能表示目标已经改变。
        return expectedRows >= 1 && rowCount(node, "NumberTotalRows") == expectedRows
                && rowCount(node, "NumberLoadedRows") == expectedRows && rowCount(node, "NumberFilteredRows") == 0
                && rowCount(node, "NumberUnselectedRows") == 0 ? ReceiptState.VISIBLE : ReceiptState.VISIBLE_INCOMPLETE;
    }

    /**
     * 读取响应中的非负整数行数，非法或缺失时返回未知值。
     */
    private static long rowCount(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : -1;
    }

    /**
     * 以 UTF-8 编码用户名和密码并生成 HTTP Basic 认证头，空值按空字符串处理。
     */
    private static String basicAuthHeader(String username, String password) {
        String toBeEncoded = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(toBeEncoded.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按字段名将数据行数组转换为对象列表。
     */
    private static List<Object> asObjectList(List<String> fieldNames, List<Object[]> recordValues) {
        int fieldCount = fieldNames.size();
        List<Object> rows = new ArrayList<>(recordValues.size());
        for (Object[] row : recordValues) {
            Map<String, Object> columns = new LinkedHashMap<>(fieldCount);
            for (int i = 0; i < fieldCount; ++i) {
                columns.put(fieldNames.get(i), normalizeJsonValue(row[i]));
            }
            rows.add(columns);
        }
        return rows;
    }

    /**
     * 把日期/时间类值转成 Doris/StarRocks 可识别的字符串字面量，其余类型交给 Jackson
     * （包内可见以便单元测试）
     */
    static Object normalizeJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp
                || value instanceof java.sql.Date
                || value instanceof java.sql.Time) {
            return String.valueOf(value);
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DATETIME_FORMATTER);
        }
        if (value instanceof OffsetDateTime) {
            return java.sql.Timestamp.from(((OffsetDateTime) value).toInstant()).toString();
        }
        if (value instanceof java.time.LocalDate || value instanceof java.time.LocalTime) {
            return String.valueOf(value);
        }
        if (value instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) value).getTime()).toString();
        }
        return value;
    }

    /**
     * 通过 SHOW FRONTENDS 自动发现 FE 的 HTTP 端口：
     * 验证已配置 JDBC 入口对应的唯一存活 FE HTTP 端口及同连接目录中的 BE 地址。
     */
    private record Endpoint(String host, int port) {

    }

    private record Topology(int frontendPort, Set<Endpoint> backends, Map<String, InetAddress[]> addresses) {

    }

    /** 凭据离开集群前，两个目录都须来自配置的 JDBC 集群。 */
    private static Topology discover(DataSource source, String jdbcHost, int timeoutSeconds) throws Exception {
        Map<String, InetAddress[]> pins = new HashMap<>();
        InetAddress[] frontendAddresses = resolve(jdbcHost);
        pins.put(jdbcHost, frontendAddresses);
        Set<Integer> frontendPorts = new HashSet<>();
        Set<Endpoint> backends = new HashSet<>();
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(257);
            try (ResultSet rows = statement.executeQuery("SHOW FRONTENDS")) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 256) {
                        throw new IllegalArgumentException("Stream Load topology exceeds the node budget");
                    }
                    if ("true".equalsIgnoreCase(rows.getString("Alive"))) {
                        frontendPorts.add(port(rows.getString("HttpPort")));
                    }
                }
            }
            // JDBC 负载均衡器不足以证明应选择多个不同 HTTP 端口中的哪一个。
            if (frontendPorts.size() != 1) {
                throw new IllegalArgumentException("Stream Load frontend HTTP port is ambiguous");
            }
            try (ResultSet rows = statement.executeQuery("SHOW BACKENDS")) {
                int hostColumn = 0;
                ResultSetMetaData metadata = rows.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String label = metadata.getColumnLabel(index);
                    if ("Host".equalsIgnoreCase(label) || "IP".equalsIgnoreCase(label)) {
                        if (hostColumn != 0) {
                            throw new IllegalArgumentException("Ambiguous Stream Load backend host metadata");
                        }
                        hostColumn = index;
                    }
                }
                if (hostColumn == 0) {
                    throw new IllegalArgumentException("Missing Stream Load backend host metadata");
                }
                int count = 0;
                while (rows.next()) {
                    if (++count > 256) {
                        throw new IllegalArgumentException("Stream Load topology exceeds the node budget");
                    }
                    if (!"true".equalsIgnoreCase(rows.getString("Alive"))) {
                        continue;
                    }
                    String host = rows.getString(hostColumn);
                    if (host == null || host.length() > 253) {
                        throw new IllegalArgumentException("Invalid Stream Load backend host");
                    }
                    host = host.toLowerCase(Locale.ROOT);
                    int port = port(rows.getString("HttpPort"));
                    URI endpoint = new URI("http", null, host, port, null, null, null);
                    if (endpoint.getHost() == null || !endpoint.getHost().equalsIgnoreCase(host)) {
                        throw new IllegalArgumentException("Invalid Stream Load backend host");
                    }
                    InetAddress[] addresses = pins.computeIfAbsent(host, OlapStreamLoadHelper::resolve);
                    for (InetAddress address : addresses) {
                        boolean originLoopback = java.util.Arrays.stream(frontendAddresses)
                                .allMatch(InetAddress::isLoopbackAddress);
                        boolean originPrivate = java.util.Arrays.stream(frontendAddresses)
                                .allMatch(OlapStreamLoadHelper::privateAddress);
                        if (address.isLoopbackAddress() && !originLoopback
                                || privateAddress(address) != originPrivate) {
                            throw new IllegalArgumentException(
                                    "Stream Load redirect crosses the configured network trust boundary");
                        }
                    }
                    backends.add(new Endpoint(host, port));
                }
            }
        }
        if (backends.isEmpty()) {
            throw new IllegalArgumentException("No live authenticated Stream Load backend");
        }
        return new Topology(frontendPorts.iterator().next(), Set.copyOf(backends), Map.copyOf(pins));
    }

    /**
     * 返回端口。
     */
    private static int port(String text) {
        if (text == null || !text.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException("Invalid Stream Load port");
        }
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid Stream Load port");
        }
        return port;
    }

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    private static InetAddress[] resolve(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0 || addresses.length > 16) {
                throw new IllegalArgumentException();
            }
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException();
                }
            }
            return addresses;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Stream Load pinned address");
        }
    }

    /**
     * 判断网络地址是否位于回环、私网或本地范围。
     */
    private static boolean privateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isLoopbackAddress() || address.isSiteLocalAddress()
                || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    /**
     * 从数据源中提取 JDBC URL 与账号：支持常规 Hikari 配置与隔离驱动模式（InvisibleDataSource）
     */
    private static ConnectionInfo resolveConnectionInfo(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikari = (HikariDataSource) dataSource;
            if (hikari.getJdbcUrl() != null) {
                return new ConnectionInfo(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
            }
            // 隔离驱动模式：url/账号在底层 InvisibleDataSource 中
            if (hikari.getDataSource() instanceof InvisibleDataSource) {
                InvisibleDataSource invisible = (InvisibleDataSource) hikari.getDataSource();
                return new ConnectionInfo(invisible.getJdbcUrl(),
                        invisible.getProperties().getProperty("user"),
                        invisible.getProperties().getProperty("password"));
            }
            return null;
        }
        if (dataSource instanceof InvisibleDataSource) {
            InvisibleDataSource invisible = (InvisibleDataSource) dataSource;
            return new ConnectionInfo(invisible.getJdbcUrl(),
                    invisible.getProperties().getProperty("user"),
                    invisible.getProperties().getProperty("password"));
        }
        return null;
    }

    private static final class ConnectionInfo {

        private final String jdbcUrl;

        private final String username;

        private final String password;

        /**
         * 初始化 {@code ConnectionInfo}。
         */
        private ConnectionInfo(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }
    }
}
