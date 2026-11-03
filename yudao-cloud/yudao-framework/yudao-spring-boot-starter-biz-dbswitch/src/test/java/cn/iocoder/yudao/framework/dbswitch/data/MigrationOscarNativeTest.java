package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@Timeout(90)
@EnabledIfSystemProperty(named = "datahub.test.oscar.url", matches = "jdbc:oscar://127[.]0[.]0[.]1:[0-9]+/OSRDB")
@EnabledIfSystemProperty(named = "datahub.test.oscar.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_OSCAR_PASSWORD", matches = ".+")
class MigrationOscarNativeTest {
    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void fullDifferenceAndReplayPreserveBasicValuesAndPhysicalNames(boolean quoted, boolean autoCreate) throws Exception {
        String schema = "MIG88_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String sourceTable = quoted ? "Mixed;\"Source" : "SOURCE";
        String targetTable = quoted ? "Mixed;\"Target" : "TARGET";
        String sourceRelation = q(schema) + "." + q(sourceTable), targetRelation = q(schema) + "." + q(targetTable);
        try (var driver = new VerifiedDriverTestHandle(Path.of(System.getProperty("datahub.test.oscar.driver")), ProductTypeEnum.OSCAR)) {
            var source = driver.dataSource(System.getProperty("datahub.test.oscar.url"), "com.oscar.Driver",
                    System.getenv("DATAHUB_TEST_OSCAR_USER"), System.getenv("DATAHUB_TEST_OSCAR_PASSWORD"), new Properties());
            try (var connection = source.getConnection(); var sql = connection.createStatement()) {
                sql.setQueryTimeout(10); sql.execute("CREATE SCHEMA " + q(schema));
                try {
                    String definition = "(ID BIGINT PRIMARY KEY,AMOUNT NUMERIC(24,6),NOTE VARCHAR(100),D DATE)";
                    sql.execute("CREATE TABLE " + sourceRelation + definition);
                    if (!autoCreate) sql.execute("CREATE TABLE " + targetRelation + definition);
                    try (var insert = connection.prepareStatement("INSERT INTO " + sourceRelation + " VALUES(?,?,?,?)")) {
                        insert.setQueryTimeout(10);
                        for (int i = 0; i < 503; i++) {
                            insert.setLong(1, 5_000_000_000L + i); insert.setBigDecimal(2, new BigDecimal("123456789012345678.123456"));
                            insert.setString(3, "中文 O'Reilly " + i); insert.setDate(4, java.sql.Date.valueOf("2024-02-29")); insert.addBatch();
                        }
                        assertEquals(503, insert.executeBatch().length);
                    }
                    var request = request(schema, sourceTable, targetTable);
                    success(run(request, source)); assertEquals(rows(connection, sourceRelation), rows(connection, targetRelation));
                    try (var metadata = connection.prepareStatement("SELECT AMOUNT FROM " + targetRelation + " WHERE 1=0");
                         var empty = metadata.executeQuery()) {
                        assertEquals(24, empty.getMetaData().getPrecision(1));
                        assertEquals(6, empty.getMetaData().getScale(1));
                    }
                    sql.execute("UPDATE " + sourceRelation + " SET NOTE='updated 中文' WHERE ID=5000000000");
                    sql.execute("DELETE FROM " + sourceRelation + " WHERE ID=5000000001");
                    sql.execute("INSERT INTO " + sourceRelation + " VALUES(6000000000,0.000001,NULL,NULL)");
                    request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
                    success(run(request, source)); assertEquals(rows(connection, sourceRelation), rows(connection, targetRelation));
                    var replay = run(request, source); success(replay);
                    assertEquals(0, replay.getTableResultList().getFirst().getSyncCount().get());
                } finally { sql.execute("DROP SCHEMA " + q(schema) + " CASCADE"); }
            }
        }
    }

    private static MigrationRequest request(String schema, String sourceTable, String targetTable) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.OSCAR); input.setUrl(System.getProperty("datahub.test.oscar.url"));
        input.setUsername(System.getenv("DATAHUB_TEST_OSCAR_USER")); input.setPassword(System.getenv("DATAHUB_TEST_OSCAR_PASSWORD"));
        input.setSourceSchema(schema); input.setSourceTableNames(List.of(sourceTable)); input.setIncludeOrExclude(1);
        input.setPoolSize(2); input.setFetchSize(17);
        input.setRegexTableMapper(List.of(new PatternMapper("^" + Pattern.quote(sourceTable) + "$", targetTable)));
        request.getSource().add(input); var output = request.getTarget();
        output.setTargetProductType(ProductTypeEnum.OSCAR); output.setUrl(input.getUrl()); output.setUsername(input.getUsername());
        output.setPassword(input.getPassword()); output.setTargetSchema(schema); output.setPoolSize(2);
        return request;
    }

    private static DbSwitchResult run(MigrationRequest request, DataSource source) throws Exception {
        var pools = new ArrayList<HikariDataSource>();
        try (var factories = mockStatic(DataSourceUtils.class)) {
            factories.when(() -> DataSourceUtils.createSourceDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any()))
                    .thenAnswer(call -> pool(source, call.getArgument(1), pools));
            factories.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any()))
                    .thenAnswer(call -> pool(source, call.getArgument(1), pools));
            return new MigrationService(request, ignored -> { }).run();
        } finally { pools.forEach(HikariDataSource::close); }
    }

    private static HikariDataSource pool(DataSource source, ThreadFactory threads, List<HikariDataSource> pools) {
        var config = new HikariConfig(); config.setDataSource(source); config.setMaximumPoolSize(2); config.setMinimumIdle(0);
        config.setConnectionTestQuery("SELECT 1"); config.setConnectionTimeout(10000); config.setInitializationFailTimeout(10000);
        config.setThreadFactory(threads); var pool = new HikariDataSource(config); pools.add(pool); return pool;
    }
    private static List<List<Object>> rows(Connection connection, String relation) throws Exception {
        var values = new ArrayList<List<Object>>();
        try (var query = connection.createStatement(); var result = query.executeQuery("SELECT ID,AMOUNT,NOTE,D FROM " + relation + " ORDER BY ID")) {
            while (result.next()) values.add(Arrays.asList(result.getLong(1), result.getBigDecimal(2), result.getString(3), result.getDate(4)));
        }
        return values;
    }
    private static String q(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private static void success(DbSwitchResult result) { assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); }
}
