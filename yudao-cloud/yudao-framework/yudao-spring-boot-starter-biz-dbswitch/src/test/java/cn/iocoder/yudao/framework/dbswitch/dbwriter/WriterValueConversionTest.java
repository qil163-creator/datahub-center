package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.db2.DB2WriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.dm.DmWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb.GreenplumCopyWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb.GreenplumInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.hive.HiveWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle.OracleWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oscar.OscarWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite.Sqlite3WriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbCopyWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.util.ObjectCastUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Struct;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WriterValueConversionTest {
    static Stream<Class<? extends AbstractDatabaseWriter>> writers() {
        // Doris requires native metadata preparation; its same whole-batch/LOB contract is
        // covered by DorisNativeWriteOutcomeTest.preparedPublicWriterAndSyncRejectInvalidBatchBeforeConnecting.
        return Stream.of(DB2WriterImpl.class, DmWriterImpl.class,
                GreenplumInsertWriterImpl.class, HiveWriterImpl.class,
                SqlServerWriterImpl.class, MySqlWriterImpl.class, OracleWriterImpl.class, OscarWriterImpl.class,
                PostgresInsertWriterImpl.class, Sqlite3WriterImpl.class);
        // TDengine requires its native table/key model; its whole-batch guard is covered by
        // TdengineNativeExecutionTest.preparedWriterRejectsLaterInvalidRowBeforeConnecting.
    }

    @ParameterizedTest @MethodSource("writers")
    void laterConversionFailureDoesNotWriteOrMutateEarlierRows(Class<? extends AbstractDatabaseWriter> type) throws Exception {
        var source = mock(DataSource.class);
        var writer = type.getConstructor(DataSource.class).newInstance(source);
        prepare(writer, Types.VARCHAR);
        var owner = Thread.currentThread();
        var clob = mock(Clob.class);
        when(clob.getCharacterStream()).thenAnswer(call -> { assertSame(owner, Thread.currentThread()); return new StringReader("first\nsecond"); });
        var unsupported = mock(Struct.class);
        var first = new Object[]{clob}; var second = new Object[]{unsupported};
        var rows = List.of(first, second);
        var failure = assertThrows(IllegalArgumentException.class, () -> writer.write(List.of("value"), rows));
        assertNull(failure.getCause());
        assertSame(clob, rows.get(0)[0]); assertSame(unsupported, rows.get(1)[0]);
        assertEquals(2, rows.size()); verify(clob).free(); verifyNoInteractions(source);
    }

    @Test void receiptInsertAlsoRejectsConversionBeforeStartingWitnessOrTransaction() {
        var source = mock(DataSource.class); var writer = new PostgresInsertWriterImpl(source);
        prepare(writer, Types.VARCHAR);
        assertThrows(IllegalArgumentException.class, () -> writer.writeWithReceipt(List.of("value"),
                List.of(new Object[]{"valid"}, new Object[]{mock(Struct.class)})));
        verifyNoInteractions(source);
    }

    @Test void successfulJdbcWriteKeepsImmutableInputAndExactDecimal() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeBatch()).thenReturn(new int[]{1});
        var writer = new MySqlWriterImpl(source); prepare(writer, Types.NUMERIC);
        var decimal = new BigDecimal("123456789012345678901234567890.12345678901234567890");
        var row = new Object[]{decimal}; var rows = List.<Object[]>of(row);
        assertEquals(1, writer.write(List.of("value"), rows));
        assertEquals(1, rows.size()); assertSame(row, rows.get(0)); assertSame(decimal, rows.get(0)[0]);
        verify(statement).setObject(1, decimal, Types.NUMERIC); verify(connection).commit();
        assertSame(decimal, ObjectCastUtils.castByDetermine(decimal));
    }

    @Test void copyCannotTranslateUnrecognizedBooleanToFalse() {
        var greenplum = new GreenplumCopyWriterImpl(mock(DataSource.class));
        prepare(greenplum, Types.BOOLEAN);
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> greenplum.write(List.of("value"), List.of(new Object[]{true}, new Object[]{"unknown"})));
        verifyNoInteractions(greenplum.getDataSource());
        var uxdb = new UxdbCopyWriterImpl(mock(DataSource.class));
        prepare(uxdb, Types.BOOLEAN);
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> uxdb.write(List.of("value"), List.of(new Object[]{true}, new Object[]{"unknown"})));
        verifyNoInteractions(uxdb.getDataSource());
    }

    @Test void copyRejectsTextNulAndUnsupportedNullTypesBeforeConnecting() {
        var greenplum = new GreenplumCopyWriterImpl(mock(DataSource.class));
        prepare(greenplum, Types.VARCHAR);
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> greenplum.write(List.of("value"), List.<Object[]>of(new Object[]{"before\0after"})));
        prepare(greenplum, Types.STRUCT);
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> greenplum.write(List.of("value"), List.<Object[]>of(new Object[]{null})));
        verifyNoInteractions(greenplum.getDataSource());
        var uxdb = new UxdbCopyWriterImpl(mock(DataSource.class));
        prepare(uxdb, Types.VARCHAR);
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> uxdb.write(List.of("value"), List.<Object[]>of(new Object[]{"before\0after"})));
        verifyNoInteractions(uxdb.getDataSource());
    }

    private static void prepare(AbstractDatabaseWriter writer, int type) {
        ReflectionTestUtils.setField(writer, "columnType", Map.of("value", type));
        ReflectionTestUtils.setField(writer, "schemaName", "fixture");
        ReflectionTestUtils.setField(writer, "tableName", "rows");
    }
}
