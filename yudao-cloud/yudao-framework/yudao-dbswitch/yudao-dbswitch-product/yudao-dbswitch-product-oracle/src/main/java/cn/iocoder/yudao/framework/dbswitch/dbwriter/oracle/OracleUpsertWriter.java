package cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractNativeUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleExactValues;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleReplayProfile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 已验证 Free 23.26.3 PDB 上，以派生表为源执行原生参数化 Oracle MERGE。必须恰好
 * 存在一个与显式键相同的完整非空唯一约束；JDBC 影响行数
 * 不论 Oracle 报告为每条插入或更新源行计 1，都不能作为数据行凭据；仅在 JDBC 提交返回后
 * 确认输入计数。键采用原生比较，即 NUMBER 相等、本地
 * DATE/TIMESTAMP 通过固定格式文本转换比较、BINARY 文本相等；载荷参数
 * 直接绑定目标列。批次中任何行违反原生值域时，在借用任何
 * 连接之前拒绝整个批次。ROW EXCLUSIVE 阻止复检与 DML 之间的触发器及 DDL 变化。
 */
public final class OracleUpsertWriter extends AbstractNativeUpsertWriter {

    private final OracleReplayProfile profile;

    private OracleCursorMetadata.Shape shape;

    private CursorKeyContract contract;

    private List<String> parameters;

    private boolean rendering;

    /**
     * 使用给定参数初始化 {@code OracleUpsertWriter}。
     */
    public OracleUpsertWriter(DataSource source) {
        this(source, OracleReplayProfile.ORACLE);
    }

    /**
     * 校验构造参数并初始化 {@code OracleUpsertWriter}及其父类状态。
     */
    public OracleUpsertWriter(DataSource source, OracleReplayProfile profile) {
        super(source);
        this.profile = java.util.Objects.requireNonNull(profile);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return profile.productName();
    }

    /**
     * 按Oracle方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return OracleCursorMetadata.quote(name);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection connection) throws SQLException {
        profile.prepareSession(connection, runtimeOptions.queryTimeoutSeconds(), true);
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection connection, boolean writing) throws SQLException {
        if (writing) {
            profile.lock(connection, schemaName, tableName, runtimeOptions.queryTimeoutSeconds(), true);
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection connection) throws SQLException {
        shape = profile.inspect(connection, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        return profile.identity(shape);
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection connection) throws SQLException {
        if (!shape.primaryKey().equals(uniqueKeys) || shape.uniqueIndexes().size() != 1 || !shape.unsafeUniqueIndexes()
                .isEmpty()
                || uniqueKeys.size() != shape.uniqueIndexes().values().iterator().next().size()
                || !Set.copyOf(shape.uniqueIndexes().values().iterator().next()).equals(Set.copyOf(uniqueKeys))) {
            throw new SQLException(
                    "Oracle UPSERT requires the exact ordered non-null primary key without additional unique constraints");
        }
        contract = profile.keys(shape, uniqueKeys,
                profile.comparison(connection, runtimeOptions.queryTimeoutSeconds()));
        for (String field : fields) {
            var column = shape.columns().get(field);
            if (column == null) {
                throw new SQLException("Oracle UPSERT target column is missing");
            }
            OracleExactValues.writable(column);
        }
        profile.verifyTarget(connection, shape, fields, uniqueKeys, runtimeOptions.queryTimeoutSeconds());
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
        // 基类在原生元数据准入前调用此方法；仅在验证阶段内部渲染。
        if (!rendering) {
            return "";
        }
        try {
            String source = String.join(",", uniqueKeys.stream().map(name -> {
                try {
                    return OracleCursorMetadata.keySource(shape.columns().get(name)) + " " + quote(name);
                } catch (SQLException failure) {
                    throw new IllegalArgumentException("Oracle key type is unsupported");
                }
            }).toList());
            String on = String.join(" AND ", uniqueKeys.stream().map(name -> {
                try {
                    return OracleCursorMetadata.keyMatch(shape.columns().get(name), "d." + quote(name),
                            "s." + quote(name));
                } catch (SQLException failure) {
                    throw new IllegalArgumentException("Oracle key type is unsupported");
                }
            }).toList());
            String update = updateFields().isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET " + String.join(",",
                    updateFields().stream()
                            .map(name -> "d." + quote(name) + "=" + profile.payloadParameter(shape.columns().get(name)))
                            .toList());
            String values = String.join(",", fields.stream().map(name -> uniqueKeys.contains(name) ? "s." + quote(name)
                    : profile.payloadParameter(shape.columns().get(name))).toList());
            String sql = "MERGE INTO " + target() + " d USING(SELECT " + source + " FROM dual)s ON(" + on + ")" + update
                    + " WHEN NOT MATCHED THEN INSERT(" + quoted(fields) + ")VALUES(" + values + ")";
            if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
                throw new IllegalArgumentException("Oracle UPSERT SQL budget exceeded");
            }
            return sql;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Oracle UPSERT SQL could not be prepared");
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
                var column = shape.columns().get(fields.get(index));
                try {
                    return profile.exactValue(column, value, uniqueKeys.contains(fields.get(index)));
                } catch (SQLException failure) {
                    throw new BatchRejectedException("Oracle input cannot be bound without loss");
                }
            });
        } catch (RuntimeException failure) {
            throw new BatchRejectedException("Oracle UPSERT input cannot be bound without loss");
        }
        JdbcBatchExecutor.execute(dataSource, context -> {
            inspect(context.connection(), true);
            int keys = uniqueKeys.size();
            var expanded = new ArrayList<Object[]>();
            for (Object[] row : exact) {
                var copy = new Object[parameters.size()];
                for (int i = 0; i < copy.length; i++) {
                    var column = shape.columns().get(parameters.get(i));
                    Object value = row[fields.indexOf(parameters.get(i))];
                    try {
                        copy[i] = i < keys && ("DATE".equals(column.dataType()) || "TIMESTAMP".equals(
                                column.dataType()))
                                ? OracleCursorMetadata.temporalText(column, (java.time.LocalDateTime) value)
                                : i < keys ? value : profile.payloadParameterValue(column, value);
                    } catch (SQLException failure) {
                        throw new BatchRejectedException("Oracle key parameter cannot be bound without loss");
                    }
                }
                expanded.add(copy);
            }
            var bounded = BatchValueConverter.convert(expanded, parameters.size(), batchLimits);
            int[] types = new int[parameters.size()];
            for (int i = 0; i < types.length; i++) {
                types[i] = i < keys
                        ? OracleCursorMetadata.keyBindType(shape.columns().get(parameters.get(i)))
                        : profile.payloadBindType(shape.columns().get(parameters.get(i)));
            }
            JdbcBatchExecutor.executeBatch(context, upsertSql, bounded, types, runtimeOptions.queryTimeoutSeconds());
            return null;
        });
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
