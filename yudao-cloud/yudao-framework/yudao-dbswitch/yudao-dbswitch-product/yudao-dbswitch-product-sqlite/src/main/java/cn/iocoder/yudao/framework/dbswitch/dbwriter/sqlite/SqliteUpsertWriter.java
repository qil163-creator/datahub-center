package cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqliteCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 以冲突目标执行 UPSERT，绝不使用可能删除自增标识并触发删除副作用的 INSERT OR REPLACE。 */
public final class SqliteUpsertWriter extends AbstractNativeUpsertWriter {

    private SqliteCursorMetadata.Shape shape;

    private CursorKeyContract contract;

    /**
     * 初始化 {@code SqliteUpsertWriter}及其父类状态。
     */
    public SqliteUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "SQLite";
    }

    /**
     * 按SQLite方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return SqliteCursorMetadata.quote(name);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        if (!"SQLite".equals(c.getMetaData().getDatabaseProductName())) {
            throw new SQLException("SQLite UPSERT requires SQLite");
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        shape = SqliteCursorMetadata.inspect(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        return shape.identity();
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        var current = SqliteCursorMetadata.keyContract(c, schemaName, tableName, uniqueKeys, shape,
                runtimeOptions.queryTimeoutSeconds(), false);
        SqliteCursorMetadata.requireReplaySafe(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        if (contract != null && !contract.equals(current)) {
            throw new SQLException("SQLite conflict-key contract changed");
        }
        for (String field : fields) {
            if (!shape.columns().containsKey(field) || shape.columns().get(field).hidden() != 0) {
                throw new SQLException("SQLite generated or missing input column");
            }
        }
        contract = current;
        upsertSql = buildUpsertSql();
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
        var updates = updateFields();
        String action = updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + String.join(",",
                updates.stream().map(n -> quote(n) + "=excluded." + quote(n)).toList());
        return "INSERT INTO " + target() + " (" + quoted(fields) + ") VALUES (" + placeholders() + ") ON CONFLICT ("
                + String.join(",", uniqueKeys.stream().map(n -> quote(n) + (shape != null && "TEXT".equals(
                shape.columns().get(n).affinity()) ? " COLLATE BINARY" : "")).toList()) + ") " + action;
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> input) {
        var rows = BatchValueConverter.convert(input, fields.size(), batchLimits,
                (value, index) -> SqliteCursorMetadata.exactValue(value, shape.columns().get(fields.get(index))));
        synchronized (Sqlite3WriterImpl.GLOBAL_WRITE_LOCK) {
            JdbcBatchExecutor.execute(dataSource, context -> {
                inspect(context.connection(), true);
                try (var statement = context.connection().prepareStatement(upsertSql)) {
                    statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                    for (var row : rows) {
                        for (int i = 0; i < row.length; i++) {
                            Object value = row[i];
                            int parameter = i + 1;
                            if (value == null) {
                                statement.setNull(parameter, Types.NULL);
                            } else if (value instanceof Long integer) {
                                statement.setLong(parameter, integer);
                            } else if (value instanceof String text) {
                                statement.setString(parameter, text);
                            } else if (value instanceof byte[] bytes) {
                                statement.setBytes(parameter, bytes);
                            } else if (value instanceof Double real) {
                                statement.setDouble(parameter, real);
                            } else {
                                throw new SQLException("SQLite value binding was not canonicalized");
                            }
                        }
                        statement.addBatch();
                    }
                    context.markWriteAttempted();
                    var counts = statement.executeBatch();
                    if (counts == null || counts.length != rows.size()) {
                        throw new SQLException("SQLite batch acknowledgement is incomplete");
                    }
                    for (int count : counts) {
                        if (count != 0 && count != 1) {
                            throw new SQLException("SQLite UPSERT acknowledgement is not confirmed");
                        }
                    }
                }
                return null;
            }, null, cn.iocoder.yudao.framework.dbswitch.product.sqlite3.SqliteNativeTables::configureWrite);
        }
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
