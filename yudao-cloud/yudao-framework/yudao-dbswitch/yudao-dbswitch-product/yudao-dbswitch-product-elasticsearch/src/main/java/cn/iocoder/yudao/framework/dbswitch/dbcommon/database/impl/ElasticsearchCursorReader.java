package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.ElasticsearchSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchCursorKeys;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchMapping;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不持有读取快照的 Elasticsearch 类型化游标。水位字段必须采用单一可排序的 doc-values 映射
 * 类型（keyword、整数族、unsigned_long、date 或 date_nanos）；文本字段、{@code _id} 水位、规范化
 * 或部分索引的 keyword 字段，以及容忍错误格式的字段，均在释放任何行前拒绝。
 * 固定上界是在打开时显式 refresh 后可见的最大水位；单个升序
 * search_after 流按 {@code (watermark, _doc)} 分页，完整读取每个同水位分组，从而无需跨类型
 * {@code _id} 比较。完成时再次 refresh，并重新校验集群固定信息、索引
 * UUID、完整映射快照及精确交付窗口计数：窗口内并发插入或删除会使
 * 运行失败关闭，避免丢行。固定上界之上较晚到达的行等待
 * 下次运行；带旧水位的较晚到达行按契约位于游标范围之外，且只有
 * 幂等覆盖目标才能保证重放安全。
 */
final class ElasticsearchCursorReader implements CursorReadSession {

    private final Connection connection;

    private final ElasticsearchNativeSession session;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final int fetchSize;

    private final List<String> fields;

    private final String index;

    private Map<String, ElasticsearchMapping.Field> mapping;

    private String uuid;

    private String mappingHash;

    private ElasticsearchCursorKeys.Family family;

    private CursorKeyContract keys;

    private String fingerprint;

    private TypedCursor upper, position;

    private Object upperValue, lowerValue;

    private JsonNode hits;

    private int hitPosition;

    private JsonNode lastSort;

    private long lastDoc;

    private Object lastWatermark;

    private Object[] row;

    private long delivered;

