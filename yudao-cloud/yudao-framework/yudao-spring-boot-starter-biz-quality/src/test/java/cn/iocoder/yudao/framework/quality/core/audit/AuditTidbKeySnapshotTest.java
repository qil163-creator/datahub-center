package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Protocol vectors are independent of query assembly and do not require a JDBC driver here. */
class AuditTidbKeySnapshotTest {
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final AuditRuleContext context = new AuditRuleContext(spy(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()));
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach void setup() throws Exception {
        context.setProductType(ProductTypeEnum.TIDB); context.setTableName("sample");
        context.setColumn("amount"); context.setConfig(Map.of());
        when(connection.createStatement()).thenReturn(statement);
    }

    private ResultSet tupleRows(int secondType, String secondName, long count) throws Exception {
        var rows = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        nativeType(metadata, 4, Types.BIGINT, "BIGINT"); nativeType(metadata, 6, secondType, secondName);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong(1)).thenReturn(count); when(rows.getLong(2)).thenReturn(0L);
        when(rows.getBytes(3)).thenReturn(bytes("2"), bytes("1"));
        when(rows.getBytes(5)).thenReturn(bytes("ab"), bytes("ba"));
        return rows;
    }

    private static void nativeType(ResultSetMetaData metadata, int column, int type, String name) throws SQLException {
        when(metadata.isSigned(column)).thenReturn(true); when(metadata.getColumnType(column)).thenReturn(type);
        when(metadata.getColumnTypeName(column)).thenReturn(name);
    }

    @Test void oldIntegerTupleKeepsItsGoldenHashAndNeverOptsIntoMixedProjection() throws Exception {
        var rows = tupleRows(Types.BIGINT, "BIGINT", 1);
        when(rows.getBytes(5)).thenReturn(bytes("12"), bytes("11"));
        when(statement.executeQuery(anyString())).thenReturn(rows);
        var read = tuple(2);
        assertTrue(read.complete()); assertEquals(TIDB_COMPOSITE_ALGORITHM, read.algorithm());
        assertEquals("fa97b41d488311f492c3478dd6d6b597a0434f1e0c55a53990889360ed7d3c60", read.keyHash());
        assertFalse(read.sql().contains("WHERE FALSE")); verify(statement).executeQuery(anyString());
        verifyOwnership(1); verify(rows).close();
    }

    @Test void mixedFallbackDiscardsEarlierCountsAndUsesItsOwnGoldenFrame() throws Exception {
        var first = tupleRows(Types.VARCHAR, "VARCHAR", 7);
        var second = tupleRows(Types.VARCHAR, "VARCHAR", 1);
        when(statement.executeQuery(anyString())).thenReturn(first, second);
        var read = tuple(2);
        assertTrue(read.complete()); assertEquals(2, read.total()); assertEquals(0, read.fail());
        assertEquals(TIDB_TYPED_COMPOSITE_ALGORITHM, read.algorithm());
        assertEquals("0f669da63706389a69a3db3ade44a68d98b8e379cd8492c5ed5519436ab0e841", read.keyHash());
        assertTrue(read.sql().contains("WHERE FALSE")); assertTrue(read.sql().contains("COLLATE ascii_bin"));
        assertEquals(read, JsonUtils.parseObject(JsonUtils.toJsonString(read), AuditKeySnapshot.class));
        verify(first, never()).getBytes(anyInt()); verify(first).close(); verify(second).close(); verifyOwnership(2);
    }

    @Test void timeoutDuringFallbackDoesNotReturnEarlierCounts() throws Exception {
        var first = tupleRows(Types.VARCHAR, "VARCHAR", 7);
        var failure = new SQLTimeoutException("synthetic fallback timeout");
        when(statement.executeQuery(anyString())).thenReturn(first).thenThrow(failure);
        assertSame(failure, assertThrows(SQLTimeoutException.class, () -> tuple(2)));
        verify(first).close(); verifyOwnership(2);
    }

    @Test void keyLimitAndUnknownFailureDoNotTriggerFallback() throws Exception {
        var first = tupleRows(Types.VARCHAR, "VARCHAR", 7);
        when(statement.executeQuery(anyString())).thenReturn(first);
        var read = tuple(1);
        assertEquals("KEY_LIMIT_EXCEEDED", read.reasonCode()); assertEquals(-1, read.total()); assertNull(read.keyHash());
        verify(statement).executeQuery(anyString());
        var failure = new SQLException("synthetic failure", "08006");
        when(statement.executeQuery(anyString())).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class, () -> tuple(2))); verifyOwnership(2);
    }

    @Test void enumMasqueradingAsVarcharRemainsUnsupportedAfterFallback() throws Exception {
        var first = tupleRows(Types.VARCHAR, "ENUM", 7);
        var second = tupleRows(Types.VARCHAR, "ENUM", 1);
        when(statement.executeQuery(anyString())).thenReturn(first, second);
        var read = tuple(2);
        assertEquals("KEY_TYPE_UNSUPPORTED", read.reasonCode()); assertEquals(2, read.total()); assertNull(read.keyHash());
        verify(first, never()).getBytes(anyInt()); verify(second, never()).getBytes(anyInt()); verifyOwnership(2);
    }

    @Test void textInstitutionUsesProductBoundEncodingAndNoPreviousGroups() throws Exception {
        var first = tupleRows(Types.VARCHAR, "VARCHAR", 7);
        var second = tupleRows(Types.VARCHAR, "VARCHAR", 1);
        for (var rows : List.of(first, second)) {
            nativeType(rows.getMetaData(), 8, Types.VARCHAR, "VARCHAR");
            when(rows.getBytes(7)).thenReturn(bytes("A"), bytes("a"));
        }
        when(statement.executeQuery(anyString())).thenReturn(first, second);
        var read = withFactory(() -> readGroupedComposite(connection, generator, context, List.of("part", "id"), "org", 2, 2, 5));
        assertTrue(read.complete()); assertEquals(TIDB_TEXT_GROUP_ENCODING, read.groupEncoding());
        assertEquals(List.of("A", "a"), read.groups().stream().map(Group::key).toList());
        assertTrue(read.groups().stream().allMatch(g -> g.snapshot().complete() && g.snapshot().total() == 1
                && TIDB_TYPED_COMPOSITE_ALGORITHM.equals(g.snapshot().algorithm())));
        verify(first, never()).next(); verifyOwnership(2);
    }

    @Test void textSingleUsesNativeWitnessAndOwnGoldenDigest() throws Exception {
        var first = tupleRows(Types.VARCHAR, "VARCHAR", 7);
        var second = tupleRows(Types.VARCHAR, "VARCHAR", 1);
        nativeType(first.getMetaData(), 4, Types.VARCHAR, "VARCHAR");
        nativeType(second.getMetaData(), 4, Types.VARCHAR, "VARCHAR");
        when(first.getLong(2)).thenReturn(7L); when(second.getLong(2)).thenReturn(1L);
        when(second.getBytes(1)).thenReturn(bytes("A"), bytes("a"));
        when(statement.executeQuery(anyString())).thenReturn(first, second);
        var read = withFactory(() -> AuditKeySnapshot.read(connection, generator, context, "id", 2, 5));
        assertTrue(read.complete()); assertEquals(2, read.total()); assertEquals(TIDB_TEXT_ALGORITHM, read.algorithm());
        assertEquals("b55b5883f02abd23d2ba479b9c91b75e3df0666765e8ba0d453cb2cc8d8b4018", read.keyHash());
        assertTrue(read.sql().contains("WHERE FALSE")); verifyOwnership(2);
    }

    @Test void oldSingleIntegerGoldenDigestIsUnchanged() throws Exception {
        var rows = tupleRows(Types.BIGINT, "BIGINT", 1);
        when(rows.getLong(1)).thenReturn(1L, 2L); when(rows.getLong(2)).thenReturn(1L);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        var read = withFactory(() -> AuditKeySnapshot.read(connection, generator, context, "id", 2, 5));
        assertTrue(read.complete()); assertEquals(ALGORITHM, read.algorithm());
        assertEquals("636b04242467aa0380aa3fc413d44fa04a82e2c2f408df1ebd9d5aba517fb752", read.keyHash());
        verifyOwnership(1);
    }

    private AuditKeySnapshot tuple(int maxKeys) throws Exception {
        return withFactory(() -> readComposite(connection, generator, context, List.of("part", "id"), maxKeys, 5));
    }
    private <T> T withFactory(java.util.concurrent.Callable<T> action) throws Exception {
        var database = mock(DatabaseTidbImpl.class, CALLS_REAL_METHODS);
        {
            doReturn(database).when(context.products()).metadata(ProductTypeEnum.TIDB);
            return action.call();
        }
    }
    private void verifyOwnership(int queries) throws SQLException {
        verify(statement, times(queries)).close(); verify(connection, never()).close();
        verify(connection, never()).commit(); verify(connection, never()).rollback();
        verify(connection, never()).setAutoCommit(anyBoolean());
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
