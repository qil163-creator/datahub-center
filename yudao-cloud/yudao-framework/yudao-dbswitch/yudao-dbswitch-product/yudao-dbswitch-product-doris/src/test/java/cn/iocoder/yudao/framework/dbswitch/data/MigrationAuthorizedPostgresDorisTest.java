package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperties;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 只在显式本机测试库创建随机命名空间；覆盖真实授权、建表、写入、拒绝重放及清理。 */
@Timeout(180)
@EnabledIfSystemProperties({
        @EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/"),
        @EnabledIfSystemProperty(named = "datahub.test.pg.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[A-Za-z0-9_]+")})
class MigrationAuthorizedPostgresDorisTest {
    private Connection pg;
    private Connection doris;
    private String schema;

    @BeforeEach void setup() throws Exception {
        pg = DriverManager.getConnection(System.getProperty("datahub.test.pg.url"),
                System.getProperty("datahub.test.pg.user", "postgres"), System.getProperty("datahub.test.pg.password", ""));
        doris = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), "root", "");
        schema = "authorized_" + UUID.randomUUID().toString().replace("-", "");
        sql(pg, "CREATE SCHEMA " + schema);
        sql(doris, "CREATE DATABASE " + schema);
        sql(pg, "CREATE TABLE " + schema + ".rows (id BIGINT PRIMARY KEY, payload VARCHAR(64) DEFAULT 'seed')");
        sql(pg, "COMMENT ON COLUMN " + schema + ".rows.payload IS '来源''字段'");
        sql(pg, "COMMENT ON TABLE " + schema + ".rows IS '复制''测试'");
        sql(pg, "INSERT INTO " + schema + ".rows VALUES (9007199254740993,'" + "😀".repeat(64) + "'),(9007199254740994,NULL)");
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (pg != null) try (var connection = pg) {
                if (schema != null) sql(connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        } finally {
            if (doris != null) try (var connection = doris) {
                if (schema != null) sql(connection, "DROP DATABASE IF EXISTS " + schema + " FORCE");
            }
        }
    }

    @Test void fullCopyRetainsExactValuesAndRefusesExistingTarget() throws Exception {
        var calls = new AtomicInteger();
        try (var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), null)) {
            var authorizer = (cn.iocoder.yudao.framework.dbswitch.data.service.MigrationTargetSqlAuthorizer)
                    (table, fields, plan) -> {
                        calls.incrementAndGet();
                        assertEquals(ProductTypeEnum.DORIS, plan.product());
                        assertEquals(plan, runtime.products().metadata(ProductTypeEnum.DORIS).planAuthorizedCreateNew(
                                schema, table, fields, plan.steps().getFirst().sql(), plan.steps().getLast().sql()));
                        return plan;
                    };
            var result = new MigrationService(request(), ignored -> { }, null, runtime, authorizer).run();
            assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
            assertEquals(1, calls.get());
            assertEquals(2, result.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
            var replay = new MigrationService(request(), ignored -> { }, null, runtime, authorizer).run();
            assertFalse(replay.getIfAllSuccess().get());
            assertEquals(1, calls.get());
        }
        try (var statement = doris.createStatement(); var rows = statement.executeQuery(
                "SELECT id,payload FROM `" + schema + "`.`rows` ORDER BY id")) {
            assertTrue(rows.next()); assertEquals(9007199254740993L, rows.getLong(1)); assertEquals("😀".repeat(64), rows.getString(2));
            assertTrue(rows.next()); assertEquals(9007199254740994L, rows.getLong(1)); assertNull(rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test void deniedAuthorizationCreatesNoTarget() throws Exception {
        try (var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), null)) {
            var result = new MigrationService(request(), ignored -> { }, null, runtime,
                    (table, fields, plan) -> { throw new SecurityException("denied"); }).run();
            assertFalse(result.getIfAllSuccess().get());
        }
        try (var statement = doris.createStatement(); var tables = statement.executeQuery("SHOW TABLES FROM `" + schema + "`")) {
            assertFalse(tables.next());
        }
    }

    @Test void truncateBetweenAuthorizedBatchesIsRejectedBeforeFurtherInsert() throws Exception {
        var writer = authorizedWriter(false);
        var fields = java.util.List.of("id", "payload");
        assertEquals(1, writer.write(fields, java.util.Collections.singletonList(new Object[]{1L, "first"})));
        sql(doris, "TRUNCATE TABLE `" + schema + "`.`rows`");
        assertThrows(IllegalStateException.class, () -> writer.write(fields,
                java.util.Collections.singletonList(new Object[]{2L, "second"})));
        try (var statement = doris.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM `" + schema + "`.`rows`")) {
            assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
        }
    }

    @Test void truncateAfterInsertAcknowledgementTurnsTheWholeBatchUnknown() throws Exception {
        var writer = authorizedWriter(true);
        var failure = assertThrows(cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException.class,
                () -> writer.write(java.util.List.of("id", "payload"),
                        java.util.Collections.singletonList(new Object[]{1L, "lost"})));
        assertEquals(0, failure.acknowledgedRows());
        assertEquals(1, failure.unknownRows());
    }

    @Test void createdIdentityAndEmptyTargetMustStillMatchBeforePreparation() throws Exception {
        var writer = authorizedWriter(false, false);
        sql(doris, "DROP TABLE `" + schema + "`.`rows`");
        sql(doris, "CREATE TABLE `" + schema + "`.`rows` (id BIGINT NOT NULL,payload VARCHAR(64) NULL) "
                + "ENGINE=OLAP DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        assertThrows(IllegalStateException.class, () -> writer.prepareWrite(schema, "rows", java.util.List.of("id", "payload")));
    }

    @Test void externallyInsertedRowsBeforePreparationAreRejected() throws Exception {
        var writer = authorizedWriter(false, false);
        sql(doris, "INSERT INTO `" + schema + "`.`rows` VALUES (1,'external')");
        assertThrows(IllegalStateException.class, () -> writer.prepareWrite(schema, "rows", java.util.List.of("id", "payload")));
    }

    @Test void finalNativeCountMismatchCannotConfirmTheTransfer() throws Exception {
        var writer = authorizedWriter(false);
        assertEquals(1, writer.write(java.util.List.of("id", "payload"),
                java.util.Collections.singletonList(new Object[]{1L, "copied"})));
        assertDoesNotThrow(() -> writer.completeAuthorizedTransfer(1));
        sql(doris, "INSERT INTO `" + schema + "`.`rows` VALUES (2,'external')");
        assertThrows(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class,
                () -> writer.completeAuthorizedTransfer(1));
    }

    private cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl authorizedWriter(boolean truncateAfterInsert) throws Exception {
        return authorizedWriter(truncateAfterInsert, true);
    }

    private cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl authorizedWriter(boolean truncateAfterInsert, boolean prepare) throws Exception {
        var source = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public Connection getConnection() throws java.sql.SQLException {
                Connection actual = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), "root", "");
                if (!truncateAfterInsert) return actual;
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            Object result = invoke(method, actual, args);
                            if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO")) {
                                var statement = (java.sql.PreparedStatement) result;
                                return java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{java.sql.PreparedStatement.class}, (p, m, a) -> {
                                            Object answer = invoke(m, statement, a);
                                            if (m.getName().equals("executeUpdate")) sql(doris, "TRUNCATE TABLE `" + schema + "`.`rows`");
                                            return answer;
                                        });
                            }
                            return result;
                        });
            }
            @Override public Connection getConnection(String user, String password) throws java.sql.SQLException { return getConnection(); }
        };
        var writer = new cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl(source);
        writer.configure(DbSwitchOptions.defaults().withOlapStreamLoad(false));
        var fields = java.util.List.of("id", "payload");
        String insert = writer.planInsertSql(schema, "rows", fields);
        var plan = new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl().planAuthorizedCreateNew(
                schema, "rows", fields, "CREATE TABLE `" + schema + "`.`rows` (id BIGINT NOT NULL,payload VARCHAR(64) NULL) "
                        + "ENGINE=OLAP DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')", insert);
        writer.useAuthorizedInsertSql(schema, "rows", fields, insert, java.util.List.of(0, 1));
        writer.executeAuthorizedCreateTable(plan);
        if (prepare) writer.prepareWrite(schema, "rows", fields);
        return writer;
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }

    private MigrationRequest request() {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setUrl(System.getProperty("datahub.test.pg.url"));
        source.setUsername(System.getProperty("datahub.test.pg.user", "postgres"));
        source.setPassword(System.getProperty("datahub.test.pg.password", ""));
        source.setSourceSchema(schema); source.setSourceIncludes("rows"); source.setPoolSize(1);
        request.getSource().add(source);
        var target = request.getTarget();
        target.setTargetProductType(ProductTypeEnum.DORIS);
        target.setUrl(System.getProperty("datahub.test.doris.url")); target.setUsername("root"); target.setPassword("");
        target.setTargetSchema(schema); target.setPoolSize(1); target.setWriteMode("CREATE_NEW"); target.setWriterEngineInsert(true);
        return request;
    }

    private static void sql(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(30); statement.execute(sql); }
    }
}
