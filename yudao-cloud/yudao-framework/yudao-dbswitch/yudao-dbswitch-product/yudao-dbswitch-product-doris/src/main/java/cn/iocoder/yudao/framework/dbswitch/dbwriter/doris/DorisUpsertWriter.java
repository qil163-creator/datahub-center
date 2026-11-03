package cn.iocoder.yudao.framework.dbswitch.dbwriter.doris;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadHelper;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadAdmission;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/**
 * Doris UNIQUE 键写时合并 UPSERT。原生更新插入即键模型的 INSERT：完整行 INSERT
 * 替换已有键，因此重放已确认批次会收敛。必须写入完整行（部分
 * INSERT 会默默用默认值替换缺失列）。借用任何连接前先证明值精确。
 * Stream Load 要求固定的原生服务器和驱动身份、完整行
 * 投影及可见回执。已启用的传输无法建立此契约时直接拒绝，
 * 不回退到 JDBC。关闭开关时保留严格按请求确认的 JDBC INSERT。
 * 不虚构事务或回滚，也不自动重试。
 */
public final class DorisUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    private DorisJdbcSupport.Evidence captured;

    private List<String> fields, keys;

    private String fingerprint;

    private OlapStreamLoadHelper stream;

    /**
     * 初始化 {@code DorisUpsertWriter}及其父类状态。
     */
    public DorisUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Doris";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        refuseUnverifiedStreamLoad();
        if (stream != null) {
            stream.requireUsable();
        }
        throw new IllegalArgumentException("Native UPSERT requires explicit unique keys");
    }

    /**
     * 不支持的键、索引或存储语义必须在发送任何数据语句前失败。
     */
    @Override
    public void prepareUpsert(String schema, String table, List<String> names, List<String> uniqueKeys) {
        refuseUnverifiedStreamLoad();
        if (stream != null) {
            stream.requireUsable();
        }
        captured = null;
        fields = null;
        keys = null;
        fingerprint = null;
        stream = null;
        try (var connection = dataSource.getConnection()) {
            if (runtimeOptions.olapStreamLoadEnabled()) {
                OlapStreamLoadAdmission.verify(connection, "Doris", runtimeOptions.queryTimeoutSeconds());
            }
            var observation = DorisJdbcSupport.inspect(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
            var shape = observation.table();
            if (!shape.unique() || !"true".equals(shape.properties().get("enable_unique_key_merge_on_write"))
                    || shape.properties().containsKey("function_column.sequence_col")
                    || uniqueKeys == null || !shape.keys().equals(uniqueKeys)
                    || names == null || new HashSet<>(names).size() != names.size()
                    || !new HashSet<>(names).equals(
                    new HashSet<>(shape.columns().stream().map(c -> c.name()).toList()))) {
                throw new IllegalArgumentException();
            }
            names.forEach(shape::column);
            if (runtimeOptions.olapStreamLoadEnabled() && shape.columns().stream()
                    .anyMatch(column -> column.kind().equals("json"))) {
                throw new IllegalArgumentException("Stream Load JSON is outside the native scalar profile");
            }
            if (shape.partitioning() != null && !names.containsAll(shape.partitioning().columns())) {
                throw new IllegalArgumentException();
            }
            captured = observation;
        } catch (Exception failure) {
            captured = null;
            if (failure instanceof java.sql.SQLFeatureNotSupportedException) {
                OlapStreamLoadAdmission.unsupported("Doris");
            }
            throw new IllegalArgumentException(
                    "Doris UPSERT target, unique key model or full-row projection is unsupported");
        }
        schemaName = schema;
        tableName = table;
        fields = List.copyOf(names);
        keys = List.copyOf(uniqueKeys);
        fingerprint = DigestUtil.sha256Hex(
                "DORIS_UPSERT_TARGET_V1\0" + captured.generation() + '\0' + captured.physical()
                        + '\0' + String.join("\0", names) + '\0' + String.join("\0", uniqueKeys));
        stream = runtimeOptions.olapStreamLoadEnabled()
                ? OlapStreamLoadHelper.tryCreate(dataSource, schema, table, "Doris",
                runtimeOptions.queryTimeoutSeconds()) : null;
        if (runtimeOptions.olapStreamLoadEnabled() && stream == null) {
            OlapStreamLoadAdmission.unsupported("Doris");
        }
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        if (fields == null) {
            throw new IllegalStateException("Doris UPSERT target is not prepared");
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
            throw new IllegalStateException("Doris UPSERT target is not prepared");
        }
        return new DorisCursorMetadata.Shape(schemaName, tableName, captured).sourceContract();
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
        refuseUnverifiedStreamLoad();
        if (stream != null) {
            stream.requireUsable();
        }
        if (runtimeOptions.olapStreamLoadEnabled() != (stream != null) && captured != null) {
            throw new IllegalStateException("Prepared Stream Load transport cannot change");
        }
        if (fields == null || !fields.equals(names)) {
            throw new BatchRejectedException("Doris UPSERT input fields differ from the prepared fields");
        }
        if (rows.isEmpty()) {
            return new BatchWriteResult(0, null);
        }
        // 借用任何连接或发送任何请求前，先证明整个批次的精确性并完成原生分区路由。
        var converted = DorisJdbcSupport.convert(rows, fields, captured.table(), batchLimits);
        var seen = new HashSet<List<Object>>();
        for (var row : converted) {
            var key = new ArrayList<Object>();
            for (String name : keys) {
                Object value = row[fields.indexOf(name)];
                if (value == null) {
                    throw new BatchRejectedException("Doris UPSERT key values must not be NULL");
                }
                key.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
            }
            if (!seen.add(key)) {
                throw new BatchRejectedException("Doris UPSERT batch repeats a native key");
            }
        }
        long written;
        if (runtimeOptions.olapStreamLoadEnabled() && stream != null) {
            var encoded = stream.encode(fields, converted, java.util.Set.of(), batchLimits);
            try (var connection = dataSource.getConnection()) {
                OlapStreamLoadAdmission.verify(connection, "Doris", runtimeOptions.queryTimeoutSeconds());
                var current = DorisJdbcSupport.inspect(connection, schemaName, tableName,
                        runtimeOptions.queryTimeoutSeconds());
                if (!captured.physical().equals(current.physical()) || !captured.table().equals(current.table())) {
                    throw new IllegalStateException("Doris UPSERT target changed after prepare");
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Doris stream preflight failed");
            }
            try {
                written = stream.writeEncoded(encoded);
            } catch (UnconfirmedWriteException failure) {
                throw failure;
            } catch (BatchRejectedException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new UnconfirmedWriteException();
            }
        } else {
            written = DorisJdbcSupport.write(dataSource, schemaName, tableName, captured, fields, rows,
                    DorisJdbcSupport.Operation.INSERT, runtimeOptions.queryTimeoutSeconds(), batchLimits);
        }
        if (written != converted.size()) {
            throw new UnconfirmedWriteException();
        }
        return new BatchWriteResult(written, null,
                runtimeOptions.olapStreamLoadEnabled() ? CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE : CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /** 运行时准入独立于产品的 DECLARED 能力。 */
    private void refuseUnverifiedStreamLoad() {
        if (runtimeOptions.olapStreamLoadEnabled()) {
            OlapStreamLoadAdmission.requireSource(dataSource, "Doris");
        }
    }

    /**
     * 在原生目标操作前校验目标对象及能力。
     */
    @Override
    public void preflightNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition definition,
            java.util.Map<String, String> mapping) {
        if (runtimeOptions.olapStreamLoadEnabled()) {
            cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeTables.preflightStreamLoad(definition,
                    mapping);
        }
    }

    /**
     * 在写入前检查目标表定义是否满足当前路径的要求。
     */
    @Override
    public void preflightTargetDefinition(String schema, String table, List<String> statements, List<String> fields) {
        if (runtimeOptions.olapStreamLoadEnabled()) {
            cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadTargetDefinition.require("Doris", schema,
                    table, statements, fields);
        }
    }

    /**
     * 在实际写入前检查写入能力及目标条件。
     */
    @Override
    public void preflightRuntimeWrite() {
        if (stream != null) {
            stream.requireUsable();
        }
        refuseUnverifiedStreamLoad();
        if (!runtimeOptions.olapStreamLoadEnabled()) {
            return;
        }
        try (var connection = dataSource.getConnection()) {
            OlapStreamLoadAdmission.verify(connection, "Doris", runtimeOptions.queryTimeoutSeconds());
        } catch (java.sql.SQLException failure) {
            OlapStreamLoadAdmission.unsupported("Doris");
        }
        if (OlapStreamLoadHelper.tryCreate(dataSource, "_runtime", "_runtime", "Doris",
                runtimeOptions.queryTimeoutSeconds()) == null) {
            OlapStreamLoadAdmission.unsupported("Doris");
        }
    }
}
