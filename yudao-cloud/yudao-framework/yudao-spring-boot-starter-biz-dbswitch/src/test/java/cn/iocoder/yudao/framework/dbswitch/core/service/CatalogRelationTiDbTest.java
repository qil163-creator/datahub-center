package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Native TiDB 8.5.6/TiKV catalog name evidence; this does not enable exact query authorization. */
@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/.*")
class CatalogRelationTiDbTest {
    private String schema;
    private Connection connection;
    private MetaDataByJdbcServiceImpl facade;

    @BeforeEach void createOwnedObjects() throws SQLException {
        connection = open();
        schema = "Catalog_" + UUID.randomUUID().toString().replace("-", "");
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("CREATE DATABASE " + quote(schema));
            sql.execute("CREATE TABLE " + table() + "(id INTEGER PRIMARY KEY,Name VARCHAR(64),org_id INTEGER,secret VARCHAR(64))");
            sql.execute("INSERT INTO " + table() + " VALUES (1,'Alice',7,'hidden'),(2,'Bob',8,'hidden'),(3,'Carol',7,'hidden')");
            sql.execute("CREATE VIEW " + quote(schema) + "." + quote("ViewRows") + " AS SELECT * FROM " + table());
        }
        facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.TIDB, DbSwitchProductRegistry.discover(),
                (url, user, password) -> open());
    }

    @AfterEach void releaseOwnedDatabase() throws SQLException {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.setQueryTimeout(10);
            assertTrue(schema.matches("Catalog_[a-f0-9]{32}"));
            sql.execute("DROP DATABASE IF EXISTS " + quote(schema));
        }
    }

    @Test void allCaseAliasesHaveTheSameNativeNamesAndId() throws SQLException {
        CatalogRelationIdentity expected = facade.resolveCatalogRelation(url(), user(), password(), schema, "Orders", 15_000);
        assertEquals(ProductTypeEnum.TIDB, expected.product());
        assertEquals(schema, expected.schemaName());
        assertEquals("Orders", expected.tableName());
        for (String aliasSchema : List.of(schema, schema.toLowerCase(Locale.ROOT), schema.toUpperCase(Locale.ROOT))) {
            for (String aliasTable : List.of("Orders", "orders", "ORDERS")) {
                assertEquals(expected, facade.resolveCatalogRelation(url(), user(), password(), aliasSchema, aliasTable, 15_000));
            }
        }
        assertFalse(RestrictedSelectGuard.supportsExactBinding(ProductTypeEnum.TIDB));
    }

    @Test void droppedAndRecreatedTableDoesNotReuseNativeObjectId() throws SQLException {
        var before = facade.resolveCatalogRelation(url(), user(), password(), schema, "orders", 15_000);
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("DROP TABLE " + table());
            sql.execute("CREATE TABLE " + table() + "(id INTEGER PRIMARY KEY)");
        }
        var after = facade.resolveCatalogRelation(url(), user(), password(), schema, "orders", 15_000);
        assertEquals(before.schemaName(), after.schemaName());
        assertEquals(before.tableName(), after.tableName());
        assertNotEquals(before.objectId(), after.objectId());
    }

    @Test void viewAndAbsentObjectCannotReturnAnInputSpellingFallback() {
        for (String name : List.of("ViewRows", "Absent")) {
            assertThrows(IllegalStateException.class,
                    () -> facade.resolveCatalogRelation(url(), user(), password(), schema, name, 15_000));
        }
    }

    @Test void catalogBoundProjectionExecutesOnNativeTiDbAndKeepsParameterOrderAndLimits() throws SQLException {
        var identity = facade.resolveCatalogRelation(url(), user(), password(), schema, "orders", 15_000);
        var bound = RestrictedSelectGuard.bindToCatalogRelation(
                "select o.* from " + schema.toLowerCase(Locale.ROOT) + ".orders o where o.name=? or o.id=? order by o.id limit 1",
                schema, identity);
        var columns = facade.queryExactTableColumns(url(), user(), password(), identity.schemaName(), identity.tableName(), null, 15_000)
                .stream().map(cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription::getFieldName).toList();
        assertEquals(List.of("id", "Name", "org_id", "secret"), columns);
        var projection = RestrictedSelectGuard.restrictProjection(ProductTypeEnum.TIDB, bound.getNormalizedSql(),
                identity.schemaName(), identity.tableName(), columns, Set.of("id", "Name"));
        assertFalse(projection.sql().contains("secret"));
        try (var sql = connection.prepareStatement(projection.sql())) {
            sql.setQueryTimeout(10);
            sql.setString(1, "Carol");
            sql.setInt(2, 2);
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt("id"));
                assertEquals("Bob", rows.getString("Name"));
                assertFalse(rows.next());
            }
        }
        for (String denied : List.of("select secret from orders", "select id from orders where secret='hidden'",
                "select id from orders order by SECRET")) {
            var canonical = RestrictedSelectGuard.bindToCatalogRelation(denied, schema, identity);
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(ProductTypeEnum.TIDB,
                    canonical.getNormalizedSql(), identity.schemaName(), identity.tableName(), columns, Set.of("id", "Name")));
        }
    }

    @Test void nativeCatalogTablePickerPreservesNamesIsBoundedAndExcludesViews() throws SQLException {
        for (String alias : List.of(schema, schema.toLowerCase(Locale.ROOT), schema.toUpperCase(Locale.ROOT))) {
            var tables = facade.queryExactTableList(url(), user(), password(), alias, 1, 15_000);
            assertEquals(1, tables.size());
            assertEquals(schema, tables.getFirst().getSchemaName());
            assertEquals("Orders", tables.getFirst().getTableName());
            assertEquals(facade.resolveCatalogRelation(url(), user(), password(), schema, "Orders", 15_000).objectId(),
                    tables.getFirst().getCatalogObjectId());
            assertFalse(tables.getFirst().isViewTable());
        }
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("CREATE TABLE " + quote(schema) + "." + quote("Another") + "(id INTEGER)");
        }
        assertThrows(IllegalArgumentException.class,
                () -> facade.queryExactTableList(url(), user(), password(), schema, 1, 15_000));
        assertThrows(IllegalStateException.class,
                () -> facade.queryExactTableList(url(), user(), password(), "Absent_" + schema, 10, 15_000));
    }

    @Test void exactColumnsResolveAliasesAndRetainPhysicalColumnSpellingAndProjectionOrder() {
        var all = facade.queryExactTableColumns(url(), user(), password(), schema.toUpperCase(Locale.ROOT), "ORDERS", null, 15_000);
        assertEquals(List.of("id", "Name", "org_id", "secret"), all.stream().map(c -> c.getFieldName()).toList());
        var selected = facade.queryExactTableColumns(url(), user(), password(), schema, "orders", List.of("NAME", "ID"), 15_000);
        assertEquals(List.of("Name", "id"), selected.stream().map(c -> c.getFieldName()).toList());
        assertThrows(IllegalStateException.class,
                () -> facade.queryExactTableColumns(url(), user(), password(), schema, "ViewRows", null, 15_000));
        assertThrows(IllegalArgumentException.class,
                () -> facade.queryExactTableColumns(url(), user(), password(), schema, "Orders", List.of(), 15_000));
    }

    @Test void columnsBoundToAnEarlierCatalogObservationRejectSameNameRecreation() throws SQLException {
        var observed = facade.resolveCatalogRelation(url(), user(), password(), schema, "Orders", 15_000);
        assertEquals(4, facade.queryExactTableColumns(url(), user(), password(), observed, null, 15_000).size());
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("DROP VIEW " + quote(schema) + "." + quote("ViewRows"));
            sql.execute("DROP TABLE " + table());
            sql.execute("CREATE TABLE " + table() + "(id INTEGER PRIMARY KEY,Name VARCHAR(64),org_id INTEGER,secret VARCHAR(64))");
        }
        assertThrows(IllegalStateException.class,
                () -> facade.queryExactTableColumns(url(), user(), password(), observed, null, 15_000));
    }

    @Test void catalogRowConditionsKeepUserOrGroupedAndHiddenPolicyColumnsOutOfProjection() throws SQLException {
        var relation = facade.resolveCatalogRelation(url(), user(), password(), schema, "ORDERS", 15_000);
        var columns = facade.queryExactTableColumns(url(), user(), password(), relation, null, 15_000)
                .stream().map(c -> c.getFieldName()).toList();
        var restricted = RestrictedSelectGuard.restrictCatalogProjectionWithRowFilters(
                "select NAME from orders where id=1 or ID=2 order by id", schema, relation, columns,
                Set.of("id", "Name"), List.of("org_id=7", "secret='hidden'"));
        assertEquals(Set.of("Name"), restricted.selectedColumns());
        try (var sql = connection.prepareStatement(restricted.sql())) {
            sql.setQueryTimeout(10);
            try (var rows = sql.executeQuery()) {
                assertEquals(1, rows.getMetaData().getColumnCount());
                assertEquals("Name", rows.getMetaData().getColumnName(1));
                assertTrue(rows.next()); assertEquals("Alice", rows.getString(1)); assertFalse(rows.next());
            }
        }
        assertEquals(relation, facade.resolveCatalogRelation(url(), user(), password(), schema, "Orders", 15_000));
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(
                ProductTypeEnum.TIDB, "select Name from orders", schema, "Orders", columns, Set.of("Name"), List.of()));
    }

    @Test void actualPhysicalColumnKeysRejectCaseAliasesAndPredicateOrderOrSelectAliasEscapes() throws SQLException {
        var relation = facade.resolveCatalogRelation(url(), user(), password(), schema, "Orders", 15_000);
        var columns = facade.queryExactTableColumns(url(), user(), password(), relation, null, 15_000)
                .stream().map(c -> c.getFieldName()).toList();
        assertEquals(List.of("id", "Name", "org_id", "secret"), columns);
        for (String sql : List.of("select SECRET as Name from orders", "select Name as SECRET from orders",
                "select Name from orders where SeCrEt='hidden'", "select Name from orders order by SECRET")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictCatalogProjectionWithRowFilters(
                    sql, schema, relation, columns, Set.of("Name", "id"), List.of("org_id=7")));
        }
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictCatalogProjectionWithRowFilters(
                "select name from orders", schema, relation, columns, Set.of("NAME"), List.of()));
    }

    private String table() { return quote(schema) + "." + quote("Orders"); }
    private static String quote(String name) { return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TIDB, name); }
    private static String url() { return System.getProperty("datahub.test.tidb.url"); }
    private static String user() { return System.getProperty("datahub.test.tidb.user", "root"); }
    private static String password() {
        String value = System.getenv("DATAHUB_TIDB_TEST_PASSWORD");
        return value == null ? "" : value;
    }
    private static Connection open() throws SQLException { return DriverManager.getConnection(url(), user(), password()); }
}
