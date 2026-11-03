package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.time.LocalTime;
import java.util.*;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native scale and JDBC 4.2 transport are independent of the historical java.sql.Time codec. */
@Timeout(240)
class SqlServerTimePrecisionTest {
    private final String schema = "stime_" + UUID.randomUUID().toString().replace("-", "");
    private DbSwitchRuntime runtime;
    private Connection sqlserver, postgres;

    @AfterEach void cleanup() throws Exception {
        try {
            if (sqlserver != null) try (var c = sqlserver) {
                var names = new ArrayList<String>();
                try (var s = c.prepareStatement("SELECT t.name FROM sys.tables t JOIN sys.schemas n ON n.schema_id=t.schema_id WHERE n.name=?")) {
                    s.setString(1, schema); try (var r = s.executeQuery()) { while (r.next()) names.add(r.getString(1)); }
                }
                for (String name : names) sql(c, "DROP TABLE [" + schema + "].[" + name + "]");
                sql(c, "DROP SCHEMA [" + schema + "]");
            }
        } finally {
            try { if (postgres != null) try (var c = postgres) { sql(c, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); } }
            finally { if (runtime != null) runtime.close(); }
        }
    }

    @ParameterizedTest @ValueSource(ints={4,5,6,7})
    void mappingKeepsSourceAndSqlserverTargetFractionalDigits(int digits) {
        var mapper = DbSwitchProductRegistry.discover().typeMapper(SQLSERVER).orElseThrow();
        var logical = mapper.toLogical(SqlFamilyMappingFixtures.family(SQLSERVER, "v", "time(" + digits + ")"));
        assertEquals(TypeFidelity.EXACT, logical.fidelity());
        var time = assertInstanceOf(LogicalType.TimeType.class, logical.value());
        assertEquals(digits, time.fractionalDigits());
        var target = mapper.toNative(new LogicalColumn("v", time, true, false, SQLSERVER, "time(" + digits + ")"));
        assertEquals(TypeFidelity.EXACT, target.fidelity());
        assertEquals("time(" + digits + ")", target.value().typeSql());
    }

    @ParameterizedTest @ValueSource(ints={8,9})
    void finerLogicalTimeStillDeclaresSqlserverPrecisionLoss(int digits) {
        var mapper = DbSwitchProductRegistry.discover().typeMapper(SQLSERVER).orElseThrow();
        var target = mapper.toNative(new LogicalColumn("v", new LogicalType.TimeType(digits, false, LogicalType.TimeRange.TIME_OF_DAY),
                true, false, SQLSERVER, "time(" + digits + ")"));
        assertEquals(TypeFidelity.LOSSY, target.fidelity());
        assertEquals("time(7)", target.value().typeSql());
        assertEquals(List.of("TIME_FRACTION_TRUNCATED"), target.issues().stream().map(TypeMappingIssue::code).toList());
    }

