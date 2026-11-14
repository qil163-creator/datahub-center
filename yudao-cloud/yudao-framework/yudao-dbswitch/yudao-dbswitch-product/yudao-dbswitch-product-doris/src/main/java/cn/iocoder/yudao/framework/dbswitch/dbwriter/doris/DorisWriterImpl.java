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

    private AuthorizedInsert authorizedInsert;

    private boolean authorizedCreateAttempted;

    private DorisJdbcSupport.Evidence authorizedCreated;

    private record AuthorizedInsert(String schema, String table, List<String> fields, String sql) { }

    @Override
    public String planAuthorizedCreateTable(String schema, String table,
            List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> columns,
            List<String> primaryKeys, String comment, java.util.Map<String, String> properties,
            cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMappings mappings) {
        planInsertSql(schema, table, columns.stream()
                .map(cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription::getFieldName).toList());
        return cn.iocoder.yudao.framework.dbswitch.product.doris.DorisAuthorizedCreateNew.createSql(
                schema, table, columns, primaryKeys, comment, properties, mappings);
    }

    /** 授权复制只提供显式 JDBC 参数模板，不以流载入替代已授权语句。 */
    @Override
    public String planInsertSql(String schema, String table, List<String> fields) {
        if (copyRequired || runtimeOptions.olapStreamLoadEnabled()) {
            throw new UnsupportedOperationException("Authorized Doris INSERT requires JDBC transport");
        }
        return cn.iocoder.yudao.framework.dbswitch.product.doris.DorisAuthorizedCreateNew.insertSql(schema, table, fields);
    }

    @Override
    public void useAuthorizedInsertSql(String schema, String table, List<String> fields, String sql,
            List<Integer> parameterOrder) {
        if (authorizedInsert != null || captured != null || !planInsertSql(schema, table, fields).equals(sql)
                || !java.util.stream.IntStream.range(0, fields.size()).boxed().toList().equals(parameterOrder)) {
            throw new IllegalArgumentException("Doris authorized INSERT differs from the exact target projection");
        }
        authorizedInsert = new AuthorizedInsert(schema, table, List.copyOf(fields), sql);
    }

    /** 用原生字面值会话执行同一授权 CREATE；发出后失败保持未知结果，不重试或覆盖已有表。 */
    @Override
    public void executeAuthorizedCreateTable(cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan plan) {
        if (authorizedInsert == null || authorizedCreateAttempted || plan == null || plan.steps().size() != 2
                || !plan.equals(cn.iocoder.yudao.framework.dbswitch.product.doris.DorisAuthorizedCreateNew.plan(
                authorizedInsert.schema(), authorizedInsert.table(), authorizedInsert.fields(),
                plan.steps().getFirst().sql(), authorizedInsert.sql()))) {
            throw new IllegalArgumentException("Doris CREATE differs from the installed authorized plan");
        }
        planInsertSql(authorizedInsert.schema(), authorizedInsert.table(), authorizedInsert.fields());
        authorizedCreateAttempted = true;
        cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution.execute(dataSource,
                (connection, attempt) -> {
                    if (!connection.getAutoCommit()) throw new java.sql.SQLException("Doris DDL requires an owned idle connection");
                    var observed = cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaChanges.observe(connection,
                            new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl(),
                            authorizedInsert.schema(), authorizedInsert.table(),
                            cn.iocoder.yudao.framework.dbswitch.core.schema.MetadataReadOptions.fresh());
                    if (observed.precondition().tableExists()) {
                        throw new java.sql.SQLException("Authorized Doris CREATE_NEW refuses an existing native target");
                    }
                    try (var literals = DorisJdbcSupport.LiteralMode.open(connection, runtimeOptions.queryTimeoutSeconds());
                         var statement = connection.createStatement()) {
                        statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                        attempt.sending();
                        statement.execute(plan.steps().getFirst().sql());
                        if (statement.getWarnings() != null) throw new java.sql.SQLException("Doris CREATE returned warnings");
                        var created = DorisJdbcSupport.inspect(connection, authorizedInsert.schema(),
                                authorizedInsert.table(), runtimeOptions.queryTimeoutSeconds());
                        if (!cn.iocoder.yudao.framework.dbswitch.product.doris.DorisModelingSchemaScript.matchesCreate(
                                authorizedInsert.schema(), authorizedInsert.table(), plan.steps().getFirst().sql(), created.table())) {
                            throw new java.sql.SQLException("Doris created definition differs from the authorized native plan");
                        }
                        authorizedCreated = created;
                    }
                    return 0;
                });
    }

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
        if (authorizedInsert != null && (!authorizedInsert.schema().equals(schema)
                || !authorizedInsert.table().equals(table) || !authorizedInsert.fields().equals(fields)
                || authorizedCreated == null || !authorizedInsert.sql().equals(planInsertSql(schema, table, fields)))) {
            throw new IllegalArgumentException("Doris prepared target differs from the authorized INSERT");
        }
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
            if (authorizedInsert != null && (!authorizedCreated.equals(observation)
                    || DorisJdbcSupport.countRows(c, schema, table, runtimeOptions.queryTimeoutSeconds()) != 0)) {
                throw new IllegalStateException("Doris authorized target changed or received rows before copy");
            }
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
        if (authorizedInsert != null) planInsertSql(schemaName, tableName, fields);
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
                DorisJdbcSupport.Operation.INSERT, runtimeOptions.queryTimeoutSeconds(), batchLimits,
                authorizedInsert == null ? null : authorizedInsert.sql());
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

    /** 只对同一创建代次确认当前行数；检查后发生的外部写入不在此次观测承诺内。 */
    @Override
    public void completeAuthorizedTransfer(long acknowledgedRows) {
        if (authorizedInsert == null || captured == null || acknowledgedRows < 0) {
            throw new IllegalStateException("Doris authorized transfer is not prepared");
        }
        try (var connection = dataSource.getConnection()) {
            var before = DorisJdbcSupport.inspect(connection, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
            long count = DorisJdbcSupport.countRows(connection, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
            var after = DorisJdbcSupport.inspect(connection, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
            if (!authorizedCreated.equals(before) || !before.equals(after) || count != acknowledgedRows) {
                throw new java.sql.SQLException("Doris copied rows or native generation differ from acknowledged transfer");
            }
        } catch (Exception uncertain) {
            throw new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
        }
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
