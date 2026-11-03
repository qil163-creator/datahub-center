package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TidbCatalogRelationTest {
    @ParameterizedTest @CsvSource({"tenant,orders", "TENANT,ORDERS", "Tenant,Orders"})
    void aliasesResolveToCatalogSpellingOnTheSameBorrowedConnection(String schema, String table) throws Exception {
        var fixture = new Fixture();
        var identity = new DatabaseTidbImpl().resolveCatalogRelation(fixture.connection, schema, table, 7);
        assertEquals(ProductTypeEnum.TIDB, identity.product());
        assertEquals("Tenant", identity.schemaName());
        assertEquals("Orders", identity.tableName());
        assertEquals("41", identity.objectId());
        assertFalse(RestrictedSelectGuard.supportsExactBinding(ProductTypeEnum.TIDB),
                "The read API must not enable a business permission route before native binding tests");
        assertEquals(2, fixture.tableReads.get());
        for (var statement : fixture.statements) verify(statement).close();
        for (var rows : fixture.results) verify(rows).close();
        verify(fixture.connection, never()).setAutoCommit(anyBoolean());
        verify(fixture.connection, never()).setReadOnly(anyBoolean());
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).close();
    }

    @ParameterizedTest @ValueSource(strings = {"bad.name", "quoted`name", "name;DROP", "", "中文", "space name"})
    void namesOutsideVerifiedSubsetStopBeforeAnyJdbcCall(String name) {
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> TidbRelationIdentity.resolveCatalogRelation(connection, name, "orders", 5));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> TidbRelationIdentity.resolveCatalogRelation(connection, "tenant", name, 5));
        verifyNoInteractions(connection);
    }

    @Test void invalidTimeoutStopsBeforeJdbcAndInheritedProductsRemainUnsupported() {
        var connection = mock(Connection.class);
        for (int timeout : new int[]{0, -1, 61}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TidbRelationIdentity.resolveCatalogRelation(connection, "tenant", "orders", timeout));
        }
        var inherited = mock(DatabaseTidbImpl.class, CALLS_REAL_METHODS);
        when(inherited.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> inherited.resolveCatalogRelation(connection, "tenant", "orders", 5));
        verifyNoInteractions(connection);
    }

    @Test void recreatedOrRenamedRelationCannotReturnAnEarlierIdentity() throws Exception {
        for (String change : List.of("id", "name", "cluster")) {
            var fixture = new Fixture();
            fixture.change = change;
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> TidbRelationIdentity.resolveCatalogRelation(fixture.connection, "tenant", "orders", 5), change);
            for (var rows : fixture.results) verify(rows).close();
            verify(fixture.connection, never()).close();
        }
    }

    @Test void incompleteOrAmbiguousCatalogRowsNeverBecomeCallerSpellingFallbacks() throws Exception {
        for (String fault : List.of("absent", "duplicate", "view", "invalid_id", "wrong_name", "version")) {
            var fixture = new Fixture();
            fixture.fault = fault;
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> TidbRelationIdentity.resolveCatalogRelation(fixture.connection, "tenant", "orders", 5), fault);
            for (var rows : fixture.results) verify(rows).close();
        }
    }

    @Test void boundedCatalogPickerUsesPreparedSchemaAndLimitAndPreservesBorrowedOwnership() throws Exception {
        var fixture = new Fixture();
        var tables = new DatabaseTidbImpl().queryCatalogTableList(fixture.connection, "TENANT", 10, 7);
        assertEquals(1, tables.size());
        assertEquals("Tenant", tables.getFirst().getSchemaName());
        assertEquals("Orders", tables.getFirst().getTableName());
        assertEquals("41", tables.getFirst().getCatalogObjectId());
        assertEquals("native remarks", tables.getFirst().getRemarks());
        assertEquals(2, fixture.tableReads.get());
        for (var statement : fixture.statements) verify(statement).close();
        for (var rows : fixture.results) verify(rows).close();
        verify(fixture.connection, never()).getMetaData();
        verify(fixture.connection, never()).close();
        verify(fixture.connection, never()).commit();
    }

    @Test void pickerNeverReturnsTruncationDuplicatesOrChangingNativeIds() throws Exception {
        var overBound = new Fixture(); overBound.fault = "picker_overbound";
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseTidbImpl().queryCatalogTableList(overBound.connection, "Tenant", 1, 7));
        for (String fault : List.of("picker_duplicate", "picker_absent_schema", "picker_changed_id")) {
            var fixture = new Fixture(); fixture.fault = fault;
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> new DatabaseTidbImpl().queryCatalogTableList(fixture.connection, "Tenant", 10, 7));
        }
        var connection = mock(Connection.class);
        for (int maximum : new int[]{0, -1, 10_001}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DatabaseTidbImpl().queryCatalogTableList(connection, "Tenant", maximum, 7));
        }
        verifyNoInteractions(connection);
    }

    private static final class Fixture {
        final Connection connection = mock(Connection.class);
        final List<Statement> statements = new ArrayList<>();
        final List<ResultSet> results = new ArrayList<>();
        final AtomicInteger tableReads = new AtomicInteger(), clusterReads = new AtomicInteger();
        String change = "", fault = "";

        Fixture() throws SQLException {
            when(connection.createStatement()).thenAnswer(call -> {
                var statement = mock(Statement.class); statements.add(statement);
                when(statement.executeQuery(anyString())).thenAnswer(query -> {
                    String sql = query.getArgument(0);
                    if (sql.startsWith("SELECT TIDB_VERSION()")) return rows(new String[]{
                            fault.equals("version") ? "Release Version: v8.5.5\nEdition: Community\nStore: tikv"
                                    : "Release Version: v8.5.6\nEdition: Community\nStore: tikv",
                            "tenant", "root@%", "NONE", "2", "", "0", "leader", "0", "", "0", "1"});
                    if (sql.contains("VARIABLE_NAME='cluster_id'")) return rows(new String[]{
                            change.equals("cluster") && clusterReads.incrementAndGet() > 1 ? "222" : "111"});
                    if (sql.startsWith("SHOW CREATE TABLE")) return rows(new String[]{"Orders",
                            fault.equals("view") ? "CREATE VIEW Orders AS SELECT 1" : "CREATE TABLE Orders(id INT)"});
                    throw new AssertionError(sql);
                });
                return statement;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                var statement = mock(PreparedStatement.class); statements.add(statement);
                var parameters = new HashMap<Integer, Object>();
                doAnswer(binding -> { parameters.put(binding.getArgument(0), binding.getArgument(1)); return null; })
                        .when(statement).setString(anyInt(), anyString());
                doAnswer(binding -> { parameters.put(binding.getArgument(0), binding.getArgument(1)); return null; })
                        .when(statement).setInt(anyInt(), anyInt());
                when(statement.executeQuery()).thenAnswer(execute -> {
                    if (sql.contains("information_schema.SCHEMATA")) {
                        assertEquals(Map.of(1, "tenant"), parameters);
                        return fault.equals("picker_absent_schema") ? rows() : rows(new String[]{"Tenant"});
                    }
                    if (sql.contains("TABLE_COMMENT")) {
                        assertEquals("tenant", parameters.get(1));
                        assertEquals(fault.equals("picker_overbound") ? 2 : 11, parameters.get(2));
                        int read = tableReads.incrementAndGet();
                        String id = fault.equals("picker_changed_id") && read > 1 ? "42" : "41";
                        String[] table = {"Tenant", "Orders", id, "native remarks"};
                        if (fault.equals("picker_duplicate")) return rows(table, table);
                        if (fault.equals("picker_overbound")) return rows(table, new String[]{"Tenant", "Other", "43", ""});
                        return rows(table);
                    }
                    assertEquals(Map.of(1, "tenant", 2, "orders"), parameters);
                    if (sql.contains("information_schema.PARTITIONS"))
                        return rows(new String[]{"Tenant", "Orders", null, null, null});
                    assertTrue(sql.contains("information_schema.TABLES"));
                    int observation = tableReads.incrementAndGet();
                    if (fault.equals("absent")) return rows();
                    String[] table = {"Tenant", fault.equals("wrong_name") ? "Denied" :
                            change.equals("name") && observation > 1 ? "ORDERS" : "Orders", "BASE TABLE", "InnoDB",
                            fault.equals("invalid_id") ? "0" : change.equals("id") && observation > 1 ? "42" : "41"};
                    return fault.equals("duplicate") ? rows(table, table) : rows(table);
                });
                return statement;
            });
        }

        ResultSet rows(String[]... values) throws SQLException {
            var rows = mock(ResultSet.class); results.add(rows);
            var index = new AtomicInteger(-1);
            when(rows.next()).thenAnswer(call -> index.incrementAndGet() < values.length);
            when(rows.getString(anyInt())).thenAnswer(call -> {
                if (index.get() < 0 || index.get() >= values.length) throw new SQLException("Read after cursor exhaustion");
                return values[index.get()][call.getArgument(0, Integer.class) - 1];
            });
            when(rows.getInt(anyInt())).thenAnswer(call -> Integer.parseInt(values[index.get()][call.getArgument(0, Integer.class) - 1]));
            return rows;
        }
    }
}
