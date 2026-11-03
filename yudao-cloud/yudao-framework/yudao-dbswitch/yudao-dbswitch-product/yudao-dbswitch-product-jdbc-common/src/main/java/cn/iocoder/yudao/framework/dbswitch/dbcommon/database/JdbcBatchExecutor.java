package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.SqlTypeValue;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 全量/增量及 COPY 共用的单批 JDBC 执行边界。独占借出的 autoCommit=true 连接，不加入调用方事务。
 * 不重投：驱动支持事务不等于当前表/存储引擎保证回滚，批次失败不得自动逐行重写。
 * 一旦尝试发送写入，异常只报告未知外部结果；后续 rollback/close 成功不能证明先前没有提交。
 * 成功仅表示本批 JDBC 调用正常完成，不提供原生提交位置、精确变更行数或读取可见性凭证。
 */
public final class JdbcBatchExecutor {

    /**
     * 初始化 {@code JdbcBatchExecutor}。
     */
    private JdbcBatchExecutor() {
    }

    /** 回调不得提交、回滚、关闭连接或跨线程保留上下文；原生流协议须标记开始和确认退出。 */
    @FunctionalInterface
    public interface WriteAction<T> {

        /**
         * 按给定 SQL、JDBC 类型及批次选项执行参数化写入。
         */
        T execute(WriteContext context) throws Exception;
    }

    /** 提交并恢复自动提交后，在同一连接作只读复核；不得写入、改变连接状态或提前发布结果。 */
    @FunctionalInterface
    public interface CommittedCheck<T> {

        /**
         * 检查数据库连接、结果是否满足当前操作的约定。
         */
        void verify(Connection connection, T result) throws Exception;
    }

    /** 借用连接后、任何 JDBC 状态变更前，安装调用方持有的保护措施；绝不接管连接。 */
    @FunctionalInterface
    public interface BorrowedConnectionObserver {

        /**
         * 读取当前目标对象或运行状态的最新事实。
         */
        void observe(Connection connection) throws Exception;
    }

    public static final class WriteContext {

        private final Connection connection;

        private boolean writeAttempted;

        private boolean protocolActive;

        /**
         * 初始化 {@code WriteContext}。
         */
        private WriteContext(Connection connection) {
            this.connection = connection;
        }

        /**
         * 返回数据库连接。
         */
        public Connection connection() {
            return connection;
        }

        /** 必须在首次可能向数据库发送写入的调用之前标记，不能等到收到成功回执。 */
        public void markWriteAttempted() {
            writeAttempted = true;
        }

        /** 在发送 COPY 等独占连接的协议前调用；未确认退出时不允许在同会话发送 rollback。 */
        public void markWriteProtocolStarted() {
            writeAttempted = true;
            protocolActive = true;
        }

        /** 仅在驱动确认协议正常结束或已取消后调用，不是事务提交证明。 */
        public void markWriteProtocolFinished() {
            protocolActive = false;
        }
    }

    /**
     * 按给定 SQL、JDBC 类型及批次选项执行参数化写入。
     */
    public static void execute(DataSource dataSource, String sql, List<Object[]> rows, int[] types) {
        executeConfigured(dataSource, sql, rows, types, null);
    }

    /** 应用显式语句超时；无效限额在借用连接前失败。 */
    public static void execute(DataSource dataSource, String sql, List<Object[]> rows, int[] types,
            int queryTimeoutSeconds) {
        if (queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("JDBC query timeout must be positive");
        }
        executeConfigured(dataSource, sql, rows, types, queryTimeoutSeconds);
    }

    /**
     * 校验批次输入并预处理参数值，按指定 SQL 类型和超时执行 JDBC 批次；空批次直接返回。
     */
    private static void executeConfigured(DataSource dataSource, String sql, List<Object[]> rows, int[] types,
            Integer queryTimeoutSeconds) {
        if (dataSource == null || sql == null || sql.isBlank() || rows == null || types == null) {
            throw new IllegalArgumentException("JDBC 批次参数不完整");
        }
        List<Object[]> prepared = prepareBatchValues(rows, types);
        if (rows.isEmpty()) {
            return;
        }

        execute(dataSource, context -> {
            executePreparedBatch(context, sql, prepared, types, queryTimeoutSeconds);
            return null;
        });
    }

