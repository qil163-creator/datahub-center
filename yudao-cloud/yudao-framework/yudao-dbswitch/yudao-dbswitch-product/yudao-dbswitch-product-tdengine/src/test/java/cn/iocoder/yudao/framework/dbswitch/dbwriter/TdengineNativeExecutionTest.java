package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.TdengineDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TdengineNativeExecutionTest {
    private static final List<String> FIELDS = List.of("ts", "amount");
    private static final TdengineJdbcSupport.Table TABLE = new TdengineJdbcSupport.Table(List.of(
            new TdengineJdbcSupport.Column("ts", Types.TIMESTAMP, "TIMESTAMP", 0, 0),
            new TdengineJdbcSupport.Column("amount", Types.DECIMAL, "DECIMAL", 38, 9)), List.of("ts"), 9);
    private static final Timestamp TIME = Timestamp.from(Instant.parse("2026-10-03T00:00:00.123456789Z"));
    @Test void preparedWriterRejectsLaterInvalidRowBeforeConnecting() {
        var source=mock(DataSource.class);
        var writer=new cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine.TdengineWriterImpl(source);
        org.springframework.test.util.ReflectionTestUtils.setField(writer,"metadata",TABLE);
        var first=new Object[]{TIME,BigDecimal.ONE};
        var unsupported=mock(Struct.class);
        var second=new Object[]{Timestamp.from(TIME.toInstant().plusSeconds(1)),unsupported};
        var rows=List.of(first,second);
        assertThrows(IllegalArgumentException.class,()->writer.write(FIELDS,rows));
        assertSame(first,rows.getFirst());assertSame(BigDecimal.ONE,rows.getFirst()[1]);
        assertSame(unsupported,rows.getLast()[1]);assertEquals(2,rows.size());
        verifyNoInteractions(source);
    }
    @Test void wholeBatchRejectsPrecisionLossDuplicatesNullAndParameterBudgetBeforeJdbc() {
        var limits = new BatchLimits(10, 8192, 2048, 8, 1000);
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert(List.of(new Object[]{TIME, BigDecimal.ONE},
                new Object[]{Timestamp.from(TIME.toInstant().plusSeconds(1)), new BigDecimal("0.0000000001")}), FIELDS, TABLE, limits));
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert(List.of(new Object[]{TIME, null}, new Object[]{TIME, null}), FIELDS, TABLE, limits));
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert(List.<Object[]>of(new Object[]{null, null}), FIELDS, TABLE, limits));
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.timestamp(TIME, 3));
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert(List.<Object[]>of(new Object[]{TIME, null}), FIELDS, TABLE,
                new BatchLimits(10, 8192, 2048, 1, 1000)));
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 2})
    void insufficientServerCountsRemainUnknownAndNeverCommitOrRetry(int count) throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        var statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:TAOS-WS://127.0.0.1:1/");
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeUpdate()).thenReturn(count);
        assertThrows(UnconfirmedWriteException.class, () -> NonTransactionalWriteExecution.execute(source, (c, attempt) ->
                TdengineJdbcSupport.insert(c, attempt, "fixture", "rows", FIELDS, TABLE, List.<Object[]>of(new Object[]{1L, null}), 19)));
        verify(statement).setQueryTimeout(19); verify(statement, times(1)).executeUpdate();
        verify(connection, never()).setAutoCommit(anyBoolean()); verify(connection, never()).commit(); verify(connection, never()).rollback();
    }
    @Test void textBinaryLengthAndUnicodeAreValidatedBeforeNativeBinding() {
        var text = new TdengineJdbcSupport.Column("note", Types.NCHAR, "NCHAR", 2, 0);
        var bytes = new TdengineJdbcSupport.Column("raw", Types.VARBINARY, "VARBINARY", 2, 0);
        assertEquals("中😀", TdengineJdbcSupport.convert("中😀", text, 9));
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert("中文长", text, 9));
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert("\uD800", text, 9));
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert(new byte[]{1,2,3}, bytes, 9));
    }
    @Test void unsignedIntegersRetainNativeRangeAndWebSocketBoxedTypes() throws Exception {
        String[] names = {"TINYINT UNSIGNED", "SMALLINT UNSIGNED", "INT UNSIGNED", "BIGINT UNSIGNED"};
        int[] jdbc = {Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC};
        int[] bits = {8,16,32,64};
        Object[] expected = {(short)255, 65535, 4294967295L, new java.math.BigInteger("18446744073709551615")};
        for (int index = 0; index < names.length; index++) {
            var column = new TdengineJdbcSupport.Column("value", jdbc[index], names[index], 20, 0);
            var maximum = java.math.BigInteger.ONE.shiftLeft(bits[index]).subtract(java.math.BigInteger.ONE);
            assertEquals(expected[index], TdengineJdbcSupport.convert(maximum, column, 9));
            assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert(maximum.add(java.math.BigInteger.ONE), column, 9));
            assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert(-1, column, 9));
            assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert(new BigDecimal("1.5"), column, 9));
        }
        var statement = mock(PreparedStatement.class);
        TdengineJdbcSupport.bind(statement, 1, expected[3], new TdengineJdbcSupport.Column("u", Types.NUMERIC, names[3], 20, 0));
        verify(statement).setObject(1, expected[3]); verify(statement, never()).setString(anyInt(), anyString());
    }
    @Test void varcharAndBinaryUseByteCapacityWhileNcharUsesCodePoints() {
        var varchar = new TdengineJdbcSupport.Column("text", Types.VARCHAR, "BINARY", 3, 0);
        assertArrayEquals("中".getBytes(java.nio.charset.StandardCharsets.UTF_8), (byte[]) TdengineJdbcSupport.convert("中", varchar, 9));
        assertThrows(IllegalArgumentException.class, () -> TdengineJdbcSupport.convert("中文", varchar, 9));
        assertArrayEquals(new byte[]{0,-1}, (byte[]) TdengineJdbcSupport.convert(new byte[]{0,-1}, varchar, 9));
    }
    @Test void truncateFailureDoesNotIssueFallbackSqlAndDoesNotClaimRollback() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:TAOS-WS://127.0.0.1:1/"); when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("private fixture response"));
        var error = assertThrows(UnconfirmedWriteException.class, () -> new TdengineDatabaseOperator(source).truncateTableData("fixture", "rows"));
        assertNull(error.getCause()); verify(statement).execute("DELETE FROM `fixture`.`rows`"); verify(statement, times(1)).execute(anyString());
    }
    @Test void legacyRestDriverIsRejectedBeforeAnyValueQueryOrWrite() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:TAOS-RS://127.0.0.1:1/");
        assertThrows(IllegalStateException.class, () -> new TdengineDatabaseOperator(source).queryTableData("SELECT * FROM fixture.rows"));
        verify(connection, never()).createStatement(); verify(connection).close();
    }
    @Test void numericTagEvidenceRejectsUnboundedExponentBeforeAcquiringConnection() {
        var source = mock(DataSource.class); var operator = new TdengineDatabaseOperator(source);
        for (String number : List.of("1e2147483647", "1e-100000000", "1".repeat(1025))) {
            String document = """
                    {"kind":"CHILD_TABLE","parent":"metrics","precision":"ns",
                    "columns":[{"name":"ts","type":"TIMESTAMP","key":false,"tag":false,"encoding":"delta-i","compression":"lz4","level":"medium"},
                    {"name":"site","type":"DOUBLE","key":false,"tag":true,"encoding":"","compression":"","level":""}],
                    "tags":[{"encoding":"NUMBER","value":"NUMBER_VALUE"}],
                    "properties":{"comment":"","ttl":0},"parentProperties":{"comment":"","ttl":0}}
                    """.replace("NUMBER_VALUE", number);
            var evidence = new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TDENGINE,
                    "TDENGINE_TABLE_FAMILY_V1", document, List.of("ts"));
            assertThrows(IllegalArgumentException.class, () -> operator.prepareNativeTarget(evidence, "fixture", "target", java.util.Map.of("ts", "ts"), true));
        }
        verifyNoInteractions(source);
    }
    @Test void tableSelectionBoundAppliesAcrossAllSelectedFamilies() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:TAOS-WS://127.0.0.1:1/"); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT `type`")) return generatedRows(0, "");
            if (sql.startsWith("SELECT stable_name")) return generatedRows(1, "family");
            return sql.contains("stable_name='first'") ? generatedRows(100000, "a") : generatedRows(1, "b");
        });
        var first = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription(); first.setSchemaName("fixture"); first.setTableName("first");
        var second = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription(); second.setSchemaName("fixture"); second.setTableName("second");
        assertThrows(IllegalStateException.class, () -> new TdengineDatabaseOperator(source).planMigrationSources(List.of(first, second)));
        verify(connection).close(); verify(statement, never()).execute(anyString());
    }
    private static ResultSet generatedRows(int count, String prefix) {
        var position = new java.util.concurrent.atomic.AtomicInteger(-1);
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> position.incrementAndGet() < count;
                    case "getString" -> prefix + position.get();
                    case "close" -> null;
                    default -> throw new AssertionError("Unexpected fixture call: " + method.getName());
                });
    }
}
