package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 使用现有批次事务边界；绝不探测行是否存在，也不重试写入。 */
public abstract class AbstractNativeUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    protected List<String> fields;

    protected List<String> uniqueKeys;

    private List<Integer> keyIndexes;

    protected String upsertSql;

    private String targetFingerprint;

    /**
     * 初始化 {@code AbstractNativeUpsertWriter}及其父类状态。
     */
    protected AbstractNativeUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public final void prepareWrite(String schema, String table, List<String> fields) {
        throw new IllegalArgumentException("Native UPSERT requires explicit unique keys");
    }

    /**
     * 不支持的键、索引或存储语义必须在发送任何数据语句前失败。
     */
    @Override
    public final void prepareUpsert(String schema, String table, List<String> names, List<String> keys) {
        fields = null;
        upsertSql = null;
        targetFingerprint = null;
        schemaName = identifier(schema);
        tableName = identifier(table);
        fields = identifiers(names);
        uniqueKeys = identifiers(keys);
        if (!fields.containsAll(uniqueKeys)) {
            throw new IllegalArgumentException("UPSERT keys must be present in the input fields");
        }
        keyIndexes = new ArrayList<>();
        uniqueKeys.forEach(key -> keyIndexes.add(fields.indexOf(key)));
        batchLimits.requireStatementParameters(fields.size());
        upsertSql = buildUpsertSql();
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Caller transaction is not supported");
            }
            inspect(connection, false);
        } catch (Exception failure) {
            fields = null;
            upsertSql = null;
            throw new IllegalArgumentException("Native UPSERT target or unique key is unsupported");
        }
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public final String targetSchemaFingerprint() {
        if (fields == null || targetFingerprint == null) {
            throw new IllegalStateException("UPSERT target is not prepared");
        }
        return targetFingerprint;
    }

    /**
     * 批量数据写入。
     */
    @Override
    public final long write(List<String> names, List<Object[]> rows) {
        return writeWithReceipt(names, rows).acknowledgedRows();
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public final BatchWriteResult writeWithReceipt(List<String> names, List<Object[]> rows) {
        if (fields == null || !fields.equals(names)) {
            throw new BatchRejectedException("UPSERT input fields differ from the prepared fields");
        }
        batchLimits.requireStatementParameters(fields.size());
        List<Object[]> converted = BatchValueConverter.convert(rows, fields.size(), batchLimits);
        for (Object[] row : converted) {
            for (int index : keyIndexes) {
                if (row[index] == null) {
                    throw new BatchRejectedException("UPSERT unique key values must not be NULL");
                }
            }
        }
        if (converted.isEmpty()) {
            return new BatchWriteResult(0, null);
        }
        return writePreparedRows(converted);
    }

    /**
     * 写入已完成预处理的数据行。
     */
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        return JdbcBatchExecutor.execute(dataSource, context -> {
            int[] types = inspect(context.connection(), true);
            JdbcBatchExecutor.executeBatch(context, upsertSql, rows, types);
            // JDBC 受影响行数取决于产品（MySQL 可能返回 0/1/2）。仅按已确认的输入计数。
            return new BatchWriteResult(rows.size(), null);
        });
    }

    /** 在绑定或发送数据批次前，在写入事务中重新检查结构及键语义。 */
    protected final int[] inspect(Connection connection, boolean writing) throws SQLException {
        verifyProduct(connection);
        lockTarget(connection, writing);
        int[] types = new int[fields.size()];
        StringBuilder shape = new StringBuilder("UPSERT_TARGET_V1").append('\0').append(target())
                .append('\0').append(String.join("\0", uniqueKeys)).append('\0')
                .append(additionalSchemaIdentity(connection));
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            try (var result = statement.executeQuery("SELECT " + quoted(fields) + " FROM " + target() + " WHERE 1=2")) {
                var metadata = result.getMetaData();
                if (metadata.getColumnCount() != fields.size()) {
                    throw new SQLException("UPSERT metadata mismatch");
                }
                for (int i = 0; i < fields.size(); i++) {
                    types[i] = bindingType(metadata.getColumnType(i + 1), metadata.getColumnTypeName(i + 1));
                    shape.append('\0').append(fields.get(i)).append(':').append(types[i]).append(':')
                            .append(metadata.getColumnTypeName(i + 1))
                            .append(':').append(metadata.getPrecision(i + 1)).append(':')
                            .append(metadata.getScale(i + 1))
                            .append(':').append(metadata.isNullable(i + 1));
                }
            }
        }
        verifyUniqueKey(connection);
        String fingerprint = cn.hutool.crypto.digest.DigestUtil.sha256Hex(shape.toString());
        if (targetFingerprint != null && !targetFingerprint.equals(fingerprint)) {
            throw new SQLException("UPSERT target schema changed");
        }
        targetFingerprint = fingerprint;
        return types;
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    protected void lockTarget(Connection connection, boolean writing) throws SQLException {
    }

    /**
     * 返回JDBC 类型。
     */
    protected int bindingType(int jdbcType, String typeName) {
        return jdbcType;
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    protected String additionalSchemaIdentity(Connection connection) throws SQLException {
        return getDatabaseProductName();
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    protected abstract void verifyProduct(Connection connection) throws SQLException;

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    protected abstract void verifyUniqueKey(Connection connection) throws SQLException;

    /**
     * 按目标方言生成按键更新或插入的 SQL。
     */
    protected abstract String buildUpsertSql();

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    protected abstract String quote(String name);

    /**
     * 返回目标端。
     */
    protected final String target() {
        return quote(schemaName) + "." + quote(tableName);
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    protected final String quoted(List<String> names) {
        return String.join(",", names.stream().map(this::quote).toList());
    }

    /**
     * 按字段数量及绑定类型生成参数占位符集合。
     */
    protected final String placeholders() {
        return String.join(",", java.util.Collections.nCopies(fields.size(), "?"));
    }

    /**
     * 更新字段集合。
     */
    protected final List<String> updateFields() {
        return fields.stream().filter(name -> !uniqueKeys.contains(name)).toList();
    }

    /**
     * 检查或转换当前数据库标识符。
     */
    private static String identifier(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid UPSERT identifier");
        }
        return value;
    }

    /**
     * 返回当前标识符集合。
     */
    private static List<String> identifiers(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("UPSERT fields and keys are required");
        }
        var copy = values.stream().map(AbstractNativeUpsertWriter::identifier).toList();
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("Duplicate UPSERT identifier");
        }
        return copy;
    }
}
