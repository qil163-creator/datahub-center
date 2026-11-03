package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual SQL Server 2000 SP3 catalogs through the isolated Microsoft JDBC 3.0 driver. */
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_URL", matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;.*")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_DRIVER", matches = ".+")
class SqlServer2000NativeCatalogTest {
    private Connection connection;
    private URLClassLoader loader;
    private Driver driver;
    private String table;
    private final List<String> dropOwnedObjects = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getenv("DATAHUB_TEST_SQL2000_DRIVER")).toRealPath().toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        driver = (Driver) loader.loadClass("com.microsoft.sqlserver.jdbc.SQLServerDriver").getConstructor().newInstance();
        var properties = new Properties(); properties.setProperty("user", System.getenv("DATAHUB_TEST_SQL2000_USER"));
        properties.setProperty("password", System.getenv("DATAHUB_TEST_SQL2000_PASSWORD"));
        connection = driver.connect(System.getenv("DATAHUB_TEST_SQL2000_URL"), properties);
        table = "catalog_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE TABLE [dbo].[" + table + "] (id INT NOT NULL PRIMARY KEY,amount DECIMAL(10,2) NULL,"
                + "label VARCHAR(32) NULL,nlabel NVARCHAR(12) NULL,spare INT NULL)");
    }
    @AfterEach void cleanup() throws Exception {
        try {
            if (connection != null) {
                try (var owned = connection) {
                    if (table != null) {
                        assertTrue(table.matches("catalog_[a-f0-9]{32}")); sql("DROP TABLE [dbo].[" + table + "]");
                    }
                    for (String statement : dropOwnedObjects) sql(statement);
                }
            }
        } finally { if (loader != null) loader.close(); }
    }

    @Test void completeNumbersStringsAndPrimaryKeysRetainBorrowedConnectionAndExactSourceFacts() throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        var schema = NativeSchemaReader.read(connection, ProductTypeEnum.SQLSERVER2000, "dbo", table, 10);
        assertTrue(schema.provenance().nativeProduct(ProductTypeEnum.SQLSERVER2000));
        assertEquals(List.of("id", "amount", "label", "nlabel", "spare"), schema.columns().stream().map(c -> c.getFieldName()).toList());
        assertEquals(List.of("id"), schema.primaryKeys()); assertEquals(MetadataValue.State.ABSENT, schema.additionalObjects().state());
        var mappings = DbSwitchProductRegistry.discover().typeMappings();
        for (var column : schema.columns()) {
            var facts = column.getMetadataEvidence();
            assertEquals(MetadataValue.State.ABSENT, facts.defaultExpression().state());
            assertEquals(MetadataValue.State.ABSENT, facts.identity().state());
            assertEquals(MetadataValue.State.ABSENT, facts.generatedExpression().state());
            assertEquals(MetadataValue.State.ABSENT, facts.charset().state()); assertEquals(MetadataValue.State.ABSENT, facts.sequence().state());
            assertEquals(TypeFidelity.EXACT, mappings.mapper(ProductTypeEnum.SQLSERVER2000).orElseThrow().toLogical(column).fidelity());
        }
        var amount = schema.columns().get(1).getMetadataEvidence();
        assertEquals("decimal(10,2)", amount.nativeType().value()); assertEquals(10, amount.numericPrecision().value());
        assertEquals(2, amount.numericScale().value()); assertEquals(MetadataValue.State.ABSENT, amount.characterLength().state());
        var text = schema.columns().get(3).getMetadataEvidence();
        assertEquals("nvarchar(12)", text.nativeType().value()); assertEquals(12L, text.characterLength().value());
        assertEquals("SQL_Latin1_General_CP1_CI_AS", text.collation().value());
        assertEquals(MetadataValue.State.ABSENT, text.numericPrecision().state());
        assertEquals(autoCommit, connection.getAutoCommit()); assertFalse(connection.isClosed());
    }

    @Test void observedDefaultRemainsUnmappedAndFragmentedDefinitionIsRefused() throws Exception {
        sql("ALTER TABLE [dbo].[" + table + "] ADD defaulted INT NULL DEFAULT 0");
        var schema = NativeSchemaReader.read(connection, ProductTypeEnum.SQLSERVER2000, "dbo", table, 10);
        var column = schema.columns().getLast(); assertEquals("(0)", column.getMetadataEvidence().defaultExpression().value());
        assertEquals(TypeFidelity.UNSUPPORTED, DbSwitchProductRegistry.discover().typeMappings().map(ProductTypeEnum.POSTGRESQL, column, false).fidelity());
        sql("ALTER TABLE [dbo].[" + table + "] ADD long_default VARCHAR(8000) NULL DEFAULT '" + "x".repeat(5000) + "'");
        var fragmented = assertThrows(SQLFeatureNotSupportedException.class,
                () -> NativeSchemaReader.read(connection, ProductTypeEnum.SQLSERVER2000, "dbo", table, 10));
        assertEquals("0A000", fragmented.getSQLState());
        assertTrue(fragmented.getMessage().contains("default definition is incomplete"));
    }

    @Test void differentObservedCollationDoesNotAcquireACharacterMapping() throws Exception {
        sql("ALTER TABLE [dbo].[" + table + "] ALTER COLUMN label VARCHAR(32) COLLATE Latin1_General_BIN NULL");
        var column = NativeSchemaReader.read(connection, ProductTypeEnum.SQLSERVER2000, "dbo", table, 10).columns().get(2);
        assertEquals("Latin1_General_BIN", column.getMetadataEvidence().collation().value());
        var mapping = DbSwitchProductRegistry.discover().typeMappings().map(ProductTypeEnum.POSTGRESQL, column, false);
        assertEquals(TypeFidelity.UNSUPPORTED, mapping.fidelity()); assertEquals("COLLATION_UNVERIFIED", mapping.reasonCode());
    }

    @Test void publicSchemaFacadeRefusesDriverWithoutItsRequiredRealNetworkDeadlineAndClosesConnection() throws Exception {
        assertTrue(cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqlServer2000NativeCatalog.admits(connection, "dbo", table));
        var opens = new java.util.concurrent.atomic.AtomicInteger();
        var opened = new java.util.concurrent.atomic.AtomicReference<Connection>();
        var facade = new cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl(
                ProductTypeEnum.SQLSERVER2000, DbSwitchProductRegistry.discover(), (url, user, password) -> {
            var properties = new Properties(); properties.setProperty("user", user); properties.setProperty("password", password);
            var actual = driver.connect(url, properties); opens.incrementAndGet(); opened.set(actual); return actual;
        });
        var failure = assertThrows(IllegalStateException.class, () -> facade.queryTableSchema(System.getenv("DATAHUB_TEST_SQL2000_URL"),
                System.getenv("DATAHUB_TEST_SQL2000_USER"), System.getenv("DATAHUB_TEST_SQL2000_PASSWORD"), "dbo", table, 10000));
        assertInstanceOf(SQLFeatureNotSupportedException.class, failure.getCause());
        assertEquals("0A000", ((SQLException) failure.getCause()).getSQLState());
        assertEquals(1, opens.get()); assertTrue(opened.get().isClosed()); assertFalse(connection.isClosed());
    }

    @ParameterizedTest @ValueSource(strings = {"identity", "computed", "check", "unique", "index", "trigger", "alias", "rule"})
    void specialColumnsAndAdditionalObjectsCannotReachColumnOnlyCrossProductApi(String kind) throws Exception {
        String relation = "[dbo].[" + table + "]";
        switch (kind) {
            case "identity" -> sql("ALTER TABLE " + relation + " ADD special INT IDENTITY(1,1)");
            case "computed" -> sql("ALTER TABLE " + relation + " ADD special AS id+1");
            case "check" -> sql("ALTER TABLE " + relation + " ADD CHECK (id>=0)");
            case "unique" -> sql("ALTER TABLE " + relation + " ADD UNIQUE (amount)");
            case "index" -> sql("CREATE INDEX [extra_" + table + "] ON " + relation + "(amount)");
            case "trigger" -> sql("CREATE TRIGGER [dbo].[trigger_" + table + "] ON " + relation + " FOR INSERT AS SELECT 1");
            case "alias" -> {
                sql("EXEC sp_addtype 'alias_" + table + "','int','NULL'");
                dropOwnedObjects.add("EXEC sp_droptype 'alias_" + table + "'");
                sql("ALTER TABLE " + relation + " ADD special [alias_" + table + "] NULL");
            }
            case "rule" -> {
                sql("CREATE RULE [dbo].[rule_" + table + "] AS @value>0");
                dropOwnedObjects.add("DROP RULE [dbo].[rule_" + table + "]");
                sql("EXEC sp_bindrule 'dbo.rule_" + table + "','dbo." + table + ".spare'");
            }
            default -> fail("Unknown native fixture");
        }
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> NativeSchemaReader.read(connection, ProductTypeEnum.SQLSERVER2000, "dbo", table, 10));
        assertEquals("0A000", failure.getSQLState());
        String expectedGuard = switch (kind) {
            case "identity", "computed", "alias", "rule" -> "alias, identity, generated, rule";
            case "index" -> "extra index or statistics";
            default -> "additional objects";
        };
        assertTrue(failure.getMessage().contains(expectedGuard), failure::getMessage);
        assertFalse(connection.isClosed());
    }

    private void sql(String text) throws SQLException {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(10); statement.execute(text); }
    }
}
