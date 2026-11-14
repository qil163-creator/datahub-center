package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DorisRelationIdentityTest {
    @Test void nativeCatalogResolutionRetainsFingerprintAndBoundsEveryObservation() throws Exception {
        var fixture = new NativeFixture();
        var relation = DorisRelationIdentity.resolveCatalogRelation(fixture.connection, "warehouse", "orders", 7);
        assertEquals(ProductTypeEnum.DORIS, relation.product());
        assertEquals("warehouse", relation.schemaName());
        assertEquals("orders", relation.tableName());
        assertEquals(DorisRelationIdentity.fingerprint(fixture.connection, "warehouse.orders"), relation.objectId());
        assertTrue(relation.objectId().matches("[0-9a-f]{64}"));
        verify(fixture.connection, atLeastOnce()).prepareStatement(contains("STRCMP(TABLE_NAME,?)=0"));
        verify(fixture.connection, never()).close();
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).setCatalog(any());
    }

    @Test void nativeCatalogRejectsTemporaryShadowsChangedGenerationsAndOtherCatalogs() throws Exception {
        var shadow = new NativeFixture(); shadow.temporary = true;
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> DorisRelationIdentity.resolveCatalogRelation(shadow.connection, "warehouse", "orders", 5));
        var change = new NativeFixture(); change.changePartition = true;
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> DorisRelationIdentity.resolveCatalogRelation(change.connection, "warehouse", "orders", 5));
        var external = new NativeFixture(); external.catalog = "external";
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> DorisRelationIdentity.queryCatalogTableList(external.connection, "warehouse", 10, 5));
    }

    @Test void nativePickerReturnsTheVerifiedGenerationAndRejectsNonordinaryNamesBeforeJdbc() throws Exception {
        var fixture = new NativeFixture();
        var tables = DorisRelationIdentity.queryCatalogTableList(fixture.connection, "warehouse", 10, 5);
        assertEquals(1, tables.size());
        assertEquals("warehouse", tables.getFirst().getSchemaName());
        assertEquals("orders", tables.getFirst().getTableName());
        assertEquals(DorisRelationIdentity.fingerprint(fixture.connection, "warehouse.orders"),
                tables.getFirst().getCatalogObjectId());
        var unused = mock(Connection.class);
        for (String name : List.of("warehouse.orders", "odd name", "`orders`", "orders;select 1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DorisRelationIdentity.resolveCatalogRelation(unused, "warehouse", name, 5));
        }
        assertThrows(IllegalArgumentException.class,
                () -> DorisRelationIdentity.resolveCatalogRelation(unused, "warehouse", "orders", 0));
        verifyNoInteractions(unused);
    }

    /** 仅模拟原生协议响应；真实 Doris SQL 由独立原生用例验证。 */
    private static final class NativeFixture {
        final Connection connection = mock(Connection.class);
        final AtomicInteger partitions = new AtomicInteger();
        boolean temporary, changePartition;
        String catalog = "internal";

        NativeFixture() throws Exception {
            when(connection.createStatement()).thenAnswer(call -> {
                var statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query -> nativeRows(query.getArgument(0)));
                return statement;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String query = call.getArgument(0);
                var statement = mock(PreparedStatement.class);
                when(statement.executeQuery()).thenAnswer(ignored -> nativeRows(query));
                return statement;
            });
        }

        ResultSet nativeRows(String sql) throws Exception {
            Map<Object, Object> values;
            if (sql.equals("SHOW FRONTENDS")) {
                values = Map.of("ClusterId", "123", "Version", "doris-4.0.8", "CurrentConnected", "Yes",
                        "Alive", true, "Join", true);
            } else if (sql.startsWith("SELECT CURRENT_CATALOG()")) {
                values = Map.of(1, catalog, 2, "warehouse", 3, "reader", 4, 0);
            } else if (sql.startsWith("SHOW CREATE TABLE")) {
                values = Map.of(1, "orders", 2, temporary ? "CREATE TEMPORARY TABLE orders(id BIGINT)"
                        : "CREATE TABLE orders(id BIGINT)");
            } else if (sql.startsWith("SELECT TABLE_CATALOG")) {
                values = Map.of(1, "internal", 2, "warehouse", 3, "orders", 4, "BASE TABLE", 5, "Doris");
            } else if (sql.startsWith("SELECT CATALOG_ID")) {
                values = Map.of(1, "0", 2, "23", 3, "41", 4, "warehouse", 5, "orders");
            } else if (sql.startsWith("SHOW PARTITIONS")) {
                values = Map.of("PartitionId", changePartition ? Integer.toString(100 + partitions.incrementAndGet())
                        : "100", "State", "NORMAL");
            } else if (sql.startsWith("SELECT TABLE_SCHEMA,TABLE_NAME")) {
                values = Map.of(1, "warehouse", 2, "orders");
            } else {
                throw new AssertionError("未预期的目录查询：" + sql);
            }
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(anyInt())).thenAnswer(call -> (String) values.get(call.getArgument(0)));
            when(rows.getString(anyString())).thenAnswer(call -> (String) values.get(call.getArgument(0)));
            when(rows.getInt(anyInt())).thenAnswer(call -> values.getOrDefault(call.getArgument(0), 0));
            when(rows.getBoolean(anyString())).thenAnswer(call -> values.getOrDefault(call.getArgument(0), false));
            return rows;
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"sample alias", "a.b.c", "sample;SELECT 1", "sample/*x*/", "sample#x", "\"sample\"", "'sample'", "[sample]"})
    void rejectsSqlInsteadOfSingleRelation(String reference) {
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseRelationReference(
                ProductTypeEnum.DORIS, reference, "schema"));
    }

    @Test
    void quotesExactNamesWithoutEnablingAuthorizationSql() {
        var plan = RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.DORIS, "`Db.X`.`T``.x`", "unused");
        assertEquals("Db.X", plan.getSchemaName());
        assertEquals("T`.x", plan.getTableName());
        assertEquals("SELECT * FROM `Db.X`.`T``.x`", plan.getNormalizedSql());
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseTableReference(ProductTypeEnum.DORIS, "t", "s"));
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseBound(ProductTypeEnum.DORIS, "SELECT * FROM t", "s"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.7.99", "MySQL", "doris-2.1.7", "doris-4.1.0", "doris-4.0.", "doris-4.0.fake", ""})
    void protocolOrUnverifiedVersionDoesNotCountAsProductEvidence(String version) throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("ClusterId")).thenReturn("123");
        when(rows.getString("Version")).thenReturn(version);
        assertThrows(SQLFeatureNotSupportedException.class, () -> DorisRelationIdentity.fingerprint(connection, "sample"));
        verify(statement).setQueryTimeout(5);
        verify(statement).setMaxRows(65);
        verify(statement).close();
        verify(rows).close();
        verify(connection, never()).close();
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void infrastructureFailurePropagatesAndDoesNotBecomeUnsupported() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        SQLException failure = new SQLException("test timeout", "HYT00");
        when(statement.executeQuery("SHOW FRONTENDS")).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class, () -> DorisRelationIdentity.fingerprint(connection, "t")));
        verify(statement).close();
        verify(connection, never()).close();
    }
}
