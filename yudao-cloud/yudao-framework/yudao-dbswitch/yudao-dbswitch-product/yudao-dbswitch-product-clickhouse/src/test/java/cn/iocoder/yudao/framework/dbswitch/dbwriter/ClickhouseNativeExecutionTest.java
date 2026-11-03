package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.ClickhouseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.clickhouse.ClickhouseWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Fault injection only; real product behavior is covered by ClickhouseMigrationClickhouseTest. */
class ClickhouseNativeExecutionTest {
    private static final List<String> FIELDS = List.of("id", "note");
    private static final List<Object[]> ROW = List.<Object[]>of(new Object[]{1L, "中文"});
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(4096, false, false, 17, 0, 0, 250, 0);

    @Test void positiveTimeoutAndAcknowledgedCountsWithoutTransactionCalls() throws Exception {
        var fixture = new Fixture();
        assertEquals(1, fixture.writer.write(FIELDS, ROW));
        verify(fixture.writeStatement).setQueryTimeout(17);
        verify(fixture.writeConnection).prepareStatement(contains("SETTINGS async_insert=0, wait_for_async_insert=1"));
        verify(fixture.writeConnection, never()).getAutoCommit();
        verify(fixture.writeConnection, never()).setAutoCommit(anyBoolean());
        verify(fixture.writeConnection, never()).commit(); verify(fixture.writeConnection, never()).rollback();
        verify(fixture.writeConnection).close();
        assertArrayEquals(new Object[]{1L, "中文"}, ROW.getFirst());
    }
    @Test void oversizeBatchFailsBeforeBorrowAndBeforePrefixWrite() throws Exception {
        var fixture = new Fixture();
        fixture.writer.configureBatchLimits(new BatchLimits(1, 1024, 512, 10, 1000));
        clearInvocations(fixture.source);
        assertThrows(BatchRejectedException.class, () -> fixture.writer.write(FIELDS,
                List.of(new Object[]{1L, "first"}, new Object[]{2L, "second"})));
        verifyNoInteractions(fixture.source, fixture.writeConnection, fixture.writeStatement);
    }
    @Test void bindingFailureHasNoUnknownOutcomeAndNeverExecutesPrefix() throws Exception {
        var fixture = new Fixture();
        doThrow(new SQLException("private row must not escape")).when(fixture.writeStatement).setObject(2, "中文");
        var failure = assertThrows(IllegalStateException.class, () -> fixture.writer.write(FIELDS, ROW));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private"));
        verify(fixture.writeStatement, never()).executeBatch();
    }
    @ParameterizedTest @ValueSource(strings = {"execute", "statement-close", "connection-close"})
    void sentRequestOrUnconfirmedCleanupIsUnknownAndNeverRetried(String stage) throws Exception {
        var fixture = new Fixture();
        if (stage.equals("execute")) when(fixture.writeStatement.executeBatch()).thenThrow(new SQLException("private response"));
        if (stage.equals("statement-close")) doThrow(new SQLException("private cleanup")).when(fixture.writeStatement).close();
        if (stage.equals("connection-close")) doThrow(new SQLException("private cleanup")).when(fixture.writeConnection).close();
        var failure = assertThrows(UnconfirmedWriteException.class, () -> fixture.writer.write(FIELDS, ROW));
        assertNull(failure.getCause()); verify(fixture.writeStatement, times(1)).executeBatch();
        verify(fixture.writeConnection, never()).commit(); verify(fixture.writeConnection, never()).rollback();
    }
    @ParameterizedTest @ValueSource(ints = {0, -2, -3, 2})
    void incompleteOrNonExactDriverCountsCannotBeReturnedAsInputCount(int count) throws Exception {
        var fixture = new Fixture(); when(fixture.writeStatement.executeBatch()).thenReturn(new int[]{count});
        assertThrows(UnconfirmedWriteException.class, () -> fixture.writer.write(FIELDS, ROW));
    }
    @Test void readerUsesPositiveFetchSizeAndNeverRestoresAutoCommit() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var statement = mock(Statement.class);
        var header = mock(Statement.class); var rows = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(source.getConnection()).thenReturn(connection); when(connection.createStatement()).thenReturn(header, statement);
        when(header.executeQuery(anyString())).thenReturn(rows); when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata); when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("value"); when(metadata.getColumnName(1)).thenReturn("value");
        when(metadata.getColumnTypeName(1)).thenReturn("UInt8");
        var reader = new ClickhouseDatabaseOperator(source); reader.configure(OPTIONS); reader.setFetchSize(29);
        try (var result = reader.queryTableData("SELECT 1")) { assertNotNull(result.getResultset()); }
        verify(statement).setFetchSize(29); verify(statement).setQueryTimeout(17);
        verify(header).setQueryTimeout(17); verify(header).close();
        verify(connection, never()).getAutoCommit(); verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection).close(); assertThrows(IllegalArgumentException.class, () -> reader.setFetchSize(Integer.MIN_VALUE));
    }
    @Test void nativePhysicalNamesAreEscapedWithoutBroadeningRestrictedQueryGrammar() {
        assertEquals("`a\\`b\\\\c`", ClickhouseSql.quote("a`b\\c"));
        assertThrows(IllegalArgumentException.class, () -> ClickhouseSql.quote("a\0b"));
    }
    private static final class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection writeConnection = mock(Connection.class);
        final PreparedStatement writeStatement = mock(PreparedStatement.class);
        final ClickhouseWriterImpl writer = new ClickhouseWriterImpl(source);
        Fixture() throws Exception {
            var metadataConnection = mock(Connection.class); var metadataStatement = mock(Statement.class);
            var rows = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
            when(source.getConnection()).thenReturn(metadataConnection, writeConnection);
            when(metadataConnection.createStatement()).thenReturn(metadataStatement);
            when(metadataStatement.executeQuery(anyString())).thenReturn(rows); when(rows.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(2); when(metadata.getColumnName(1)).thenReturn("id");
            when(metadata.getColumnName(2)).thenReturn("note"); when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
            when(metadata.getColumnType(2)).thenReturn(Types.VARCHAR);
            when(writeConnection.prepareStatement(anyString())).thenReturn(writeStatement);
            when(writeStatement.executeBatch()).thenReturn(new int[]{1});
            writer.configure(OPTIONS); writer.prepareWrite("default", "rows", FIELDS);
        }
    }
}
