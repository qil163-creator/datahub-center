package cn.iocoder.yudao.framework.dbswitch.dbwriter.doris;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadHelper;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadAdmission;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport;

import java.util.List;

import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;

import javax.sql.DataSource;

/**
 * Doris写入实现类。
 *
 * @author jrl
 */
public class DorisWriterImpl extends AbstractDatabaseWriter {

    private DorisJdbcSupport.Evidence captured;

    private List<String> preparedFields;

    private OlapStreamLoadHelper stream;

    /**
     * 使用给定参数初始化 {@code DorisWriterImpl}。
     */
    public DorisWriterImpl(DataSource source) {
        this(source, false);
    }

    private final boolean copyRequired;

    /**
     * 初始化 {@code DorisWriterImpl}及其父类状态。
     */
    public DorisWriterImpl(DataSource source, boolean copyRequired) {
        super(source);
        this.copyRequired = copyRequired;
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
        captured = null;
        preparedFields = null;
        stream = null;
        schemaName = null;
        tableName = null;
        names(fields);
        try (var c = dataSource.getConnection()) {
            if (runtimeOptions.olapStreamLoadEnabled()) {
                OlapStreamLoadAdmission.verify(c, "Doris", runtimeOptions.queryTimeoutSeconds());
            }
            var observation = DorisJdbcSupport.inspect(c, schema, table, runtimeOptions.queryTimeoutSeconds());
            fields.forEach(observation.table()::column);
            if (runtimeOptions.olapStreamLoadEnabled() && (!java.util.Set.of("DUPLICATE", "UNIQUE")
                    .contains(observation.table().model())
                    || observation.table().columns().stream().anyMatch(column -> column.kind().equals("json")))) {
                throw new IllegalArgumentException("Stream Load native type or aggregation requires a separate plan");
            }
            if (!fields.containsAll(observation.table().keys())) {
                throw new IllegalArgumentException("Doris native key columns must be retained");
            }
            if (observation.table().partitioning() != null && !fields.containsAll(
                    observation.table().partitioning().columns())) {
                throw new IllegalArgumentException("Doris native partition columns must be written");
            }
            if (observation.table().unique() && !"true".equals(
                    observation.table().properties().get("enable_unique_key_merge_on_write"))
                    || observation.table().properties().containsKey("function_column.sequence_col")) {
                throw new IllegalArgumentException("Doris native write precedence requires a separate plan");
            }
            if (runtimeOptions.olapStreamLoadEnabled() && !new java.util.HashSet<>(fields).equals(
                    new java.util.HashSet<>(observation.table().columns().stream().map(c1 -> c1.name()).toList()))) {
                throw new IllegalArgumentException("Stream Load requires a full-row projection");
            }
            captured = observation;
        } catch (Exception failure) {
            if (failure instanceof java.sql.SQLFeatureNotSupportedException) {
                OlapStreamLoadAdmission.unsupported("Doris");
            }
            throw new IllegalStateException("Doris writer native preflight failed");
        }
        schemaName = schema;
        tableName = table;
        preparedFields = List.copyOf(fields);
        stream = runtimeOptions.olapStreamLoadEnabled() ? OlapStreamLoadHelper.tryCreate(dataSource, schema, table,
                "Doris", runtimeOptions.queryTimeoutSeconds()) : null;
        if (runtimeOptions.olapStreamLoadEnabled() && stream == null) {
            OlapStreamLoadAdmission.unsupported("Doris");
        }
    }

    /**
     * 核验已准备投影、目标结构及传输配置后批量写入数据。
     */
    @Override
    public long write(List<String> fields, List<Object[]> rows) {
        refuseUnverifiedStreamLoad();
        if (stream != null) {
            stream.requireUsable();
        }
        if (runtimeOptions.olapStreamLoadEnabled() != (stream != null) && captured != null) {
            throw new IllegalStateException("Prepared Stream Load transport cannot change");
        }
        if (captured == null || !fields.equals(preparedFields)) {
            throw new IllegalStateException("Doris writer projection is not prepared");
        }
        if (rows.isEmpty()) {
            return 0;
        }
        if (runtimeOptions.olapStreamLoadEnabled() && stream != null) {
            var converted = DorisJdbcSupport.convert(rows, fields, captured.table(), batchLimits);
            var encoded = stream.encode(fields, converted, java.util.Set.of(), batchLimits);
            try (var c = dataSource.getConnection()) {
                if (runtimeOptions.olapStreamLoadEnabled()) {
                    OlapStreamLoadAdmission.verify(c, "Doris", runtimeOptions.queryTimeoutSeconds());
                }
                var current = DorisJdbcSupport.inspect(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
                if (!captured.physical().equals(current.physical()) || !captured.table().equals(current.table())) {
                    throw new IllegalStateException("Doris target changed after prepare");
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Doris stream preflight failed");
            }
            try {
                return stream.writeEncoded(encoded);
            } catch (cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException failure) {
                throw failure;
            } catch (cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
            }
        }
        return DorisJdbcSupport.write(dataSource, schemaName, tableName, captured, fields, rows,
                DorisJdbcSupport.Operation.INSERT, runtimeOptions.queryTimeoutSeconds(), batchLimits);
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
            OlapStreamLoadAdmission.unsupported("Doris");
        }
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
