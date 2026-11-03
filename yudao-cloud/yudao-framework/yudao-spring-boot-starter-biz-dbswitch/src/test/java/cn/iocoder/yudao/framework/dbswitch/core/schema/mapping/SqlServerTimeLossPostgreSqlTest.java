package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.time.LocalTime;
import java.util.*;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public migration contract on owned native SQL Server/PG tables; no source read truncation. */
@Timeout(240)
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_TEST_SQLSERVER_PASSWORD",matches=".+")
class SqlServerTimeLossPostgreSqlTest {
    private final String schema = "lossclock_" + UUID.randomUUID().toString().replace("-", "");
    private DbSwitchRuntime runtime;
    private Connection source, target;

    @BeforeEach void setup() throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver"); Class.forName("org.postgresql.Driver");
        source = DriverManager.getConnection(sqlUrl(), "sa", sqlPassword());
        target = DriverManager.getConnection(pgUrl(), pgUser(), System.getProperty("datahub.test.postgres.password", ""));
        assertEquals(16, source.getMetaData().getDatabaseMajorVersion());
        assertTrue(Set.of(16,18).contains(target.getMetaData().getDatabaseMajorVersion()));
        runtime = DbSwitchRuntime.standalone();
        sql(source, "CREATE SCHEMA [" + schema + "]"); sql(target, "CREATE SCHEMA \"" + schema + "\"");
        sql(source, "CREATE TABLE [" + schema + "].src(v time(7) NULL,k bigint NOT NULL PRIMARY KEY)");
        sql(source, "INSERT INTO [" + schema + "].src VALUES('23:59:59.9999999',1),('00:00:00.0000001',2),('12:34:56.1234565',3),(NULL,4),('00:00:00',5)");
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (source != null) try (var c = source) {
                sql(c, "DROP TABLE IF EXISTS [" + schema + "].src"); sql(c, "DROP SCHEMA [" + schema + "]");
            }
        } finally {
            try { if (target != null) try (var c = target) { sql(c, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); } }
            finally { if (runtime != null) runtime.close(); }
        }
    }

    @ParameterizedTest @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void exactScopedAckFloorsOnlyTargetValuesAndFullDiffRepeatConverges(boolean existingTarget, boolean insertWriter) throws Exception {
        if (existingTarget) createTarget("time(6) without time zone", false);
        var request = request();
        request.getTarget().setWriterEngineInsert(insertWriter);
        var denied = run(request, false); rejected(denied);
        var column = mapping(denied);
        request.setTypeMappingAcknowledgements(List.of(LossyMappingAcknowledgement.forColumn(schema, "dst", column)));
        var full = run(request, false); success(full); assertEquals(Set.of("clock"), full.getTableResultList().getFirst().getTypeMappingPlan().acceptedLossyColumns());
        assertEquals(Arrays.asList(LocalTime.parse("23:59:59.999999"), LocalTime.MIDNIGHT, LocalTime.parse("12:34:56.123456"), null, LocalTime.MIDNIGHT), values(target, "\"" + schema + "\".dst", "clock"));
        assertTrue(full.getTableResultList().getFirst().getTransferSummary().transformed());
        assertEquals(LocalTime.parse("23:59:59.9999999"), values(source, "[" + schema + "].src", "v").getFirst());
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(sqlUrl(), "sa", sqlPassword());
        try (var rows = runtime.reader(ds, SQLSERVER).queryTableData(schema, "src", List.of("v", "k"), List.of("k"))) {
            var rs = rows.getResultset(); assertTrue(rs.next());
            assertEquals(LocalTime.parse("23:59:59.9999999"), rs.getObject(1));
        }
        var repeated = run(request, true); success(repeated); assertEquals(0, count(repeated));
        sql(source, "UPDATE [" + schema + "].src SET v='12:34:56.1234569' WHERE k=3");
        var sameBucket = run(request, true); success(sameBucket); assertEquals(0, count(sameBucket));
        sql(source, "UPDATE [" + schema + "].src SET v='12:34:56.1234571' WHERE k=3");
        sql(source, "DELETE FROM [" + schema + "].src WHERE k=5");
        sql(source, "INSERT INTO [" + schema + "].src VALUES('00:00:00.0000019',6)");
        var diff = run(request, true); success(diff); assertEquals(3, count(diff));
        assertEquals(Arrays.asList(LocalTime.parse("23:59:59.999999"), LocalTime.MIDNIGHT, LocalTime.parse("12:34:56.123457"), null, LocalTime.parse("00:00:00.000001")), values(target, "\"" + schema + "\".dst", "clock"));
        repeated = run(request, true); success(repeated); assertEquals(0, count(repeated));
    }

    @Test void timeKeyCollisionIsUnsupportedBeforeAnyOverwrite() throws Exception {
        sql(source, "DROP TABLE [" + schema + "].src");
        sql(source, "CREATE TABLE [" + schema + "].src(v time(7) NOT NULL PRIMARY KEY,k bigint NOT NULL)");
        sql(source, "INSERT INTO [" + schema + "].src VALUES('12:00:00.0000001',1),('12:00:00.0000009',2)");
        createTarget("time(6) without time zone", true);
        var request = request(); request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        request.getTarget().setWriteMode("OVERWRITE");
        var result = run(request, false); rejected(result); assertSentinel();
        assertEquals(TypeFidelity.UNSUPPORTED, mapping(result).fidelity());
        assertEquals("LOSSY_TIME_KEY_UNSUPPORTED", mapping(result).reasonCode());
    }

    @ParameterizedTest @ValueSource(strings={"none","product","schema","table","column","fingerprint","code","historical"})
    void impreciseAcknowledgementRejectsBeforeExplicitReplacement(String mismatch) throws Exception {
        createTarget("time(6) without time zone", true);
        var request = request(); var mapping = mapping(run(request, false));
        var ack = LossyMappingAcknowledgement.forColumn(schema, "dst", mapping);
        if (mismatch.equals("historical")) {
            var old = new ColumnTypeMapping(mapping.column(), mapping.sourceProduct(), mapping.sourceNativeType(), mapping.logicalType(),
                    mapping.targetProduct(), mapping.targetNativeType(), mapping.fidelity(),
                    List.of(TypeMappingIssue.loss("TIME_FRACTION_TRUNCATED", "PostgreSQL time keeps microseconds")), mapping.definition());
            ack = LossyMappingAcknowledgement.forColumn(schema, "dst", old);
        } else ack = new LossyMappingAcknowledgement(mismatch.equals("product") ? MYSQL : POSTGRESQL,
                mismatch.equals("schema") ? schema + "x" : schema, mismatch.equals("table") ? "other" : "dst",
                mismatch.equals("column") ? "v" : "clock", mismatch.equals("fingerprint") ? "0".repeat(64) : ack.mappingFingerprint(),
                mismatch.equals("code") ? Set.of("TIME_24_00_WRAPPED") : ack.lossCodes());
        request.setTypeMappingAcknowledgements(mismatch.equals("none") ? List.of() : List.of(ack));
        request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        request.getTarget().setWriteMode("OVERWRITE");
        rejected(run(request, false)); assertSentinel();
    }

    @ParameterizedTest @ValueSource(strings={"time(0) without time zone","time(3) without time zone","time(6) with time zone","clock_domain"})
    void correctAckCannotOverwriteAContradictoryExistingTarget(String type) throws Exception {
        if (type.equals("clock_domain")) {
            sql(target, "CREATE DOMAIN \"" + schema + "\".clock_domain AS time(6)"); type = "\"" + schema + "\".clock_domain";
        }
        createTarget(type, true);
        var request = request(); var mapping = mapping(run(request, false));
        request.setTypeMappingAcknowledgements(List.of(LossyMappingAcknowledgement.forColumn(schema, "dst", mapping)));
        request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        request.getTarget().setWriteMode("OVERWRITE");
        var full = run(request, false); rejected(full); assertSentinel();
        assertTrue(full.getTableResultList().getFirst().getTypeMappingPlan().accepted());
        // Public errors intentionally redact causes; prove accepted policy plus unchanged physical target.
        assertTrue(full.getTableResultList().getFirst().getErrorMsg().contains("externalWriteUnconfirmed=false"));
        request.getTarget().setTargetDrop(false); request.getTarget().setTargetTruncateData(false);
        request.getTarget().setWriteMode(null);
        var diff = run(request, true); rejected(diff); assertSentinel();
        assertTrue(diff.getTableResultList().getFirst().getTypeMappingPlan().accepted());
        assertTrue(diff.getTableResultList().getFirst().getErrorMsg().contains("externalWriteUnconfirmed=false"));
    }

    private void createTarget(String type, boolean sentinel) throws SQLException {
        sql(target, "CREATE TABLE \"" + schema + "\".dst(clock " + type + ",k bigint NOT NULL PRIMARY KEY)");
        if (sentinel) sql(target, "INSERT INTO \"" + schema + "\".dst VALUES('12:00:00',77)");
    }
    private void assertSentinel() throws SQLException {
        try (var s = target.createStatement(); var r = s.executeQuery("SELECT k FROM \"" + schema + "\".dst")) {
            assertTrue(r.next()); assertEquals(77, r.getLong(1)); assertFalse(r.next());
        }
    }
    private MigrationRequest request() {
        var request = new MigrationRequest(); var sourceProperties = new SourceDataSourceProperties();
        sourceProperties.setSourceProductType(SQLSERVER); sourceProperties.setUrl(sqlUrl()); sourceProperties.setUsername("sa"); sourceProperties.setPassword(sqlPassword());
        sourceProperties.setSourceSchema(schema); sourceProperties.setSourceType(1); sourceProperties.setIncludeOrExclude(1);
        sourceProperties.setSourceTableNames(List.of("src")); sourceProperties.setPoolSize(1); sourceProperties.setFetchSize(2);
        sourceProperties.setRegexTableMapper(List.of(new PatternMapper("^src$", "dst")));
        sourceProperties.setRegexColumnMapper(List.of(new PatternMapper("^v$", "clock"))); request.getSource().add(sourceProperties);
        var destination = request.getTarget(); destination.setTargetProductType(POSTGRESQL); destination.setUrl(pgUrl());
        destination.setUsername(pgUser()); destination.setPassword(System.getProperty("datahub.test.postgres.password", ""));
        destination.setTargetSchema(schema); destination.setPoolSize(1); destination.setWriterEngineInsert(true); destination.setTargetTruncateData(false);
        return request;
    }
    private DbSwitchResult run(MigrationRequest request, boolean diff) throws Exception {
        request.getTarget().setChangeDataSync(diff); if (diff) request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        return new MigrationService(request, ignored -> {}, null, runtime).run();
    }
    private static ColumnTypeMapping mapping(DbSwitchResult result) { return result.getTableResultList().getFirst().getTypeMappingPlan().column("clock"); }
    private static long count(DbSwitchResult result) { return result.getTableResultList().getFirst().getSyncCount().get(); }
    private static void success(DbSwitchResult result) { assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); }
    private static void rejected(DbSwitchResult result) { assertFalse(result.getIfAllSuccess().get()); assertEquals(0, count(result)); }
    private static List<LocalTime> values(Connection connection, String table, String column) throws SQLException {
        var values = new ArrayList<LocalTime>(); try (var s = connection.createStatement(); var r = s.executeQuery("SELECT " + column + " FROM " + table + " ORDER BY k")) {
            while (r.next()) values.add(r.getObject(1, LocalTime.class));
        } return values;
    }
    private static void sql(Connection connection, String query) throws SQLException { try (var s = connection.createStatement()) { s.setQueryTimeout(30); s.execute(query); } }
    private static String sqlUrl() { return System.getProperty("datahub.test.sqlserver.url"); }
    private static String sqlPassword() { return System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD"); }
    private static String pgUrl() { return System.getProperty("datahub.test.postgres.url"); }
    private static String pgUser() { return System.getProperty("datahub.test.postgres.user", "dataforge_test"); }
}
