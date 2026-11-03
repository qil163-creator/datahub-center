package cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCompatibleCopyWriterImpl;

import javax.sql.DataSource;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Greenplum数据库Copy写入实现类
 * <p>
 * 实际 GP 7.1.0 驱动、会话与 heap/hash 表布局通过原生检查后进入共享二进制 COPY 协议。
 *
 * @author jrl
 */
public class GreenplumCopyWriterImpl extends PostgresCompatibleCopyWriterImpl {

    private GreenplumCopyAdmission admission;

    /**
     * 初始化 {@code GreenplumCopyWriterImpl}及其父类状态。
     */
    public GreenplumCopyWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志与批量插入 SQL 的方言判断）
     */
    @Override
    protected String getDatabaseProductName() {
        return "Greenplum";
    }

    /** 每个 writer 必须先建立自身运行时证据；清单声明不授予协议执行权限。 */
    @Override
    protected void requireCopyAdmission() {
        if (admission == null) {
            unsupported("Greenplum WRITE_COPY runtime profile, session or column layout is not verified");
        }
    }

    /**
     * 在实际写入前检查写入能力及目标条件。
     */
    @Override
    public void preflightRuntimeWrite() {
        try (var connection = dataSource.getConnection()) {
            GreenplumCopyAdmission.verifyRuntime(connection);
        } catch (java.sql.SQLException invalid) {
            unsupported("Greenplum WRITE_COPY runtime profile is not verified");
        }
    }

    /**
     * 在原生目标操作前校验目标对象及能力。
     */
    @Override
    public void preflightNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition source,
            java.util.Map<String, String> mapping) {
        try {
            GreenplumCopyAdmission.preflightNativeTarget(source, mapping);
        } catch (java.sql.SQLException invalid) {
            unsupported("Greenplum WRITE_COPY native source layout is not supported");
        }
    }

    /**
     * 在写入前检查目标表定义是否满足当前路径的要求。
     */
    @Override
    public void preflightTargetDefinition(String schema, String table,
            java.util.List<String> statements, java.util.List<String> fields) {
        try {
            cn.iocoder.yudao.framework.dbswitch.core.service.PostgresCopyTargetDefinition
                    .validateGreenplum(schema, table, statements, fields);
        } catch (IllegalArgumentException invalid) {
            unsupported("Greenplum WRITE_COPY planned target layout is not supported");
        }
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, java.util.List<String> fields) {
        admission = null;
        GreenplumCopyAdmission candidate;
        try (var connection = dataSource.getConnection()) {
            GreenplumCopyAdmission.verifyRuntime(connection);
            candidate = GreenplumCopyAdmission.capture(connection, schema, table, fields);
        } catch (java.sql.SQLException invalid) {
            unsupported("Greenplum WRITE_COPY runtime profile, session or column layout is not verified");
            return;
        }
        super.prepareWrite(schema, table, fields);
        try {
            candidate.requireJdbcLayout(columnType);
        } catch (java.sql.SQLException invalid) {
            unsupported("Greenplum WRITE_COPY JDBC/native column layout is not verified");
        }
        admission = candidate;
    }

    /**
     * 截断已准备目标端。
     */
    @Override
    public boolean truncatePreparedTarget(String schema, String table) {
        requireCopyAdmission();
        GreenplumCopyAdmission before = admission;
        // 替换失败或结果未确认时，不能保留供后续写入使用的准入信息。
        admission = null;
        try {
            before.requireRelation(schema, table);
        } catch (java.sql.SQLException invalid) {
            unsupported("Greenplum WRITE_COPY prepared target differs from truncate target");
        }
        admission = cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(dataSource,
                context -> {
                    String relation = cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables.table(
                            schema, table);
                    try (var statement = context.connection().createStatement()) {
                        statement.setQueryTimeout(5);
                        statement.execute("LOCK TABLE " + relation + " IN ACCESS EXCLUSIVE MODE");
                        before.verifyBatch(context.connection(), before.fields());
                        context.markWriteAttempted();
                        statement.execute("TRUNCATE TABLE " + relation);
                    }
                    var after = GreenplumCopyAdmission.capture(context.connection(), schema, table, before.fields());
                    after.requireJdbcLayout(columnType);
                    return after;
                });
        return true;
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(java.util.List<String> fields, java.util.List<Object[]> rows) {
        return writeWithReceipt(fields, rows).acknowledgedRows();
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult writeWithReceipt(
            java.util.List<String> fields, java.util.List<Object[]> rows) {
        requireCopyAdmission();
        var batch = prepareCopy(fields, rows);
        if (batch == null) {
            return new cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult(0, null);
        }
        long written = cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(dataSource,
                context -> {
                    admission.verifyBatch(context.connection(), batch.fields());
                    return executeCopy(context, batch);
                });
        return new cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult(written, null,
                cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /**
     * 先验证原始值并完成已有有界批次物化，再进行转换。
     */
    @Override
    protected Object convertCopyValue(String field, Object value) {
        return admission.convert(field, value);
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String reason) throws E {
        throw (E) new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
