package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;

import javax.sql.DataSource;
import java.util.List;

/** PostgreSQL INSERT 的可选提交见证；兼容产品和 COPY 不继承此路径。 */
public final class PostgresInsertWriterImpl extends PostgresCompatibleInsertWriterImpl {

    private final PostgresIdentityTransfer identity;

    private AuthorizedInsert authorizedInsert;

    /** 仅支持普通 PostgreSQL 表的显式列 INSERT；不覆盖兼容产品或 COPY。 */
    @Override
    public String planInsertSql(String schema, String table, List<String> fields) {
        if (fields == null || fields.isEmpty() || new java.util.HashSet<>(fields).size() != fields.size()) {
            throw new IllegalArgumentException("Authorized INSERT requires distinct physical columns");
        }
        return plainInsertSql(schema, table, fields);
    }

    /** 返回的 SQL 必须与组件模板严格一致；仍保存并实际执行授权返回值。 */
    @Override
    public void useAuthorizedInsertSql(String schema, String table, List<String> fields, String sql,
            List<Integer> parameterOrder) {
        var expectedOrder = java.util.stream.IntStream.range(0, fields.size()).boxed().toList();
        String expected = com.alibaba.druid.sql.SQLUtils.toSQLString(
                com.alibaba.druid.sql.SQLUtils.parseSingleStatement(planInsertSql(schema, table, fields),
                        com.alibaba.druid.DbType.postgresql), com.alibaba.druid.DbType.postgresql);
        if (authorizedInsert != null || !expected.equals(sql) || !expectedOrder.equals(parameterOrder)) {
            throw new IllegalArgumentException("Authorized INSERT changed the planned SQL or parameter order");
        }
        authorizedInsert = new AuthorizedInsert(schema, table, List.copyOf(fields), sql, List.copyOf(parameterOrder));
    }

    private record AuthorizedInsert(String schema, String table, List<String> fields, String sql,
                                    List<Integer> parameterOrder) { }

    /**
     * 初始化 {@code PostgresInsertWriterImpl}及其父类状态。
     */
    public PostgresInsertWriterImpl(DataSource dataSource) {
        super(dataSource);
        identity = new PostgresIdentityTransfer(dataSource);
    }

    /**
     * 配置标识列在当前搬运路径中的处理方式。
     */
    @Override
    public void configureIdentityTransfer(
            cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions options) {
        identity.configure(options);
    }

    /**
     * 配置序列传输。
     */
    @Override
    public void configureSequenceTransfer(
            cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TableBinding binding) {
        identity.configureShared(binding);
    }

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    @Override
    public void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        super.configure(options);
        identity.configureRuntime(options);
    }

    /**
     * 在迁移前检查目标标识列策略和相关序列条件。
     */
    @Override
    public void preflightIdentityTransfer(String schema, String table, boolean exists) {
        identity.preflight(schema, table, exists);
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        if (authorizedInsert != null && (!authorizedInsert.schema().equals(schema)
                || !authorizedInsert.table().equals(table) || !authorizedInsert.fields().equals(fields))) {
            throw new IllegalArgumentException("Prepared target differs from the authorized INSERT");
        }
        identity.prepare(schema, table, fields);
        super.prepareWrite(schema, table, fields);
    }

    /**
     * 完成标识传输。
     */
    @Override
    public void completeIdentityTransfer() {
        identity.complete();
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    @Override
    protected String insertSql(List<String> fields) {
        if (authorizedInsert != null) {
            if (!authorizedInsert.schema().equals(schemaName) || !authorizedInsert.table().equals(tableName)
                    || !authorizedInsert.fields().equals(fields) || identity.overrideSystemValue()) {
                throw new IllegalStateException("Batch target differs from the authorized INSERT");
            }
            return authorizedInsert.sql();
        }
        String sql = super.insertSql(fields);
        if (!identity.overrideSystemValue()) {
            return sql;
        }
        int values = sql.lastIndexOf("VALUES");
        if (values < 0) {
            throw new IllegalStateException("Expected controlled INSERT VALUES statement");
        }
        return sql.substring(0, values) + "OVERRIDING SYSTEM VALUE " + sql.substring(values);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "PostgreSQL";
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> rows) {
        return writeWithReceipt(fields, rows).acknowledgedRows();
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public BatchWriteResult writeWithReceipt(List<String> fields, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return new BatchWriteResult(0, null);
        }
        for (var row : rows) {
            if (row == null || row.length != fields.size()) {
                throw new IllegalArgumentException("Invalid batch shape");
            }
        }
        String sql = insertSql(fields);
        List<Object[]> boundRows = rows;
        int[] originalTypes = fieldTypes(fields);
        int[] types = originalTypes;
        if (authorizedInsert != null) {
            // 参数始终按授权结果绑定；当前闭合契约仅接受组件原始顺序。
            boundRows = new java.util.ArrayList<>(rows.size());
            types = new int[fields.size()];
            for (int parameter = 0; parameter < fields.size(); parameter++) {
                types[parameter] = originalTypes[authorizedInsert.parameterOrder().get(parameter)];
            }
            for (Object[] row : rows) {
                Object[] reordered = new Object[fields.size()];
                for (int parameter = 0; parameter < fields.size(); parameter++) {
                    reordered[parameter] = row[authorizedInsert.parameterOrder().get(parameter)];
                }
                boundRows.add(reordered);
            }
        }
        List<Object[]> converted = BatchValueConverter.convert(boundRows, fields.size(), batchLimits);
        final int[] boundTypes = types;
        batchLimits.requireStatementParameters(types.length);
        return JdbcBatchExecutor.execute(dataSource, context -> {
            identity.beforeBatch(context.connection());
            var witness = PostgresCommitWitness.begin(context.connection(), schemaName, tableName);
            JdbcBatchExecutor.executeBatch(context, sql, converted, boundTypes, runtimeOptions.queryTimeoutSeconds());
            if (witness != null) {
                witness.verifyTransaction(context.connection());
            }
            return new Pending(converted.size(), witness);
        }, (connection, pending) -> {
            if (pending.witness() != null) {
                pending.witness().verifyCommitted(connection);
            }
        }).result();
    }

    private record Pending(long rows, PostgresCommitWitness witness) {

        /**
         * 返回结果。
         */
        BatchWriteResult result() {
            return new BatchWriteResult(rows, witness == null ? null : witness.receipt());
        }
    }
}
