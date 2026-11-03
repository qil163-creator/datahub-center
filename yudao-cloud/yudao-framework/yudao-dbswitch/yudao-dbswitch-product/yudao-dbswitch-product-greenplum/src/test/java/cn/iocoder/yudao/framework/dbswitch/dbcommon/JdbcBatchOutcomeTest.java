package cn.iocoder.yudao.framework.dbswitch.dbcommon;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb.GreenplumInsertWriterImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcBatchOutcomeTest {
    static final List<Object[]> ROWS = List.of(new Object[]{1}, new Object[]{2});

    static void execute(String path, DataSource dataSource, String sql) {
        if (path.equals("writer")) {
            ReflectionTestUtils.invokeMethod(new GreenplumInsertWriterImpl(dataSource), "batchWrite",
                    List.of("id"), ROWS, sql, new int[]{Types.INTEGER});
        } else {
            ReflectionTestUtils.invokeMethod(new PostgresqlDatabaseSyncImpl(dataSource), "batchOperator",
                    ROWS, sql, new int[]{Types.INTEGER});
        }
    }

    @ParameterizedTest
    @CsvSource({"writer,rollback", "synchronizer,rollback", "writer,commit", "synchronizer,commit",
            "writer,commit-batch-exception", "synchronizer,commit-batch-exception"})
    void unknownCommitOrRollbackMustNotReplayOrResetAutoCommit(String path, String fault) throws Exception {
        var dataSource = mock(DataSource.class); var connection = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps); when(ps.executeBatch()).thenReturn(new int[]{1, 1});
        if (fault.equals("rollback")) {
            when(ps.executeBatch()).thenThrow(new BatchUpdateException("private-test-detail", new int[]{1}));
            doThrow(new SQLException("private-test-detail")).when(connection).rollback();
        } else if (fault.equals("commit-batch-exception")) {
            doThrow(new BatchUpdateException("private-test-detail", new int[]{1, 1})).when(connection).commit();
        } else {
            doThrow(new SQLException("private-test-detail")).when(connection).commit();
        }
        var failure = assertThrows(UnconfirmedWriteException.class, () -> execute(path, dataSource, "INSERT INTO fixture VALUES (?)"));
        verify(ps, times(1)).executeBatch(); verify(ps, never()).executeUpdate();
        verify(connection, atMostOnce()).commit(); verify(connection, never()).setAutoCommit(true);
        verify(connection).abort(any()); verify(connection).close();
        var stack = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(stack));
        assertFalse(stack.toString().contains("private-test-detail"));
    }

    static java.util.stream.Stream<Arguments> failures() {
        return java.util.stream.Stream.of("writer", "synchronizer").flatMap(path -> java.util.stream.Stream.of(
                "batch-error", "execute-error", "execute-timeout", "statement-close", "restore", "connection-close",
                "null-counts", "short-counts", "long-counts", "failed-entry", "invalid-negative", "cleanup-failures")
                .map(fault -> Arguments.of(path, fault)));
    }

    @ParameterizedTest @MethodSource("failures")
    void anyUncertainExecutedBatchStaysUnknownAndHasNoSensitiveCause(String path, String fault) throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps); when(ps.executeBatch()).thenReturn(new int[]{1, 1});
        var detail = new SQLException("private-test-detail");
        switch (fault) {
            case "batch-error" -> when(ps.executeBatch()).thenThrow(new BatchUpdateException("private-test-detail", new int[]{1}));
            case "execute-error" -> when(ps.executeBatch()).thenThrow(detail);
            case "execute-timeout" -> when(ps.executeBatch()).thenThrow(new java.sql.SQLTimeoutException("private-test-detail"));
            case "statement-close" -> doThrow(detail).when(ps).close();
            case "restore" -> doThrow(detail).when(connection).setAutoCommit(true);
            case "connection-close" -> doThrow(detail).when(connection).close();
            case "null-counts" -> when(ps.executeBatch()).thenReturn(null);
            case "short-counts" -> when(ps.executeBatch()).thenReturn(new int[]{1});
            case "long-counts" -> when(ps.executeBatch()).thenReturn(new int[]{1, 1, 1});
            case "failed-entry" -> when(ps.executeBatch()).thenReturn(new int[]{1, java.sql.Statement.EXECUTE_FAILED});
            case "invalid-negative" -> when(ps.executeBatch()).thenReturn(new int[]{1, -1});
            case "cleanup-failures" -> {
                when(ps.executeBatch()).thenThrow(detail); doThrow(detail).when(connection).rollback();
                doThrow(detail).when(connection).abort(any()); doThrow(detail).when(connection).close();
            }
            default -> throw new AssertionError(fault);
        }
        var failure = assertThrows(UnconfirmedWriteException.class, () -> execute(path, source, "INSERT INTO fixture VALUES (?)"));
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
        verify(ps, times(1)).executeBatch(); verify(ps, never()).executeUpdate();
        verify(connection, atMostOnce()).commit(); verify(connection, atLeastOnce()).abort(any());
        if (!List.of("restore", "connection-close").contains(fault)) {
            verify(connection, never()).commit(); verify(connection, never()).setAutoCommit(true);
        }
        assertFalse(failure.toString().contains("private-test-detail"));
    }

    @ParameterizedTest @CsvSource({"writer,1", "synchronizer,1", "writer,-2", "synchronizer,-2", "synchronizer,0"})
    void ordinaryAndUnknownRowCountSuccessStillCommitExactlyOnce(String path, int count) throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps); when(ps.executeBatch()).thenReturn(new int[]{count, count});
        execute(path, source, "INSERT INTO fixture VALUES (?)");
        var order = inOrder(connection, ps);
        order.verify(connection).setAutoCommit(false); order.verify(connection).prepareStatement(anyString());
        order.verify(ps).setQueryTimeout(3600);
        order.verify(ps).setObject(1, 1, Types.INTEGER); order.verify(ps).addBatch();
        order.verify(ps).setObject(1, 2, Types.INTEGER); order.verify(ps).addBatch();
        order.verify(ps).executeBatch(); order.verify(ps).close(); order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true); order.verify(connection).close();
        verify(connection, never()).rollback(); verify(connection, never()).abort(any()); verify(ps, never()).executeUpdate();
    }

    @ParameterizedTest @ValueSource(strings = {"acquire", "get-auto-commit", "manual-transaction", "begin", "prepare", "timeout", "bind"})
    void preparationFailureDoesNotPretendToHaveSubmittedDml(String fault) throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        var detail = new SQLException("private-test-detail");
        switch (fault) {
            case "acquire" -> when(source.getConnection()).thenThrow(detail);
            case "get-auto-commit" -> when(connection.getAutoCommit()).thenThrow(detail);
            case "manual-transaction" -> when(connection.getAutoCommit()).thenReturn(false);
            case "begin" -> doThrow(detail).when(connection).setAutoCommit(false);
            case "prepare" -> when(connection.prepareStatement(anyString())).thenThrow(detail);
            case "timeout" -> doThrow(detail).when(ps).setQueryTimeout(anyInt());
            case "bind" -> doThrow(detail).when(ps).setObject(anyInt(), any(), anyInt());
            default -> throw new AssertionError(fault);
        }
        var failure = assertThrows(IllegalStateException.class, () -> execute("writer", source, "INSERT INTO fixture VALUES (?)"));
        assertNull(failure.getCause()); verify(ps, never()).executeBatch(); verify(connection, never()).commit();
        verify(connection, never()).setAutoCommit(true);
        if (fault.equals("manual-transaction")) { verify(connection, never()).rollback(); verify(connection, never()).setAutoCommit(false); }
    }

    @org.junit.jupiter.api.Test void malformedInputAndEmptyBatchDoNotAcquireConnections() {
        var source = mock(DataSource.class);
        cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(source, "INSERT INTO fixture VALUES (?)", List.of(), new int[]{Types.INTEGER});
        assertThrows(IllegalArgumentException.class, () -> cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(
                source, "INSERT INTO fixture VALUES (?)", List.<Object[]>of(new Object[]{1, 2}), new int[]{Types.INTEGER}));
        verifyNoInteractions(source);
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void explicitTimeoutMustBePositiveBeforeBorrowOrContextUse(int timeout) {
        var source = mock(DataSource.class);
        assertThrows(IllegalArgumentException.class, () -> JdbcBatchExecutor.execute(source,
                "INSERT INTO fixture VALUES (?)", ROWS, new int[]{Types.INTEGER}, timeout));
        assertThrows(IllegalArgumentException.class, () -> JdbcBatchExecutor.executeBatch(null,
                "INSERT INTO fixture VALUES (?)", ROWS, new int[]{Types.INTEGER}, timeout));
        verifyNoInteractions(source);
    }

    @ParameterizedTest @ValueSource(strings = {"execute", "executeBatch"})
    void explicitTimeoutIsAppliedBeforeBindingAndExecute(String entry) throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps); when(ps.executeBatch()).thenReturn(new int[]{1, 1});
        if (entry.equals("execute")) {
            JdbcBatchExecutor.execute(source, "INSERT INTO fixture VALUES (?)", ROWS, new int[]{Types.INTEGER}, 23);
        } else {
            JdbcBatchExecutor.execute(source, context -> {
                JdbcBatchExecutor.executeBatch(context, "INSERT INTO fixture VALUES (?)", ROWS, new int[]{Types.INTEGER}, 23);
                return null;
            });
        }
        var order = inOrder(connection, ps);
        order.verify(connection).prepareStatement(anyString());
        order.verify(ps).setQueryTimeout(23);
        order.verify(ps).setObject(1, 1, Types.INTEGER); order.verify(ps).addBatch();
        order.verify(ps).setObject(1, 2, Types.INTEGER); order.verify(ps).addBatch();
        order.verify(ps).executeBatch(); order.verify(ps).close(); order.verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @org.junit.jupiter.api.Test void customBindingIsPreserved() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps); when(ps.executeBatch()).thenReturn(new int[]{1});
        var custom = mock(org.springframework.jdbc.core.SqlTypeValue.class);
        cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(source, "INSERT INTO fixture VALUES (?)",
                List.<Object[]>of(new Object[]{custom}), new int[]{Types.OTHER});
        verify(custom).setTypeValue(ps, 1, Types.OTHER, null); verify(ps, never()).setObject(anyInt(), any(), anyInt());
        verify(ps, never()).setQueryTimeout(anyInt());
    }

    @org.junit.jupiter.api.Test void unsupportedAbortStillEvictsOwnedHikariConnection() throws Exception {
        var source = mock(com.zaxxer.hikari.HikariDataSource.class); var connection = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps); when(ps.executeBatch()).thenThrow(new SQLException("private-test-detail"));
        doThrow(new java.sql.SQLFeatureNotSupportedException()).when(connection).abort(any());
        assertThrows(UnconfirmedWriteException.class, () -> execute("writer", source, "INSERT INTO fixture VALUES (?)"));
        verify(source).evictConnection(connection); verify(connection).close(); verify(connection, never()).setAutoCommit(true);
    }

    @org.junit.jupiter.api.Test void fatalErrorStillCarriesUnknownOutcomeForMigrationService() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(ps); var fatal = new AssertionError("synthetic fatal");
        when(ps.executeBatch()).thenThrow(fatal);
        assertSame(fatal, assertThrows(AssertionError.class, () -> execute("writer", source, "INSERT INTO fixture VALUES (?)")));
        assertInstanceOf(UnconfirmedWriteException.class, fatal.getSuppressed()[0]); verify(connection).close();
    }
}
