package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Native evidence before enabling openGauss in any business permission route. */
@EnabledIfSystemProperty(named = "datahub.test.opengauss.url", matches = "jdbc:opengauss://127[.]0[.]0[.]1:[0-9]+/(?:postgres|dbswitch_test)")
@EnabledIfSystemProperty(named = "datahub.test.opengauss.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_OPENGAUSS_TEST_PASSWORD", matches = ".+")
class ExactSelectBindingOpenGaussTest {
    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.OPENGAUSS;
    private static final List<String> PHYSICAL = List.of("id", "Id", "name", "org_id", "secret");
    private String schema;
    private String other;
    private Connection connection;
    private URLClassLoader driverLoader;
    private Driver driver;

    @BeforeEach void createOwnedSchemas() throws Exception {
        Path jar = Path.of(System.getProperty("datahub.test.opengauss.driver")).toRealPath();
        driverLoader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        driver = (Driver) Class.forName("org.opengauss.Driver", true, driverLoader).getDeclaredConstructor().newInstance();
        connection = open();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT version()")) {
            assertTrue(rows.next());
            assertTrue(rows.getString(1).toLowerCase(Locale.ROOT).contains("opengauss"));
        }
        schema = "exact_" + UUID.randomUUID().toString().replace("-", "");
        other = schema + "_other";
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE SCHEMA " + other);
            for (String owner : List.of(schema, other)) {
                sql.execute("CREATE TABLE " + relation(owner, "Orders")
                        + "(id INTEGER,\"Id\" INTEGER,name VARCHAR(64),org_id INTEGER,secret VARCHAR(64))");
            }
            sql.execute("INSERT INTO " + relation(schema, "Orders")
                    + " VALUES(1,101,'one',9,'y'),(2,102,'two',11,'y'),(3,103,'three',9,NULL),(4,104,'four',10,'y')");
            sql.execute("INSERT INTO " + relation(other, "Orders") + " VALUES(99,999,'other',9,'y')");
            sql.execute("SET search_path TO " + other);
        }
    }

    @AfterEach void releaseOwnedSchemas() throws Exception {
        try {
            if (connection != null) try (var owned = connection; var sql = owned.createStatement()) {
                sql.setQueryTimeout(10);
                if (schema != null) {
                    assertTrue(schema.matches("exact_[a-f0-9]{32}"));
                    sql.execute("DROP SCHEMA IF EXISTS " + other + " CASCADE");
                    sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        } finally {
            if (driverLoader != null) driverLoader.close();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"on", "off"})
    void defaultSchemaAndCaseAreBoundBeforeReadingColumnsAndRows(String stringMode) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("SET standard_conforming_strings=" + stringMode);
        }
        var bound = RestrictedSelectGuard.parseBound(PRODUCT,
                "SELECT * FROM \"Orders\" WHERE id<=2 OR id>=3 ORDER BY id LIMIT 3", schema);
        assertEquals(schema, bound.getSchemaName());
        assertEquals("Orders", bound.getTableName());
        var reference = RestrictedSelectGuard.parseTableReference(PRODUCT, relation(schema, "Orders"), other);
        assertEquals(schema, reference.getSchemaName());
        assertEquals("Orders", reference.getTableName());

        var metadata = new MetaDataByJdbcServiceImpl(PRODUCT, DbSwitchProductRegistry.discover(),
                (url, user, password) -> open());
        assertEquals(PHYSICAL, metadata.queryExactTableColumns(url(), user(), password(), schema,
                "Orders", null, 10_000).stream().map(column -> column.getFieldName()).toList());

        var merged = RestrictedSelectGuard.restrictProjectionWithRowFilters(PRODUCT,
                bound.getNormalizedSql(), schema, "Orders", PHYSICAL, Set.of("id", "Id", "name"),
                List.of("org_id=9 OR org_id=10", "secret IS NOT NULL"));
        assertEquals(Set.of("id", "Id", "name"), merged.selectedColumns());
        assertEquals(List.of(1, 4), ids(merged.sql()));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(
                PRODUCT, "SELECT id FROM " + relation(other, "Orders"), schema, "Orders", PHYSICAL,
                Set.of("id"), List.of("org_id=9")));
    }

    /**
     * Probe the current, already-enabled projection and metadata APIs against the native server.
     * This deliberately makes no claim that the still-gated exact-binding/row-filter APIs pass.
     */
    @Test void nativeContractProbeBeforeExpandingExactBinding() throws Exception {
        var metadata = new MetaDataByJdbcServiceImpl(PRODUCT, DbSwitchProductRegistry.discover(),
                (url, user, password) -> open());
        assertEquals(PHYSICAL, metadata.queryExactTableColumns(url(), user(), password(), schema,
                "Orders", null, 10_000).stream().map(column -> column.getFieldName()).toList());
        for (String mode : List.of("on", "off")) {
            try (var sql = connection.createStatement()) {
                sql.execute("SET standard_conforming_strings=" + mode);
            }
            var projection = RestrictedSelectGuard.restrictProjection(PRODUCT,
                    "SELECT id,\"Id\",name FROM \"Orders\" WHERE (id<=2 OR id>=3)"
                            + " AND (org_id=9 OR org_id=10) AND secret IS NOT NULL ORDER BY id LIMIT 3",
                    schema, "Orders", PHYSICAL, new LinkedHashSet<>(PHYSICAL));
            assertEquals(Set.of("id", "Id", "name"), projection.selectedColumns());
            assertEquals(List.of(1, 4), ids(projection.sql()));
            var quoted = RestrictedSelectGuard.restrictProjection(PRODUCT,
                    "SELECT \"Id\" FROM \"Orders\" WHERE id=1", schema, "Orders", PHYSICAL,
                    Set.of("id", "Id"));
            assertEquals(List.of(101), ids(quoted.sql()));
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(PRODUCT,
                    "SELECT Id FROM \"Orders\"", schema, "Orders", PHYSICAL, Set.of("Id")));
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(PRODUCT,
                    "SELECT id FROM " + relation(other, "Orders"), schema, "Orders", PHYSICAL, Set.of("id")));
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(PRODUCT,
                    "SELECT id FROM ORDERS", schema, "Orders", PHYSICAL, Set.of("id")));
        }
    }

    @Test void quotedAndUnquotedCaseRemainDifferentPhysicalColumnsAndTables() throws Exception {
        var folded = RestrictedSelectGuard.parseTableReference(PRODUCT, schema + ".ORDERS", null);
        assertEquals("orders", folded.getTableName());
        assertThrows(SQLException.class, () -> ids(folded.getNormalizedSql()));
        var selected = RestrictedSelectGuard.restrictProjectionWithRowFilters(PRODUCT,
                "SELECT \"Id\" FROM " + relation(schema, "Orders") + " WHERE id=1", schema,
                "Orders", PHYSICAL, Set.of("id", "Id"), List.of());
        assertEquals(List.of(101), ids(selected.sql()));
        var denied = "SELECT Id FROM " + relation(schema, "Orders");
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(
                PRODUCT, denied, schema, "Orders", PHYSICAL, Set.of("Id"), List.of()));
    }

    @Test void userReferencesCannotBorrowPolicyColumnsOrAddUnsafePolicySql() {
        String table = relation(schema, "Orders");
        for (String query : List.of("SELECT secret FROM " + table,
                "SELECT id FROM " + table + " WHERE org_id=11", "SELECT id FROM " + table + " ORDER BY secret")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(
                    PRODUCT, query, schema, "Orders", PHYSICAL, Set.of("id"), List.of("org_id=9")));
        }
        for (String filter : List.of("org_id=$1", "org_id=?", "org_id=9) OR id>0 --",
                "org_id IN (SELECT id FROM " + table + ")", "missing=1")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(
                    PRODUCT, "SELECT id FROM " + table, schema, "Orders", PHYSICAL, Set.of("id"), List.of(filter)));
        }
    }

    private List<Integer> ids(String query) throws SQLException {
        var values = new ArrayList<Integer>();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            try (var rows = sql.executeQuery(query)) { while (rows.next()) values.add(rows.getInt(1)); }
        }
        return values;
    }

    private static String relation(String owner, String table) {
        return RestrictedSelectGuard.qualifiedTableName(PRODUCT, owner, table);
    }
    private static String url() { return System.getProperty("datahub.test.opengauss.url"); }
    private static String user() { return System.getProperty("datahub.test.opengauss.user", "dataforge_test"); }
    private static String password() { return System.getenv("DATAHUB_OPENGAUSS_TEST_PASSWORD"); }
    private Connection open() throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", user());
        properties.setProperty("password", password());
        Connection result = driver.connect(url(), properties);
        if (result == null) throw new SQLException("Isolated driver rejected the native openGauss URL");
        return result;
    }
}
