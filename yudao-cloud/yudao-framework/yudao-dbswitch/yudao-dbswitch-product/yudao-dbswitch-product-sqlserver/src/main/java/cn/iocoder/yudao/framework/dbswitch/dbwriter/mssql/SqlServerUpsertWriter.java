package cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql;

import cn.iocoder.yudao.framework.dbswitch.common.util.*;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerWriteColumns;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 在事务持有的目标锁下执行参数化原生 MERGE，提交和 UNKNOWN 处理仍共享。 */
public final class SqlServerUpsertWriter extends AbstractNativeUpsertWriter {

    private SqlServerCursorMetadata.Shape shape;

    private Map<String, ColumnDescription> columns;

    private CursorKeyContract contract;

    private boolean identityValues;

    /**
     * 初始化 {@code SqlServerUpsertWriter}及其父类状态。
     */
    public SqlServerUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Microsoft SQL Server";
    }

    /**
     * 按SQL Server方言转义并引用标识符。
     */
    @Override
    protected String quote(String value) {
        return SqlServerCursorMetadata.quote(value);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        SqlServerCursorMetadata.product(c);
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
        if (writing) {
            SqlServerCursorMetadata.lockTarget(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        shape = SqlServerCursorMetadata.read(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        columns = shape.columns();
        return shape.physical() + '\0' + shape.fingerprint();
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        contract = SqlServerCursorMetadata.keys(shape, uniqueKeys);
        identityValues = false;
        for (String name : fields) {
            var column = columns.get(name);
            if (column == null) {
                throw new SQLException("SQL Server target column is missing");
            }
            if (column.getMetadataEvidence().identity().state() == MetadataValue.State.VALUE) {
                if (!uniqueKeys.contains(name)) {
                    throw new SQLException("SQL Server replay identity must be an immutable explicit key");
                }
                identityValues = true;
            }
        }
        if (identityValues) {
            try (var p = c.prepareStatement("SELECT HAS_PERMS_BY_NAME(?, 'OBJECT', 'ALTER')")) {
                p.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                p.setString(1, target());
                try (var r = p.executeQuery()) {
                    if (!r.next() || r.getInt(1) != 1) {
                        throw new SQLException("Explicit replay identity requires ALTER permission");
                    }
                }
            }
        }
        if (fields.size() > 2100) {
            throw new SQLException("SQL Server binding parameter limit exceeded");
        }
        upsertSql = render();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        targetSchemaFingerprint();
        return contract;
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return keyContract().nativeFingerprint();
    }

    /**
     * 按目标方言生成按键更新或插入的 SQL。
     */
    @Override
    protected String buildUpsertSql() {
        return "";
    }

    /**
     * 将当前结构定义渲染为目标 SQL 或表达式。
     */
    private String render() throws SQLException {
        var values = new ArrayList<String>();
        for (String field : fields) {
            values.add("CAST(? AS " + SqlServerCursorMetadata.nativeType(columns.get(field)) + ")");
        }
        String on = String.join(" AND ", uniqueKeys.stream().map(n -> "d." + quote(n) + "=s." + quote(n)).toList());
        String update = updateFields().isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET " + String.join(",",
                updateFields().stream().map(n -> "d." + quote(n) + "=s." + quote(n)).toList());
        String sql = "MERGE " + target() + " WITH(HOLDLOCK) AS d USING(VALUES(" + String.join(",",
                values) + "))AS s(" + quoted(fields) + ") ON " + on + update
                + " WHEN NOT MATCHED THEN INSERT(" + quoted(fields) + ")VALUES(" + String.join(",",
                fields.stream().map(n -> "s." + quote(n)).toList()) + ");";
        if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
            throw new SQLException("SQL Server replay SQL byte budget exceeded");
        }
        return sql;
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        List<Object[]> bound;
        int[] types = new int[fields.size()];
        try {
            var order = new ArrayList<SqlServerWriteColumns.Column>();
            for (int i = 0; i < types.length; i++) {
                String name = fields.get(i);
                var column = columns.get(name);
                types[i] = SqlServerCursorMetadata.jdbcType(column);
                order.add(SqlServerCursorMetadata.domain(column, shape.codePages().get(name)));
            }
            bound = SqlServerWriteColumns.exactRows(BatchValueConverter.convert(rows, fields.size(), batchLimits),
                    order, true);
            types = SqlServerWriteColumns.bindingTypes(types, order);
        } catch (SQLException | RuntimeException failure) {
            throw new BatchRejectedException("SQL Server UPSERT input cannot be represented without loss");
        }
        int[] parameters = types;
        JdbcBatchExecutor.execute(dataSource, context -> {
            inspect(context.connection(), true);
            if (identityValues) {
                SqlServerIdentityTransfer.withExplicitValues(context, schemaName, tableName,
                        runtimeOptions.queryTimeoutSeconds(), ctx -> {
                            JdbcBatchExecutor.executeBatch(ctx, upsertSql, bound, parameters,
                                    runtimeOptions.queryTimeoutSeconds());
                            return null;
                        });
            } else {
                JdbcBatchExecutor.executeBatch(context, upsertSql, bound, parameters,
                        runtimeOptions.queryTimeoutSeconds());
            }
            return null;
        });
        // 仅确认输入，绝不宣称每次 MERGE 恰好修改了一条存储行。
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
