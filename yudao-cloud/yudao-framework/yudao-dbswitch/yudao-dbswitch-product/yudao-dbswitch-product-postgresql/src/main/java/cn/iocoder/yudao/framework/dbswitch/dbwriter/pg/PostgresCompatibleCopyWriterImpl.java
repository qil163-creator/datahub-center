package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy.PostgresCopySession;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.row.SimpleRow;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.row.SimpleRowWriter;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * PostgreSQL 协议 COPY 写入基础实现
 * <p>
 * 职责：使用 COPY ... FROM STDIN (FORMAT BINARY) 二进制协议流式导入，吞吐量远高于批量 INSERT，适合全量迁移场景。
 * <p>
 * 协作关系：继承 {@link AbstractDatabaseWriter} 复用“prepareWrite 阶段查询目标表字段 JDBC 类型（columnType）”的逻辑；
 * 写入时借助 pgwriter 包的 SimpleRowWriter/SimpleRow 组装 COPY 二进制流，字段值先经 {@link JdbcValueConversions}
 * 转换为对应的 Java 类型。
 * <p>
 * 每个产品必须在 {@link #requireCopyAdmission()} 中给出自己的 WRITE_COPY 准入，不能借用其他兼容产品的验证结论。
 */
public abstract class PostgresCompatibleCopyWriterImpl extends AbstractDatabaseWriter implements IDatabaseWriter {

    /**
     * 初始化 {@code PostgresCompatibleCopyWriterImpl}及其父类状态。
     */
    protected PostgresCompatibleCopyWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /** 只有此产品允许 WRITE_COPY，才构造 COPY 语句或借用连接，否则提前拒绝。 */
    protected abstract void requireCopyAdmission();

    /**
     * 在实际写入前检查写入能力及目标条件。
     */
    @Override
    public void preflightRuntimeWrite() {
        requireCopyAdmission();
    }

    /**
     * 在写入前检查目标表定义是否满足当前路径的要求。
     */
    @Override
    public void preflightTargetDefinition(String schema, String table, List<String> statements, List<String> fields) {
        requireCopyAdmission();
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schema, String table, List<String> fields) {
        return "SELECT " + (fields == null || fields.isEmpty() ? "*" : PostgresCompatibleIdentifiers.fields(fields))
                + " FROM " + PostgresCompatibleIdentifiers.table(schema, table) + " WHERE 1=2";
    }

    /**
     * 用 COPY 二进制协议批量写入一批数据
     * <p>
     * 产品准入不通过时在拼 COPY 与借连接之前拒绝。
     * <p>
     * 主要流程：
     * 1. 参数校验：记录为空直接返回 0；必须先调用 prepareWrite() 拿到目标表字段类型（columnType）；
     * 2. 校验传入的每个字段名都存在于目标表中，组装 COPY 的列名数组；
     * 3. 校验整批行结构及字段值，拒绝不能表示的值，通过共用事务边界取得连接开启 COPY；
     * 4. 逐行调用 startRow，由 getConsumer 返回的回调按字段类型把值写入二进制流；
     * 5. 正常结束 COPY，核对数据库实际处理行数后才提交；失败取消协议并保留未知结果，不重投
     *
     * @param fieldNames   写入的字段名列表（顺序与记录数组一致）
     * @param recordValues 一批记录，每条为与字段列表等长的对象数组
     * @return 实际写入的行数
     */
    @Override
    public long write(List<String> fieldNames, List<Object[]> recordValues) {
        requireCopyAdmission();
        return writePrepared(fieldNames, recordValues);
    }

    /** 受保护的协议执行机制；公开产品写入器必须执行自己的操作准入。 */
    protected final long writePrepared(List<String> fieldNames, List<Object[]> recordValues) {
        var batch = prepareCopy(fieldNames, recordValues);
        return batch == null ? 0 : JdbcBatchExecutor.execute(dataSource, context -> executeCopy(context, batch));
    }

    /** 单次批次预检产物；由当前 writer/调用线程独占，调用方不得并发修改记录。 */
    protected record CopyBatch(List<String> fields, List<Object[]> rows, SimpleRowWriter.Table table,
                               long expectedRows) {

    }

    /** 在借连接或开启原生协议前完成所有结构检查；空批次返回 null，不分配事务。 */
    protected final CopyBatch prepareCopy(List<String> fieldNames, List<Object[]> recordValues) {
        if (fieldNames == null || recordValues == null) {
            throw new IllegalArgumentException("COPY 批次参数不完整");
        }
        if (recordValues.isEmpty()) {
            return null;
        }
        if (fieldNames.isEmpty()) {
            throw new IllegalArgumentException("第一个参数[fieldNames]为空,无效!");
        }
        if (null == this.columnType || this.columnType.isEmpty()) {
            throw new RuntimeException("请先调用prepareWrite()函数，或者出现内部代码集成调用错误！");
        }

        // 校验字段在目标表中存在，并组装 COPY 用的列名数组
        String[] columnNames = new String[fieldNames.size()];
        for (int i = 0; i < fieldNames.size(); ++i) {
            String s = fieldNames.get(i);
            if (!this.columnType.containsKey(s)) {
                throw new RuntimeException(
                        String.format("表%s.%s 中不存在字段名为%s的字段，请检查参数传入!", schemaName, tableName, s));
            }

            columnNames[i] = s;
        }

        // 整批结构必须在获取连接、发送 COPY 前校验，避免后续坏行导致前缀被提交。
        for (Object[] row : recordValues) {
            if (row == null || row.length != fieldNames.size()) {
                throw new IllegalArgumentException("COPY 批次字段数量不一致");
            }
            for (int column = 0; column < row.length; column++) {
                validateCopyValue(fieldNames.get(column), row[column]);
            }
        }
        SimpleRowWriter.Table table = new SimpleRowWriter.Table(schemaName, tableName, columnNames);
        List<Object[]> converted = BatchValueConverter.convert(recordValues, fieldNames.size(), batchLimits,
                (value, column) -> convertCopyValue(fieldNames.get(column), value));
        return new CopyBatch(List.copyOf(fieldNames), converted, table, converted.size());
    }

    /** 只执行已有独占事务内的 COPY，不自行借连接/提交/重试，允许产品层围绕协议复核原生事务。 */
    protected final long executeCopy(JdbcBatchExecutor.WriteContext context, CopyBatch batch) throws SQLException {
        var prepared = PostgresCopySession.prepare(context.connection());
        // 构造行写入器就会发送 COPY，失败时不能假定数据库未接收。
        context.markWriteProtocolStarted();
        SimpleRowWriter pgwriter = new SimpleRowWriter(batch.table(),
                prepared.start(batch.table().getCopyCommand(true)));
        try {
            for (Object[] objects : batch.rows()) {
                pgwriter.startRow(this.getConsumer(batch.fields(), objects));
            }
            long handledRows = pgwriter.finish();
            if (handledRows != batch.expectedRows()) {
                throw new SQLException("COPY row acknowledgement mismatch");
            }
            return handledRows;
        } catch (Exception | Error failure) {
            try {
                pgwriter.abort();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            if (pgwriter.isProtocolFinished()) {
                context.markWriteProtocolFinished();
            }
        }
    }

    /** 仅编码已完整验证的批次；COPY 活跃期间不执行源端转换。 */
    private Consumer<SimpleRow> getConsumer(List<String> fieldNames, Object[] objects) {
        return row -> {
            for (int i = 0; i < objects.length; i++) {
                Object value = objects[i];
                encodeCopyValue(row, i, fieldNames.get(i), value);
            }
        };
    }

    /** 产品特定编码只有在原生准入确认后才能覆盖此实现。 */
    protected void validateCopyValue(String field, Object value) {
    }

    /** 先验证原始值并完成已有有界批次物化，再进行转换。 */
    protected Object convertCopyValue(String field, Object value) {
        return JdbcValueConversions.forJdbcType(value, columnType.get(field));
    }

    /** 编码已完整预检批次中的一个值，保留兼容产品的默认设置。 */
    protected void encodeCopyValue(SimpleRow row, int i, String field, Object value) {
        switch (columnType.get(field)) {
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR -> row.setVarChar(i, (String) value);
            case Types.CLOB, Types.NCLOB, Types.NULL, Types.OTHER -> row.setText(i, (String) value);
            case Types.TINYINT -> row.setByte(i, (Byte) value);
            case Types.SMALLINT -> row.setShort(i, (Short) value);
            case Types.INTEGER -> row.setInteger(i, (Integer) value);
            case Types.BIGINT -> row.setLong(i, (Long) value);
            case Types.NUMERIC, Types.DECIMAL -> row.setNumeric(i, (Number) value);
            case Types.FLOAT, Types.REAL -> row.setFloat(i, (Float) value);
            case Types.DOUBLE -> row.setDouble(i, (Double) value);
            case Types.BOOLEAN, Types.BIT -> row.setBoolean(i, (Boolean) value);
            case Types.TIME -> row.setTime(i, (LocalTime) value);
            case Types.DATE -> row.setDate(i, (LocalDate) value);
            case Types.TIMESTAMP -> row.setTimeStamp(i, (LocalDateTime) value);
            case Types.BINARY, Types.VARBINARY, Types.BLOB, Types.LONGVARBINARY -> row.setByteArray(i, (byte[]) value);
            default -> throw new IllegalStateException("COPY batch contains an unvalidated JDBC type");
        }
    }
}
