package cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCompatibleInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.util.ObjectCastUtils;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * UXDB COPY 高速写入。每个操作验证原厂驱动、会话和原生列布局；清单声明不授予执行权限。
 * 已验证后复用单批事务边界，发送后的失败保留未知结果，绝不回退重投 INSERT。
 * 不具备 COPY API 的驱动在发送前拒绝；需要 INSERT 时由调用方在开始运行前显式选择。
 */
public class UxdbCopyWriterImpl extends PostgresCompatibleInsertWriterImpl implements IDatabaseWriter {

    private UxdbCopyAdmission admission;

    private static final String COPY_NULL = "\\N";

    private static final DateTimeFormatter COPY_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final DateTimeFormatter COPY_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_TIME;

    private static final DateTimeFormatter COPY_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * 初始化 {@code UxdbCopyWriterImpl}及其父类状态。
     */
    public UxdbCopyWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "UXDB";
    }

    /**
     * 在实际写入前检查写入能力及目标条件。
     */
    @Override
    public void preflightRuntimeWrite() {
        try (var connection = dataSource.getConnection()) {
            UxdbCopyAdmission.runtime(connection);
        } catch (java.sql.SQLException invalid) {
            unsupported("UXDB WRITE_COPY runtime profile is not verified");
        }
    }

    /**
     * 在写入前检查目标表定义是否满足当前路径的要求。
     */
    @Override
    public void preflightTargetDefinition(String schema, String table, List<String> statements, List<String> fields) {
        try {
            UxdbCopyTargetDefinition.validate(schema, table, statements, fields);
        } catch (IllegalArgumentException invalid) {
            unsupported("UXDB WRITE_COPY planned target layout is not verified");
        }
    }

    /**
     * 批量写入预处理。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        admission = null;
        UxdbCopyAdmission candidate;
        try (var connection = dataSource.getConnection()) {
            UxdbCopyAdmission.runtime(connection);
            candidate = UxdbCopyAdmission.capture(connection, schema, table, fields);
        } catch (java.sql.SQLException invalid) {
            unsupported("UXDB WRITE_COPY runtime profile, session or column layout is not verified");
            return;
        }
        super.prepareWrite(schema, table, fields);
        try {
            candidate.requireJdbcLayout(columnType);
        } catch (java.sql.SQLException invalid) {
            unsupported("UXDB WRITE_COPY JDBC/native layout is not verified");
        }
        admission = candidate;
    }

    /**
     * 校验并取得准入。
     */
    private void requireAdmission() {
        if (admission == null) {
            unsupported("UXDB WRITE_COPY runtime profile, session or column layout is not verified");
        }
    }

    /**
     * 截断已准备目标端。
     */
    @Override
    public boolean truncatePreparedTarget(String schema, String table) {
        requireAdmission();
        var before = admission;
        admission = null;
        try {
            before.requireRelation(schema, table);
        } catch (java.sql.SQLException invalid) {
            unsupported("UXDB WRITE_COPY prepared target differs from truncate target");
        }
        admission = JdbcBatchExecutor.execute(dataSource, context -> {
            String relation = cn.iocoder.yudao.framework.dbswitch.core.util.UxdbCursorMetadata.reference(schema, table);
            try (var statement = context.connection().createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("LOCK TABLE " + relation + " IN ACCESS EXCLUSIVE MODE");
                before.verifyBatch(context.connection(), before.fields());
                context.markWriteAttempted();
                statement.execute("TRUNCATE TABLE " + relation);
            }
            var after = UxdbCopyAdmission.capture(context.connection(), schema, table, before.fields());
            after.requireJdbcLayout(columnType);
            return after;
        });
        return true;
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
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult writeWithReceipt(List<String> fields,
            List<Object[]> rows) {
        requireAdmission();
        if (fields == null || rows == null || !new java.util.HashSet<>(fields).equals(
                new java.util.HashSet<>(admission.fields()))
                || fields.size() != admission.fields().size()) {
            throw new IllegalArgumentException("UXDB COPY requires all admitted columns exactly once");
        }
        if (rows.isEmpty()) {
            return new cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult(0, null);
        }
        long written;
        try {
            written = doCopyWrite(fields, rows);
        } catch (cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException unknown) {
            admission = null;
            throw unknown;
        }
        return new cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult(written, null,
                cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String reason) throws E {
        throw (E) new SQLFeatureNotSupportedException(reason, "0A000");
    }

    /**
     * 按 COPY 协议写入当前数据行或批次。
     */
    private long doCopyWrite(List<String> fields, List<Object[]> rows) {
        String sql = getCopyInSql(fields);
        byte[] payload;
        try {
            payload = buildCopyPayload(fields, rows).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException invalid) {
            throw new cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException(
                    "UXDB COPY batch cannot be represented exactly before sending");
        }
        if (payload.length > batchLimits.maxBatchBytes()) {
            throw new cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException(
                    "UXDB COPY encoded batch exceeds its byte budget");
        }
        return JdbcBatchExecutor.execute(dataSource, context -> {
            admission.verifyBatch(context.connection(), fields);
            try (var statement = context.connection().createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("SET LOCAL DateStyle='ISO, YMD'");
            }
            Object manager = UxdbCopyAdmission.nativeApi(context.connection());
            return UxdbCopyProtocol.execute(context, manager, sql, payload, rows.size());
        });
    }

    /**
     * 返回COPY输入SQL。
     */
    private String getCopyInSql(List<String> fieldNames) {
        for (String name : new String[]{schemaName, tableName}) {
            if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("COPY 目标标识符不合法");
            }
        }
        for (String name : fieldNames) {
            if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("COPY 字段标识符不合法");
            }
        }
        var dialect = new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseUxdbImpl();
        String fullTableName = dialect.quoteIdentifier(schemaName) + "." + dialect.quoteIdentifier(tableName);
        String columns = fieldNames.stream()
                .map(dialect::quoteIdentifier)
                .collect(Collectors.joining(","));
        return String.format("COPY %s (%s) FROM STDIN", fullTableName, columns);
    }

    /**
     * 构建COPY载荷。
     */
    private String buildCopyPayload(List<String> fieldNames, List<Object[]> recordValues) {
        recordValues = BatchValueConverter.convert(recordValues, fieldNames.size(), batchLimits,
                (value, column) -> admission.convert(fieldNames.get(column), value));
        StringBuilder builder = new StringBuilder(recordValues.size() * Math.max(64, fieldNames.size() * 16));
        for (Object[] values : recordValues) {
            if (fieldNames.size() != values.length) {
                throw new RuntimeException(String.format(
                        "Field count %d does not match value count %d", fieldNames.size(), values.length));
            }
            appendRecord(builder, fieldNames, values);
        }
        return builder.toString();
    }

    /**
     * 追加记录。
     */
    private void appendRecord(StringBuilder builder, List<String> fieldNames, Object[] values) {
        for (int i = 0; i < values.length; ++i) {
            String fieldName = fieldNames.get(i);
            Integer fieldType = this.columnType.get(fieldName);
            if (null == fieldType) {
                throw new RuntimeException(String.format(
                        "Column %s does not exist on %s.%s", fieldName, schemaName, tableName));
            }
            if (i > 0) {
                builder.append('\t');
            }
            builder.append(formatCopyValue(fieldName, fieldType, values[i]));
        }
        builder.append('\n');
    }

    /**
     * 格式化COPY值。
     */
    private String formatCopyValue(String fieldName, int fieldType, Object fieldValue) {
        if (fieldValue == null) {
            return COPY_NULL;
        }
        switch (fieldType) {
            case Types.CHAR:
            case Types.NCHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
            case Types.NULL:
            case Types.OTHER:
                return escapeCopyText(ObjectCastUtils.castToString(fieldValue));
            case Types.TINYINT:
                return String.valueOf(ObjectCastUtils.castToByte(fieldValue));
            case Types.SMALLINT:
                return String.valueOf(ObjectCastUtils.castToShort(fieldValue));
            case Types.INTEGER:
                return String.valueOf(ObjectCastUtils.castToInteger(fieldValue));
            case Types.BIGINT:
                return String.valueOf(ObjectCastUtils.castToLong(fieldValue));
            case Types.NUMERIC:
            case Types.DECIMAL:
                return String.valueOf(ObjectCastUtils.castToNumeric(fieldValue));
            case Types.FLOAT:
            case Types.REAL:
                return String.valueOf(ObjectCastUtils.castToFloat(fieldValue));
            case Types.DOUBLE:
                return String.valueOf(ObjectCastUtils.castToDouble(fieldValue));
            case Types.BOOLEAN:
                return ObjectCastUtils.castToBoolean(fieldValue) ? "t" : "f";
            case Types.BIT:
                return ObjectCastUtils.castToBoolean(fieldValue) ? "1" : "0";
            case Types.TIME:
                return COPY_TIME_FORMATTER.format(ObjectCastUtils.castToLocalTime(fieldValue));
            case Types.DATE:
                return COPY_DATE_FORMATTER.format(ObjectCastUtils.castToLocalDate(fieldValue));
            case Types.TIMESTAMP:
                return COPY_TIMESTAMP_FORMATTER.format(ObjectCastUtils.castToLocalDateTime(fieldValue));
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.BLOB:
            case Types.LONGVARBINARY:
                return "\\\\x" + toHex(ObjectCastUtils.castToByteArray(fieldValue));
            default:
                throw new RuntimeException(String.format(
                        "Unsupported column type for %s.%s.%s", schemaName, tableName, fieldName));
        }
    }

    /**
     * 转义COPY文本。
     */
    private String escapeCopyText(String value) {
        if (null == value) {
            throw new IllegalArgumentException("Non-null COPY text converted to null");
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                default:
                    builder.append(c);
                    break;
            }
        }
        return builder.toString();
    }

    /**
     * 转换为十六进制。
     */
    private String toHex(byte[] bytes) {
        if (null == bytes) {
            throw new IllegalArgumentException("Non-null COPY bytes converted to null");
        }
        if (bytes.length == 0) {
            return StringUtils.EMPTY;
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value = b & 0xFF;
            if (value < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(value));
        }
        return builder.toString();
    }
}
