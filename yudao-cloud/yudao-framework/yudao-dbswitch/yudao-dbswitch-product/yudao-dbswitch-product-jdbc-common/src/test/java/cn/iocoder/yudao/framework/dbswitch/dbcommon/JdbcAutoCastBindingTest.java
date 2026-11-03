package cn.iocoder.yudao.framework.dbswitch.dbcommon;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.SqlTypeValue;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAutoCastBindingTest {
    private static IntStream textTypes() {
        return IntStream.of(Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR,
                Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB);
    }

    private static IntStream numericTypes() {
        return IntStream.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE);
    }

    @ParameterizedTest @MethodSource("textTypes")
    void binaryTextUsesStrictUtf8ForEveryCharacterTarget(int type) throws Exception {
        var fixture = new Fixture(3);
        byte[] value = "中文🙂\ntext".getBytes(StandardCharsets.UTF_8);
        Object[] row = {value};
        fixture.write(List.of(row, new Object[]{new byte[0]}, new Object[]{null}), type);
        verify(fixture.statement).setObject(1, "中文🙂\ntext", type);
        verify(fixture.statement).setObject(1, "", type);
        verify(fixture.statement).setObject(1, null, type);
        assertSame(value, row[0]);
        assertArrayEquals("中文🙂\ntext".getBytes(StandardCharsets.UTF_8), value);
        verify(fixture.connection).commit();
    }

    @ParameterizedTest @MethodSource("numericTypes")
    void booleanWordsUseUpstreamTrimAndCaseRulesWithoutNarrowingNumbers(int type) throws Exception {
        var fixture = new Fixture(4);
        Object[] first = {" \tTrUe\r\n"};
        fixture.write(List.of(first, new Object[]{" FALSE "}, new Object[]{true}, new Object[]{false}), type);
        verify(fixture.statement, times(2)).setObject(1, BigDecimal.ONE, type);
        verify(fixture.statement, times(2)).setObject(1, BigDecimal.ZERO, type);
        assertEquals(" \tTrUe\r\n", first[0]);
    }

    @ParameterizedTest @ValueSource(strings = {"truth", "falsehood", "\u00a0true", "1", "0", ""})
    void unrelatedNumericTextRemainsTheDriversResponsibility(String value) throws Exception {
        var fixture = new Fixture(1);
        fixture.write(List.<Object[]>of(new Object[]{value}), Types.NUMERIC);
        verify(fixture.statement).setObject(1, value, Types.NUMERIC);
    }

    @Test void malformedUtf8InALaterRowRejectsTheWholeBatchBeforeBorrowing() throws Exception {
        var fixture = new Fixture(2);
        Object[] first = {"valid".getBytes(StandardCharsets.UTF_8)};
        Object[] second = {new byte[]{(byte) 0xc3, 0x28}};
        var failure = assertThrows(BatchRejectedException.class,
                () -> fixture.write(List.of(first, second), Types.VARCHAR));
        assertTrue(failure.getMessage().contains("row 2, column 1"));
        assertNull(failure.getCause());
        assertInstanceOf(byte[].class, first[0]);
        verifyNoInteractions(fixture.source);
        verify(fixture.statement, never()).executeBatch();
    }

    @Test void nativeValuesBinaryTargetsAndCustomBindersAreNotAutoCast() throws Exception {
        var fixture = new Fixture(1);
        byte[] bytes = {(byte) 0xff, 0};
        var integer = new BigInteger("9223372036854775808");
        var decimal = new BigDecimal("12345678901234567890.1234567890");
        var time = OffsetDateTime.parse("2026-10-08T12:34:56.123456+08:00");
        Object vendor = new Object();
        SqlTypeValue custom = mock(SqlTypeValue.class);
        var row = new Object[]{bytes, bytes, integer, decimal, time, vendor, custom, "true"};
        int[] types = {Types.VARBINARY, Types.OTHER, Types.NUMERIC, Types.DECIMAL,
                Types.TIMESTAMP_WITH_TIMEZONE, Types.OTHER, Types.CLOB, Types.VARCHAR};
        JdbcBatchExecutor.execute(fixture.source, "INSERT INTO fixture VALUES (?,?,?,?,?,?,?,?)",
                List.<Object[]>of(row), types);
        for (int index = 0; index < row.length; index++) {
            if (index == 6) verify(custom).setTypeValue(fixture.statement, 7, Types.CLOB, null);
            else verify(fixture.statement).setObject(index + 1, row[index], types[index]);
        }
    }

    @Test void callerOwnedTransactionEntryAlsoValidatesBeforePreparingAnyStatement() throws Exception {
        var fixture = new Fixture(2);
        var failure = assertThrows(IllegalStateException.class, () -> JdbcBatchExecutor.execute(fixture.source, context -> {
            JdbcBatchExecutor.executeBatch(context, "INSERT INTO fixture VALUES (?)",
                    List.of(new Object[]{"valid".getBytes(StandardCharsets.UTF_8)},
                            new Object[]{new byte[]{(byte) 0x80}}), new int[]{Types.VARCHAR});
            return null;
        }));
        assertNull(failure.getCause());
        verify(fixture.connection, never()).prepareStatement(anyString());
        verify(fixture.connection, never()).commit();
        verify(fixture.connection).rollback();
    }

    private static final class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);

        Fixture(int rows) throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenReturn(new int[rows]);
        }

        void write(List<Object[]> rows, int type) {
            JdbcBatchExecutor.execute(source, "INSERT INTO fixture VALUES (?)", rows, new int[]{type}, 5);
        }
    }
}