    private boolean exhausted, finished, closed;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        ElasticsearchCursorReader reader = null;
        try {
            reader = new ElasticsearchCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException(
                    "Elasticsearch cursor generation, shape, bounds or ordering is not verified");
        }
    }

    /**
     * 初始化 {@code ElasticsearchCursorReader}。
     */
    private ElasticsearchCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) throws SQLException {
        this.connection = connection;
        this.request = request;
        this.options = options;
        this.fetchSize = Math.max(1, Math.min(fetchSize, request.limits().maxRows()));
        this.session = ElasticsearchNativeSession.open(connection, options.maxCacheBytes(),
                options.queryTimeoutSeconds());
        // Elasticsearch 没有 schema：请求的表就是单个具体物理索引。
        this.index = ElasticsearchNativeSession.index(request.table());
        this.fields = request.fields();
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        var pin = request.sourceGenerationPin();
        if (pin == null) {
            throw new SQLException("Elasticsearch cursor requires an externally retained source generation pin");
        }
        ElasticsearchSourceGeneration.verify(connection, pin, request.table(), timeout());
        if (!List.of("_id").equals(request.primaryKeys())) {
            throw new SQLException("Elasticsearch cursor requires only the native _id key");
        }
        if ("_id".equals(request.watermark())) {
            throw new SQLException(
                    "Elasticsearch cursor watermarks require a mapped sortable field; _id fielddata is unavailable");
        }
        JsonNode response = session.request("GET", "/" + index + "?flat_settings=true", null).requireSuccess();
        if (response.size() != 1 || !response.has(index)) {
            throw new SQLException("Source is not one concrete physical index");
        }
        JsonNode physical = response.get(index);
        uuid = physical.path("settings").path("index.uuid").asText();
        if (!uuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new SQLException("Native source physical identity is unavailable");
        }
        if (physical.path("settings").path("index.mapping.ignore_malformed").asBoolean(false)) {
            throw new SQLException("Elasticsearch cursor requires exact watermark indexation");
        }
        mapping = ElasticsearchMapping.read(session, index);
        if (!mapping.keySet().containsAll(fields)) {
            throw new SQLException("Elasticsearch cursor projection must contain mapped fields");
        }
        family = ElasticsearchCursorKeys.familyOf(mapping.get(request.watermark()));
        if (family == null) {
            throw new SQLException("Elasticsearch cursor watermark type is not replayable");
        }
        ElasticsearchCursorKeys.requireWatermarkField(
                physical.path("mappings").path("properties").path(request.watermark()));
        mappingHash = ElasticsearchCursorKeys.mappingFingerprint(physical.path("mappings"));
        if (request.lower() != null) {
            lowerValue = ElasticsearchCursorKeys.decode(family, tuple(request.lower()));
        }
        if (request.fixedUpper() != null) {
            upperValue = ElasticsearchCursorKeys.decode(family, tuple(request.fixedUpper()));
        }
        if (lowerValue != null || upperValue == null && !request.upperBoundFrozen()) {
            refresh();
        }
        if (lowerValue != null && count(windowQuery()) == 0) {
            throw new SQLException(
                    "Elasticsearch source watermark moved backwards; explicit reconciliation is required");
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            upperValue = captureMax();
            upper = upperValue == null ? null : cursor(upperValue);
        }
        keys = ElasticsearchCursorKeys.keyContract();
        String material = "ELASTICSEARCH_CURSOR_V1\0ELASTICSEARCH_INDEX_V1\0" + request.table() + '\0' + uuid + '\0' + mappingHash + '\0'
                + request.watermark() + '\0' + String.join("\0", fields) + '\0' + pin.fingerprint();
        fingerprint = DigestUtil.sha256Hex(material);
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("Elasticsearch cursor source generation, index or shape changed");
        }
        if (upperValue == null) {
            exhausted = true;
        }
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Elasticsearch cursor interrupted or closed");
        }
        if (exhausted) {
            return false;
        }
        if (hits == null || hitPosition >= hits.size()) {
            hits = page();
            hitPosition = 0;
            if (hits.isEmpty()) {
                finish();
                exhausted = true;
                hits = null;
                row = null;
                position = null;
                return false;
            }
        }
        JsonNode hit = hits.get(hitPosition++);
        JsonNode sort = hit.path("sort");
        if (!sort.isArray() || sort.size() != 2 || !sort.get(1).isIntegralNumber()) {
            throw new SQLException("Elasticsearch cursor sort evidence is not verified");
        }
        Object watermark = ElasticsearchCursorKeys.sortValue(family, sort.get(0));
        String id = hit.path("_id").asText();
        if (!hit.path("_id").isTextual() || id.isEmpty()) {
            throw new SQLException("Elasticsearch cursor hit has no document identity");
        }
        long doc = sort.get(1).longValue();
        if (lastWatermark != null) {
            // 运行中的同值排序依据是每个索引稳定的 _doc 顺序，绝不是 _id 名称。
            int compared = ElasticsearchCursorKeys.compare(family, watermark, lastWatermark);
            if (compared < 0 || compared == 0 && doc <= lastDoc) {
                throw new SQLException("Elasticsearch cursor page order or bound is not verified");
            }
        }
        lastWatermark = watermark;
        lastSort = sort.get(0);
        lastDoc = doc;
        position = cursor(watermark);
        delivered++;
        row = fieldRow(hit);
        row = BatchValueConverter.convert(List.<Object[]>of(row), row.length, request.limits()).getFirst();
        return true;
    }

    /** 仅在重新证明固定信息、索引 UUID、映射快照和交付窗口计数后，才声明完成。 */
    private void finish() throws SQLException {
        if (finished) {
            return;
        }
        verifyCurrent();
        refresh();
        long scope = count(windowQuery());
        if (scope != delivered) {
            throw new SQLException("Elasticsearch cursor window changed during the read; the run cannot be confirmed");
        }
        finished = true;
    }

    /**
     * 重新读取当前对象事实并校验其与预期一致。
     */
    private void verifyCurrent() throws SQLException {
        ElasticsearchSourceGeneration.verify(connection, request.sourceGenerationPin(), request.table(), timeout());
        JsonNode response = session.request("GET", "/" + index + "?flat_settings=true", null).requireSuccess();
        if (response.size() != 1 || !response.has(index)) {
            throw new SQLException("Source is not one concrete physical index");
        }
        JsonNode physical = response.get(index);
        if (!uuid.equals(physical.path("settings").path("index.uuid").asText())) {
            throw new SQLException("Elasticsearch physical index changed during the cursor read");
        }
        if (!mappingHash.equals(ElasticsearchCursorKeys.mappingFingerprint(physical.path("mappings")))) {
            throw new SQLException("Elasticsearch mapping changed during the cursor read");
        }
    }

    /**
     * 请求刷新 Elasticsearch 索引并校验各分片结果。
     */
    private void refresh() throws SQLException {
        JsonNode response = session.request("POST", "/" + index + "/_refresh", null).requireSuccess();
        if (!response.path("_shards").path("failed").isIntegralNumber() || response.path("_shards").path("failed")
                .longValue() != 0) {
            throw new SQLException("Elasticsearch refresh did not complete on all shards");
        }
    }

    /** 按 {@code (watermark, _doc)} 升序读取单页 search_after；服务器自身的排序值原样返回。 */
    private JsonNode page() throws SQLException {
        var body = new LinkedHashMap<String, Object>();
        body.put("size", fetchSize);
        var sort = new ArrayList<Object>();
        sort.add(Map.of(request.watermark(), "asc"));
        sort.add("_doc");
        body.put("sort", sort);
        var range = new LinkedHashMap<String, Object>();
        if (lowerValue != null) {
            range.put("gte", ElasticsearchCursorKeys.bound(family, lowerValue));
        }
        if (upperValue != null) {
            range.put("lte", ElasticsearchCursorKeys.bound(family, upperValue));
        }
        body.put("query",
                Map.of("bool", Map.of("filter", List.of(Map.of("range", Map.of(request.watermark(), range))))));
        body.put("_source", fields.stream().filter(name -> !name.equals("_id")).toList());
        body.put("stored_fields", List.of("_routing"));
        if (lastSort != null) {
            body.put("search_after", List.of(lastSort, lastDoc));
        }
        body.put("timeout", timeout() + "s");
        JsonNode response = session.request("POST", "/" + index + "/_search", ElasticsearchNativeSession.json(body))
                .requireSuccess();
        if (!response.path("timed_out").isBoolean() || response.path("timed_out").booleanValue()
                || !response.path("_shards").path("failed").isIntegralNumber() || response.path("_shards")
                .path("failed").longValue() != 0) {
            throw new SQLException("Elasticsearch cursor search did not complete on all shards");
        }
        JsonNode found = response.path("hits").path("hits");
        if (!found.isArray() || found.size() > fetchSize) {
            throw new SQLException("Elasticsearch cursor page is incomplete");
        }
        for (JsonNode hit : found) {
            ElasticsearchMapping.requireUnroutedHit(hit);
        }
        return found;
    }

    /** 固定上界是在打开时 refresh 后立即可见的最大水位。 */
    private Object captureMax() throws SQLException {
        var body = new LinkedHashMap<String, Object>();
        body.put("size", 1);
        body.put("sort", List.of(Map.of(request.watermark(), "desc")));
        body.put("_source", false);
        body.put("stored_fields", List.of("_routing"));
        body.put("timeout", timeout() + "s");
        JsonNode response = session.request("POST", "/" + index + "/_search", ElasticsearchNativeSession.json(body))
                .requireSuccess();
        JsonNode found = response.path("hits").path("hits");
        if (!found.isArray() || found.size() > 1) {
            throw new SQLException("Elasticsearch cursor upper bound is not verified");
        }
        if (found.isEmpty()) {
            return null;
        }
        ElasticsearchMapping.requireUnroutedHit(found.get(0));
        JsonNode sort = found.get(0).path("sort");
        if (!sort.isArray() || sort.isEmpty()) {
            throw new SQLException("Elasticsearch cursor upper bound is not verified");
        }
        return ElasticsearchCursorKeys.sortValue(family, sort.get(0));
    }

    /**
     * 返回数量。
     */
    private long count(Map<String, Object> query) throws SQLException {
        JsonNode response = session.request("POST", "/" + index + "/_count",
                ElasticsearchNativeSession.json(Map.of("query", query))).requireSuccess();
        if (!response.path("_shards").path("failed").isIntegralNumber() || response.path("_shards").path("failed")
                .longValue() != 0
                || !response.path("count").isIntegralNumber()) {
            throw new SQLException("Elasticsearch cursor count is not verified");
        }
        return response.path("count").longValue();
    }

    /**
     * 根据当前游标下界和上界构建范围查询。
     */
    private Map<String, Object> windowQuery() {
        var range = new LinkedHashMap<String, Object>();
        if (lowerValue != null) {
            range.put("gte", ElasticsearchCursorKeys.bound(family, lowerValue));
        }
        if (upperValue != null) {
            range.put("lte", ElasticsearchCursorKeys.bound(family, upperValue));
        }
        return Map.of("bool", Map.of("filter", List.of(Map.of("range", Map.of(request.watermark(), range)))));
    }

    /**
     * 从目录结果行构建字段描述。
     */
    private Object[] fieldRow(JsonNode hit) throws SQLException {
        Object[] values = new Object[fields.size()];
        for (int i = 0; i < values.length; i++) {
            String field = fields.get(i);
            values[i] = field.equals("_id") ? hit.path("_id").asText()
                    : ElasticsearchMapping.value(mapping.get(field), hit.path("_source").get(field));
        }
        return values;
    }

    /**
     * 返回当前元组。
     */
    private TypedCursorValue tuple(TypedCursor value) throws SQLException {
        if (value == null || value.values().size() != 1) {
            throw new SQLException("Elasticsearch cursor tuple shape changed");
        }
        return value.values().getFirst();
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(Object value) {
        return new TypedCursor(1, List.of(ElasticsearchCursorKeys.value(family, value)));
    }

    /**
     * 返回超时设置。
     */
    private int timeout() {
        return options.queryTimeoutSeconds();
    }

    /**
     * 返回指纹。
     */
    @Override
    public String schemaFingerprint() {
        return fingerprint;
    }

    /**
     * 返回当前游标键契约的原生指纹。
     */
    @Override
    public String keyContractFingerprint() {
        return keys.nativeFingerprint();
    }

    /**
     * 返回键集合。
     */
    @Override
    public CursorKeyContract keyContract() {
        return keys;
    }

    /**
     * 返回上界。
     */
    @Override
    public TypedCursor upperBound() {
        return upper;
    }

    /**
     * 返回位置。
     */
    @Override
    public TypedCursor position() {
        return position;
    }

    /**
     * 返回数据行。
     */
    @Override
    public Object[] row() {
        if (row == null) {
            throw new IllegalStateException("Elasticsearch cursor has no current row");
        }
        return row.clone();
    }

    /**
     * 关闭游标读取状态并释放其持有的结果集及相关资源。
     */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (Exception failure) {
            throw new SQLException("Elasticsearch cursor connection cleanup was not confirmed");
        }
    }
}
