package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.SingletonObject;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoCellCodec;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoMigrationFrame;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 不持有读取快照的 MongoDB 类型化游标。整集合的水位字段必须只采用一种 BSON 类型族，
 * 即 int32、int64、decimal128 或 date；其他类型、缺失水位和混合
 * 数值类型均在释放任何行前拒绝，因为 MongoDB 的排序和查询比较只能
 * 在单一类型内精确重放。固定上界为运行开始时可见的最大水位；
 * 通过单个升序原生游标完整读取每个同水位分组，绝不需要跨类型 {@code _id}
 * 键集比较。完成时重新校验集合标识、水位值域及
 * 精确交付计数：窗口内并发插入或删除会使运行失败关闭，避免丢行。
 * 水位高于固定上界的较晚到达行等待下次运行；
 * 带旧水位的较晚到达行按契约位于游标范围之外，且只有幂等覆盖目标才能保证重放安全。
 */
final class MongoCursorReader implements CursorReadSession {

    private record Family(String bsonType, int tag, String typeName, int jdbcType) {

    }

    private static final Map<String, Family> FAMILIES = Map.of(
            "int", new Family("int", 0x10, "BSON_INT32", Types.INTEGER),
            "long", new Family("long", 0x12, "BSON_INT64", Types.BIGINT),
            "decimal", new Family("decimal", 0x13, "BSON_DECIMAL128", Types.NUMERIC),
            "date", new Family("date", 0x09, "BSON_DATE", Types.TIMESTAMP_WITH_TIMEZONE));

    private static final Map<String, String> FAMILY_OF_TYPE_NAME = Map.of(
            "BSON_INT32", "int", "BSON_INT64", "long", "BSON_DECIMAL128", "decimal", "BSON_DATE", "date");

    private static final String ID_KEY_FINGERPRINT = DigestUtil.sha256Hex("MONGODB_ID_KEY_V1");

    private final Connection connection;

    private final MongoNativeSession session;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final int fetchSize;

    private final List<String> fields;

    private final boolean frame;

    private String uuid;

    private String family;

    private CursorKeyContract keys;

    private String fingerprint;

    private TypedCursor upper, lower, position;

    private Object upperValue, lowerValue;

    private MongoNativeSession.Cursor stream;

    private Object lastWatermark;

    private Object[] row;

    private long delivered;

