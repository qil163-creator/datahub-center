package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real modern file/isolated driver; ordinary affinity facts are not certified as STRICT domains. */
@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.sqlite.driver", matches = ".+")
class LogicalTypeMappingSqliteJdbcTest {
    @TempDir Path directory;
    DbSwitchRuntime runtime;
    HikariDataSource source;

    @BeforeEach void setup() throws Exception {
        Path artifact = Path.of(System.getProperty("datahub.test.sqlite.driver"));
        var spec = new DbSwitchProperties.Driver(); spec.setPath(artifact.toAbsolutePath().toString());
        spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
        spec.setDriverClassName("org.sqlite.JDBC");
        var drivers = new ConfiguredJdbcDrivers(Map.of(SQLITE3, spec));
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
        var properties = new TargetDataSourceProperties(); properties.setTargetProductType(SQLITE3);
        properties.setUrl("jdbc:sqlite:" + directory.resolve("source.db")); properties.setPoolSize(1);
        source = DataSourceUtils.createTargetDataSource(properties, null, null, drivers.find(SQLITE3).orElseThrow(), drivers.runtimes());
    }

    @AfterEach void cleanup() { try { if (source != null) source.close(); } finally { if (runtime != null) runtime.close(); } }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "INTEGER; LOSSY|bigint|DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL",
            "REAL; LOSSY|double precision|DECLARED_TYPE_AFFINITY_NOT_STRICT,NAN_STORED_AS_NULL",
            "TEXT; LOSSY|text COLLATE \"C\"|DECLARED_TYPE_AFFINITY_NOT_STRICT",
            "VARCHAR(20); LOSSY|character varying(20) COLLATE \"C\"|DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED",
            "CHAR(5); LOSSY|character(5) COLLATE \"C\"|DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED,PAD_SPACE_EQUALITY_CHANGED",
            "BLOB; UNSUPPORTED|-|BLOB_AFFINITY_MIXED_STORAGE",
            "NUMERIC; UNSUPPORTED|-|NUMERIC_AFFINITY_MIXED_STORAGE",
            "DECIMAL(12,2); UNSUPPORTED|-|DECIMAL_STORED_AS_REAL_OR_TEXT",
            "BOOLEAN; UNSUPPORTED|-|BOOLEAN_IS_NUMERIC_AFFINITY",
            "DATE; UNSUPPORTED|-|DATE_STORED_AS_TEXT_OR_NUMBER",
            "DATETIME; UNSUPPORTED|-|DATETIME_STORED_AS_TEXT_OR_NUMBER",
            "VARCHAR(0); UNSUPPORTED|-|ZERO_LENGTH_NOT_A_DOMAIN"})
    void modernCatalogMapsOnlyObservedTypeShapes(String declared, String expected) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            assertEquals("3.49.1", connection.getMetaData().getDatabaseProductVersion());
            statement.execute("CREATE TABLE source(v " + declared + ")");
            var facts = NativeSchemaReader.read(connection, SQLITE3, "main", "source", 10);
            assertEquals(MetadataValue.State.ABSENT, facts.additionalObjects().state());
            assertEquals(expected, PgFamilyMappingFixtures.outcome(runtime.products().typeMappings().map(
                    POSTGRESQL, facts.columns().getFirst(), false)));
        }
    }

    @Test void int64UnicodeBinaryRealAndMixedStorageAreObservedRatherThanInferredFromDeclarations() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE source(n INTEGER, r REAL, t TEXT, d DECIMAL(12,2), b BLOB)");
            try (var insert = connection.prepareStatement("INSERT INTO source VALUES(?,?,?,?,?)")) {
                insert.setLong(1, Long.MAX_VALUE); insert.setDouble(2, -0.0); insert.setString(3, "中文 😀\n'quoted'");
                insert.setBigDecimal(4, new java.math.BigDecimal("1.234567890123456789")); insert.setBytes(5, new byte[]{0, 1, -1});
                insert.executeUpdate();
            }
            try (var rows = statement.executeQuery("SELECT n,r,t,d,b FROM source")) {
                assertTrue(rows.next()); assertEquals(Long.MAX_VALUE, rows.getLong(1));
                assertEquals(0L, Double.doubleToRawLongBits(rows.getDouble(2)));
                assertEquals("中文 😀\n'quoted'", rows.getString(3));
                assertInstanceOf(Double.class, rows.getObject(4));
                assertNotEquals(0, new java.math.BigDecimal("1.234567890123456789").compareTo(rows.getBigDecimal(4)));
                assertArrayEquals(new byte[]{0, 1, -1}, rows.getBytes(5));
            }
            statement.execute("INSERT INTO source(n,r,t,b) VALUES('mixed','mixed',X'00ff','text')");
            try (var rows = statement.executeQuery("SELECT typeof(n),typeof(r),typeof(t),typeof(b) FROM source WHERE n='mixed'")) {
                assertTrue(rows.next()); assertEquals("text", rows.getString(1)); assertEquals("text", rows.getString(2));
                assertEquals("blob", rows.getString(3)); assertEquals("text", rows.getString(4));
            }
            try (var insert = connection.prepareStatement("INSERT INTO source(r) VALUES(?)")) { insert.setDouble(1, Double.NaN); insert.executeUpdate(); }
            try (var rows = statement.executeQuery("SELECT typeof(r) FROM source WHERE rowid=3")) { assertTrue(rows.next()); assertEquals("null", rows.getString(1)); }
        }
    }

    @Test void strictGeneratedAndNotNullSemanticsDoNotBecomeAnExactPortableDomain() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE source(n INTEGER NOT NULL, doubled INTEGER GENERATED ALWAYS AS(n*2) STORED) STRICT");
            var facts = NativeSchemaReader.read(connection, SQLITE3, "main", "source", 10);
            assertEquals(MetadataValue.State.UNKNOWN, facts.additionalObjects().state());
            assertEquals(TypeFidelity.LOSSY, runtime.products().typeMappings().map(POSTGRESQL, facts.columns().getFirst(), false).fidelity());
            assertEquals(MetadataValue.State.UNKNOWN, facts.columns().get(1).getMetadataEvidence().generatedExpression().state());
            assertEquals(TypeFidelity.UNSUPPORTED, runtime.products().typeMappings().map(POSTGRESQL, facts.columns().get(1), false).fidelity());
            assertThrows(java.sql.SQLException.class, () -> statement.execute("INSERT INTO source(n) VALUES('wrong')"));
        }
    }

    @Test void dialectVerificationRestoresBusyTimeoutAndDoesNotCommitTheCallersTransaction() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE source(n INTEGER)");
            statement.execute("PRAGMA busy_timeout=2222");
            connection.setAutoCommit(false);
            statement.execute("INSERT INTO source VALUES(7)");
            var dialect = runtime.products().metadata(SQLITE3);
            assertTrue(dialect.requiresDialectConnection()); dialect.prepareDialect(connection);
            assertFalse(connection.getAutoCommit());
            try (var rows = statement.executeQuery("PRAGMA busy_timeout")) { assertTrue(rows.next()); assertEquals(2222, rows.getInt(1)); }
            connection.rollback(); connection.setAutoCommit(true);
            try (var rows = statement.executeQuery("SELECT count(*) FROM source")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"3.49.2", "3.31.1"})
    void targetVersionIsCheckedAgainstTheActualEngineRatherThanTheJdbcClaim(String claim) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=3333");
            var jdbc = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
            org.mockito.Mockito.when(jdbc.getDatabaseProductName()).thenReturn("SQLite");
            org.mockito.Mockito.when(jdbc.getDatabaseProductVersion()).thenReturn(claim);
            var disguised = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getMetaData")) return jdbc;
                        try { return method.invoke(connection, args); }
                        catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
                    });
            var error = assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                    () -> runtime.products().metadata(SQLITE3).prepareDialect(disguised));
            assertEquals("0A000", error.getSQLState());
            try (var rows = statement.executeQuery("PRAGMA busy_timeout")) { assertTrue(rows.next()); assertEquals(3333, rows.getInt(1)); }
        }
    }

    @Test void generatedSqliteDdlQuotesLiteralSemicolonsWithoutEnablingArbitraryScripts() throws Exception {
        var column = LogicalTypeMappingTest.pg("v\"; -- literal", "bigint", java.sql.Types.BIGINT);
        var mapping = runtime.products().typeMappings().map(SQLITE3, column, false);
        var table = "copy\"; -- literal";
        var options = new TypeMappingOptions(runtime.products().typeMappings(), LossyMappingAcknowledgement.acceptanceFor(
                java.util.List.of(LossyMappingAcknowledgement.forColumn("main", table, mapping)), SQLITE3, "main", table));
        var ddl = runtime.metadata(source, SQLITE3).getDDLCreateTableSQL(SQLITE3, java.util.List.of(column),
                java.util.List.of(), "main", table, null, false, Map.of(), options);
        assertEquals(1, ddl.size());
        assertThrows(UnsupportedOperationException.class, () -> cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(SQLITE3, ddl.getFirst()));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(ddl.getFirst());
            try (var query = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
                query.setString(1, table); try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(table, rows.getString(1)); assertFalse(rows.next()); }
            }
        }
    }
}
