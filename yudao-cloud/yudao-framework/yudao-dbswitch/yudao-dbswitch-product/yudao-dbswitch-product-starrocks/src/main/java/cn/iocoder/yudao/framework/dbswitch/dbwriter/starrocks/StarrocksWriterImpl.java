package cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadHelper;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadAdmission;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport;

import javax.sql.DataSource;
import java.sql.Types;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport.*;

/** 两种传输都绑定原生值，并保持请求确认边界。 */
public class StarrocksWriterImpl extends AbstractDatabaseWriter {

    private Table metadata;

    private OlapStreamLoadHelper streamLoadHelper;

    private Set<String> binary;

    private List<String> preparedFields;

    private boolean prepared;

    /**
     * 使用给定参数初始化 {@code StarrocksWriterImpl}。
     */
    public StarrocksWriterImpl(DataSource source) {
        this(source, false);
    }

    private final boolean copyRequired;

    /**
     * 初始化 {@code StarrocksWriterImpl}及其父类状态。
     */
    public StarrocksWriterImpl(DataSource source, boolean copyRequired) {
        super(source);
        this.copyRequired = copyRequired;
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
        refuseUnverifiedStreamLoad();
        if (streamLoadHelper != null) {
            streamLoadHelper.requireUsable();
        }
        prepared = false;
        metadata = null;
        preparedFields = null;
        streamLoadHelper = null;
        binary = null;
        schemaName = null;
        tableName = null;
        columns(fields);
        try (var c = dataSource.getConnection()) {
            if (runtimeOptions.olapStreamLoadEnabled()) {
                OlapStreamLoadAdmission.verify(c, "StarRocks", runtimeOptions.queryTimeoutSeconds());
            }
            metadata = metadata(c, schema, table, runtimeOptions.queryTimeoutSeconds());
            fields.forEach(metadata::column);
            if (!fields.containsAll(metadata.keys())) {
                throw new IllegalArgumentException("StarRocks key columns must be retained");
            }
            if (metadata.partitioning() != null && !fields.containsAll(metadata.partitioning().columns())) {
                throw new IllegalArgumentException("StarRocks partition columns must be written");
            }
            if (runtimeOptions.olapStreamLoadEnabled() && !new HashSet<>(fields).equals(
                    new HashSet<>(metadata.columns().stream().map(Column::name).toList()))) {
                throw new IllegalArgumentException("Stream Load requires a full-row projection");
            }
        } catch (Exception failure) {
            if (failure instanceof java.sql.SQLFeatureNotSupportedException) {
                OlapStreamLoadAdmission.unsupported("StarRocks");
            }
            throw new IllegalStateException("StarRocks writer native preflight failed");
        }
        schemaName = schema;
        tableName = table;
        preparedFields = List.copyOf(fields);
        binary = fields.stream().filter(f -> metadata.column(f).jdbcType() == Types.VARBINARY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        streamLoadHelper = runtimeOptions.olapStreamLoadEnabled() ? OlapStreamLoadHelper.tryCreate(dataSource, schema,
                table, "StarRocks", runtimeOptions.queryTimeoutSeconds()) : null;
        if (runtimeOptions.olapStreamLoadEnabled() && streamLoadHelper == null) {
            OlapStreamLoadAdmission.unsupported("StarRocks");
        }
        prepared = true;
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> rows) {
        refuseUnverifiedStreamLoad();
        if (streamLoadHelper != null) {
            streamLoadHelper.requireUsable();
        }
        if (runtimeOptions.olapStreamLoadEnabled() != (streamLoadHelper != null) && prepared) {
            throw new IllegalStateException("Prepared Stream Load transport cannot change");
        }
        if (!prepared) {
            throw new IllegalStateException("StarRocks writer is not prepared");
        }
        if (rows.isEmpty()) {
            return 0;
        }
        if (!preparedFields.equals(fields)) {
            throw new IllegalArgumentException("StarRocks writer projection changed after prepare");
        }
        var converted = convert(rows, fields, metadata, batchLimits);
        if (runtimeOptions.olapStreamLoadEnabled() && streamLoadHelper != null) {
            var encoded = streamLoadHelper.encode(fields, converted, binary, batchLimits);
            // 上述路由使用捕获的分区；HTTP 请求前，它们必须仍属于目标。
            try (var c = dataSource.getConnection()) {
                if (runtimeOptions.olapStreamLoadEnabled()) {
                    OlapStreamLoadAdmission.verify(c, "StarRocks", runtimeOptions.queryTimeoutSeconds());
                }
                var current = metadata(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
                if (!metadata.physicalIdentity().equals(current.physicalIdentity()) || !metadata.shape()
                        .equals(current.shape())) {
                    throw new IllegalStateException("StarRocks target changed after prepare");
                }
            } catch (java.sql.SQLException | RuntimeException failure) {
                throw new IllegalStateException("StarRocks stream preflight failed");
            }
            try {
                return streamLoadHelper.writeEncoded(encoded);
            } catch (UnconfirmedWriteException failure) {
                throw failure;
            } catch (cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new UnconfirmedWriteException();
            }
        }
        String sql = "INSERT INTO " + table(schemaName, tableName) + " (" + columns(
                fields) + ") VALUES (" + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
        return StarrocksJdbcSupport.write(dataSource, sql, converted, fields.stream().map(metadata::column).toList(),
                runtimeOptions.queryTimeoutSeconds(), batchLimits, metadata, schemaName, tableName);
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult writeWithReceipt(List<String> fields,
            List<Object[]> rows) {
        long count = write(fields, rows);
        return new cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult(count, null,
                count == 0 ? cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.NONE
                        : runtimeOptions.olapStreamLoadEnabled() ? cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE
                        : cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /** 运行时准入独立于产品的 DECLARED 能力。 */
    private void refuseUnverifiedStreamLoad() {
        if (copyRequired && !runtimeOptions.olapStreamLoadEnabled()) {
            OlapStreamLoadAdmission.unsupported("StarRocks");
        }
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
        if (streamLoadHelper != null) {
            streamLoadHelper.requireUsable();
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
