package cn.iocoder.yudao.framework.dbswitch.dbwriter.opengauss;

import cn.iocoder.yudao.framework.dbswitch.core.util.OpengaussCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** openGauss 显式主键 MERGE，不推断支持 PostgreSQL ON CONFLICT 或 PostgreSQL 事务凭据。 */
public final class OpengaussUpsertWriter extends AbstractNativeUpsertWriter {

    private OpengaussCursorMetadata.Shape shape;

    private CursorKeyContract contract;

    private List<String> parameters;

    private boolean rendering;

    /**
     * 初始化 {@code OpengaussUpsertWriter}及其父类状态。
     */
    public OpengaussUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "OpenGauss";
    }

    /**
     * 按openGauss方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return OpengaussCursorMetadata.quote(name);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException { /* 原生身份验证和目录准入随后在同一连接执行。 */ }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
        if (writing) {
            OpengaussCursorMetadata.lock(c, schemaName, tableName, true, runtimeOptions.queryTimeoutSeconds());
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        shape = OpengaussCursorMetadata.inspect(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        return shape.identity() + '\0' + shape.fingerprint();
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        contract = OpengaussCursorMetadata.contract(shape, uniqueKeys);
        for (String field : fields) {
            if (!shape.columns().containsKey(field)) {
                throw new SQLException("openGauss target column is missing");
            }
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
        if (!rendering) {
            return "";
        }
        String source = String.join(",", uniqueKeys.stream().map(n -> {
            try {
                return "CAST(? AS pg_catalog." + OpengaussCursorMetadata.keyType(shape.columns().get(n)) + ") " + quote(
                        n);
            } catch (SQLException e) {
                throw new IllegalArgumentException("openGauss key type is unsupported");
            }
        }).toList());
        String on = String.join(" AND ", uniqueKeys.stream().map(n -> "d." + quote(n) + "=s." + quote(n)).toList());
        String update = updateFields().isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET " + String.join(",",
                updateFields().stream().map(n -> quote(n) + "=?").toList());
        String values = String.join(",",
                fields.stream().map(n -> uniqueKeys.contains(n) ? "s." + quote(n) : "?").toList());
        String sql = "MERGE INTO " + target() + " d USING(SELECT " + source + ")s ON(" + on + ")" + update + " WHEN NOT MATCHED THEN INSERT(" + quoted(
                fields) + ")VALUES(" + values + ")";
        if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
            throw new IllegalArgumentException("openGauss UPSERT SQL budget exceeded");
        }
        return sql;
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        var exact = BatchValueConverter.convert(rows, fields.size(), batchLimits, (value, index) -> {
            try {
                return OpengaussCursorMetadata.exact(value, shape.columns().get(fields.get(index)));
            } catch (SQLException e) {
                throw new BatchRejectedException("openGauss UPSERT input cannot be preserved exactly");
            }
        });
        JdbcBatchExecutor.execute(dataSource, context -> {
            inspect(context.connection(), true);
            var expanded = new ArrayList<Object[]>();
            for (Object[] row : exact) {
                var copy = new Object[parameters.size()];
                for (int i = 0; i < copy.length; i++) {
                    copy[i] = row[fields.indexOf(parameters.get(i))];
                }
                expanded.add(copy);
            }
            var bounded = BatchValueConverter.convert(expanded, parameters.size(), batchLimits);
            int[] types = new int[parameters.size()];
            for (int i = 0; i < types.length; i++) {
                types[i] = OpengaussCursorMetadata.jdbcType(shape.columns().get(parameters.get(i)));
            }
            JdbcBatchExecutor.executeBatch(context, upsertSql, bounded, types, runtimeOptions.queryTimeoutSeconds());
            return null;
        });
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
