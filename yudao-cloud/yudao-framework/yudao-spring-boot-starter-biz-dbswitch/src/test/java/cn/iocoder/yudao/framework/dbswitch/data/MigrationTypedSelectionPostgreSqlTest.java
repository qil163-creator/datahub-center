package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native quoted identifiers and real FULL / primary-key difference migrations; isolated schemas only. */
@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationTypedSelectionPostgreSqlTest {
    private final String source = "literal_src_" + UUID.randomUUID().toString().replace("-", "");
    private final String target = "literal_dst_" + UUID.randomUUID().toString().replace("-", "");
    @BeforeEach void setup() throws Exception { sql("CREATE SCHEMA " + source, "CREATE SCHEMA " + target); }
    @AfterEach void cleanup() throws Exception { sql("DROP SCHEMA IF EXISTS " + source + " CASCADE", "DROP SCHEMA IF EXISTS " + target + " CASCADE"); }

    static java.util.stream.Stream<Arguments> fullModes() {
        return List.of("a,b", "report?", "a*b", " leading", "trailing\u3000").stream()
                .flatMap(name -> java.util.stream.Stream.of(Arguments.of(name, true), Arguments.of(name, false)));
    }

    @ParameterizedTest @MethodSource("fullModes")
    void fullMigrationSelectsExactlyOneLiteralPhysicalTableAndNoDecoy(String name, boolean insert) throws Exception {
        createSource(name); createSource("report1"); createSource("axb"); createSource("a"); createSource("b");
        var request = request(name); request.getTarget().setWriterEngineInsert(insert); assertSuccess(request);
        assertEquals(List.of(name), targetTables()); assertRows(target, name, "1:source");
    }

    @ParameterizedTest @ValueSource(strings={"a,b", "report?", "a*b", " leading", "trailing\u3000"})
    void primaryKeyDifferenceUpdatesTheLiteralTargetWithoutTouchingOrdinaryDecoys(String name) throws Exception {
        createSource(name); createSource("report1");
        sql("CREATE TABLE " + relation(target, name) + "(id integer PRIMARY KEY,note text)",
                "INSERT INTO " + relation(target, name) + " VALUES(1,'old'),(2,'delete')",
                "CREATE TABLE " + relation(target, "report1") + "(id integer PRIMARY KEY,note text)",
                "INSERT INTO " + relation(target, "report1") + " VALUES(99,'untouched')");
        var request = request(name); request.getTarget().setChangeDataSync(true);
        request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE"); assertSuccess(request);
        assertRows(target, name, "1:source"); assertRows(target, "report1", "99:untouched");
    }

    @Test void excludingAQuotedPatternLikeNameDoesNotExcludeMatchingOrdinaryTables() throws Exception {
        createSource("report?"); createSource("report1"); createSource("a,b");
        var request = request("report?"); request.getSource().getFirst().setIncludeOrExclude(0); assertSuccess(request);
        assertEquals(Set.of("report1", "a,b"), Set.copyOf(targetTables()));
        assertRows(target, "report1", "1:source"); assertRows(target, "a,b", "1:source");
    }

    private MigrationRequest request(String name) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.POSTGRESQL); input.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        input.setUrl(url()); input.setUsername(user()); input.setPassword(password()); input.setSourceSchema(source);
        input.setIncludeOrExclude(1); input.setSourceTableNames(List.of(name)); input.setPoolSize(1); input.setFetchSize(1);
        request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        output.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName()); output.setUrl(url()); output.setUsername(user()); output.setPassword(password());
        output.setTargetSchema(target); output.setPoolSize(1); output.setWriterEngineInsert(true); output.setSyncExist(true);
        return request;
    }
    private void createSource(String name) throws Exception {
        sql("CREATE TABLE " + relation(source, name) + "(id integer PRIMARY KEY,note text)", "INSERT INTO " + relation(source, name) + " VALUES(1,'source')");
    }
    private void assertSuccess(MigrationRequest request) throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> { }, null, runtime).run();
            assertTrue(result.getIfAllSuccess().get(), () -> "Literal migration must succeed; failedTables=" + result.getTotalTableFailCount());
            assertTrue(result.getTotalTableSuccessCount().get() > 0);
        }
    }
    private List<String> targetTables() throws Exception {
        var result = new ArrayList<String>();
        try (var connection = open(); var query = connection.prepareStatement("SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname=? ORDER BY tablename")) {
            query.setString(1, target); try (var rows = query.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
        }
        return result;
    }
    private static void assertRows(String schema, String name, String... expected) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,note FROM " + relation(schema, name) + " ORDER BY id")) {
            while (rows.next()) result.add(rows.getInt(1) + ":" + rows.getString(2));
        }
        assertEquals(List.of(expected), result);
    }
    private static String relation(String schema, String name) { return ProductTypeEnum.POSTGRESQL.quoteSchemaTableName(schema, name); }
    private static void sql(String... statements) throws Exception { try (var connection = open(); var statement = connection.createStatement()) { for (String sql : statements) statement.execute(sql); } }
    private static Connection open() throws Exception { return DriverManager.getConnection(url(), user(), password()); }
    private static String url() { return System.getProperty("datahub.test.postgres.url"); }
    private static String user() { return System.getProperty("datahub.test.postgres.user", "dataforge_test"); }
    private static String password() { return System.getProperty("datahub.test.postgres.password", ""); }
}
