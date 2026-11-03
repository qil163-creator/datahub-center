package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public MigrationService, actual SQLite 3.49.1 and a dedicated localhost PostgreSQL test database. */
@Timeout(90)
@EnabledIfSystemProperty(named = "datahub.test.sqlite.driver", matches = ".+")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test")
class SqlitePostgresMigrationTest {
    @TempDir Path directory;
    DbSwitchRuntime runtime;
    HikariDataSource sqlite;
    DataSource postgres;
    String sqliteUrl, pgSchema;

    @BeforeEach void setup() throws Exception {
        Path artifact = Path.of(System.getProperty("datahub.test.sqlite.driver"));
        var spec = new DbSwitchProperties.Driver(); spec.setPath(artifact.toAbsolutePath().toString());
        spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
        spec.setDriverClassName("org.sqlite.JDBC");
        var drivers = new ConfiguredJdbcDrivers(Map.of(SQLITE3, spec));
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
        sqliteUrl = "jdbc:sqlite:" + directory.resolve("transfer.db");
        var properties = new TargetDataSourceProperties(); properties.setTargetProductType(SQLITE3);
        properties.setUrl(sqliteUrl); properties.setPoolSize(1);
        sqlite = DataSourceUtils.createTargetDataSource(properties, null, null, drivers.find(SQLITE3).orElseThrow(), drivers.runtimes());
        postgres = new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), System.getProperty("datahub.test.postgres.password", ""));
        pgSchema = "sqmap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        sql(postgres, "CREATE SCHEMA " + pgSchema);
        try (var connection = sqlite.getConnection()) { assertEquals("3.49.1", connection.getMetaData().getDatabaseProductVersion()); }
    }

    @AfterEach void cleanup() throws Exception {
        try { if (sqlite != null) sqlite.close(); }
        finally { try { if (runtime != null) runtime.close(); }
            finally { if (postgres != null && pgSchema != null) sql(postgres, "DROP SCHEMA IF EXISTS " + pgSchema + " CASCADE"); } }
    }

    @Test void sqliteToPostgresFullAndDifferenceKeepLargeKeysUnicodeNullsAndActualValues() throws Exception {
        sql(sqlite, "CREATE TABLE source(id INTEGER PRIMARY KEY, n INTEGER, r REAL, note TEXT)");
        sql(sqlite, "INSERT INTO source VALUES(9007199254740993,9223372036854775807,1.25,'中文 😀'),(2,-9223372036854775808,-2.5,NULL)");
        var request = request(SQLITE3, false); acknowledge(request);
        assertSuccess(run(request));
        assertEquals(List.of("2|-9223372036854775808|-2.5|null", "9007199254740993|9223372036854775807|1.25|中文 😀"),
                rows(postgres, "SELECT id,n,r,note FROM " + pgSchema + ".copied ORDER BY id"));
        sql(sqlite, "UPDATE source SET n=42,note='changed' WHERE id=2");
        sql(sqlite, "DELETE FROM source WHERE id=9007199254740993");
        sql(sqlite, "INSERT INTO source VALUES(3,7,3.5,'new')");
        request.getTarget().setChangeDataSync(true);
        assertSuccess(run(request));
        assertEquals(List.of("2|42|-2.5|changed", "3|7|3.5|new"), rows(postgres, "SELECT id,n,r,note FROM " + pgSchema + ".copied ORDER BY id"));
    }

    @Test void postgresToSqliteFullAndDifferenceKeepInt64UnicodeAndBytes() throws Exception {
        sql(postgres, "CREATE TABLE " + pgSchema + ".source(id bigint PRIMARY KEY,n integer,note text COLLATE \"C\",payload bytea)");
        sql(postgres, "INSERT INTO " + pgSchema + ".source VALUES(9007199254740993,2147483647,'中文 😀',decode('0001ff','hex')),(2,NULL,NULL,NULL)");
        var request = request(POSTGRESQL, false); acknowledge(request);
        assertSuccess(run(request));
        assertEquals(List.of("2|null|null|", "9007199254740993|2147483647|中文 😀|0001FF"),
                rows(sqlite, "SELECT id,n,note,hex(payload) FROM copied ORDER BY id"));
        sql(postgres, "UPDATE " + pgSchema + ".source SET n=42,note='changed',payload=decode('ff00','hex') WHERE id=2");
        sql(postgres, "DELETE FROM " + pgSchema + ".source WHERE id=9007199254740993");
        sql(postgres, "INSERT INTO " + pgSchema + ".source VALUES(3,7,'new',decode('00','hex'))");
        request.getTarget().setChangeDataSync(true);
        assertSuccess(run(request));
        assertEquals(List.of("2|42|changed|FF00", "3|7|new|00"), rows(sqlite, "SELECT id,n,note,hex(payload) FROM copied ORDER BY id"));
    }

    @Test void unacknowledgedAndStaleDecisionsCannotCreateReplaceOrClearATarget() throws Exception {
        sql(sqlite, "CREATE TABLE source(id INTEGER PRIMARY KEY,n INTEGER)"); sql(sqlite, "INSERT INTO source VALUES(1,7)");
        var request = request(SQLITE3, false);
        var rejected = run(request);
        assertFalse(rejected.getIfAllSuccess().get());
        var plan = rejected.getTableResultList().getFirst().getTypeMappingPlan();
        assertNotNull(plan); assertFalse(plan.accepted());
        assertEquals(Set.of("id", "n"), plan.rejectedColumns().stream().map(ColumnTypeMapping::column).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("0"), rows(postgres, "SELECT count(*) FROM information_schema.tables WHERE table_schema='" + pgSchema + "' AND table_name='copied'"));
        sql(postgres, "CREATE TABLE " + pgSchema + ".copied(id bigint PRIMARY KEY,n bigint)");
        sql(postgres, "INSERT INTO " + pgSchema + ".copied VALUES(9,999)");
        assertFalse(run(request).getIfAllSuccess().get());
        assertEquals(List.of("9|999"), rows(postgres, "SELECT id,n FROM " + pgSchema + ".copied"));
        acknowledge(request);
        sql(sqlite, "DROP TABLE source"); sql(sqlite, "CREATE TABLE source(id INTEGER PRIMARY KEY,n REAL)"); sql(sqlite, "INSERT INTO source VALUES(1,1.5)");
        request.getTarget().setTargetDrop(true);
        assertFalse(run(request).getIfAllSuccess().get());
        assertEquals(List.of("9|999"), rows(postgres, "SELECT id,n FROM " + pgSchema + ".copied"));
    }

    @Test void acknowledgementForAnotherPhysicalTableCannotAuthorizeReplacement() throws Exception {
        sql(sqlite, "CREATE TABLE source(id INTEGER PRIMARY KEY,n INTEGER)"); sql(sqlite, "INSERT INTO source VALUES(1,7)");
        sql(postgres, "CREATE TABLE " + pgSchema + ".copied(id bigint PRIMARY KEY,n bigint)"); sql(postgres, "INSERT INTO " + pgSchema + ".copied VALUES(9,999)");
        var request = request(SQLITE3, true); acknowledge(request);
        request.setTypeMappingAcknowledgements(request.getTypeMappingAcknowledgements().stream().map(decision ->
                new LossyMappingAcknowledgement(decision.targetProduct(), decision.targetSchema(), "another", decision.column(),
                        decision.mappingFingerprint(), decision.lossCodes())).toList());
        assertFalse(run(request).getIfAllSuccess().get());
        assertEquals(List.of("9|999"), rows(postgres, "SELECT id,n FROM " + pgSchema + ".copied"));
    }

    @Test void strictTableCannotLoseItsUnrepresentedStructureDuringCrossProductReplacement() throws Exception {
        sql(sqlite, "CREATE TABLE source(id INTEGER PRIMARY KEY,n INTEGER) STRICT"); sql(sqlite, "INSERT INTO source VALUES(1,7)");
        sql(postgres, "CREATE TABLE " + pgSchema + ".copied(id bigint PRIMARY KEY,n bigint)"); sql(postgres, "INSERT INTO " + pgSchema + ".copied VALUES(9,999)");
        var request = request(SQLITE3, true); acknowledge(request);
        assertFalse(run(request).getIfAllSuccess().get());
        assertEquals(List.of("9|999"), rows(postgres, "SELECT id,n FROM " + pgSchema + ".copied"));
    }

    @Test void decimalAndMixedBlobRemainUnsupportedEvenWhenAllOtherLossesAreAccepted() throws Exception {
        sql(sqlite, "CREATE TABLE source(id INTEGER PRIMARY KEY,n INTEGER,d DECIMAL(12,2),b BLOB)");
        sql(sqlite, "INSERT INTO source VALUES(1,7,1.234567890123456789,'mixed')");
        var request = request(SQLITE3, false); acknowledge(request);
        var result = run(request); assertFalse(result.getIfAllSuccess().get());
        assertTrue(result.getTableResultList().getFirst().getTypeMappingPlan().rejectedColumns().stream()
                .anyMatch(column -> column.column().equals("d") && column.codes(TypeMappingIssue.Severity.BLOCKER).contains("DECIMAL_STORED_AS_REAL_OR_TEXT")));
        assertEquals(List.of("0"), rows(postgres, "SELECT count(*) FROM information_schema.tables WHERE table_schema='" + pgSchema + "' AND table_name='copied'"));
    }

    @Test void anUnverifiedUtf16TargetCannotBeClearedAfterTypeLossesWereAcknowledged() throws Exception {
        sql(postgres, "CREATE TABLE " + pgSchema + ".source(id bigint PRIMARY KEY,n integer)");
        sql(postgres, "INSERT INTO " + pgSchema + ".source VALUES(1,7)");
        sql(sqlite, "PRAGMA encoding='UTF-16'");
        sql(sqlite, "CREATE TABLE copied(id INTEGER PRIMARY KEY,n INTEGER)"); sql(sqlite, "INSERT INTO copied VALUES(9,999)");
        assertEquals(List.of("UTF-16le"), rows(sqlite, "PRAGMA encoding"));
        var request = request(POSTGRESQL, false); acknowledge(request);
        assertFalse(run(request).getIfAllSuccess().get());
        assertEquals(List.of("9|999"), rows(sqlite, "SELECT id,n FROM copied"));
    }

    @Test void acceptedReplacementUsesTheAcknowledgedPlanBeforeChangingTheOldTable() throws Exception {
        sql(sqlite, "CREATE TABLE source(id INTEGER PRIMARY KEY,n INTEGER)"); sql(sqlite, "INSERT INTO source VALUES(9007199254740993,7)");
        sql(postgres, "CREATE TABLE " + pgSchema + ".copied(id bigint PRIMARY KEY,n bigint)"); sql(postgres, "INSERT INTO " + pgSchema + ".copied VALUES(9,999)");
        var request = request(SQLITE3, true); acknowledge(request);
        var result = run(request); assertSuccess(result);
        assertTrue(result.getTableResultList().getFirst().getTypeMappingPlan().accepted());
        assertEquals(List.of("9007199254740993|7"), rows(postgres, "SELECT id,n FROM " + pgSchema + ".copied"));
    }

    private MigrationRequest request(ProductTypeEnum from, boolean replace) {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(from); source.setUrl(from == SQLITE3 ? sqliteUrl : System.getProperty("datahub.test.postgres.url"));
        source.setDriverClassName(from.getDriveClassName()); source.setPoolSize(1); source.setFetchSize(2);
        source.setSourceSchema(from == SQLITE3 ? "main" : pgSchema); source.setSourceIncludes("source");
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied")));
        if (from == POSTGRESQL) { source.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test")); source.setPassword(System.getProperty("datahub.test.postgres.password", "")); }
        request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(from == SQLITE3 ? POSTGRESQL : SQLITE3);
        target.setUrl(from == SQLITE3 ? System.getProperty("datahub.test.postgres.url") : sqliteUrl);
        target.setDriverClassName(target.getTargetProductType().getDriveClassName()); target.setPoolSize(1);
        target.setTargetSchema(from == SQLITE3 ? pgSchema : "main"); target.setWriterEngineInsert(true); target.setTargetDrop(replace);
        if (from == SQLITE3) { target.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test")); target.setPassword(System.getProperty("datahub.test.postgres.password", "")); }
        return request;
    }

    private void acknowledge(MigrationRequest request) throws SQLException {
        var from = request.getSource().getFirst(); var target = request.getTarget();
        try (var connection = (from.getSourceProductType() == SQLITE3 ? sqlite : postgres).getConnection()) {
            var schema = NativeSchemaReader.read(connection, from.getSourceProductType(), from.getSourceSchema(), "source", 10);
            var plan = runtime.products().typeMappings().plan(target.getTargetProductType(), schema.columns(), schema.primaryKeys(), LossyColumnAcceptance.rejectAll());
            request.setTypeMappingAcknowledgements(plan.columns().stream().filter(column -> column.fidelity() == TypeFidelity.LOSSY)
                    .map(column -> LossyMappingAcknowledgement.forColumn(target.getTargetSchema(), "copied", column)).toList());
        }
    }

    private DbSwitchResult run(MigrationRequest request) throws Exception {
        var service = new MigrationService(request, ignored -> { }, null, runtime);
        var result = service.run(); service.physicalExit().toCompletableFuture().get(30, TimeUnit.SECONDS); return result;
    }

    private static void assertSuccess(DbSwitchResult result) { assertEquals(1, result.getTableResultList().size()); assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); }
    private static void sql(DataSource source, String text) throws SQLException { try (var connection = source.getConnection(); var statement = connection.createStatement()) { statement.setQueryTimeout(30); statement.execute(text); } }
    private static List<String> rows(DataSource source, String text) throws SQLException {
        var result = new ArrayList<String>();
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(text)) {
            while (rows.next()) { var values = new ArrayList<String>(); for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) values.add(String.valueOf(rows.getString(column))); result.add(String.join("|", values)); }
        }
        return result;
    }
}
