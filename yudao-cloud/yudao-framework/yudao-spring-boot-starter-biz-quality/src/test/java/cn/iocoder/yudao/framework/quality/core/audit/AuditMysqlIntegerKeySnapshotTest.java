package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditMysqlIntegerKeySnapshotTest {
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    private final AuditRuleContext context = new AuditRuleContext(spy(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()));
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach
    void setup() throws Exception {
        context.setProductType(ProductTypeEnum.MYSQL); context.setTableName("sample");
        context.setColumn("amount"); context.setWhereClause("region=1 OR region=2");
        context.setConfig(java.util.Map.of());
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.isSigned(4)).thenReturn(true);
        when(metadata.getColumnType(4)).thenReturn(Types.BIGINT);
        when(metadata.getColumnTypeName(4)).thenReturn("BIGINT");
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong(1)).thenReturn(Long.MIN_VALUE, Long.MAX_VALUE);
        when(rows.getLong(2)).thenReturn(1L); when(rows.getLong(3)).thenReturn(0L);
    }

    @Test
    void delegatesProjectionAndUsesOneBoundedResultWithoutOwningTransaction() throws Exception {
        var snapshot = read();
        assertTrue(snapshot.complete()); assertEquals(2, snapshot.total());
        assertEquals(Types.BIGINT, snapshot.jdbcType());
        assertEquals(AuditKeySnapshot.ALGORITHM, snapshot.algorithm());
        assertTrue(snapshot.sql().contains("WHERE (region=1 OR region=2) GROUP BY `id` ORDER BY 1 ASC LIMIT 3"));
        assertTrue(snapshot.sql().endsWith("ORDER BY k.`groupKey` ASC"));
        assertTrue(snapshot.sql().contains("LEFT(CAST(`k`.`groupKey` AS CHAR CHARACTER SET ascii), 21)"));
        verify(statement, times(1)).executeQuery(anyString());
        verify(statement).setMaxRows(3); verify(statement).setQueryTimeout(5);
        verify(rows, never()).getInt(4);
        verify(rows).close(); verify(statement).close();
        verify(connection, never()).close(); verify(connection, never()).commit();
        verify(connection, never()).rollback(); verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @Test
    void unsignedOrDecimalMetadataNeverUsesIntegerOrPostgresTypedFallback() throws Exception {
        when(metadata.isSigned(4)).thenReturn(false);
        when(rows.next()).thenReturn(true, true, false, true, true, false);
        var snapshot = read();
        assertFalse(snapshot.complete()); assertNull(snapshot.keyHash());
        assertEquals("KEY_TYPE_UNSUPPORTED", snapshot.reasonCode());
        assertEquals(2, snapshot.total());
        verify(rows, never()).getLong(1); verify(rows, never()).getBytes(1);
        verify(statement, times(2)).executeQuery(anyString());
    }

    @Test
    void keyLimitReturnsNoPartialCountsOrProof() throws Exception {
        when(rows.next()).thenReturn(true);
        var snapshot = read();
        assertEquals("KEY_LIMIT_EXCEEDED", snapshot.reasonCode());
        assertEquals(-1, snapshot.total()); assertNull(snapshot.keyHash()); assertFalse(snapshot.complete());
    }

    @Test
    void duplicateCountsCannotBeUsedAsKeyCoverage() throws Exception {
        when(rows.getLong(2)).thenReturn(2L);
        var snapshot = read();
        assertEquals(4, snapshot.total()); assertFalse(snapshot.complete()); assertNull(snapshot.keyHash());
    }

    @Test
    void sqlFailureClosesStatementAndIsNotClassifiedAsUnsupported() throws Exception {
        var timeout = new SQLTimeoutException("synthetic timeout");
        when(statement.executeQuery(anyString())).thenThrow(timeout);
        assertSame(timeout, assertThrows(SQLTimeoutException.class, this::read));
        verify(statement).close(); verify(connection, never()).commit();
    }

    @Test
    void unverifiedProductCompositePathsStayClosed() {
        context.setProductType(ProductTypeEnum.UNKNOWN);
        assertThrows(UnsupportedOperationException.class, () -> AuditKeySnapshot.readComposite(
                connection, generator, context, List.of("id", "part"), 2, 5));
        assertThrows(UnsupportedOperationException.class, () -> AuditKeySnapshot.readGroupedComposite(
                connection, generator, context, List.of("id", "part"), "region", 2, 2, 5));
        verifyNoInteractions(connection);
    }

    @Test
    void groupedReadUsesTwoBoundedProjectionsOnePredicateAndNativeNumericOrder() throws Exception {
        signedGroup();
        when(rows.getLong(5)).thenReturn(9L, 10L);
        var result = readGrouped(2, 2);
        assertTrue(result.complete()); assertEquals(2, result.groups().size());
        assertEquals(AuditKeySnapshot.MYSQL_INTEGER_GROUP_ENCODING, result.groupEncoding());
        assertEquals(Types.BIGINT, result.groupJdbcType());
        assertEquals(List.of("9", "10"), result.groups().stream().map(AuditKeySnapshot.Group::key).toList());
        assertTrue(result.groups().stream().allMatch(group -> group.snapshot().complete()));
        assertTrue(result.sql().contains("WHERE (region=1 OR region=2) GROUP BY `region`, `id` ORDER BY 1 ASC, 2 ASC LIMIT 3"));
        assertTrue(result.sql().contains("LEFT(CAST(`k`.`scopeKey` AS CHAR CHARACTER SET ascii), 21)"));
        assertTrue(result.sql().endsWith("ORDER BY k.`scopeKey` ASC, k.`groupKey` ASC"));
        verify(statement, times(1)).executeQuery(anyString());
        verify(statement).setMaxRows(3); verify(statement).setQueryTimeout(5);
        verify(rows).close(); verify(statement).close();
        verify(connection, never()).close(); verify(connection, never()).commit();
        verify(connection, never()).rollback(); verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @Test
    void unsupportedGroupTypeStopsBeforeDecodingAnyPayload() throws Exception {
        var result = readGrouped(2, 2);
        assertFalse(result.complete()); assertTrue(result.groups().isEmpty());
        assertEquals("GROUP_KEY_UNSUPPORTED", result.reasonCode());
        verify(rows, never()).next(); verify(rows, never()).getLong(anyInt());
        verify(rows, never()).getString(anyInt());
        verify(rows, times(2)).close(); verify(statement, times(2)).close();
    }

    @Test
    void groupedOverflowDiscardsAllGroupsRatherThanReturningPartialProof() throws Exception {
        signedGroup(); when(rows.getLong(5)).thenReturn(9L, 10L);
        var result = readGrouped(1, 2);
        assertFalse(result.complete()); assertEquals("KEY_LIMIT_EXCEEDED", result.reasonCode());
        assertTrue(result.groups().isEmpty());
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong(5)).thenReturn(9L, 10L);
        result = readGrouped(2, 1);
        assertFalse(result.complete()); assertEquals("GROUP_LIMIT_EXCEEDED", result.reasonCode());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void nullInstitutionNeverBecomesZeroOrAnEmptyIdentity() throws Exception {
        signedGroup(); when(rows.wasNull()).thenReturn(true);
        var result = readGrouped(2, 2);
        assertFalse(result.complete()); assertEquals("GROUP_KEY_UNSUPPORTED", result.reasonCode());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void unsupportedRowKeyKeepsGroupCountsButCannotProduceKeyProof() throws Exception {
        signedGroup(); when(rows.getLong(5)).thenReturn(9L);
        when(metadata.isSigned(4)).thenReturn(false);
        when(rows.next()).thenReturn(true, true, false, true, true, false);
        var result = readGrouped(2, 2);
        assertTrue(result.complete()); assertEquals(2, result.groups().getFirst().snapshot().total());
        assertFalse(result.groups().getFirst().snapshot().complete());
        assertNull(result.groups().getFirst().snapshot().keyHash());
        verify(rows, never()).getLong(1); verify(rows, never()).getBytes(1);
        verify(statement, times(2)).executeQuery(anyString());
    }

    @Test
    void groupedSqlTimeoutPropagatesAndClosesOwnedResources() throws Exception {
        var timeout = new SQLTimeoutException("synthetic timeout");
        when(statement.executeQuery(anyString())).thenThrow(timeout);
        assertSame(timeout, assertThrows(SQLTimeoutException.class, () -> readGrouped(2, 2)));
        verify(statement).close(); verify(connection, never()).close();
    }

    @Test
    void groupedInvalidIdentifiersBoundsAndUnverifiedProductsFailBeforeJdbc() {
        for (String field : List.of("region+1", "region;SELECT 1", "a".repeat(65))) {
            assertThrows(UnsupportedOperationException.class, () -> AuditKeySnapshot.readGrouped(connection,
                    generator, context, "id", field, 2, 2, 5));
        }
        assertThrows(IllegalArgumentException.class, () -> readGrouped(0, 2));
        assertThrows(IllegalArgumentException.class, () -> readGrouped(2, 0));
        context.setProductType(ProductTypeEnum.UNKNOWN);
        assertThrows(UnsupportedOperationException.class, () -> readGrouped(2, 2));
        verifyNoInteractions(connection);
    }

    private void signedGroup() throws SQLException {
        when(metadata.isSigned(6)).thenReturn(true);
        when(metadata.getColumnType(6)).thenReturn(Types.BIGINT);
        when(metadata.getColumnTypeName(6)).thenReturn("BIGINT");
    }

    @Test
    void textFallbackUsesOnlyItsOwnSingleQueryAndExactGrouping() throws Exception {
        when(metadata.getColumnType(6)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(6)).thenReturn("VARCHAR");
        when(rows.getBytes(5)).thenReturn("A".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "a ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result = readGrouped(2, 2);
        assertTrue(result.complete()); assertEquals(AuditKeySnapshot.MYSQL_TEXT_GROUP_ENCODING, result.groupEncoding());
        assertEquals(List.of("A", "a "), result.groups().stream().map(AuditKeySnapshot.Group::key).toList());
        assertTrue(result.sql().contains("CHAR(512) CHARACTER SET ascii"));
        assertTrue(result.sql().contains("NULLIF(`k`.`key0`, `k`.`key0`)"));
        assertTrue(result.sql().contains("WHERE (region=1 OR region=2)"));
        assertTrue(result.sql().endsWith("ORDER BY k.`scopeKey` ASC, k.`key1` ASC"));
        assertEquals(1, result.groups().getFirst().snapshot().total());
        verify(rows, times(2)).getLong(2);
        verify(statement, times(2)).executeQuery(anyString());
        verify(connection, never()).close(); verify(connection, never()).commit();
    }

    @Test
    void malformedUtf8IsAnErrorNotAReplacementCharacterIdentity() throws Exception {
        when(metadata.getColumnType(6)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(6)).thenReturn("VARCHAR");
        when(rows.getBytes(5)).thenReturn(new byte[]{(byte) 0xc3, 0x28});
        var error = assertThrows(SQLException.class, () -> readGrouped(2, 2));
        assertEquals("22021", error.getSQLState());
        verify(rows, times(2)).close(); verify(statement, times(2)).close();
    }

    @Test
    void oversizedTextPayloadDiscardsEveryGroup() throws Exception {
        when(metadata.getColumnType(6)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(6)).thenReturn("VARCHAR");
        when(rows.getBytes(5)).thenReturn(new byte[257]);
        var result = readGrouped(2, 2);
        assertFalse(result.complete()); assertTrue(result.groups().isEmpty());
        assertNull(result.groupEncoding());
    }

    private AuditKeySnapshot.Grouped readGrouped(int keys, int groups) throws SQLException {
        var database = mock(DatabaseMysqlImpl.class, CALLS_REAL_METHODS);
        {
            doReturn(database).when(context.products()).metadata(ProductTypeEnum.MYSQL);
            return AuditKeySnapshot.readGrouped(connection, generator, context, "id", "region", keys, groups, 5);
        }
    }

    @Test
    void invalidColumnsAndLimitsFailBeforeJdbc() {
        for (String column : List.of("id; DELETE FROM t", "id+1", "a".repeat(65))) {
            assertThrows(UnsupportedOperationException.class, () -> AuditKeySnapshot.read(connection,
                    generator, context, column, 2, 5));
        }
        assertThrows(IllegalArgumentException.class, () -> AuditKeySnapshot.read(connection,
                generator, context, "id", 0, 5));
        assertThrows(IllegalArgumentException.class, () -> AuditKeySnapshot.read(connection,
                generator, context, "id", 2, 0));
        verifyNoInteractions(connection);
    }

    @Test
    void encodingSupportRequiresBothProductAndNativeType() {
        assertTrue(AuditKeySnapshot.supportsEncoding("MYSQL", AuditKeySnapshot.ALGORITHM, Types.BIGINT));
        assertTrue(AuditKeySnapshot.supportsEncoding("MARIADB", AuditKeySnapshot.ALGORITHM, Types.BIGINT));
        assertTrue(AuditKeySnapshot.supportsEncoding("TIDB", AuditKeySnapshot.ALGORITHM, Types.BIGINT));
        assertFalse(AuditKeySnapshot.supportsEncoding("UNKNOWN", AuditKeySnapshot.ALGORITHM, Types.BIGINT));
        assertFalse(AuditKeySnapshot.supportsEncoding("MYSQL", AuditKeySnapshot.TYPED_ALGORITHM, Types.VARCHAR));
        assertFalse(AuditKeySnapshot.supportsEncoding("MYSQL", AuditKeySnapshot.COMPOSITE_ALGORITHM, Types.STRUCT));
        assertFalse(AuditKeySnapshot.supportsEncoding(null, AuditKeySnapshot.ALGORITHM, Types.BIGINT));
        assertTrue(AuditKeySnapshot.supportsEncoding("MYSQL", AuditKeySnapshot.MYSQL_TEXT_ALGORITHM, Types.VARCHAR));
        assertTrue(AuditKeySnapshot.supportsEncoding("MYSQL", AuditKeySnapshot.MYSQL_TEXT_ALGORITHM, Types.LONGVARCHAR));
        assertFalse(AuditKeySnapshot.supportsEncoding("POSTGRESQL", AuditKeySnapshot.MYSQL_TEXT_ALGORITHM, Types.VARCHAR));
        assertFalse(AuditKeySnapshot.supportsEncoding("MARIADB", AuditKeySnapshot.MYSQL_TEXT_ALGORITHM, Types.VARCHAR));
        assertFalse(AuditKeySnapshot.supportsEncoding("MYSQL", AuditKeySnapshot.MYSQL_TEXT_ALGORITHM, Types.BIGINT));
        assertFalse(AuditKeySnapshot.supportsEncoding("MYSQL", AuditKeySnapshot.MYSQL_TEXT_ALGORITHM, Types.OTHER));
    }

    private AuditKeySnapshot read() throws SQLException {
        // 不要求本模块安装真实 JDBC 驱动；只替代工厂构造，投影使用 DB Switch 的真实实现。
        var database = mock(DatabaseMysqlImpl.class, CALLS_REAL_METHODS);
        {
            doReturn(database).when(context.products()).metadata(ProductTypeEnum.MYSQL);
            return AuditKeySnapshot.read(connection, generator, context, "id", 2, 5);
        }
    }

    @Test
    void textKeyFallbackDiscardsFirstCountsAndPreservesVersionedJsonAndOwnership() throws Exception {
        when(metadata.getColumnType(4)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(4)).thenReturn("VARCHAR");
        when(rows.getLong(2)).thenReturn(7L);
        var textRows = mock(ResultSet.class);
        when(statement.executeQuery(anyString())).thenReturn(rows, textRows);
        when(textRows.getMetaData()).thenReturn(metadata);
        when(textRows.next()).thenReturn(true, true, false);
        when(textRows.getLong(2)).thenReturn(1L);
        when(textRows.getLong(3)).thenReturn(1L, 0L);
        when(textRows.getBytes(1)).thenReturn(new byte[]{65}, new byte[]{97});
        var result = read();
        assertTrue(result.complete()); assertEquals(2, result.total()); assertEquals(1, result.fail());
        assertEquals(AuditKeySnapshot.MYSQL_TEXT_ALGORITHM, result.algorithm());
        // Protocol golden vector: ASCII domain + big-endian JDBC VARCHAR + framed UTF-8 "A", "a".
        assertEquals("26c3ed3d7660450a0796373d73d5270481964d6afd4bb788b84adf3c225e1ad7", result.keyHash());
        assertTrue(result.sql().contains("COLLATE ascii_bin"));
        assertTrue(result.sql().contains("NULLIF(`k`.`key0`, `k`.`key0`)"));
        assertTrue(result.sql().endsWith("ORDER BY k.`scopeKey` ASC"));
        assertEquals(result, cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(result), AuditKeySnapshot.class));
        verify(rows, never()).getBytes(1); verify(textRows, never()).getLong(1);
        verify(rows).close(); verify(textRows).close(); verify(statement, times(2)).close();
        verify(statement, times(2)).setMaxRows(3); verify(statement, times(2)).setQueryTimeout(5);
        verify(connection, never()).close(); verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test
    void legacyJsonWithoutEncodingKeepsItsOriginalProtocol() {
        var read = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(
                "{\"sql\":\"sample\",\"total\":1,\"fail\":0,\"keyCount\":1,\"jdbcType\":12,"
                        + "\"keyHash\":\"legacy\",\"complete\":true}", AuditKeySnapshot.class);
        assertNull(read.encoding()); assertEquals(AuditKeySnapshot.TYPED_ALGORITHM, read.algorithm());
        assertEquals(new AuditKeySnapshot("sample", 1, 0, 1, Types.VARCHAR, "legacy", true, null), read);
        assertFalse(AuditKeySnapshot.supportsEncoding("MYSQL", read.algorithm(), read.jdbcType()));
    }

    @Test
    void textFallbackTimeoutDoesNotReuseEarlierCounts() throws Exception {
        when(metadata.getColumnType(4)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(4)).thenReturn("VARCHAR");
        var timeout = new SQLTimeoutException("synthetic second query timeout");
        when(statement.executeQuery(anyString())).thenReturn(rows).thenThrow(timeout);
        assertSame(timeout, assertThrows(SQLTimeoutException.class, this::read));
        verify(rows).close(); verify(statement, times(2)).close();
        verify(connection, never()).close(); verify(connection, never()).commit();
    }
}
