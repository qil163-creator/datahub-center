package cn.iocoder.yudao.framework.dbswitch.pgwriter.row;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions.BinaryWriteFailedException;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.PgBinaryWriter;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.ValueHandlerProvider;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.StringUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy.CopySession;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy.CopySessionOutputStream;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy.PostgresCopySession;
import org.postgresql.PGConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 简单行写入器,基于 COPY 二进制协议按列名逐行写入数据,无需实体映射,是迁移数据批量写入 PostgreSQL 的入口。
 */
public class SimpleRowWriter implements AutoCloseable {

    public static class Table {

        private final String schema;

        private final String table;

        private final String[] columns;

        /**
         * 使用给定参数初始化 {@code Table}。
         */
        public Table(String table, String... columns) {
            this(null, table, columns);
        }

        /**
         * 初始化 {@code Table}。
         */
        public Table(String schema, String table, String... columns) {
            this.schema = schema;
            this.table = table;
            this.columns = columns;
        }

        /**
         * 返回模式。
         */
        public String getSchema() {
            return schema;
        }

        /**
         * 返回表。
         */
        public String getTable() {
            return table;
        }

        /**
         * 返回列集合。
         */
        public String[] getColumns() {
            return columns;
        }

        /**
         * 返回包含模式名的完整限定表名。
         */
        public String getFullyQualifiedTableName(boolean usePostgresQuoting) {
            return PostgreSqlUtils.getFullyQualifiedTableName(schema, table, usePostgresQuoting);
        }

        /**
         * 返回COPY命令。
         */
        public String getCopyCommand(boolean usePostgresQuoting) {

            String commaSeparatedColumns = Arrays.stream(columns)
                    .map(x -> usePostgresQuoting ? PostgreSqlUtils.quoteIdentifier(x) : x)
                    .collect(Collectors.joining(", "));

            return String.format("COPY %1$s(%2$s) FROM STDIN BINARY",
                    getFullyQualifiedTableName(usePostgresQuoting),
                    commaSeparatedColumns);
        }
    }

    private final Table table;

    private final PgBinaryWriter writer;

    private final CopySession copy;

    private final ValueHandlerProvider provider;

    private final Map<String, Integer> lookup;

    private Function<String, String> nullCharacterHandler;

    private boolean isOpened;

    private boolean isClosed;

    private boolean finished;

    private boolean protocolFinished;

    private long handledRowCount = -1;

    /**
     * 使用给定参数初始化 {@code SimpleRowWriter}。
     */
    public SimpleRowWriter(final Table table, final PGConnection connection) throws SQLException {
        this(table, connection, false);
    }

    /**
     * 使用给定参数初始化 {@code SimpleRowWriter}。
     */
    public SimpleRowWriter(final Table table, final PGConnection connection,
            final boolean usePostgresQuoting) throws SQLException {
        this(table, PostgresCopySession.openNative(connection, table.getCopyCommand(usePostgresQuoting)));
    }

    /**
     * 使用给定参数初始化 {@code SimpleRowWriter}。
     */
    public SimpleRowWriter(final Table table, final Connection connection,
            final boolean usePostgresQuoting) throws SQLException {
        this(table, PostgresCopySession.open(connection, table.getCopyCommand(usePostgresQuoting)));
    }

    /**
     * 校验构造参数并初始化 {@code SimpleRowWriter}。
     */
    public SimpleRowWriter(final Table table, final CopySession copy) {
        this.table = table;
        this.isClosed = false;
        this.isOpened = false;
        this.nullCharacterHandler = (val) -> val;

        this.provider = new ValueHandlerProvider();
        this.lookup = new HashMap<>();

        for (int ordinal = 0; ordinal < table.columns.length; ordinal++) {
            lookup.put(table.columns[ordinal], ordinal);
        }

        this.copy = java.util.Objects.requireNonNull(copy);
        try {
            this.writer = new PgBinaryWriter(new CopySessionOutputStream(copy));
        } catch (RuntimeException | Error failure) {
            try {
                cancelActiveCopy();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }

        isClosed = false;
        isOpened = true;
    }

    /**
     * 启动数据行。
     */
    public synchronized void startRow(Consumer<SimpleRow> consumer) {

        // 尝试写入一行，但通向 PostgreSQL 的底层流尚未
        // 打开，应停止执行并抛出异常：
        if (!isOpened) {
            throw new BinaryWriteFailedException("The SimpleRowWriter has not been opened");
        }

        // 尝试写入一行，但通向 PostgreSQL 的底层流已经
        // 关闭，应停止执行并抛出异常：
        if (isClosed) {
            throw new BinaryWriteFailedException("The PGCopyOutputStream has already been closed");
        }

        try {

            writer.startRow(table.columns.length);

            SimpleRow row = new SimpleRow(provider, lookup, nullCharacterHandler);

            consumer.accept(row);

            row.writeRow(writer);

        } catch (RuntimeException | Error failure) {
            // close/endCopy 是成功结束协议，不能用于失败行的清理。
            try {
                abort();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * 关闭当前写入器并释放其持有的写入资源。
     */
    @Override
    public synchronized void close() {
        if (isClosed) {
            return;
        }

        // 此流不应复用，因此在这里记录标志：
        isOpened = false;
        isClosed = true;

        try {
            writer.close();
            protocolFinished = !copy.isActive();
            if (!protocolFinished) {
                throw new BinaryWriteFailedException("COPY protocol is still active");
            }
            // 保留原始协议操作读取数据库回执，完成 COPY 不代表外层事务已提交。
            handledRowCount = copy.getHandledRowCount();
            if (handledRowCount < 0) {
                throw new BinaryWriteFailedException("COPY row acknowledgement unavailable");
            }
            finished = true;
        } catch (SQLException failure) {
            try {
                cancelActiveCopy();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw new BinaryWriteFailedException(failure);
        } catch (RuntimeException | Error failure) {
            try {
                cancelActiveCopy();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** 正常结束 COPY 并读取实际处理行数，不代表外层 JDBC 事务已经提交。 */
    public synchronized long finish() {
        close();
        if (!finished) {
            throw new BinaryWriteFailedException("COPY did not finish successfully");
        }
        return handledRowCount;
    }

    /** 失败路径只取消协议；之后 close/abort 均不会再发送成功结束标记。 */
    public synchronized void abort() {
        if (isClosed) {
            return;
        }
        isOpened = false;
        isClosed = true;
        cancelActiveCopy();
    }

    /** 用于外层连接清理，未确认退出的协议只能终止连接，不能再发送同会话 SQL。 */
    public synchronized boolean isProtocolFinished() {
        return protocolFinished;
    }

    /**
     * 取消活跃COPY。
     */
    private void cancelActiveCopy() {
        try {
            if (copy.isActive()) {
                copy.cancelCopy();
            }
            protocolFinished = !copy.isActive();
        } catch (SQLException failure) {
            throw new BinaryWriteFailedException(failure);
        }
    }

    /**
     * 启用写入前移除文本零字符的处理器。
     */
    public void enableNullCharacterHandler() {
        this.nullCharacterHandler = (val) -> StringUtils.removeNullCharacter(val);
    }

    /**
     * 设置空字符处理器。
     */
    public void setNullCharacterHandler(Function<String, String> nullCharacterHandler) {
        this.nullCharacterHandler = nullCharacterHandler;
    }
}