    @ParameterizedTest @ValueSource(ints={4,5,6})
    @EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
    @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named="DATAHUB_TEST_SQLSERVER_PASSWORD",matches=".+")
    void sqlserverMicrosecondTimeCreatesAndSynchronizesExactPostgresPrecision(int digits) throws Exception {
        nativeSetup();
        sql(sqlserver, "CREATE TABLE [" + schema + "].src(k bigint NOT NULL PRIMARY KEY,v time(" + digits + ") NULL)");
        String fraction = "123456".substring(0, digits);
        sql(sqlserver, "INSERT INTO [" + schema + "].src VALUES(1,'23:59:59." + fraction + "'),(2,NULL),(3,'00:00:00')");
        var request = request(true);
        var full = run(request, false); success(full);
        assertEquals(TypeFidelity.EXACT, mapping(full).fidelity());
        try (var s = postgres.createStatement(); var r = s.executeQuery("SELECT pg_catalog.format_type(a.atttypid,a.atttypmod) FROM pg_catalog.pg_attribute a WHERE a.attrelid='\"" + schema + "\".dst'::regclass AND a.attname='v'")) {
            assertTrue(r.next()); assertEquals("time(" + digits + ") without time zone", r.getString(1));
        }
        assertEquals(values(sqlserver, "[" + schema + "].src"), values(postgres, "\"" + schema + "\".dst"));
        sql(sqlserver, "UPDATE [" + schema + "].src SET v='12:34:56." + "987654".substring(0, digits) + "' WHERE k=1");
        sql(sqlserver, "DELETE FROM [" + schema + "].src WHERE k=3");
        sql(sqlserver, "INSERT INTO [" + schema + "].src VALUES(4,'00:00:00." + "0".repeat(digits-1) + "1')");
        var diff = run(request, true); success(diff); assertEquals(3, diff.getTableResultList().getFirst().getSyncCount().get());
        assertEquals(values(sqlserver, "[" + schema + "].src"), values(postgres, "\"" + schema + "\".dst"));
        var repeated = run(request, true); success(repeated); assertEquals(0, repeated.getTableResultList().getFirst().getSyncCount().get());
    }

    @Test
    @EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
    @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named="DATAHUB_TEST_SQLSERVER_PASSWORD",matches=".+")
    void hundredNanosecondSourcePreviewAndDefaultExecutionRejectPostgresPrecisionLoss() throws Exception {
        nativeSetup();
        sql(sqlserver, "CREATE TABLE [" + schema + "].src(k bigint NOT NULL PRIMARY KEY,v time(7) NULL)");
        sql(sqlserver, "INSERT INTO [" + schema + "].src VALUES(1,'23:59:59.1234567'),(2,NULL)");
        var columns = runtime.metadata(new org.springframework.jdbc.datasource.DriverManagerDataSource(sqlUrl(), "sa", sqlPassword()), SQLSERVER)
                .queryTableColumnMetaWithNativeEvidence(schema, "src");
        var mapped = runtime.products().typeMappings().map(POSTGRESQL, columns.get(1), false);
        assertEquals(TypeFidelity.LOSSY, mapped.fidelity());
        assertEquals("time(6) without time zone", mapped.targetNativeType());
        assertEquals(Set.of("TIME_FRACTION_TRUNCATED"), mapped.lossCodes());
        var rejected = run(request(true), false); rejected(rejected);
        try (var s = postgres.prepareStatement("SELECT count(*) FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname='dst'")) {
            s.setString(1, schema); try (var r = s.executeQuery()) { assertTrue(r.next()); assertEquals(0, r.getInt(1)); }
        }
        assertEquals(Arrays.asList(LocalTime.parse("23:59:59.1234567"), null), values(sqlserver, "[" + schema + "].src"));
    }

    @Test
    @EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
    @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named="DATAHUB_TEST_SQLSERVER_PASSWORD",matches=".+")
    void postgresMicrosecondSourceRetainsRangeAcknowledgementAndExactFullDiffFractions() throws Exception {
        nativeSetup();
        sql(postgres, "CREATE TABLE \"" + schema + "\".src(k bigint NOT NULL PRIMARY KEY,v time(6))");
        sql(postgres, "INSERT INTO \"" + schema + "\".src VALUES(1,'23:59:59.123456'),(2,NULL),(3,'00:00:00.000001')");
        sql(sqlserver, "CREATE TABLE [" + schema + "].dst(k bigint NOT NULL PRIMARY KEY,v time(6) NULL)");
        var request = request(false); var denied = run(request, false); rejected(denied);
        assertEquals(List.of(), values(sqlserver, "[" + schema + "].dst"));
        var time = mapping(denied);
        assertEquals("time(6)", time.targetNativeType());
        assertEquals(Set.of("TIME_24_00_WRAPPED"), time.lossCodes());
        request.setTypeMappingAcknowledgements(List.of(LossyMappingAcknowledgement.forColumn(schema, "dst", time)));
        success(run(request, false));
        assertEquals(values(postgres, "\"" + schema + "\".src"), values(sqlserver, "[" + schema + "].dst"));
        sql(postgres, "UPDATE \"" + schema + "\".src SET v='12:34:56.987654' WHERE k=1");
        sql(postgres, "DELETE FROM \"" + schema + "\".src WHERE k=3");
        sql(postgres, "INSERT INTO \"" + schema + "\".src VALUES(4,'00:00:00.000002')");
        var diff = run(request, true); success(diff); assertEquals(3, diff.getTableResultList().getFirst().getSyncCount().get());
        assertEquals(values(postgres, "\"" + schema + "\".src"), values(sqlserver, "[" + schema + "].dst"));
        var repeated = run(request, true); success(repeated); assertEquals(0, repeated.getTableResultList().getFirst().getSyncCount().get());
    }

    private void nativeSetup() throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver"); Class.forName("org.postgresql.Driver");
        runtime = DbSwitchRuntime.standalone();
        sqlserver = DriverManager.getConnection(sqlUrl(), "sa", sqlPassword());
        assertEquals(16, sqlserver.getMetaData().getDatabaseMajorVersion());
        postgres = DriverManager.getConnection(pgUrl(), System.getProperty("datahub.test.postgres.user", "dataforge_test"), System.getProperty("datahub.test.postgres.password", ""));
        assertEquals(16, postgres.getMetaData().getDatabaseMajorVersion());
        sql(sqlserver, "CREATE SCHEMA [" + schema + "]"); sql(postgres, "CREATE SCHEMA \"" + schema + "\"");
    }
    private MigrationRequest request(boolean fromSqlserver) {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(fromSqlserver ? SQLSERVER : POSTGRESQL);
        source.setUrl(fromSqlserver ? sqlUrl() : pgUrl()); source.setUsername(fromSqlserver ? "sa" : System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        source.setPassword(fromSqlserver ? sqlPassword() : System.getProperty("datahub.test.postgres.password", ""));
        source.setSourceSchema(schema); source.setSourceType(1); source.setIncludeOrExclude(1);
        source.setSourceTableNames(List.of("src")); source.setPoolSize(1); source.setFetchSize(1);
        source.setRegexTableMapper(List.of(new PatternMapper("^src$", "dst"))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(fromSqlserver ? POSTGRESQL : SQLSERVER);
        target.setUrl(fromSqlserver ? pgUrl() : sqlUrl()); target.setUsername(fromSqlserver ? System.getProperty("datahub.test.postgres.user", "dataforge_test") : "sa");
        target.setPassword(fromSqlserver ? System.getProperty("datahub.test.postgres.password", "") : sqlPassword());
        target.setTargetSchema(schema); target.setPoolSize(1); target.setWriterEngineInsert(true); return request;
    }
    private DbSwitchResult run(MigrationRequest request, boolean diff) throws Exception {
        request.getTarget().setChangeDataSync(diff); if (diff) request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        return new MigrationService(request, ignored -> {}, null, runtime).run();
    }
    private static ColumnTypeMapping mapping(DbSwitchResult result) {
        return result.getTableResultList().getFirst().getTypeMappingPlan().columns().stream().filter(c -> c.column().equals("v")).findFirst().orElseThrow();
    }
    private static void success(DbSwitchResult result) { assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); }
    private static void rejected(DbSwitchResult result) { assertFalse(result.getIfAllSuccess().get()); assertEquals(0, result.getTableResultList().getFirst().getSyncCount().get()); }
    private static List<LocalTime> values(Connection connection, String table) throws SQLException {
        var values = new ArrayList<LocalTime>(); try (var s = connection.createStatement(); var r = s.executeQuery("SELECT v FROM " + table + " ORDER BY k")) {
            while (r.next()) values.add(r.getObject(1, LocalTime.class));
        } return values;
    }
    private static void sql(Connection connection, String query) throws SQLException { try (var s = connection.createStatement()) { s.setQueryTimeout(30); s.execute(query); } }
    private static String sqlUrl() { return System.getProperty("datahub.test.sqlserver.url"); }
    private static String sqlPassword() { return System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD"); }
    private static String pgUrl() { return System.getProperty("datahub.test.postgres.url"); }
}
