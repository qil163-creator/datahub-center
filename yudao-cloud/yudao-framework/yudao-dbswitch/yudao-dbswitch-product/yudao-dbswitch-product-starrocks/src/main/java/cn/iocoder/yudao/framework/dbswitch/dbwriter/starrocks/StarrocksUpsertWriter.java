package cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks;

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
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport;

import javax.sql.DataSource;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/**
 * StarRocks PRIMARY 键 UPSERT。原生更新插入即键模型的 INSERT：完整行 INSERT 替换
 * 已有主键，因此重放已确认批次会收敛。必须写入完整行（部分
 * INSERT 会默默用默认值替换缺失列）。借用任何连接前，先证明
 * 值精确；批次通过 Stream Load 回执发送（稳定标签，只接受完整 VISIBLE
 * 回执，绝不改写 UNKNOWN），或在 Stream Load 不可用时使用严格
 * 按请求确认的 JDBC INSERT。不虚构事务或回滚，也不自动重试。
 */
public final class StarrocksUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    private StarrocksJdbcSupport.Table captured;

    private List<String> fields, keys;

    private java.util.Set<String> binary;

    private String fingerprint;

    private OlapStreamLoadHelper stream;

    /**
     * 初始化 {@code StarrocksUpsertWriter}及其父类状态。
     */
    public StarrocksUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "StarRocks";
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
        refuseUnverifiedStreamLoad();
        if (stream != null) {
            stream.requireUsable();
        }
        captured = null;
        fields = null;
        keys = null;
        binary = null;
        fingerprint = null;
        stream = null;
        try (var connection = dataSource.getConnection()) {
            if (runtimeOptions.olapStreamLoadEnabled()) {
                OlapStreamLoadAdmission.verify(connection, "StarRocks", runtimeOptions.queryTimeoutSeconds());
            }
            var shape = StarrocksJdbcSupport.metadata(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
            if (!shape.model().equals("PRIMARY_KEYS")
                    || uniqueKeys == null || !shape.keys().equals(uniqueKeys)
                    || names == null || new HashSet<>(names).size() != names.size()
                    || !new HashSet<>(names).equals(
                    new HashSet<>(shape.columns().stream().map(StarrocksJdbcSupport.Column::name).toList()))) {
                throw new IllegalArgumentException();
            }
            names.forEach(shape::column);
            if (shape.partitioning() != null && !names.containsAll(shape.partitioning().columns())) {
                throw new IllegalArgumentException();
            }
            captured = shape;
        } catch (Exception failure) {
            captured = null;
            if (failure instanceof java.sql.SQLFeatureNotSupportedException) {
                OlapStreamLoadAdmission.unsupported("StarRocks");
            }
            throw new IllegalArgumentException(
                    "StarRocks UPSERT target, primary key model or full-row projection is unsupported");
        }
        schemaName = schema;
        tableName = table;
        fields = List.copyOf(names);
        keys = List.copyOf(uniqueKeys);
        binary = fields.stream().filter(name -> captured.column(name).jdbcType() == Types.VARBINARY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        fingerprint = DigestUtil.sha256Hex(
                "STARROCKS_UPSERT_TARGET_V1\0" + captured.identity() + '\0' + captured.physicalIdentity()
                        + '\0' + String.join("\0", names) + '\0' + String.join("\0", uniqueKeys));
        stream = runtimeOptions.olapStreamLoadEnabled()
                ? OlapStreamLoadHelper.tryCreate(dataSource, schema, table, "StarRocks",
                runtimeOptions.queryTimeoutSeconds()) : null;
        if (runtimeOptions.olapStreamLoadEnabled() && stream == null) {
            OlapStreamLoadAdmission.unsupported("StarRocks");
        }
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        if (fields == null) {
            throw new IllegalStateException("StarRocks UPSERT target is not prepared");
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
            throw new IllegalStateException("StarRocks UPSERT target is not prepared");
        }
        return new StarrocksCursorMetadata.Shape(schemaName, tableName, captured).sourceContract();
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
            throw new BatchRejectedException("StarRocks UPSERT input fields differ from the prepared fields");
        }
        if (rows.isEmpty()) {
            return new BatchWriteResult(0, null);
        }
        // 借用任何连接或发送任何请求前，先证明整个批次的精确性并完成原生分区路由。
        var converted = StarrocksJdbcSupport.convert(rows, fields, captured, batchLimits);
        var seen = new HashSet<List<Object>>();
        for (var row : converted) {
            var key = new ArrayList<Object>();
            for (String name : keys) {
                Object value = row[fields.indexOf(name)];
                if (value == null) {
                    throw new BatchRejectedException("StarRocks UPSERT key values must not be NULL");
                }
                key.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
            }
            if (!seen.add(key)) {
                throw new BatchRejectedException("StarRocks UPSERT batch repeats a native key");
            }
        }
        long written;
        if (runtimeOptions.olapStreamLoadEnabled() && stream != null) {
            var encoded = stream.encode(fields, converted, binary, batchLimits);
            try (var connection = dataSource.getConnection()) {
                OlapStreamLoadAdmission.verify(connection, "StarRocks", runtimeOptions.queryTimeoutSeconds());
                var current = StarrocksJdbcSupport.metadata(connection, schemaName, tableName,
                        runtimeOptions.queryTimeoutSeconds());
                if (!captured.physicalIdentity().equals(current.physicalIdentity()) || !captured.shape()
                        .equals(current.shape())) {
                    throw new IllegalStateException("StarRocks UPSERT target changed after prepare");
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("StarRocks stream preflight failed");
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
            String sql = "INSERT INTO " + StarrocksJdbcSupport.table(schemaName,
                    tableName) + " (" + StarrocksJdbcSupport.columns(fields)
                    + ") VALUES (" + String.join(",", java.util.Collections.nCopies(fields.size(), "?")) + ")";
            written = StarrocksJdbcSupport.write(dataSource, sql, converted,
                    fields.stream().map(captured::column).toList(),
                    runtimeOptions.queryTimeoutSeconds(), batchLimits, captured, schemaName, tableName);
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
            OlapStreamLoadAdmission.requireSource(dataSource, "StarRocks");
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
            cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksNativeTables.preflightStreamLoad(definition,
                    mapping);
        }
    }

    /**
     * 在写入前检查目标表定义是否满足当前路径的要求。
     */
    @Override
    public void preflightTargetDefinition(String schema, String table, List<String> statements, List<String> fields) {
        if (runtimeOptions.olapStreamLoadEnabled()) {
            cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadTargetDefinition.require("StarRocks",
                    schema, table, statements, fields);
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
            OlapStreamLoadAdmission.verify(connection, "StarRocks", runtimeOptions.queryTimeoutSeconds());
        } catch (java.sql.SQLException failure) {
            OlapStreamLoadAdmission.unsupported("StarRocks");
        }
        if (OlapStreamLoadHelper.tryCreate(dataSource, "_runtime", "_runtime", "StarRocks",
                runtimeOptions.queryTimeoutSeconds()) == null) {
            OlapStreamLoadAdmission.unsupported("StarRocks");
        }
    }
}