    private boolean exhausted, finished, closed;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        MongoCursorReader reader = null;
        try {
            reader = new MongoCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("MongoDB cursor generation, shape, bounds or ordering is not verified");
        }
    }

    /**
     * 校验构造参数并初始化 {@code MongoCursorReader}。
     */
    private MongoCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) throws SQLException {
        this.connection = connection;
        this.request = request;
        this.options = options;
        this.fetchSize = Math.max(1, Math.min(fetchSize, request.limits().maxRows()));
        this.session = MongoNativeSession.open(connection, options.maxCacheBytes(), options.queryTimeoutSeconds());
        MongoNativeSession.field(request.watermark());
        this.fields = request.fields();
        this.frame = fields.contains(MongoMigrationFrame.DOCUMENT);
        if (frame) {
            MongoMigrationFrame.requireFields(fields);
            if (!"_id".equals(request.watermark())) {
                throw new SQLException("The MongoDB native frame projects only _id; its watermark must be _id");
            }
        }
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        var pin = request.sourceGenerationPin();
        if (pin == null) {
            throw new SQLException("MongoDB cursor requires an externally retained source generation pin");
        }
        MongoSourceGeneration.verify(connection, pin, request.schema(), timeout());
        var info = MongoNativeMetadata.ordinaryCollection(connection, request.schema(), request.table());
        if (info.getJSONObject("options").containsKey("collation")
                && !"simple".equals(info.getJSONObject("options").getJSONObject("collation").getStr("locale"))) {
            throw new SQLException("MongoDB cursor ordering requires simple collation");
        }
        var identity = info.getJSONObject("info");
        if (identity == null || identity.getJSONObject("uuid") == null) {
            throw new SQLException("MongoDB physical collection identity is missing");
        }
        uuid = identity.getJSONObject("uuid").toString();
        if (!List.of("_id").equals(request.primaryKeys())) {
            throw new SQLException("MongoDB cursor requires only the native _id key");
        }
        // 释放任何行前先验证水位值域，参见类注释。
        String expected = null;
        if (request.lower() != null) {
            validateTuple(request.lower());
            expected = familyOf(request.lower());
        }
        if (request.fixedUpper() != null) {
            validateTuple(request.fixedUpper());
            String upperFamily = familyOf(request.fixedUpper());
            if (expected != null && !expected.equals(upperFamily)) {
                throw new SQLException("MongoDB cursor tuple families changed");
            }
            expected = upperFamily;
        }
        long out = count(outOfDomainQuery());
        if (out != 0) {
            throw new SQLException("MongoDB cursor watermark domain is not verified");
        }
        var present = new ArrayList<String>();
        for (String name : FAMILIES.keySet()) {
            if (count(query(typeClause(name))) > 0) {
                present.add(name);
            }
        }
        String expectedFamily = expected;
        if (expectedFamily != null && present.stream().anyMatch(name -> !name.equals(expectedFamily))) {
            throw new SQLException("MongoDB cursor watermark BSON type changed");
        }
        if (present.size() > 1) {
            throw new SQLException("MongoDB cursor watermark mixes BSON types; replayable ordering is unavailable");
        }
        family = expectedFamily != null ? expectedFamily : present.isEmpty() ? null : present.getFirst();
        lower = request.lower();
        lowerValue = lower == null ? null : javaValue(lower);
        if (lowerValue != null && count(query(rangeClause("$gte", lowerValue))) == 0) {
            throw new SQLException("MongoDB source watermark moved backwards; explicit reconciliation is required");
        }
        upper = request.fixedUpper();
        upperValue = upper == null ? null : javaValue(upper);
        if (upper == null && !request.upperBoundFrozen() && family != null) {
            var projection = new LinkedHashMap<String, Integer>();
            projection.put(request.watermark(), 1);
            if (!"_id".equals(request.watermark())) {
                projection.put("_id", 0);
            }
            try (var probe = find(Map.of(request.watermark(), typeClause(family)), projection,
                    Map.of(request.watermark(), -1))) {
                byte[] document = probe.next();
                if (document != null) {
                    upperValue = watermark(document);
                }
            }
            upper = upperValue == null ? null : cursor(upperValue);
        }
        keys = new CursorKeyContract(ProductTypeEnum.MONGODB, ID_KEY_FINGERPRINT,
                List.of(CursorKeyContract.Domain.nativeOnly()));
        // 刻意排除水位 BSON 类型族：空窗口尚无类型族，冻结的空
        // 窗口恢复时必须保持不变。任何值域漂移仍由每次运行的预检查和边界拒绝。
        String material = "MONGODB_CURSOR_V1\0MONGODB_COLLECTION_V1\0" + request.schema() + '\0' + request.table() + '\0' + uuid + '\0'
                + canonical(info.getJSONObject("options")) + '\0' + request.watermark()
                + '\0' + String.join("\0", fields) + '\0' + pin.fingerprint();
        fingerprint = DigestUtil.sha256Hex(material);
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("MongoDB cursor source generation, relation or shape changed");
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
            throw new SQLException("MongoDB cursor interrupted or closed");
        }
        if (exhausted) {
            return false;
        }
        if (stream == null) {
            stream = find(query(filterClause()), frame ? null : projection(), orderedSort());
        }
        byte[] document;
        try {
            document = stream.next();
        } catch (SQLException | RuntimeException failure) {
            stream = null;
            throw failure;
        }
        if (document == null) {
            finish();
            exhausted = true;
            stream = null;
            row = null;
            position = null;
            return false;
        }
        Object watermark = watermark(document);
        if (lastWatermark != null && compare(lastWatermark, watermark) > 0) {
            throw new SQLException("MongoDB cursor page order or bound is not verified");
        }
        lastWatermark = watermark;
        position = cursor(watermark);
        delivered++;
        row = frame ? MongoMigrationFrame.detach(session, document) : fieldRow(session.cells(document));
        row = BatchValueConverter.convert(List.<Object[]>of(row), row.length, request.limits()).getFirst();
        return true;
    }

    /** 仅在重新证明集合标识、水位值域和交付窗口计数后，才声明完成。 */
    private void finish() throws SQLException {
        if (finished) {
            return;
        }
        verifyCurrent();
        long scope = count(query(rangeClauseBoth()));
        if (scope != delivered) {
            throw new SQLException("MongoDB cursor window changed during the read; the run cannot be confirmed");
        }
        finished = true;
    }

    /**
     * 重新读取当前对象事实并校验其与预期一致。
     */
    private void verifyCurrent() throws SQLException {
        MongoSourceGeneration.verify(connection, request.sourceGenerationPin(), request.schema(), timeout());
        var info = MongoNativeMetadata.ordinaryCollection(connection, request.schema(), request.table());
        var identity = info.getJSONObject("info");
        if (identity == null || identity.getJSONObject("uuid") == null || !uuid.equals(
                identity.getJSONObject("uuid").toString())) {
            throw new SQLException("MongoDB physical collection changed during the cursor read");
        }
        long out = count(outOfDomainQuery());
        if (out != 0) {
            throw new SQLException("MongoDB cursor watermark domain changed during the read");
        }
    }

    /** 水位单元格必须存在且精确解码；服务器过滤器漏过文档时，运行失败关闭。 */
    private Object watermark(byte[] document) throws SQLException {
        byte[] cell = session.cells(document).get(request.watermark());
        if (cell == null) {
            throw new SQLException("MongoDB cursor document lacks its watermark field");
        }
        int tag = Byte.toUnsignedInt(cell[4]);
        if (family == null || tag != FAMILIES.get(family).tag()) {
            throw new SQLException("MongoDB cursor watermark type is not verified");
        }
        ByteBuffer value = ByteBuffer.wrap(cell, 7, cell.length - 8).order(ByteOrder.LITTLE_ENDIAN);
        try {
            return switch (tag) {
                case 0x10 -> value.getInt();
                case 0x12 -> value.getLong();
                case 0x13 -> session.decimal128(cell);
                case 0x09 -> Instant.ofEpochMilli(value.getLong());
                default -> throw new SQLException("MongoDB cursor watermark type is not verified");
            };
        } catch (SQLException | RuntimeException invalid) {
            throw new SQLException("MongoDB cursor watermark value is not exact");
        }
    }

    /**
     * 从目录结果行构建字段描述。
     */
    private Object[] fieldRow(Map<String, byte[]> cells) throws SQLException {
        Object[] values = new Object[fields.size()];
        for (int i = 0; i < values.length; i++) {
            String field = fields.get(i);
            byte[] cell = cells.get(field);
            values[i] = cell == null ? MissingCellValue.INSTANCE : MongoCellCodec.detach(session, cell,
                    field.equals("_id"));
        }
        return values;
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(Object value) {
        return new TypedCursor(1, List.of(cursorValue(value)));
    }

    /**
     * 返回游标值。
     */
    private TypedCursorValue cursorValue(Object value) {
        Family entry = FAMILIES.get(family);
        Object exact = entry.tag() == 0x09 ? OffsetDateTime.ofInstant((Instant) value, ZoneOffset.UTC) : value;
        return TypedCursorValue.of(entry.jdbcType(), entry.typeName(), exact);
    }

    /**
     * 校验元组。
     */
    private void validateTuple(TypedCursor tuple) throws SQLException {
        if (tuple == null || tuple.values().size() != 1) {
            throw new SQLException("MongoDB cursor tuple shape changed");
        }
        javaValue(tuple);
    }

    /**
     * 识别当前原生类型所属的类型家族。
     */
    private String familyOf(TypedCursor tuple) {
        return FAMILY_OF_TYPE_NAME.get(tuple.values().getFirst().typeName());
    }

    /**
     * 将结构化游标值转换为当前驱动使用的 Java 值。
     */
    private Object javaValue(TypedCursor tuple) throws SQLException {
        TypedCursorValue value = tuple.values().getFirst();
        Family entry = FAMILIES.get(FAMILY_OF_TYPE_NAME.getOrDefault(value.typeName(), ""));
        if (entry == null || value.jdbcType() != entry.jdbcType()) {
            throw new SQLException("MongoDB cursor tuple types changed");
        }
        Object decoded = value.jdbcValue();
        if (entry.tag() == 0x09) {
            if (!(decoded instanceof OffsetDateTime instant)) {
                throw new SQLException("MongoDB cursor tuple types changed");
            }
            return instant.toInstant();
        }
        if (!(decoded instanceof BigDecimal) && !(decoded instanceof Integer) && !(decoded instanceof Long)) {
            throw new SQLException("MongoDB cursor tuple types changed");
        }
        return decoded;
    }

    /**
     * 返回当前比较。
     */
    private int compare(Object left, Object right) {
        if (left instanceof Instant first && right instanceof Instant second) {
            return first.compareTo(second);
        }
        return decimal(left).compareTo(decimal(right));
    }

    /**
     * 按当前值域约定解析或转换精确小数。
     */
    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : BigDecimal.valueOf(((Number) value).longValue());
    }

    /** 单个升序原生流覆盖整个重放窗口；同水位分组完整读取，绝不按键集拆分。 */
    private MongoNativeSession.Cursor find(Map<String, Object> filter, Map<String, Integer> projection,
            Map<String, Integer> sort) throws SQLException {
        try {
            Map<String, Object> clause = filter == null || filter.isEmpty() ? Map.of() : new LinkedHashMap<>(filter);
            byte[] encoded = session.encode(SingletonObject.OBJECT_MAPPER.writeValueAsString(clause));
            List<String> projected = projection == null ? null : new ArrayList<>(projection.keySet());
            return session.find(request.schema(), request.table(), encoded, projected, sort, fetchSize);
        } catch (java.io.IOException failure) {
            throw new SQLException("MongoDB cursor filter cannot be encoded");
        }
    }

    /**
     * 返回数量。
     */
    private long count(Map<String, Object> query) throws SQLException {
        try {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("count", request.table());
            command.put("query", new LinkedHashMap<>(query));
            command.put("maxTimeMS", 1000L * timeout());
            return MongoReplies.integer(MongoReplies.success(session,
                    session.command(request.schema(),
                            session.encode(SingletonObject.OBJECT_MAPPER.writeValueAsString(command)))).path("n"));
        } catch (java.io.IOException failure) {
            throw new SQLException("MongoDB cursor count cannot be encoded");
        }
    }

    /**
     * 返回查询。
     */
    private Map<String, Object> query(Map<String, Object> clause) {
        return Map.of(request.watermark(), new LinkedHashMap<>(clause));
    }

    /**
     * 生成或读取当前列完整类型子句。
     */
    private Map<String, Object> typeClause(String name) {
        return Map.of("$type", name);
    }

    /** 顶层 $nor：选择水位不属于四种可重放 BSON 类型的每个文档。 */
    private Map<String, Object> outOfDomainQuery() {
        var alternatives = new ArrayList<Object>();
        for (String name : FAMILIES.keySet()) {
            alternatives.add(Map.of(request.watermark(), Map.of("$type", name)));
        }
        return Map.of("$nor", alternatives);
    }

    /**
     * 根据键类型和边界值构建单边范围条件。
     */
    private Map<String, Object> rangeClause(String operator, Object value) {
        var clause = new LinkedHashMap<String, Object>();
        clause.put("$type", family);
        clause.put(operator, valueJson(value));
        return clause;
    }

    /**
     * 根据键类型及上下边界构建双边范围条件。
     */
    private Map<String, Object> rangeClauseBoth() {
        var clause = new LinkedHashMap<String, Object>();
        clause.put("$type", family);
        if (lowerValue != null) {
            clause.put("$gte", valueJson(lowerValue));
        }
        if (upperValue != null) {
            clause.put("$lte", valueJson(upperValue));
        }
        return clause;
    }

    /**
     * 过滤子句。
     */
    private Map<String, Object> filterClause() {
        return rangeClauseBoth();
    }

    /**
     * 按扩展 JSON 协议编码当前游标键值。
     */
    private Object valueJson(Object value) {
        if (value instanceof Instant instant) {
            return Map.of("$date", Map.of("$numberLong", Long.toString(instant.toEpochMilli())));
        }
        if (value instanceof BigDecimal decimal) {
            return Map.of("$numberDecimal", decimal.toPlainString());
        }
        if (value instanceof Long || value instanceof Integer) {
            return Map.of(value instanceof Long ? "$numberLong" : "$numberInt", value.toString());
        }
        throw new IllegalStateException("MongoDB cursor watermark value is not encodable");
    }

    /**
     * 返回当前投影。
     */
    private Map<String, Integer> projection() {
        var projection = new LinkedHashMap<String, Integer>();
        fields.forEach(name -> projection.put(name, 1));
        if (!fields.contains("_id")) {
            projection.put("_id", 0);
        }
        return projection;
    }

    /** 确定性排序文档：水位优先，其后为始终存在且不同时的 _id。 */
    private Map<String, Integer> orderedSort() {
        var sort = new LinkedHashMap<String, Integer>();
        sort.put(request.watermark(), 1);
        if (!"_id".equals(request.watermark())) {
            sort.put("_id", 1);
        }
        return sort;
    }

    /** 排序且有深度上限的规范元数据依据；绝不依赖服务器键顺序。 */
    private static String canonical(JSONObject value) throws SQLException {
        try {
            return cn.hutool.json.JSONUtil.toJsonStr(canonicalValue(value, 0));
        } catch (RuntimeException invalid) {
            throw new SQLException("MongoDB collection options are not verifiable");
        }
    }

    /**
     * 将当前值整理为稳定比较使用的规范形式。
     */
    private static Object canonicalValue(Object value, int depth) throws SQLException {
        if (depth > 16) {
            throw new SQLException("MongoDB metadata nesting exceeds the verified bound");
        }
        if (value instanceof JSONObject object) {
            var entries = new TreeMap<String, Object>();
            for (var entry : object.entrySet()) {
                entries.put(entry.getKey(), canonicalValue(entry.getValue(), depth + 1));
            }
            return entries;
        }
        if (value instanceof JSONArray array) {
            var entries = new ArrayList<Object>();
            for (Object item : array) {
                entries.add(canonicalValue(item, depth + 1));
            }
            return entries;
        }
        if (value instanceof String text && (text.indexOf('\0') >= 0 || text.length() > 4096)) {
            throw new SQLException("MongoDB metadata field is invalid");
        }
        return value;
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
            throw new IllegalStateException("MongoDB cursor has no current row");
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
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception failure) {
                throw new SQLException("MongoDB cursor stream cleanup was not confirmed");
            }
        }
        try {
            connection.close();
        } catch (Exception failure) {
            throw new SQLException("MongoDB cursor connection cleanup was not confirmed");
        }
    }
}
