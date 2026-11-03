package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveJdbcWriteSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Protocol/budget fault injection, not a real Hive certification. */
class HiveNativeExecutionTest {
    private static final List<String> FIELDS = List.of("id", "amount");
    private static final BatchLimits LIMITS = new BatchLimits(10, 4096, 1024, 4, 1000);
    private static Map<String, ColumnDescription> columns() {
        var id = new ColumnDescription(); id.setFieldName("id"); id.setFieldType(Types.BIGINT);
        var amount = new ColumnDescription(); amount.setFieldName("amount"); amount.setFieldType(Types.DECIMAL);
        amount.setPrecisionSize(20); amount.setScaleSize(3);
        return Map.of("id", id, "amount", amount);
    }
    private static List<Object[]> input() {
        return List.of(new Object[]{1L, new BigDecimal("1.001")}, new Object[]{2L, null}, new Object[]{3L, new BigDecimal("3.003")});
    }
    @Test void textIsBoundAsHexBeforeHiveParsesQuotesBackslashesAndUnicode() throws Exception {
        var column = new ColumnDescription(); column.setFieldType(Types.VARCHAR);
        var statement = mock(PreparedStatement.class);
        String value = "中文'\\\n😀";
        assertEquals("decode(unhex(?),'UTF-8')", HiveJdbcWriteSupport.placeholder(column));
        HiveJdbcWriteSupport.bind(statement, 1, value, column);
        verify(statement).setString(1, java.util.HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> HiveJdbcWriteSupport.convert(
                List.of(new Object[]{"valid"}, new Object[]{"\uD800"}), List.of("note"), Map.of("note", column), LIMITS));
    }
    @Test void multiValuesBudgetSplitsRowsTimesColumnsAndUsesActualCountsAndTimeout() throws Exception {
        var ds = mock(DataSource.class); var connection = mock(Connection.class);
        var first = mock(PreparedStatement.class); var second = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.prepareStatement(anyString())).thenReturn(first, second);
        when(first.executeUpdate()).thenReturn(2); when(second.executeUpdate()).thenReturn(1);
        var records = HiveJdbcWriteSupport.convert(input(), FIELDS, columns(), LIMITS);
        long result = NonTransactionalWriteExecution.execute(ds, (c, attempt) -> HiveJdbcWriteSupport.insert(
                c, attempt, "fixture", "rows", FIELDS, columns(), records, LIMITS, 19));
        assertEquals(3, result);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class); verify(connection, times(2)).prepareStatement(sql.capture());
        assertEquals(4, sql.getAllValues().get(0).chars().filter(ch -> ch == '?').count());
        assertEquals(2, sql.getAllValues().get(1).chars().filter(ch -> ch == '?').count());
        assertTrue(sql.getValue().contains("CAST(? AS DECIMAL(20,3))"));
        verify(first).setString(2, "1.001"); verify(second).setString(2, "3.003");
        verify(first).setQueryTimeout(19); verify(second).setQueryTimeout(19);
        verify(connection, never()).setAutoCommit(anyBoolean()); verify(connection, never()).commit(); verify(connection, never()).rollback();
        assertArrayEquals(new Object[]{1L, new BigDecimal("1.001")}, input().getFirst());
    }
    @Test void overScaleLaterValueRejectsWholeInputBeforeJdbc() {
        assertThrows(IllegalArgumentException.class, () -> HiveJdbcWriteSupport.convert(
                List.of(new Object[]{1L, new BigDecimal("1.001")}, new Object[]{2L, new BigDecimal("2.0001")}), FIELDS, columns(), LIMITS));
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 1, 3})
    void serverCannotConfirmChunkByReturningDifferentCount(int count) throws Exception {
        var ds = mock(DataSource.class); var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(count);
        assertThrows(UnconfirmedWriteException.class, () -> NonTransactionalWriteExecution.execute(ds, (c, attempt) ->
                HiveJdbcWriteSupport.insert(c, attempt, "fixture", "rows", FIELDS, columns(), input(), LIMITS, 19)));
        verify(statement, times(1)).executeUpdate();
    }
    @Test void firstChunkAcknowledgedAndSecondFailsMustNotReturnPartialSuccessOrRetry() throws Exception {
        var ds = mock(DataSource.class); var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(2).thenThrow(new SQLException("private fixture failure"));
        var failure = assertThrows(UnconfirmedWriteException.class, () -> NonTransactionalWriteExecution.execute(ds, (c, attempt) ->
                HiveJdbcWriteSupport.insert(c, attempt, "fixture", "rows", FIELDS, columns(), input(), LIMITS, 19)));
        assertNull(failure.getCause()); verify(statement, times(2)).executeUpdate(); verify(connection, never()).rollback();
    }
    @Test void bindingFailureBeforeFirstExecuteIsKnownNoWrite() throws Exception {
        var ds = mock(DataSource.class); var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.prepareStatement(anyString())).thenReturn(statement);
        doThrow(new SQLException("private value")).when(statement).setString(2, "1.001");
        var failure = assertThrows(IllegalStateException.class, () -> NonTransactionalWriteExecution.execute(ds, (c, attempt) ->
                HiveJdbcWriteSupport.insert(c, attempt, "fixture", "rows", FIELDS, columns(), input(), LIMITS, 19)));
        assertNull(failure.getCause()); verify(statement, never()).executeUpdate();
    }
}
