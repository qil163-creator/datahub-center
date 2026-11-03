package cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlserver;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.SqlServer2000ReplayMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqlServer2000SourceGeneration;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 原生 SQL Server 2000 在事务独占表锁保护下执行原子更新或插入。 */
public final class SqlServer2000UpsertWriter extends AbstractNativeUpsertWriter {

    private SqlServer2000ReplayMetadata.Shape shape;

    private CursorKeyContract contract;

    private List<String> parameters;

    private SourceGenerationPin targetPin;

    /**
     * 初始化 {@code SqlServer2000UpsertWriter}及其父类状态。
     */
    public SqlServer2000UpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 为游标写入路径配置目标关系代次信息。
     */
    @Override
    public void configureCursorTargetGeneration(SourceGenerationPin pin) {
        if (pin == null || fields != null) {
            throw new IllegalArgumentException(
                    "SQL Server 2000 cursor requires a target deployment generation before preparation");
        }
        targetPin = pin;
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Microsoft SQL Server";
    }

    /**
     * 按SQL Server 2000方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return SqlServer2000ReplayMetadata.quote(name);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        SqlServer2000ReplayMetadata.product(c);
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
        if (writing) {
            SqlServer2000ReplayMetadata.lock(c, target(), true, Math.min(120, runtimeOptions.queryTimeoutSeconds()));
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        if (targetPin != null) {
            SqlServer2000SourceGeneration.verifyCurrent(c, targetPin,
                    Math.min(120, runtimeOptions.queryTimeoutSeconds()));
        }
        shape = SqlServer2000ReplayMetadata.read(c, schemaName, tableName,
                Math.min(120, runtimeOptions.queryTimeoutSeconds()));
        return shape.physical() + '\0' + shape.fingerprint() + '\0' + (targetPin == null ? "STANDALONE_UPSERT" : targetPin.fingerprint());
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        contract = SqlServer2000ReplayMetadata.keys(shape, uniqueKeys);
        if (!shape.columns().keySet().containsAll(fields)) {
            throw new SQLException("SQL Server 2000 UPSERT projection is incomplete");
        }
        for (var entry : shape.columns().entrySet()) {
            if (!fields.contains(entry.getKey()) && !entry.getValue().nullable()) {
                throw new SQLException("SQL Server 2000 UPSERT omits a required column");
            }
        }
        upsertSql = render();
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
    private String render() {
        var update = updateFields();
        var names = new ArrayList<String>();
        String assignment;
        if (update.isEmpty()) {
            assignment = quote(uniqueKeys.getFirst()) + "=" + quote(uniqueKeys.getFirst());
        } else {
            assignment = String.join(",", update.stream().map(n -> quote(n) + "=?").toList());
            names.addAll(update);
        }
        names.addAll(uniqueKeys);
        names.addAll(fields);
        parameters = List.copyOf(names);
        batchLimits.requireStatementParameters(parameters.size());
        return "UPDATE " + target() + " SET " + assignment + " WHERE "
                + String.join(" AND ", uniqueKeys.stream().map(n -> quote(n) + "=?").toList())
                + "; IF @@ROWCOUNT=0 INSERT INTO " + target() + "(" + quoted(
                fields) + ") VALUES(" + placeholders() + ")";
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
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        var exact = new ArrayList<Object[]>();
        int[] types = new int[parameters.size()];
        try {
            for (int i = 0; i < types.length; i++) {
                types[i] = shape.columns().get(parameters.get(i)).jdbcType();
            }
            for (Object[] row : rows) {
                var values = new Object[fields.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = shape.columns().get(fields.get(i)).exact(row[i]);
                }
                var bound = new Object[parameters.size()];
                for (int i = 0; i < bound.length; i++) {
                    bound[i] = values[fields.indexOf(parameters.get(i))];
                }
                exact.add(bound);
            }
        } catch (SQLException | RuntimeException invalid) {
            throw new BatchRejectedException("SQL Server 2000 UPSERT values cannot be represented without loss");
        }
        var bounded = BatchValueConverter.convert(exact, parameters.size(), batchLimits);
        JdbcBatchExecutor.execute(dataSource, context -> {
            inspect(context.connection(), true);
            JdbcBatchExecutor.executeBatch(context, upsertSql, bounded, types,
                    Math.min(120, runtimeOptions.queryTimeoutSeconds()));
            return null;
        });
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
