package cn.iocoder.yudao.framework.dbswitch.dbwriter.dm;

import cn.iocoder.yudao.framework.dbswitch.core.util.*;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.product.dm.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 参数化 DM MERGE；载荷参数直接绑定到目标列，以保留 TIMESTAMP(9)。 */
public final class DmUpsertWriter extends AbstractNativeUpsertWriter {

    private DmNativeTables.ReadShape shape;

    private Map<String, DmNativeTables.ReadColumn> columns;

    private CursorKeyContract contract;

    private List<String> parameters;

    private boolean rendering;

    /**
     * 初始化 {@code DmUpsertWriter}及其父类状态。
     */
    public DmUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "DM";
    }

    /**
     * 按DM方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return DmSqlNames.quote(name);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        DmDriverCompatibility.require(c);
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
        if (writing) {
            DmTargetSideEffects.lock(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        DmTargetSideEffects.require(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        var current = DmNativeTables.readReplayShape(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        shape = current;
        columns = DmCursorMetadata.columns(shape);
        return shape.identity() + '\0' + shape.fingerprint();
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        contract = DmCursorMetadata.keyContract(c, shape, uniqueKeys, runtimeOptions.queryTimeoutSeconds());
        for (String field : fields) {
            if (!columns.containsKey(field)) {
                throw new SQLException("DM target column is missing");
            }
            DmCursorMetadata.jdbcType(columns.get(field));
        }
        parameters = new ArrayList<>(uniqueKeys);
        parameters.addAll(updateFields());
        parameters.addAll(updateFields());
        parameters = List.copyOf(parameters);
        batchLimits.requireStatementParameters(parameters.size());
        rendering = true;
        try {
            upsertSql = buildUpsertSql();
        } finally {
            rendering = false;
        }
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
        // 基类在原生元数据准入之前调用此方法；仅在已验证阶段渲染。
        if (!rendering) {
            return "";
        }
        try {
            String source = String.join(",", uniqueKeys.stream().map(n -> {
                try {
                    return DmCursorMetadata.keySource(columns.get(n)) + " " + quote(n);
                } catch (SQLException e) {
                    throw new IllegalArgumentException("DM key type is unsupported");
                }
            }).toList());
            String on = String.join(" AND ", uniqueKeys.stream().map(n -> {
                try {
                    return DmCursorMetadata.keyMatch(columns.get(n), "d." + quote(n), "s." + quote(n));
                } catch (SQLException e) {
                    throw new IllegalArgumentException("DM key type is unsupported");
                }
            }).toList());
            String update = updateFields().isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET " + String.join(",",
                    updateFields().stream().map(n -> "d." + quote(n) + "=?").toList());
            String values = String.join(",",
                    fields.stream().map(n -> uniqueKeys.contains(n) ? "s." + quote(n) : "?").toList());
            String sql = "MERGE INTO " + target() + " d USING(SELECT " + source + " FROM DUAL)s ON(" + on + ")" + update + " WHEN NOT MATCHED THEN INSERT(" + quoted(
                    fields) + ")VALUES(" + values + ")";
            if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
                throw new IllegalArgumentException("DM UPSERT SQL budget exceeded");
            }
            return sql;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("DM UPSERT SQL could not be prepared");
        }
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        List<Object[]> exact;
        try {
            exact = BatchValueConverter.convert(rows, fields.size(), batchLimits, (value, index) -> {
                try {
                    var column = columns.get(fields.get(index));
                    return uniqueKeys.contains(fields.get(index)) ? DmCursorMetadata.exactKey(value,
                            column) : DmCursorMetadata.exact(value, column);
                } catch (SQLException failure) {
                    throw new BatchRejectedException("DM input cannot be bound without loss");
                }
            });
        } catch (RuntimeException failure) {
            throw new BatchRejectedException("DM UPSERT input cannot be bound without loss");
        }
        JdbcBatchExecutor.execute(dataSource, context -> {
            inspect(context.connection(), true);
            int keys = uniqueKeys.size();
            var expanded = new ArrayList<Object[]>();
            for (Object[] row : exact) {
                var copy = new Object[parameters.size()];
                for (int i = 0; i < copy.length; i++) {
                    Object value = row[fields.indexOf(parameters.get(i))];
                    copy[i] = i < keys ? DmCursorMetadata.keyParameter(value, columns.get(parameters.get(i))) : value;
                }
                expanded.add(copy);
            }
            var bounded = BatchValueConverter.convert(expanded, parameters.size(), batchLimits);
            int[] types = new int[parameters.size()];
            for (int i = 0; i < types.length; i++) {
                var column = columns.get(parameters.get(i));
                types[i] = i < keys ? DmCursorMetadata.keyParameterType(column) : DmCursorMetadata.jdbcType(column);
            }
            JdbcBatchExecutor.executeBatch(context, upsertSql, bounded, types, runtimeOptions.queryTimeoutSeconds());
            return null;
        });
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
