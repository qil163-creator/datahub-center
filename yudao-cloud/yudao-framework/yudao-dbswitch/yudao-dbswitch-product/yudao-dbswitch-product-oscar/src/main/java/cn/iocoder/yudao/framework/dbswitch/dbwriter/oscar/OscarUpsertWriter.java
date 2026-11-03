package cn.iocoder.yudao.framework.dbswitch.dbwriter.oscar;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarReplayMetadata;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 在表排他锁下执行原生 ShenTong MERGE，共用批次提交及 UNKNOWN 语义。 */
public final class OscarUpsertWriter extends AbstractNativeUpsertWriter {

    private OscarReplayMetadata.Shape shape;

    private CursorKeyContract contract;

    /**
     * 初始化 {@code OscarUpsertWriter}及其父类状态。
     */
    public OscarUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "OSCAR";
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return OscarReplayMetadata.quote(name);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        OscarReplayMetadata.product(c);
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
        if (writing) {
            OscarReplayMetadata.lock(c, target(), true, Math.min(120, runtimeOptions.queryTimeoutSeconds()));
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        shape = OscarReplayMetadata.read(c, schemaName, tableName, Math.min(120, runtimeOptions.queryTimeoutSeconds()));
        return shape.physical() + '\0' + shape.fingerprint();
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        contract = OscarReplayMetadata.keys(shape, uniqueKeys);
        if (!shape.columns().keySet().containsAll(fields)) {
            throw new SQLException("Oscar UPSERT projection is incomplete");
        }
        for (var entry : shape.columns().entrySet()) {
            if (!fields.contains(entry.getKey()) && !entry.getValue().nullable()) {
                throw new SQLException("Oscar UPSERT omits a required column");
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
        String projection = String.join(",",
                fields.stream().map(n -> "CAST(? AS " + shape.columns().get(n).nativeType() + ") AS " + quote(n))
                        .toList());
        String on = String.join(" AND ", uniqueKeys.stream().map(n -> "T." + quote(n) + "=S." + quote(n)).toList());
        String update = updateFields().isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET "
                + String.join(",", updateFields().stream().map(n -> "T." + quote(n) + "=S." + quote(n)).toList());
        return "MERGE INTO " + target() + " T USING(SELECT " + projection + " FROM DUAL) S ON(" + on + ")" + update
                + " WHEN NOT MATCHED THEN INSERT(" + quoted(fields) + ") VALUES(" + String.join(",",
                fields.stream().map(n -> "S." + quote(n)).toList()) + ")";
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
        int[] types = new int[fields.size()];
        try {
            for (int i = 0; i < types.length; i++) {
                types[i] = shape.columns().get(fields.get(i)).jdbcType();
            }
            for (Object[] row : rows) {
                var values = new Object[fields.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = shape.columns().get(fields.get(i)).exact(row[i]);
                }
                exact.add(values);
            }
        } catch (SQLException | RuntimeException invalid) {
            throw new BatchRejectedException("Oscar UPSERT values cannot be represented without loss");
        }
        var bounded = BatchValueConverter.convert(exact, fields.size(), batchLimits);
        JdbcBatchExecutor.execute(dataSource, context -> {
            inspect(context.connection(), true);
            JdbcBatchExecutor.executeBatch(context, upsertSql, bounded, types,
                    Math.min(120, runtimeOptions.queryTimeoutSeconds()));
            return null;
        });
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
