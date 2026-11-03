package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditMysqlCompositeKeySnapshotTest {
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    private final AuditRuleContext context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach void setup() throws Exception {
        context.setProductType(ProductTypeEnum.MYSQL); context.setTableName("sample"); context.setColumn("amount");
        context.setWhereClause("region=1 OR region=2"); context.setConfig(Map.of());
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows); when(rows.getMetaData()).thenReturn(metadata);
        when(rows.next()).thenReturn(true, true, false); when(rows.getLong(1)).thenReturn(1L);
        when(rows.getLong(2)).thenReturn(0L);
        when(metadata.isSigned(4)).thenReturn(true); when(metadata.getColumnType(4)).thenReturn(Types.BIGINT);
        when(metadata.getColumnTypeName(4)).thenReturn("BIGINT");
        when(metadata.getColumnType(6)).thenReturn(Types.VARCHAR); when(metadata.getColumnTypeName(6)).thenReturn("VARCHAR");
        when(rows.getBytes(3)).thenReturn(bytes("2"), bytes("1"));
        when(rows.getBytes(5)).thenReturn(bytes("ab"), bytes("ba"));
    }

    @Test void sameQueryCountsAndTypesUseBoundedDeterministicTupleOrdering() throws Exception {
        var result = read(2);
        assertTrue(result.complete()); assertEquals(2, result.total());
        assertEquals(AuditKeySnapshot.MYSQL_COMPOSITE_ALGORITHM, result.algorithm());
        assertEquals(Types.STRUCT, result.jdbcType());
        assertTrue(result.sql().contains("WHERE (region=1 OR region=2) GROUP BY"));
        assertTrue(result.sql().contains("COLLATE ascii_bin")); assertTrue(result.sql().contains("LIMIT 3"));
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getBytes(3)).thenReturn(bytes("1"), bytes("2"));
        when(rows.getBytes(5)).thenReturn(bytes("ba"), bytes("ab"));
        assertEquals(result.keyHash(), read(2).keyHash());
        verify(statement, times(2)).setMaxRows(3); verify(statement, times(2)).setQueryTimeout(5);
        verify(rows, times(2)).close(); verify(statement, times(2)).close();
        verify(connection, never()).close(); verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test void nativeUnsupportedComponentsNeverDecodeConvertedValues() throws Exception {
        when(metadata.getColumnTypeName(6)).thenReturn("ENUM");
        var result = read(2); assertFalse(result.complete()); assertEquals(2, result.total());
        assertNull(result.keyHash()); assertEquals("KEY_TYPE_UNSUPPORTED", result.reasonCode());
        verify(rows, never()).getBytes(anyInt());
    }

    @Test void frameLimitIncludesAllTypesAndLengths() throws Exception {
        when(rows.next()).thenReturn(true, false);
        when(rows.getBytes(3)).thenReturn(bytes("1")); when(rows.getBytes(5)).thenReturn(new byte[235]);
        assertTrue(read(2).complete()); // 4 + 8 + 1 + 8 + 235 = 256.
        when(rows.next()).thenReturn(true, false); when(rows.getBytes(5)).thenReturn(new byte[236]);
        var result = read(2); assertFalse(result.complete()); assertNull(result.keyHash());
        assertEquals("KEY_NULL_OR_TOO_LONG", result.reasonCode());
    }

    @Test void nullAndDuplicateTuplesCannotProvideProof() throws Exception {
        when(rows.getBytes(5)).thenReturn((byte[]) null);
        assertEquals("KEY_NULL_OR_TOO_LONG", read(2).reasonCode());
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getBytes(3)).thenReturn(bytes("1")); when(rows.getBytes(5)).thenReturn(bytes("a"));
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", read(2).reasonCode());
        when(rows.next()).thenReturn(true, false); when(rows.getLong(1)).thenReturn(2L);
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", read(2).reasonCode());
    }

    @Test void sharedKeyLimitReturnsNoPartialWholeTableOrInstitutionEvidence() throws Exception {
        var result = read(1); assertFalse(result.complete()); assertEquals(-1, result.total());
        assertEquals("KEY_LIMIT_EXCEEDED", result.reasonCode()); assertNull(result.keyHash());
        textGroup(); when(rows.next()).thenReturn(true, true, false);
        var group = grouped(1, 2); assertFalse(group.complete()); assertTrue(group.groups().isEmpty());
        assertEquals("KEY_LIMIT_EXCEEDED", group.reasonCode());
    }

    @Test void groupLimitAndInvalidIdentityCannotLeakPartialGroups() throws Exception {
        textGroup(); var limit = grouped(2, 1);
        assertFalse(limit.complete()); assertTrue(limit.groups().isEmpty());
        assertEquals("GROUP_LIMIT_EXCEEDED", limit.reasonCode());
        when(rows.next()).thenReturn(true, false); when(rows.getBytes(7)).thenReturn(bytes(" "));
        assertEquals("GROUP_KEY_UNSUPPORTED", grouped(2, 2).reasonCode());
    }

    @Test void groupedCountsBindEachOwnTupleSetAndExactInstitutionEncoding() throws Exception {
        textGroup(); var result = grouped(2, 2); assertTrue(result.complete());
        assertEquals(List.of("9", "09"), result.groups().stream().map(AuditKeySnapshot.Group::key).toList());
        assertEquals(AuditKeySnapshot.MYSQL_TEXT_GROUP_ENCODING, result.groupEncoding());
        assertTrue(result.groups().stream().allMatch(group -> group.snapshot().complete()));
        assertNotEquals(result.groups().getFirst().snapshot().keyHash(), result.groups().getLast().snapshot().keyHash());
        verify(statement).executeQuery(anyString());
    }

    @Test void timeoutAndInvalidCountsPropagateAndCloseOwnedResources() throws Exception {
        when(rows.getLong(2)).thenReturn(-1L);
        assertEquals("22000", assertThrows(SQLException.class, () -> read(2)).getSQLState());
        var timeout = new SQLTimeoutException("synthetic tuple timeout");
        when(statement.executeQuery(anyString())).thenThrow(timeout);
        assertSame(timeout, assertThrows(SQLTimeoutException.class, () -> read(2)));
        verify(rows).close(); verify(statement, times(2)).close(); verify(connection, never()).close();
    }

    @Test void invalidColumnsBoundsAndProductsStopBeforeJdbc() {
        for (var columns : List.of(List.of("id"), List.of("id", "ID"), List.of("id", "x;DELETE"),
                List.of("id", "a", "b", "c", "d"))) {
            assertThrows(UnsupportedOperationException.class, () -> AuditKeySnapshot.readComposite(
                    connection, generator, context, columns, 2, 5));
        }
        assertThrows(IllegalArgumentException.class, () -> read(0));
        assertThrows(IllegalArgumentException.class, () -> grouped(2, 0));
        context.setProductType(ProductTypeEnum.UNKNOWN);
        assertThrows(UnsupportedOperationException.class, () -> read(2)); verifyNoInteractions(connection);
    }
    private void textGroup() throws Exception {
        when(metadata.getColumnType(8)).thenReturn(Types.VARCHAR); when(metadata.getColumnTypeName(8)).thenReturn("VARCHAR");
        when(rows.getBytes(7)).thenReturn(bytes("9"), bytes("09"));
    }
    private AuditKeySnapshot read(int limit) throws Exception {
        return AuditKeySnapshot.readComposite(connection, generator, context, List.of("part", "id"), limit, 5);
    }
    private AuditKeySnapshot.Grouped grouped(int keys, int groups) throws Exception {
        return AuditKeySnapshot.readGroupedComposite(connection, generator, context, List.of("part", "id"), "region", keys, groups, 5);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