    /** 供组件原生见证路径复用绑定与回执校验，事务仍由 execute 独占管理。 */
    public static void executeBatch(WriteContext context, String sql, List<Object[]> rows, int[] types)
            throws SQLException {
        executeConfiguredBatch(context, sql, rows, types, null);
    }

    /**
     * 提交当前预编译语句中已积累的批次参数。
     */
    public static void executeBatch(WriteContext context, String sql, List<Object[]> rows, int[] types,
            int queryTimeoutSeconds) throws SQLException {
        if (queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("JDBC query timeout must be positive");
        }
        executeConfiguredBatch(context, sql, rows, types, queryTimeoutSeconds);
    }

    /**
     * 按当前 SQL 类型和写入选项执行已配置批次。
     */
    private static void executeConfiguredBatch(WriteContext context, String sql, List<Object[]> rows, int[] types,
            Integer queryTimeoutSeconds) throws SQLException {
        executePreparedBatch(context, sql, prepareBatchValues(rows, types), types, queryTimeoutSeconds);
    }

    /**
     * 移植上游 JDBC setObject 无法保留的自动类型转换情形。例如，
     * PostgreSQL 将 byte[] 写入 VARCHAR 时变成 "[B@..."，并拒绝数值 "true"/"false"。
     * 原生值、数值宽度、时间类型和自定义绑定器继续遵循现有
     * 产品契约。准备语句前校验整批，不修改行。
     */
    private static List<Object[]> prepareBatchValues(List<Object[]> rows, int[] types) {
        if (rows == null || types == null) {
            throw new BatchRejectedException("Invalid JDBC batch arguments");
        }
        for (var row : rows) {
            if (row == null || row.length != types.length) {
                throw new BatchRejectedException("JDBC batch column count mismatch");
            }
        }
        List<Object[]> prepared = new ArrayList<>(rows.size());
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Object[] row = rows.get(rowIndex).clone();
            for (int column = 0; column < types.length; column++) {
                Object value = row[column];
                try {
                    if (value instanceof byte[] && isText(types[column])) {
                        // 共享解码器拒绝格式错误的 UTF-8，不替换源字节。
                        row[column] = JdbcValueConversions.toText(value);
                    } else if (isNumeric(types[column]) && isBooleanValue(value)) {
                        row[column] = JdbcValueConversions.toDecimal(value);
                    }
                } catch (RuntimeException failure) {
                    throw new BatchRejectedException("JDBC conversion failed before write at row "
                            + (rowIndex + 1) + ", column " + (column + 1));
                }
            }
            prepared.add(row);
        }
        return prepared;
    }

    /**
     * 判断文本。
     */
    private static boolean isText(int type) {
        return switch (type) {
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR,
                 Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
    }

    /**
     * 判断数值。
     */
    private static boolean isNumeric(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE -> true;
            default -> false;
        };
    }

    /**
     * 判断布尔值。
     */
    private static boolean isBooleanValue(Object value) {
        if (value instanceof Boolean) {
            return true;
        }
        if (!(value instanceof CharSequence)) {
            return false;
        }
        String text = value.toString().trim();
        return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
    }

    /**
     * SUCCESS_NO_INFO 是合法成功但数量未知；绝不把它解释为精确变更行数。
     */
    private static void executePreparedBatch(WriteContext context, String sql, List<Object[]> rows, int[] types,
            Integer queryTimeoutSeconds) throws SQLException {
        List<java.sql.Array> arrays = new java.util.ArrayList<>();
        try (var statement = context.connection().prepareStatement(sql)) {
            if (queryTimeoutSeconds != null) {
                statement.setQueryTimeout(queryTimeoutSeconds);
            }
            for (var row : rows) {
                for (int i = 0; i < types.length; i++) {
                    if (row[i] instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue detached) {
                        if (types[i] != java.sql.Types.ARRAY) {
                            throw new SQLException("Array requires an ARRAY target");
                        }
                        var array = context.connection()
                                .createArrayOf(detached.baseTypeName(), detached.elementsArray());
                        if (array == null) {
                            throw new SQLException("Driver did not create an array");
                        }
                        arrays.add(array);
                        statement.setArray(i + 1, array);
                    } else if (row[i] instanceof SqlTypeValue custom) {
                        custom.setTypeValue(statement, i + 1, types[i], null);
                    } else {
                        statement.setObject(i + 1, row[i], types[i]);
                    }
                }
                statement.addBatch();
            }
            context.markWriteAttempted();
            int[] counts = statement.executeBatch();
            // SUCCESS_NO_INFO 是合法成功但数量未知；绝不把它解释为精确变更行数。
            if (counts == null || counts.length != rows.size()) {
                throw new SQLException("Invalid batch acknowledgement");
            }
            for (int count : counts) {
                if (count < 0 && count != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException("Failed batch entry");
                }
            }
        } finally {
            SQLException cleanupFailure = null;
            for (var array : arrays) {
                try {
                    array.free();
                } catch (Exception failure) {
                    cleanupFailure = new SQLException("JDBC array release failed");
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    /** 复用相同连接生命周期承载驱动原生写入协议；返回值只有在提交与连接清理均成功后才对外可见。 */
    public static <T> T execute(DataSource dataSource, WriteAction<T> action) {
        return execute(dataSource, action, null);
    }

    /**
     * 按给定 SQL、JDBC 类型及批次选项执行参数化写入。
     */
    public static <T> T execute(DataSource dataSource, WriteAction<T> action, CommittedCheck<T> check) {
        return execute(dataSource, action, check, null);
    }

    /**
     * 按给定 SQL、JDBC 类型及批次选项执行参数化写入。
     */
    public static <T> T execute(DataSource dataSource, WriteAction<T> action, CommittedCheck<T> check,
            BorrowedConnectionObserver observer) {
        if (dataSource == null || action == null) {
            throw new IllegalArgumentException("JDBC 批次参数不完整");
        }
        Connection connection = null;
        WriteContext context = null;
        boolean ownsTransaction = false;
        boolean completed = false;
        Throwable failure = null;
        T result = null;
        try {
            connection = dataSource.getConnection();
            if (observer != null) {
                observer.observe(connection);
            }
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("拒绝接管已有手动事务连接");
            }
            ownsTransaction = true;
            connection.setAutoCommit(false);
            context = new WriteContext(connection);
            result = action.execute(context);
            if (context.protocolActive) {
                throw new SQLException("Write protocol has not finished");
            }
            // commit 与 executeBatch 分开；commit 抛 BatchUpdateException 也不能进入重投分支。
            connection.commit();
            connection.setAutoCommit(true);
            if (check != null) {
                check.verify(connection, result);
            }
            completed = true;
        } catch (Throwable caught) {
            failure = caught;
        } finally {
            if (connection != null) {
                if (!completed && ownsTransaction) {
                    // 活动 COPY 的 rollback 会等待协议释放，可能永远阻塞在当前线程自己的操作上。
                    if (context == null || !context.protocolActive) {
                        try {
                            connection.rollback();
                        } catch (Throwable ignored) { /* 不把回滚当作未知提交的反证 */ }
                    }
                    // 不恢复 autoCommit：这会隐式提交仍存活的事务。终止并淘汰异常会话。
                    discard(dataSource, connection);
                }
                try {
                    connection.close();
                } catch (Throwable closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    }
                    discard(dataSource, connection);
                }
            }
        }
        if (failure instanceof Error fatal) {
            if (context != null && context.writeAttempted) {
                fatal.addSuppressed(new UnconfirmedWriteException());
            }
            throw fatal;
        }
        if (failure != null) {
            // JDBC 异常可含 SQL、参数及连接秘密，不把原始原因/抑制异常链交给日志和持久化调用方。
            if (context != null && context.writeAttempted) {
                throw new UnconfirmedWriteException();
            }
            throw new IllegalStateException("JDBC 批次执行前准备失败，未提交数据语句");
        }
        return result;
    }

    /**
     * 丢弃当前未发布的数据或临时输出状态。
     */
    private static void discard(DataSource dataSource, Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (Throwable ignored) { /* 不支持 abort 的驱动仍保持未知 */ }
        if (dataSource instanceof HikariDataSource pool) {
            try {
                pool.evictConnection(connection);
            } catch (Throwable ignored) { /* 不能恢复为成功 */ }
        }
    }
}
