package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class MysqlPrimaryKeyMetadataMySqlTest {
    private final String schema = "primary_key_" + UUID.randomUUID().toString().replace("-", "");
    private Connection connection;
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(url(), user(), password());
        runtime = DbSwitchRuntime.standalone();
        sql("CREATE DATABASE " + schema);
    }

    @AfterEach void cleanup() throws Exception {
        if (runtime != null) runtime.close();
        if (connection != null) {
            try (var owned = connection) { sql("DROP DATABASE IF EXISTS " + schema); }
        }
    }

    @Test void compositeKeyOrderSurvivesTheActualFullMigration() throws Exception {
        sql("CREATE TABLE " + schema + ".ordered_source(a int NOT NULL,z int NOT NULL,note varchar(40),PRIMARY KEY(z,a)) ENGINE=InnoDB");
        sql("INSERT INTO " + schema + ".ordered_source VALUES(1,8,'first'),(2,7,'second')");
        var metadata = runtime.jdbcMetadata(ProductTypeEnum.MYSQL);
        assertEquals(List.of("z", "a"), metadata.queryTablePrimaryKeys(url(), user(), password(), schema, "ordered_source"));
        var request = new MigrationRequest();
        var input = new SourceDataSourceProperties(); input.setSourceProductType(ProductTypeEnum.MYSQL);
        input.setDriverClassName(ProductTypeEnum.MYSQL.getDriveClassName());
        input.setUrl(url()); input.setUsername(user()); input.setPassword(password()); input.setPoolSize(1);
        input.setSourceSchema(schema); input.setSourceIncludes("ordered_source");
        input.setRegexTableMapper(List.of(new cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper(
                "^ordered_source$", "copied")));
        request.getSource().add(input);
        var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.MYSQL);
        target.setDriverClassName(ProductTypeEnum.MYSQL.getDriveClassName());
        target.setUrl(url()); target.setUsername(user()); target.setPassword(password()); target.setPoolSize(1);
        target.setTargetSchema(schema); target.setTargetTable("copied"); target.setWriterEngineInsert(true);
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertEquals(List.of("z", "a"), metadata.queryTablePrimaryKeys(url(), user(), password(), schema, "copied"));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT a,z,note FROM " + schema + ".copied ORDER BY a")) {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals(8, rows.getInt(2)); assertEquals("first", rows.getString(3));
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertEquals(7, rows.getInt(2)); assertEquals("second", rows.getString(3));
            assertFalse(rows.next());
        }
    }

    @Test void quotedSpecialTableNamesAreBoundAsNamesNotSqlOrPatterns() throws Exception {
        String name = "odd'_%`key";
        sql("CREATE TABLE " + schema + ".`" + name.replace("`", "``") + "`(a int,z int,PRIMARY KEY(z,a))");
        sql("CREATE TABLE " + schema + ".keyless(value int)");
        var metadata = runtime.jdbcMetadata(ProductTypeEnum.MYSQL);
        assertEquals(List.of("z", "a"), metadata.queryTablePrimaryKeys(url(), user(), password(), schema, name));
        assertEquals(List.of(), metadata.queryTablePrimaryKeys(url(), user(), password(), schema, "keyless"));
        assertEquals(List.of(), metadata.queryTablePrimaryKeys(url(), user(), password(), schema, "' OR '1'='1"));
    }

    private void sql(String sql) throws Exception { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private static String url() { return System.getProperty("datahub.test.mysql.url"); }
    private static String user() { return System.getProperty("datahub.test.mysql.user", "root"); }
    private static String password() { return System.getProperty("datahub.test.mysql.password", ""); }
}
