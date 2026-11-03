package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCompatibleCopyWriterImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Protocol mechanics only; product admission is tested separately against the native coordinator. */
class CopyOutcomeTest {
    static final List<Object[]> ROWS = List.of(new Object[]{1}, new Object[]{2});
    HikariDataSource source;
    Connection connection;
    CopyManager manager;
    CopyIn copy;
    PostgresCompatibleCopyWriterImpl writer;

    @BeforeEach void setup() throws Exception {
        source = mock(HikariDataSource.class); connection = mock(Connection.class);
        var pg = mock(PGConnection.class); manager = mock(CopyManager.class); copy = mock(CopyIn.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.isWrapperFor(PGConnection.class)).thenReturn(true);
        when(connection.unwrap(PGConnection.class)).thenReturn(pg); when(pg.getCopyAPI()).thenReturn(manager);
        when(manager.copyIn(anyString())).thenReturn(copy);
        var active = new AtomicBoolean(true);
        when(copy.isActive()).thenAnswer(invocation -> active.get());
        when(copy.endCopy()).thenAnswer(invocation -> { active.set(false); return 2L; });
        when(copy.getHandledRowCount()).thenReturn(2L);
        doAnswer(invocation -> { active.set(false); return null; }).when(copy).cancelCopy();
        writer = new PostgresCompatibleCopyWriterImpl(source) {
            @Override protected void requireCopyAdmission() { /* This unit fixture owns the synthetic protocol. */ }
            @Override protected String getDatabaseProductName() { return "Greenplum"; }
        };
        ReflectionTestUtils.setField(writer, "columnType", Map.of("id", Types.INTEGER));
        ReflectionTestUtils.setField(writer, "schemaName", "fixture");
        ReflectionTestUtils.setField(writer, "tableName", "rows");
    }

    @Test void commitsOnlyAfterCopyAndItsExactAcknowledgement() throws Exception {
        assertEquals(2, writer.write(List.of("id"), ROWS));
        var order = inOrder(connection, manager, copy);
        order.verify(connection).setAutoCommit(false); order.verify(manager).copyIn(anyString());
        order.verify(copy).endCopy(); order.verify(copy, atLeastOnce()).getHandledRowCount();
        order.verify(connection).commit(); order.verify(connection).setAutoCommit(true); order.verify(connection).close();
        verify(connection, never()).rollback(); verify(source, never()).evictConnection(any());
    }

    @ParameterizedTest @ValueSource(strings = {"start", "write", "end", "short", "long", "unknown-count",
            "commit", "commit-batch", "restore", "close", "cancel-failed"})
    void uncertainCopyNeverClaimsSuccessOrFallsBack(String fault) throws Exception {
        var privateDetail = new SQLException("private-copy-detail");
        switch (fault) {
            case "start" -> when(manager.copyIn(anyString())).thenThrow(privateDetail);
            case "write" -> doThrow(privateDetail).when(copy).writeToCopy(any(byte[].class), anyInt(), anyInt());
            case "end" -> doThrow(privateDetail).when(copy).endCopy();
            case "short" -> when(copy.getHandledRowCount()).thenReturn(1L);
            case "long" -> when(copy.getHandledRowCount()).thenReturn(3L);
            case "unknown-count" -> when(copy.getHandledRowCount()).thenReturn(-1L);
            case "commit" -> doThrow(privateDetail).when(connection).commit();
            case "commit-batch" -> doThrow(new java.sql.BatchUpdateException("private-copy-detail", new int[]{1, 1}))
                    .when(connection).commit();
            case "restore" -> doThrow(privateDetail).when(connection).setAutoCommit(true);
            case "close" -> doThrow(privateDetail).when(connection).close();
            case "cancel-failed" -> {
                doThrow(privateDetail).when(copy).endCopy(); doThrow(privateDetail).when(copy).cancelCopy();
                doThrow(privateDetail).when(connection).rollback();
                doThrow(new java.sql.SQLFeatureNotSupportedException()).when(connection).abort(any());
            }
            default -> throw new AssertionError(fault);
        }
        var failure = assertThrows(UnconfirmedWriteException.class, () -> writer.write(List.of("id"), ROWS));
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
        verify(manager, times(1)).copyIn(anyString()); verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).createStatement(); verify(connection, atMostOnce()).commit();
        verify(connection, atLeastOnce()).abort(any()); verify(source, atLeastOnce()).evictConnection(connection);
        verify(connection).close();
        // cancel 失败仍可能占有 COPY 协议锁；此时再发 rollback 可能等待自己释放协议而挂起。
        if (fault.equals("cancel-failed")) verify(connection, never()).rollback();
        if (!List.of("restore", "close").contains(fault)) verify(connection, never()).setAutoCommit(true);
        if (!List.of("commit", "commit-batch", "restore", "close").contains(fault)) verify(connection, never()).commit();
    }

    @Test void invalidRowsAndEmptyBatchNeverStartCopy() {
        assertEquals(0, writer.write(List.of("id"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> writer.write(List.of("id"), List.of(new Object[]{1}, new Object[]{2, 3})));
        assertThrows(IllegalArgumentException.class, () -> writer.write(List.of("id"), Arrays.asList(new Object[]{1}, null)));
        assertThrows(IllegalArgumentException.class, () -> writer.write(List.of("id"), null));
        verifyNoInteractions(source, connection, manager, copy);
    }

    @Test void callerOwnedTransactionIsNotAdopted() throws Exception {
        when(connection.getAutoCommit()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> writer.write(List.of("id"), ROWS));
        verifyNoInteractions(manager, copy); verify(connection, never()).setAutoCommit(false);
        verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test void conversionFailureNeverBorrowsConnectionOrStartsCopy() {
        assertThrows(IllegalArgumentException.class, () -> writer.write(List.of("id"),
                List.of(new Object[]{1}, new Object[]{"not-an-integer"})));
        verifyNoInteractions(source, connection, manager, copy);
    }
}
