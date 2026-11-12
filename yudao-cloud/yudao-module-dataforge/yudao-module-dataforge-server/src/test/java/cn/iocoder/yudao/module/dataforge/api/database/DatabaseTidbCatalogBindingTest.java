package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Native TiDB 8.5.6/TiKV provider adaptation; governance authorization is tested separately. */
@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/.*")
class DatabaseTidbCatalogBindingTest {
    private static final long DATABASE_ID = 4L;
    private String url;
    private String schema;
    private String otherSchema;
    private Connection control;
    private DbSwitchRuntime runtime;
    private DatabaseApiImpl api;
    private AccessDatabaseResolver resolver;
    private AccessDatabaseResolver.ConnectionInfo info;

    @BeforeEach
    void createOwnedDatabases() throws SQLException {
        var matcher = Pattern.compile("(jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/)[^?]*(\\?.*)?")
                .matcher(System.getProperty("datahub.test.tidb.url"));
        assertThat(matcher.matches()).isTrue();
        url = matcher.group(1) + "mysql" + (matcher.group(2) == null ? "" : matcher.group(2));
        control = DriverManager.getConnection(url, user(), password());
        try (var sql = control.createStatement()) {
            sql.setQueryTimeout(10);
            try (var version = sql.executeQuery("SELECT VERSION()")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getString(1)).contains("TiDB-v8.5.6");
            }
            String id = UUID.randomUUID().toString().replace("-", "");
            schema = "Catalog_" + id;
            otherSchema = "Other_" + id;
            sql.execute("CREATE DATABASE " + quote(schema));
            sql.execute("CREATE DATABASE " + quote(otherSchema));
            for (String owned : List.of(schema, otherSchema)) {
                sql.execute("CREATE TABLE " + table(owned)
                        + "(id INTEGER PRIMARY KEY,Name VARCHAR(64),org_id INTEGER,secret VARCHAR(64))");
            }
            sql.execute("INSERT INTO " + table(schema) + " VALUES (1,'Alice',7,'hidden'),(2,'Bob',8,'hidden')");
            sql.execute("INSERT INTO " + table(otherSchema) + " VALUES (99,'Other',9,'hidden')");
        }
        assertThat(control.getCatalog()).isEqualTo("mysql");
        info = connectionInfo();
        resolver = mock(AccessDatabaseResolver.class);
        when(resolver.resolve(eq(DATABASE_ID), any())).thenAnswer(ignored -> info);
        runtime = DbSwitchRuntime.standalone();
        api = new DatabaseApiImpl();
        ReflectionTestUtils.setField(api, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(api, "databaseResolver", resolver);
        ReflectionTestUtils.setField(api, "dynamicMaskSupport",
                cn.iocoder.yudao.module.dataforge.service.database.DatabaseDynamicMaskSupportTest.passThrough());
    }

