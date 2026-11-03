package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.config.YudaoQualityAutoConfiguration;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuditKeySnapshotTest {
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final AuditRuleContext context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NUMERIC_RANGE_VALIDATION");

    @BeforeEach void setup() throws Exception {
        context.setProductType(ProductTypeEnum.POSTGRESQL); context.setTableName("orders");
        context.setColumn("amount"); context.setConfig(Map.of("minValue", 10));
        context.setWhereClause("region=1 OR region=2");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getInt(4)).thenReturn(Types.BIGINT);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong(1)).thenReturn(1L, Long.MAX_VALUE);
        when(rows.getLong(2)).thenReturn(1L); when(rows.getLong(3)).thenReturn(0L);
    }

    @Test void boundedQueryHasStablePredicateAndClosesOnlyOwnedResources() throws Exception {
        var result = read();
        assertThat(result.complete()).isTrue(); assertThat(result.total()).isEqualTo(2);
        assertThat(result.keyHash()).matches("[0-9a-f]{64}");
        assertThat(result.sql()).contains("WHERE (region=1 OR region=2) GROUP BY id ORDER BY 1 ASC LIMIT 3");
        assertThat(result.sql()).doesNotContain("convert_to", "uuid_send");
        assertThat(result.algorithm()).isEqualTo(AuditKeySnapshot.ALGORITHM);
        verify(statement, times(1)).executeQuery(anyString());
        verify(statement).setMaxRows(3); verify(statement).setQueryTimeout(30);
        verify(rows).close(); verify(statement).close(); verify(connection, never()).close();
        verify(connection, never()).commit(); verify(connection, never()).rollback();
        verify(connection, never()).setAutoCommit(anyBoolean());
        assertThat(JsonUtils.parseObject(JsonUtils.toJsonString(result), AuditKeySnapshot.class)).isEqualTo(result);
    }

    @Test void overflowNeverClaimsPartialCountsAreComplete() throws Exception {
        when(rows.next()).thenReturn(true);
        var result = read();
        assertThat(result.complete()).isFalse(); assertThat(result.total()).isEqualTo(-1);
        assertThat(result.keyHash()).isNull(); assertThat(result.reasonCode()).isEqualTo("KEY_LIMIT_EXCEEDED");
    }

    @Test void duplicateKeysDoNotProduceProofEvenWithCompleteCounts() throws Exception {
        when(rows.getLong(2)).thenReturn(2L);
        var result = read();
        assertThat(result.total()).isEqualTo(4); assertThat(result.complete()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("KEY_NOT_UNIQUE_OR_NULL");
    }

    @Test void textKeyCannotBeSilentlyConvertedToInteger() throws Exception {
        when(rows.getInt(4)).thenReturn(0);
        when(rows.next()).thenReturn(true, true, false, true, true, false);
        var result = read();
        assertThat(result.reasonCode()).isEqualTo("KEY_TYPE_UNSUPPORTED");
        verify(rows, never()).getLong(1);
    }

    @Test void typedFallbackUsesItsOwnCountsAndNeverCoercesTextKeys() throws Exception {
        ResultSet typed = typedRows(Types.LONGVARCHAR, "0009", "9");
        when(rows.getLong(3)).thenReturn(1L); // 第一遍数据已发生变化，不能沿用旧计数。
        var result = read();
        assertThat(result.complete()).isTrue(); assertThat(result.fail()).isZero();
        assertThat(result.jdbcType()).isEqualTo(Types.LONGVARCHAR);
        assertThat(result.sql()).contains("convert_to", "octet_length", "ORDER BY 1 ASC");
        assertThat(JsonUtils.parseObject(JsonUtils.toJsonString(result), AuditKeySnapshot.class)).isEqualTo(result);
        verify(typed, never()).getLong(1); verify(rows).close(); verify(typed).close();
        verify(statement, times(2)).close(); verify(connection, never()).close();
        verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @Test void typedDigestIsLengthFramedAndTypeBound() throws Exception {
        typedRows(Types.LONGVARCHAR, "1", "23");
        var first = read();
        typedRows(Types.LONGVARCHAR, "12", "3");
        var second = read();
        typedRows(Types.VARCHAR, "1", "23");
        var varchar = read();
        assertThat(first.complete()).isTrue(); assertThat(second.complete()).isTrue();
        assertThat(first.keyHash()).isNotEqualTo(second.keyHash()).isNotEqualTo(varchar.keyHash());
    }

    @Test void typedKeysMustBeStrictlyByteSorted() throws Exception {
        typedRows(Types.LONGVARCHAR, "z", "a");
        var result = read();
        assertThat(result.complete()).isFalse(); assertThat(result.keyHash()).isNull();
        assertThat(result.reasonCode()).isEqualTo("KEY_NOT_UNIQUE_OR_NULL");
    }

    @Test void typedDigestDoesNotNormalizeNumericCaseOrUnicodeIdentity() throws Exception {
        for (String[] pair : new String[][]{{"0009", "9"}, {"A", "a"}, {"é", "e\u0301"}}) {
            typedRows(Types.LONGVARCHAR, "", pair[0]); var before = read();
            typedRows(Types.LONGVARCHAR, "", pair[1]); var after = read();
            assertThat(before.complete()).isTrue(); assertThat(after.complete()).isTrue();
            assertThat(before.keyHash()).isNotEqualTo(after.keyHash());
        }
    }

    @Test void typedQueryFailureDoesNotReuseFirstReadCounts() throws Exception {
        when(rows.getInt(4)).thenReturn(0);
        when(statement.executeQuery(anyString())).thenReturn(rows).thenThrow(new SQLTimeoutException("timeout"));
        assertThatThrownBy(this::read).isInstanceOf(SQLTimeoutException.class);
        verify(statement, times(2)).close(); verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test void typedGroupedFallbackDoesNotMixCountsOrScopesFromFirstRead() throws Exception {
        ResultSet typed = typedRows(Types.LONGVARCHAR, "a", "z");
        when(rows.getString(5)).thenReturn("old"); when(rows.getLong(3)).thenReturn(1L);
        when(typed.getString(5)).thenReturn("0009", "9");
        var result = readGrouped(2, 2);
        assertThat(result.complete()).isTrue();
        assertThat(result.groups()).extracting(AuditKeySnapshot.Group::key).containsExactly("0009", "9");
        assertThat(result.groups()).allSatisfy(group -> {
            assertThat(group.snapshot().complete()).isTrue(); assertThat(group.snapshot().fail()).isZero();
            assertThat(group.snapshot().algorithm()).isEqualTo(AuditKeySnapshot.TYPED_ALGORITHM);
        });
        assertThat(JsonUtils.parseObject(JsonUtils.toJsonString(result), AuditKeySnapshot.Grouped.class)).isEqualTo(result);
    }

    @Test void typedGroupedOverflowReturnsNoPartialKeyProof() throws Exception {
        ResultSet typed = typedRows(Types.LONGVARCHAR, "a", "z");
        when(rows.getString(5)).thenReturn("9"); when(typed.getString(5)).thenReturn("9");
        when(typed.next()).thenReturn(true);
        var result = readGrouped(2, 2);
        assertThat(result.complete()).isFalse(); assertThat(result.groups()).isEmpty();
        assertThat(result.reasonCode()).isEqualTo("KEY_LIMIT_EXCEEDED");
    }

    @Test void typedInvalidPayloadIsNotHashed() throws Exception {
        for (byte[] invalid : new byte[][]{null, new byte[AuditKeySnapshot.MAX_KEY_BYTES + 1]}) {
            var typed = typedRows(Types.LONGVARCHAR, "a", "z");
            when(typed.getBytes(1)).thenReturn(invalid);
            var result = read();
            assertThat(result.complete()).isFalse(); assertThat(result.keyHash()).isNull();
            assertThat(result.reasonCode()).isEqualTo("KEY_NULL_OR_TOO_LONG");
        }
        typedRows(Types.OTHER, "not-a-uuid", "not-a-uuid");
        assertThat(read().complete()).isFalse();
    }

    private ResultSet typedRows(int type, String first, String second) throws Exception {
        ResultSet typed = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, true, false); when(rows.getInt(4)).thenReturn(0);
        when(statement.executeQuery(anyString())).thenReturn(rows, typed);
        when(typed.next()).thenReturn(true, true, false); when(typed.getInt(4)).thenReturn(type);
        when(typed.getLong(2)).thenReturn(1L); when(typed.getLong(3)).thenReturn(0L);
        when(typed.getBytes(1)).thenReturn(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
        return typed;
    }

    @Test void timeoutPropagatesAndClosesStatementWithoutChangingTransaction() throws Exception {
        when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("timeout"));
        assertThatThrownBy(this::read).isInstanceOf(SQLTimeoutException.class);
        verify(statement).close(); verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @Test void unsupportedProductColumnAndAggregateStrategyDoNotExecuteSql() {
        context.setProductType(ProductTypeEnum.UNKNOWN);
        assertThatThrownBy(this::read).isInstanceOf(UnsupportedOperationException.class);
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        assertThatThrownBy(() -> AuditKeySnapshot.read(connection, generator, context, "id; DELETE", 2, 30))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> AuditKeySnapshot.read(connection,
                new AuditSqlRegistry().getGenerator("UNIQUE_VALIDATION"), context, "id", 2, 30))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(connection);
    }

    @Test void autoConfigurationKeepsRegistriesAvailableAndAllowsOverrides() {
        try (var app = new AnnotationConfigApplicationContext(YudaoQualityAutoConfiguration.class)) {
            assertThat(app.getBean(AuditSqlRegistry.class).getGenerator("NUMERIC_RANGE_VALIDATION")).isNotNull();
            assertThat(app.getBean(CleanFunctionRegistry.class)).isNotNull();
        }
        try (var app = new AnnotationConfigApplicationContext()) {
            AuditSqlRegistry custom = new AuditSqlRegistry();
            app.registerBean("customRegistry", AuditSqlRegistry.class, () -> custom);
            app.register(YudaoQualityAutoConfiguration.class); app.refresh();
            assertThat(app.getBeansOfType(AuditSqlRegistry.class)).hasSize(1);
            assertThat(app.getBean(AuditSqlRegistry.class)).isSameAs(custom);
        }
    }

    private AuditKeySnapshot read() throws SQLException {
        return AuditKeySnapshot.read(connection, generator, context, "id", 2, 30);
    }

    @Test void compositeKeysUseOneQueryAndFrameEveryTypeAndLength() throws Exception {
        compositeRows();
        var result = AuditKeySnapshot.readComposite(connection, generator, context, List.of("tenant_code", "id"), 2, 30);
        assertThat(result.complete()).isTrue(); assertThat(result.total()).isEqualTo(2);
        assertThat(result.algorithm()).isEqualTo(AuditKeySnapshot.COMPOSITE_ALGORITHM);
        assertThat(result.sql()).contains("GROUP BY tenant_code, id LIMIT 3", "pg_catalog.int4send(2)",
                "pg_catalog.int4send(p.type0)", "pg_catalog.int4send(p.type1)", "pg_catalog.int8send",
                "WHERE (region=1 OR region=2)", "<= 256", "ORDER BY 1 ASC");
        verify(statement).executeQuery(anyString()); verify(statement).close(); verify(rows).close();
        verify(connection, never()).close(); verify(connection, never()).commit(); verify(connection, never()).rollback();
        assertThat(JsonUtils.parseObject(JsonUtils.toJsonString(result), AuditKeySnapshot.class)).isEqualTo(result);
    }

    @Test void legacySingleKeySqlRemainsByteForByteCompatible() throws Exception {
        String suffix = " FROM (" + generator.buildGroupCountSql(context, "id") + " ORDER BY 1 ASC LIMIT 3) k ORDER BY 1 ASC";
        String integer = "SELECT CASE WHEN pg_catalog.pg_typeof(k.\"groupKey\")::text IN ('smallint','integer','bigint') "
                + "THEN k.\"groupKey\"::text::bigint ELSE NULL END AS \"keyValue\", k.\"totalCount\", k.\"errorCount\", "
                + "CASE pg_catalog.pg_typeof(k.\"groupKey\")::text WHEN 'smallint' THEN 5 WHEN 'integer' THEN 4 "
                + "WHEN 'bigint' THEN -5 ELSE 0 END AS \"keyType\"";
        assertThat(read().sql()).isEqualTo(integer + suffix);
        typedRows(Types.LONGVARCHAR, "a", "b");
        String typed = "SELECT CASE WHEN pg_catalog.pg_typeof(k.\"groupKey\")::text IN ('text','character varying') THEN CASE WHEN "
                + "pg_catalog.octet_length(pg_catalog.convert_to(k.\"groupKey\"::text, 'UTF8')) <= 256 "
                + "THEN pg_catalog.convert_to(k.\"groupKey\"::text, 'UTF8') ELSE NULL END "
                + "WHEN pg_catalog.pg_typeof(k.\"groupKey\")::text = 'uuid' THEN pg_catalog.uuid_send(k.\"groupKey\"::text::uuid) "
                + "ELSE NULL END AS \"keyValue\", k.\"totalCount\", k.\"errorCount\", "
                + "CASE pg_catalog.pg_typeof(k.\"groupKey\")::text WHEN 'text' THEN -1 WHEN 'character varying' THEN 12 "
                + "WHEN 'uuid' THEN 1111 ELSE 0 END AS \"keyType\"";
        assertThat(read().sql()).isEqualTo(typed + suffix);
    }

    @Test void compositeGroupScanKeepsIndependentScopesAndSharedBudget() throws Exception {
        compositeRows(); when(rows.getString(5)).thenReturn("0009", "9");
        var result = AuditKeySnapshot.readGroupedComposite(connection, generator, context,
                List.of("source_id", "id"), "region", 2, 2, 30);
        assertThat(result.complete()).isTrue(); assertThat(result.groups()).hasSize(2);
        assertThat(result.sql()).contains("GROUP BY region, source_id, id LIMIT 3", "ORDER BY 5 ASC, 1 ASC");
        assertThat(result.groups()).allSatisfy(group -> assertThat(group.snapshot().complete()).isTrue());
        when(rows.next()).thenReturn(true);
        var overflow = AuditKeySnapshot.readGroupedComposite(connection, generator, context,
                List.of("source_id", "id"), "region", 2, 2, 30);
        assertThat(overflow.groups()).isEmpty(); assertThat(overflow.reasonCode()).isEqualTo("KEY_LIMIT_EXCEEDED");
    }

    @Test void invalidCompositeDefinitionsNeverReachJdbc() {
        for (List<String> columns : List.of(List.<String>of(), List.of("id"), List.of("id", "ID"),
                List.of("id", "x; DELETE"), List.of("id", "a".repeat(64)), List.of("a", "b", "c", "d", "e"),
                java.util.Arrays.asList("id", null))) {
            assertThat(AuditKeySnapshot.validCompositeColumns(columns)).isFalse();
            assertThatThrownBy(() -> AuditKeySnapshot.readComposite(connection, generator, context, columns, 2, 30))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        verifyNoInteractions(connection);
    }

    @Test void compositeNullDuplicateOversizedOrUnknownTypeCannotProduceProof() throws Exception {
        compositeRows(); when(rows.getBytes(1)).thenReturn((byte[]) null);
        assertThat(readComposite().reasonCode()).isEqualTo("KEY_NULL_OR_TOO_LONG");
        compositeRows(); when(rows.getLong(2)).thenReturn(2L);
        assertThat(readComposite().reasonCode()).isEqualTo("KEY_NOT_UNIQUE_OR_NULL");
        compositeRows(); when(rows.getBytes(1)).thenReturn(new byte[257]);
        assertThat(readComposite().reasonCode()).isEqualTo("KEY_NULL_OR_TOO_LONG");
        compositeRows(); when(rows.getInt(4)).thenReturn(0);
        assertThat(readComposite().reasonCode()).isEqualTo("KEY_TYPE_UNSUPPORTED");
    }

    @Test void compositeScanTimeoutDoesNotChangeCallerTransaction() throws Exception {
        when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("timeout"));
        assertThatThrownBy(this::readComposite).isInstanceOf(SQLTimeoutException.class);
        verify(statement).close(); verify(connection, never()).setAutoCommit(anyBoolean());
    }

    private AuditKeySnapshot readComposite() throws Exception {
        return AuditKeySnapshot.readComposite(connection, generator, context, List.of("source_id", "id"), 2, 30);
    }

    private void compositeRows() throws Exception {
        when(rows.next()).thenReturn(true, true, false); when(rows.getInt(4)).thenReturn(Types.STRUCT);
        when(rows.getLong(2)).thenReturn(1L); when(rows.getBytes(1)).thenReturn(new byte[]{1}, new byte[]{2});
    }

    @Test void groupedKeysHaveIndependentCountsAndDigestInOneQuery() throws Exception {
        when(rows.getString(5)).thenReturn("0009", "9");
        when(rows.getLong(1)).thenReturn(1L);
        when(rows.getLong(3)).thenReturn(1L, 0L);
        var result = readGrouped(2, 2);
        assertThat(result.complete()).isTrue(); assertThat(result.groups()).hasSize(2);
        assertThat(result.groups()).extracting(AuditKeySnapshot.Group::key).containsExactly("0009", "9");
        assertThat(result.groups()).extracting(group -> group.snapshot().fail()).containsExactly(1L, 0L);
        assertThat(result.groups().getFirst().snapshot().keyHash()).isEqualTo(result.groups().getLast().snapshot().keyHash());
        assertThat(result.sql()).contains("WHERE (region=1 OR region=2) GROUP BY region, id ORDER BY 1 ASC, 2 ASC LIMIT 3");
        assertThat(result.sql()).contains("char_length(k.\"scopeKey\"::text) <= 64");
        verify(statement, times(1)).executeQuery(anyString()); verify(rows).close(); verify(statement).close();
        verify(connection, never()).close(); verify(connection, never()).commit(); verify(connection, never()).rollback();
        assertThat(JsonUtils.parseObject(JsonUtils.toJsonString(result), AuditKeySnapshot.Grouped.class)).isEqualTo(result);
    }

    @Test void groupedScanBudgetIsSharedAndNeverReturnsPartialPrefix() throws Exception {
        when(rows.next()).thenReturn(true); when(rows.getString(5)).thenReturn("9", "10", "11");
        var result = readGrouped(2, 2);
        assertThat(result.complete()).isFalse(); assertThat(result.reasonCode()).isEqualTo("KEY_LIMIT_EXCEEDED");
        assertThat(result.groups()).isEmpty(); verify(statement).setMaxRows(3);
    }

    @Test void tooManyGroupsCannotUseFirstGroupAsCompleteProof() throws Exception {
        when(rows.getString(5)).thenReturn("9", "10");
        var result = readGrouped(2, 1);
        assertThat(result.reasonCode()).isEqualTo("GROUP_LIMIT_EXCEEDED"); assertThat(result.groups()).isEmpty();
    }

    @Test void invalidGroupIdentityCannotBeSilentlyDiscarded() throws Exception {
        for (String value : new String[]{null, "", " ", "x".repeat(65)}) {
            when(rows.next()).thenReturn(true); when(rows.getString(5)).thenReturn(value);
            var result = readGrouped(2, 2);
            assertThat(result.reasonCode()).isEqualTo("GROUP_KEY_UNSUPPORTED"); assertThat(result.groups()).isEmpty();
        }
    }

    @Test void duplicateKeysInvalidateOnlyTheirOwnCompleteGroup() throws Exception {
        when(rows.getString(5)).thenReturn("9", "10"); when(rows.getLong(2)).thenReturn(2L, 1L);
        var result = readGrouped(2, 2);
        assertThat(result.complete()).isTrue();
        assertThat(result.groups().getFirst().snapshot().complete()).isFalse();
        assertThat(result.groups().getLast().snapshot().complete()).isTrue();
    }

    @Test void groupedTimeoutClosesResourcesWithoutChangingTransaction() throws Exception {
        when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("timeout"));
        assertThatThrownBy(() -> readGrouped(2, 2)).isInstanceOf(SQLTimeoutException.class);
        verify(statement).close(); verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @Test void unsafeGroupIdentifierOrUnboundedReadIsRejectedBeforeQuery() {
        assertThatThrownBy(() -> AuditKeySnapshot.readGrouped(connection, generator, context,
                "id", "region; DELETE", 2, 2, 30)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> readGrouped(0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> readGrouped(2, 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(connection);
    }

    private AuditKeySnapshot.Grouped readGrouped(int keys, int groups) throws SQLException {
        return AuditKeySnapshot.readGrouped(connection, generator, context, "id", "region", keys, groups, 30);
    }
}
