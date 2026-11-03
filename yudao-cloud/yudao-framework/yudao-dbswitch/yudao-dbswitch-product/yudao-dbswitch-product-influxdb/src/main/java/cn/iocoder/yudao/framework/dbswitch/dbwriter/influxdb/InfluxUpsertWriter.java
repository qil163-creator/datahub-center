package cn.iocoder.yudao.framework.dbswitch.dbwriter.influxdb;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxLineProtocol;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeSession;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * InfluxDB3 Core 原生 (tagset, time) 键写入。已有键的数据点仅替换实际写入的字段；
 * 因行值为 NULL 而省略的字段无法在线协议中表示，会保留目标原值，
 * 因此每个写入字段重放后会收敛，而 NULL 字段收敛需要 DIFF。每个批次为一个
 * 有界 HTTP 请求，解析采用全成功或报错；首次发送后的任何失败均为 UNKNOWN，此处绝不重试或
 * 回滚。在首次发送前及最后一次确认后重新检查目标代次和精确测量结构。
 * 绝不将普通整数猜测为时间戳：时间值必须是精确的
 * 纳秒纪元计数或绝对时间。
 */
public final class InfluxUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    private InfluxCursorMetadata.Shape shape;

    private List<String> fields, keys;

    private String fingerprint;

    /**
     * 初始化 {@code InfluxUpsertWriter}及其父类状态。
     */
    public InfluxUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "InfluxDB3";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        throw new IllegalArgumentException("Native UPSERT requires explicit unique keys");
    }

    /**
     * 不支持的键、索引或存储语义必须在发送任何数据语句前失败。
     */
    @Override
    public void prepareUpsert(String schema, String table, List<String> names, List<String> uniqueKeys) {
        shape = null;
        fields = null;
        fingerprint = null;
        try {
            // schema 参数是传输范围 "iox"；数据库由受控连接池指定。
            InfluxNativeCatalog.schema(schema);
            InfluxNativeSession.identifier(table);
            if (names == null || names.isEmpty() || new HashSet<>(names).size() != names.size()) {
                throw new IllegalArgumentException();
            }
            InfluxCursorMetadata.Shape current;
            try (var connection = dataSource.getConnection()) {
                var session = InfluxNativeSession.open(dataSource, connection, limit(),
                        runtimeOptions.queryTimeoutSeconds());
                current = InfluxCursorMetadata.read(session, session.database(), table);
            }
            if (!current.keys().equals(uniqueKeys) || !names.containsAll(uniqueKeys)) {
                throw new IllegalArgumentException();
            }
            for (String name : names) {
                current.nativeTable().column(name);
            }
            schemaName = current.database();
            tableName = table;
            shape = current;
            keys = List.copyOf(uniqueKeys);
            fingerprint = InfluxCursorMetadata.sha256Hex(
                    "INFLUX_UPSERT_TARGET_V1\0" + current.evidence() + '\0' + String.join("\0",
                            names) + '\0' + String.join("\0", uniqueKeys));
            fields = List.copyOf(names);
        } catch (Exception failure) {
            shape = null;
            fields = null;
            fingerprint = null;
            throw new IllegalArgumentException(
                    "InfluxDB3 UPSERT target, native tag/time key or value codec is unsupported");
        }
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        if (fields == null) {
            throw new IllegalStateException("InfluxDB3 UPSERT target is not prepared");
        }
        return fingerprint;
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return keyContract().nativeFingerprint();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        if (fields == null) {
            throw new IllegalStateException("InfluxDB3 UPSERT target is not prepared");
        }
        return shape.targetContract();
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> names, List<Object[]> rows) {
        return writeWithReceipt(names, rows).acknowledgedRows();
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public BatchWriteResult writeWithReceipt(List<String> names, List<Object[]> rows) {
        if (fields == null || !fields.equals(names)) {
            throw new BatchRejectedException("InfluxDB3 UPSERT input fields differ from the prepared fields");
        }
        var converted = new ArrayList<Object[]>();
        for (var raw : BatchValueConverter.convert(rows, fields.size(), batchLimits)) {
            var row = raw.clone();
            int index = fields.indexOf(InfluxCursorMetadata.TIME);
            row[index] = time(row[index]);
            converted.add(row);
        }
        if (converted.isEmpty()) {
            return new BatchWriteResult(0, null);
        }
        // 借用任何连接前，证明每行具有一个原生键且键值非空。
        var seen = new HashSet<List<Object>>();
        for (var row : converted) {
            var key = new ArrayList<Object>();
            for (String name : keys) {
                Object value = row[fields.indexOf(name)];
                if (value == null) {
                    throw new BatchRejectedException("InfluxDB3 UPSERT key values must not be NULL");
                }
                if (InfluxCursorMetadata.TIME.equals(name) && !(value instanceof Instant)) {
                    throw new BatchRejectedException("InfluxDB3 UPSERT time is not an absolute temporal value");
                }
                key.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
            }
            if (!seen.add(key)) {
                throw new BatchRejectedException("InfluxDB3 UPSERT batch repeats a native key");
            }
        }
        final byte[] payload;
        try {
            payload = InfluxLineProtocol.batch(shape.nativeTable(), fields, converted, batchLimits.maxBatchBytes());
        } catch (RuntimeException invalid) {
            throw new BatchRejectedException(
                    "InfluxDB3 batch has an unsupported value, timestamp or size; no write was submitted");
        }
        long written = NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> {
            var session = InfluxNativeSession.open(dataSource, connection, limit(),
                    runtimeOptions.queryTimeoutSeconds());
            verifyTarget(session);
            attempt.sending();
            session.write(payload);
            verifyTarget(session);
            return (long) converted.size();
        });
        if (written != converted.size()) {
            throw new UnconfirmedWriteException();
        }
        return new BatchWriteResult(written, null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /** 精确整数纳秒纪元计数或绝对时间；绝不将普通整数猜测为毫秒。 */
    private static Instant time(Object value) {
        Instant instant;
        if (value instanceof Instant i) {
            instant = i;
        } else if (value instanceof OffsetDateTime i) {
            instant = i.toInstant();
        } else if (value instanceof ZonedDateTime i) {
            instant = i.toInstant();
        } else if (value instanceof java.util.Date i) {
            instant = i.toInstant();
        } else if (value instanceof BigInteger i) {
            instant = Instant.ofEpochSecond(Math.floorDiv(i.longValueExact(), 1_000_000_000L),
                    Math.floorMod(i.longValueExact(), 1_000_000_000L));
        } else if (value instanceof BigDecimal i) {
            instant = Instant.ofEpochSecond(Math.floorDiv(i.longValueExact(), 1_000_000_000L),
                    Math.floorMod(i.longValueExact(), 1_000_000_000L));
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            instant = Instant.ofEpochSecond(Math.floorDiv(((Number) value).longValue(), 1_000_000_000L),
                    Math.floorMod(((Number) value).longValue(), 1_000_000_000L));
        } else {
            throw new BatchRejectedException(
                    "InfluxDB3 UPSERT requires an exact nanosecond epoch count or an absolute temporal");
        }
        return instant;
    }

    /**
     * 校验目标端。
     */
    private void verifyTarget(InfluxNativeSession session) throws Exception {
        var current = InfluxCursorMetadata.read(session, schemaName, tableName);
        if (!current.evidence().equals(shape.evidence())) {
            throw new IllegalStateException("InfluxDB3 UPSERT target generation or structure changed");
        }
    }

    /**
     * 返回数量上限。
     */
    private long limit() {
        return Math.min(Math.max(runtimeOptions.maxCacheBytes(), batchLimits.maxBatchBytes() * 2), 16L * 1024 * 1024);
    }
}