    @AfterEach
    void releaseOnlyOwnedDatabases() throws SQLException {
        if (runtime != null) runtime.close();
        if (control == null) return;
        try (var owned = control; var sql = owned.createStatement()) {
            sql.setQueryTimeout(10);
            if (schema != null) {
                assertThat(schema).matches("Catalog_[a-f0-9]{32}");
                sql.execute("DROP DATABASE IF EXISTS " + quote(schema));
            }
            if (otherSchema != null) {
                assertThat(otherSchema).matches("Other_[a-f0-9]{32}");
                sql.execute("DROP DATABASE IF EXISTS " + quote(otherSchema));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders", "ORDERS", "`Orders`"})
    void pickerAndReferenceAliasesUseOneNativeIdentity(String alias) {
        var listed = api.getQueryTableList(DATABASE_ID).getCheckedData();
        assertThat(listed).hasSize(1);
        var expected = listed.getFirst();
        assertThat(expected.getSchemaName()).isEqualTo(schema);
        assertThat(expected.getTableName()).isEqualTo("Orders");
        assertThat(expected.getRelationObjectId()).isNotBlank();
        var resolved = api.resolveQueryTable(DATABASE_ID, alias).getCheckedData();
        assertThat(resolved).isEqualTo(expected);
        var quoted = api.resolveQueryTable(DATABASE_ID,
                quote(schema.toUpperCase(Locale.ROOT)) + "." + alias).getCheckedData();
        assertThat(quoted).isEqualTo(expected);
    }

    @Test
    void parsedQuotedTableAliasRetainsNativeNamesAndObjectId() {
        var expected = api.resolveQueryTable(DATABASE_ID, "orders").getCheckedData();
        var plan = api.parseRestrictedSelect(request("SELECT o.ID,o.NAME FROM "
                + quote(schema.toLowerCase(Locale.ROOT)) + ".`ORDERS` AS o")).getCheckedData();
        assertThat(plan.getSchemaName()).isEqualTo(schema);
        assertThat(plan.getTableName()).isEqualTo("Orders");
        assertThat(plan.getRelationObjectId()).isEqualTo(expected.getRelationObjectId());
        assertThat(plan.getNormalizedSql()).contains(quote(schema), "`Orders`");
    }

    @Test
    void nativeColumnAliasesRetainPhysicalSpellingAndRequestedOrder() {
        var resolved = api.resolveQueryTable(DATABASE_ID, "ORDERS").getCheckedData();
        var request = columns(resolved, List.of("NAME", "ID"));
        assertThat(api.getQueryTableColumns(request).getCheckedData()).extracting("columnName")
                .containsExactly("Name", "id");
        // Metadata-only aliases obtain a fresh identity. A previously observed ID uses its canonical names.
        request.setExpectedObjectId(null);
        request.setSchemaName(schema.toLowerCase(Locale.ROOT));
        request.setTableName("orders");
        assertThat(api.getQueryTableColumns(request).getCheckedData()).extracting("columnName")
                .containsExactly("Name", "id");
    }

    @Test
    void scopedWildcardReturnsOnlyApprovedPhysicalColumns() {
        var request = scoped("SELECT o.* FROM ORDERS o WHERE o.ID=1 ORDER BY o.NAME");
        var result = api.executeQuery(request).getCheckedData();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().getFirst()).containsOnlyKeys("id", "Name");
        assertThat(((Number) result.getRows().getFirst().get("id")).intValue()).isEqualTo(1);
        assertThat(result.getRows().getFirst().get("Name")).isEqualTo("Alice");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT ID FROM orders WHERE SECRET='hidden'",
            "SELECT ID FROM orders ORDER BY SECRET",
            "SELECT SECRET AS Name FROM orders"
    })
    void hiddenPredicateOrderAndProjectionAliasCannotEscapeScope(String sql) {
        assertThatThrownBy(() -> api.executeQuery(scoped(sql))).isInstanceOf(ServiceException.class);
    }

    @Test
    void configuredCatalogOverridesTheDifferentConnectionCurrentDatabase() throws SQLException {
        var plan = api.parseRestrictedSelect(request("SELECT ID FROM orders WHERE ID=1")).getCheckedData();
        assertThat(plan.getSchemaName()).isEqualTo(schema);
        var result = api.executeQuery(request(plan.getNormalizedSql())).getCheckedData();
        assertThat(result.getRows()).hasSize(1);
        assertThat(((Number) result.getRows().getFirst().get("id")).intValue()).isEqualTo(1);
        assertThat(control.getCatalog()).isEqualTo("mysql");
        var other = api.resolveQueryTable(DATABASE_ID, quote(otherSchema) + ".orders").getCheckedData();
        assertThat(other.getSchemaName()).isEqualTo(otherSchema);
        assertThat(other.getRelationObjectId()).isNotEqualTo(plan.getRelationObjectId());
        assertThatThrownBy(() -> api.executeQuery(scoped("SELECT ID FROM " + table(otherSchema))))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void expectedCatalogObjectIdRejectsSameNameDropAndRecreate() throws SQLException {
        var old = api.resolveQueryTable(DATABASE_ID, "orders").getCheckedData();
        assertThat(api.getQueryTableColumns(columns(old, List.of("id"))).getCheckedData()).hasSize(1);
        try (var sql = control.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("DROP TABLE " + table(schema));
            sql.execute("CREATE TABLE " + table(schema)
                    + "(id INTEGER PRIMARY KEY,Name VARCHAR(64),org_id INTEGER,secret VARCHAR(64))");
        }
        var fresh = api.resolveQueryTable(DATABASE_ID, "orders").getCheckedData();
        assertThat(fresh.getRelationObjectId()).isNotEqualTo(old.getRelationObjectId());
        assertThatThrownBy(() -> api.getQueryTableColumns(columns(old, List.of("id"))))
                .isInstanceOf(ServiceException.class);
        assertThat(api.getQueryTableColumns(columns(fresh, List.of("id"))).getCheckedData()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "schema", "revision"})
    void changedDatasourceBindingFailsBeforeReturningCatalogEvidence(String dimension) {
        var changed = connectionInfo();
        switch (dimension) {
            case "product" -> changed.setProductType(ProductTypeEnum.MYSQL);
            case "schema" -> changed.setSchema(otherSchema);
            case "revision" -> changed.setConfigurationRevision("r2");
            default -> throw new AssertionError("Unknown binding dimension");
        }
        var reads = new AtomicInteger();
        when(resolver.resolve(eq(DATABASE_ID), any()))
                .thenAnswer(ignored -> reads.incrementAndGet() == 1 ? info : changed);
        assertThatThrownBy(() -> api.resolveQueryTable(DATABASE_ID, "orders"))
                .isInstanceOf(ServiceException.class);
        assertThat(reads.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void revisionChangeAfterNativeLookupCannotReturnEarlierEvidence() {
        var changed = connectionInfo();
        changed.setConfigurationRevision("r2");
        var reads = new AtomicInteger();
        when(resolver.resolve(eq(DATABASE_ID), any()))
                .thenAnswer(ignored -> reads.incrementAndGet() <= 2 ? info : changed);
        assertThatThrownBy(() -> api.resolveQueryTable(DATABASE_ID, "orders"))
                .isInstanceOf(ServiceException.class);
        assertThat(reads.get()).isGreaterThanOrEqualTo(3);
    }

    private AccessDatabaseResolver.ConnectionInfo connectionInfo() {
        var value = new AccessDatabaseResolver.ConnectionInfo();
        value.setDatabaseId(DATABASE_ID);
        value.setProductType(ProductTypeEnum.TIDB);
        value.setJdbcUrl(url);
        value.setUsername(user());
        value.setPassword(password());
        value.setSchema(schema);
        value.setConfigurationRevision("r1");
        return value;
    }

    private DatabaseQueryReqDTO request(String sql) {
        var request = new DatabaseQueryReqDTO();
        request.setDatabaseId(DATABASE_ID);
        request.setSql(sql);
        return request;
    }

    private DatabaseQueryReqDTO scoped(String sql) {
        var request = request(sql);
        request.setExpectedSchema(schema);
        request.setExpectedTable("Orders");
        request.setAllowedColumns(List.of("id", "Name"));
        return request;
    }

    private static DatabaseQueryColumnsReqDTO columns(DatabaseQueryTableRespDTO table, List<String> projection) {
        var request = new DatabaseQueryColumnsReqDTO();
        request.setDatabaseId(DATABASE_ID);
        request.setSchemaName(table.getSchemaName());
        request.setTableName(table.getTableName());
        request.setExpectedObjectId(table.getRelationObjectId());
        request.setColumns(projection);
        return request;
    }

    private static String quote(String name) { return "`" + name.replace("`", "``") + "`"; }
    private static String table(String schema) { return quote(schema) + ".`Orders`"; }
    private static String user() { return System.getProperty("datahub.test.tidb.user", "root"); }
    private static String password() {
        String value = System.getenv("DATAHUB_TIDB_TEST_PASSWORD");
        return value == null ? "" : value;
    }
}
